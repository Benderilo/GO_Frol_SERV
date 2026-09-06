package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
)

// ---------- Задачи / напоминания ----------

func (s *Store) ListTasks(ctx context.Context, filter string) ([]Task, error) {
	filter = strings.TrimSpace(filter)
	var rows *sql.Rows
	var err error
	if filter == "" {
		rows, err = s.db.QueryContext(ctx, taskSelect+` ORDER BY t.done ASC, t.due_date ASC, t.id DESC`)
	} else if filter == "overdue" {
		rows, err = s.db.QueryContext(ctx, taskSelect+` WHERE t.done = 0 AND t.due_date != '' AND t.due_date < ? ORDER BY t.due_date ASC`, now())
	} else if filter == "today" {
		rows, err = s.db.QueryContext(ctx, taskSelect+` WHERE t.done = 0 AND t.due_date != '' AND t.due_date <= ? ORDER BY t.due_date ASC`, endOfDay())
	} else if filter == "done" {
		rows, err = s.db.QueryContext(ctx, taskSelect+` WHERE t.done = 1 ORDER BY t.updated_at DESC`)
	} else {
		rows, err = s.db.QueryContext(ctx, taskSelect+` WHERE t.done = 0 ORDER BY t.due_date ASC, t.id DESC`)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Task, 0, 16)
	for rows.Next() {
		t, err := scanTask(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

const taskSelect = `SELECT t.id, t.client_id, t.order_id, t.parent_id, COALESCE(c.name, ''), t.title, t.note,
       t.due_date, t.done, t.priority, t.created_at, t.updated_at
 FROM tasks t LEFT JOIN clients c ON c.id = t.client_id`

func (s *Store) CreateTask(ctx context.Context, t Task) (Task, error) {
	ts := now()
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO tasks (client_id, order_id, parent_id, title, note, due_date, done, priority, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		t.ClientID, t.OrderID, t.ParentID, t.Title, t.Note, t.DueDate, boolInt(t.Done), defaultPriority(t.Priority), ts, ts)
	if err != nil {
		return Task{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Task{}, err
	}
	return s.Task(ctx, id)
}

func (s *Store) Task(ctx context.Context, id int64) (Task, error) {
	rows, err := s.db.QueryContext(ctx, taskSelect+` WHERE t.id = ?`, id)
	if err != nil {
		return Task{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Task{}, ErrNotFound
	}
	return scanTask(rows)
}

func (s *Store) UpdateTask(ctx context.Context, id int64, t Task) (Task, error) {
	// Задача не может быть подзадачей самой себя.
	if t.ParentID != nil && *t.ParentID == id {
		t.ParentID = nil
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE tasks SET client_id = ?, order_id = ?, parent_id = ?, title = ?, note = ?, due_date = ?, done = ?, priority = ?, updated_at = ?
		 WHERE id = ?`,
		t.ClientID, t.OrderID, t.ParentID, t.Title, t.Note, t.DueDate, boolInt(t.Done), defaultPriority(t.Priority), now(), id)
	if err != nil {
		return Task{}, err
	}
	if err := affected(res); err != nil {
		return Task{}, err
	}
	return s.Task(ctx, id)
}

func (s *Store) SetTaskDone(ctx context.Context, id int64, done bool) (Task, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE tasks SET done = ?, updated_at = ? WHERE id = ?`, boolInt(done), now(), id)
	if err != nil {
		return Task{}, err
	}
	if err := affected(res); err != nil {
		return Task{}, err
	}
	return s.Task(ctx, id)
}

func (s *Store) DeleteTask(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM tasks WHERE id = ?`, id)
	if err != nil {
		return err
	}
	return affected(res)
}

func scanTask(rows *sql.Rows) (Task, error) {
	var t Task
	var clientID, orderID, parentID sql.NullInt64
	var done int
	if err := rows.Scan(&t.ID, &clientID, &orderID, &parentID, &t.ClientName, &t.Title, &t.Note,
		&t.DueDate, &done, &t.Priority, &t.CreatedAt, &t.UpdatedAt); err != nil {
		return Task{}, err
	}
	if clientID.Valid {
		id := clientID.Int64
		t.ClientID = &id
	}
	if orderID.Valid {
		id := orderID.Int64
		t.OrderID = &id
	}
	if parentID.Valid {
		id := parentID.Int64
		t.ParentID = &id
	}
	t.Done = done == 1
	return t, nil
}

func defaultPriority(v string) string {
	switch strings.TrimSpace(v) {
	case "low", "high":
		return v
	default:
		return "normal"
	}
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// endOfDay — конец текущего дня по UTC в RFC3339, для фильтра «на сегодня».
func endOfDay() string {
	n := time.Now().UTC()
	end := n.Truncate(24 * time.Hour).Add(24*time.Hour - time.Second)
	return end.Format(time.RFC3339)
}

// ---------- Журнал действий ----------

func (s *Store) AddAudit(ctx context.Context, entityType string, entityID int64, action, detail string) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO audit_log (entity_type, entity_id, action, detail, created_at)
		 VALUES (?, ?, ?, ?, ?)`,
		entityType, entityID, action, detail, now())
	return err
}

func (s *Store) ListAudit(ctx context.Context, limit int) ([]AuditEntry, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, entity_type, entity_id, action, detail, created_at
		 FROM audit_log ORDER BY id DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]AuditEntry, 0, limit)
	for rows.Next() {
		var e AuditEntry
		if err := rows.Scan(&e.ID, &e.EntityType, &e.EntityID, &e.Action, &e.Detail, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// ---------- Поиск дублей клиентов ----------

// FindClientDuplicates находит клиентов, чьи номера совпадают по последним
// десяти цифрам. Такие записи обычно появились при ручном вводе в разных
// форматах и должны быть объединены.
func (s *Store) FindClientDuplicates(ctx context.Context) ([]ClientDuplicate, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, phone FROM clients WHERE phone != '' ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	type row struct {
		id    int64
		name  string
		phone string
	}
	all := make([]row, 0)
	for rows.Next() {
		var r row
		if err := rows.Scan(&r.id, &r.name, &r.phone); err != nil {
			return nil, err
		}
		all = append(all, r)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	groups := map[string][]row{}
	for _, r := range all {
		key := tailDigits(onlyDigits(r.phone), 10)
		if key == "" {
			continue
		}
		groups[key] = append(groups[key], r)
	}

	out := make([]ClientDuplicate, 0)
	for phone, members := range groups {
		if len(members) < 2 {
			continue
		}
		d := ClientDuplicate{Phone: phone}
		for _, m := range members {
			d.ClientIDs = append(d.ClientIDs, m.id)
			d.ClientNames = append(d.ClientNames, m.name)
		}
		out = append(out, d)
	}
	return out, nil
}

// MergeClients переносит заказы и задачи из дублей в основную карточку и
// удаляет дубли. Основная карточка сохраняет свои имя/телефон.
func (s *Store) MergeClients(ctx context.Context, keepID int64, mergeIDs []int64) error {
	for _, id := range mergeIDs {
		if id == keepID {
			continue
		}
		if _, err := s.db.ExecContext(ctx,
			`UPDATE orders SET client_id = ? WHERE client_id = ?`, keepID, id); err != nil {
			return err
		}
		if _, err := s.db.ExecContext(ctx,
			`UPDATE tasks SET client_id = ? WHERE client_id = ?`, keepID, id); err != nil {
			return err
		}
		if err := s.DeleteClient(ctx, id); err != nil && !errors.Is(err, ErrNotFound) {
			return err
		}
	}
	return nil
}
