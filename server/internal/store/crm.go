package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
)

// ---------- Клиенты ----------

func (s *Store) ListClients(ctx context.Context, query string, limit, offset int) ([]Client, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	q := strings.ToLower(strings.TrimSpace(query))
	// Поиск фильтруем в Go, а не через LIKE: lower() в SQLite опускает
	// регистр только латиницы, и «Дим» не находил клиента «Диман».
	rows, err := s.db.QueryContext(ctx,
		`SELECT c.id, c.name, c.phone, c.email, c.address, c.note, c.tag,
		        c.created_at, c.updated_at,
		        c.portal_code_hash, c.portal_enabled, c.portal_last_login,
		        (SELECT COUNT(*) FROM orders o WHERE o.client_id = c.id),
		        (SELECT COALESCE(SUM(o.price_kop), 0) FROM orders o
		          WHERE o.client_id = c.id AND o.status = 'done')
		 FROM clients c
		 ORDER BY c.updated_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	all := make([]Client, 0, 16)
	for rows.Next() {
		c, err := scanClient(rows, true)
		if err != nil {
			return nil, err
		}
		if q != "" && !clientMatches(c, q) {
			continue
		}
		all = append(all, c)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// offset/limit применяем к уже отфильтрованному списку.
	if offset > 0 {
		if offset >= len(all) {
			return []Client{}, nil
		}
		all = all[offset:]
	}
	if len(all) > limit {
		all = all[:limit]
	}
	return all, nil
}

// clientMatches решает, подходит ли клиент под поисковый запрос:
// имя и почту сравниваем без учёта регистра, телефон — по цифрам,
// чтобы «+7 (900)...» находился и по «8900...», и по «900».
func clientMatches(c Client, q string) bool {
	if strings.Contains(strings.ToLower(c.Name), q) ||
		strings.Contains(strings.ToLower(c.Email), q) {
		return true
	}
	digits := onlyDigits(q)
	return digits != "" && strings.Contains(onlyDigits(c.Phone), digits)
}

func (s *Store) Client(ctx context.Context, id int64) (Client, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, phone, email, address, note, tag, created_at, updated_at,
		        portal_code_hash, portal_enabled, portal_last_login
		 FROM clients WHERE id = ?`, id)
	if err != nil {
		return Client{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Client{}, ErrNotFound
	}
	return scanClient(rows, false)
}

func (s *Store) CreateClient(ctx context.Context, c Client) (Client, error) {
	ts := now()
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO clients (name, phone, email, address, note, tag, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		c.Name, c.Phone, c.Email, c.Address, c.Note, c.Tag, ts, ts)
	if err != nil {
		return Client{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Client{}, err
	}
	s.AddAudit(ctx, "client", id, "create", c.Name)
	return s.Client(ctx, id)
}

func (s *Store) UpdateClient(ctx context.Context, id int64, c Client) (Client, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE clients SET name = ?, phone = ?, email = ?, address = ?, note = ?, tag = ?, updated_at = ?
		 WHERE id = ?`,
		c.Name, c.Phone, c.Email, c.Address, c.Note, c.Tag, now(), id)
	if err != nil {
		return Client{}, err
	}
	if err := affected(res); err != nil {
		return Client{}, err
	}
	s.AddAudit(ctx, "client", id, "update", c.Name)
	return s.Client(ctx, id)
}

func (s *Store) DeleteClient(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM clients WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "client", id, "delete", "")
	return nil
}

// ---------- Заказы ----------

func (s *Store) ListOrders(ctx context.Context, status string, limit, offset int) ([]Order, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	status = strings.TrimSpace(status)
	rows, err := s.db.QueryContext(ctx,
		`SELECT o.id, o.client_id, COALESCE(c.name, ''), o.title, o.description, o.status,
		        o.price_kop, o.due_date, o.created_at, o.updated_at, o.closed_at,
		        (SELECT COALESCE(SUM(amount_kop), 0) FROM cash_ops p
		          WHERE p.order_id = o.id AND p.direction = 'in'),
		        (SELECT COUNT(*) FROM order_items i WHERE i.order_id = o.id),
		        (SELECT COALESCE(SUM((i.qty_milli * i.cost_kop + 500) / 1000), 0)
		           FROM order_items i WHERE i.order_id = o.id)
		 FROM orders o LEFT JOIN clients c ON c.id = o.client_id
		 WHERE ? = '' OR o.status = ?
		 ORDER BY o.updated_at DESC LIMIT ? OFFSET ?`,
		status, status, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Order, 0, 16)
	for rows.Next() {
		o, err := scanOrder(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	ids := make([]int64, 0, len(out))
	for _, o := range out {
		ids = append(ids, o.ID)
	}
	counts, err := s.PhotoCounts(ctx, ids)
	if err != nil {
		return nil, err
	}
	for i := range out {
		out[i].PhotoCount = counts[out[i].ID]
	}
	return out, nil
}

func (s *Store) Order(ctx context.Context, id int64) (Order, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT o.id, o.client_id, COALESCE(c.name, ''), o.title, o.description, o.status,
		        o.price_kop, o.due_date, o.created_at, o.updated_at, o.closed_at,
		        (SELECT COALESCE(SUM(amount_kop), 0) FROM cash_ops p
		          WHERE p.order_id = o.id AND p.direction = 'in'),
		        (SELECT COUNT(*) FROM order_items i WHERE i.order_id = o.id),
		        (SELECT COALESCE(SUM((i.qty_milli * i.cost_kop + 500) / 1000), 0)
		           FROM order_items i WHERE i.order_id = o.id)
		 FROM orders o LEFT JOIN clients c ON c.id = o.client_id
		 WHERE o.id = ?`, id)
	if err != nil {
		return Order{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Order{}, ErrNotFound
	}
	order, err := scanOrder(rows)
	if err != nil {
		return Order{}, err
	}
	rows.Close()

	photos, err := s.OrderPhotos(ctx, order.ID)
	if err != nil {
		return Order{}, err
	}
	order.Photos = photos
	order.PhotoCount = len(photos)
	return order, nil
}

func (s *Store) CreateOrder(ctx context.Context, o Order) (Order, error) {
	ts := now()
	status := defaultStatus(o.Status)
	// Заказ может сразу родиться завершённым — фиксируем момент закрытия.
	closedAt := ""
	if status == "done" {
		closedAt = ts
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO orders (client_id, title, description, status, price_kop, due_date, created_at, updated_at, closed_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		o.ClientID, o.Title, o.Description, status, o.PriceKop, o.DueDate, ts, ts, closedAt)
	if err != nil {
		return Order{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Order{}, err
	}
	s.AddAudit(ctx, "order", id, "create", o.Title)
	return s.Order(ctx, id)
}

func (s *Store) UpdateOrder(ctx context.Context, id int64, o Order) (Order, error) {
	// Момент закрытия ведём сами: отметили «завершён» — запомнили когда,
	// сняли отметку (правка, переоткрытие) — дату закрытия стёрли.
	status := defaultStatus(o.Status)
	closedAt := ""
	if status == "done" {
		var prev string
		var prevClosed string
		err := s.db.QueryRowContext(ctx,
			`SELECT status, closed_at FROM orders WHERE id = ?`, id).Scan(&prev, &prevClosed)
		if err != nil {
			return Order{}, err
		}
		if prev == "done" && prevClosed != "" {
			closedAt = prevClosed // уже был завершён — дату не сдвигаем
		} else {
			closedAt = now()
		}
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE orders SET client_id = ?, title = ?, description = ?, status = ?, price_kop = ?, due_date = ?,
		        updated_at = ?, closed_at = ?
		 WHERE id = ?`,
		o.ClientID, o.Title, o.Description, status, o.PriceKop, o.DueDate, now(), closedAt, id)
	if err != nil {
		return Order{}, err
	}
	if err := affected(res); err != nil {
		return Order{}, err
	}
	// У заказа с составом цену определяют строки, а не присланное поле:
	// иначе приложение со слегка устаревшим черновиком перетёрло бы итог.
	if err := s.recalcOrderTotal(ctx, id); err != nil {
		return Order{}, err
	}
	s.AddAudit(ctx, "order", id, "update", o.Title)
	return s.Order(ctx, id)
}

func (s *Store) DeleteOrder(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM orders WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "order", id, "delete", "")
	return nil
}

func scanOrder(rows *sql.Rows) (Order, error) {
	var o Order
	var clientID sql.NullInt64
	if err := rows.Scan(&o.ID, &clientID, &o.ClientName, &o.Title, &o.Description,
		&o.Status, &o.PriceKop, &o.DueDate, &o.CreatedAt, &o.UpdatedAt, &o.ClosedAt, &o.PaidKop,
		&o.ItemsCount, &o.CostKop); err != nil {
		return Order{}, err
	}
	if clientID.Valid {
		id := clientID.Int64
		o.ClientID = &id
	}
	return o, nil
}

func defaultStatus(s string) string {
	if strings.TrimSpace(s) == "" {
		return "new"
	}
	return s
}

// ---------- Заявки с сайта ----------

func (s *Store) ListRequests(ctx context.Context, status string, limit, offset int) ([]Request, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	status = strings.TrimSpace(status)
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, phone, message, source, status, created_at, updated_at
		 FROM requests WHERE ? = '' OR status = ?
		 ORDER BY created_at DESC LIMIT ? OFFSET ?`,
		status, status, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Request, 0, 16)
	for rows.Next() {
		var r Request
		if err := rows.Scan(&r.ID, &r.Name, &r.Phone, &r.Message, &r.Source, &r.Status, &r.CreatedAt, &r.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) Request(ctx context.Context, id int64) (Request, error) {
	var r Request
	err := s.db.QueryRowContext(ctx,
		`SELECT id, name, phone, message, source, status, created_at, updated_at
		 FROM requests WHERE id = ?`, id).
		Scan(&r.ID, &r.Name, &r.Phone, &r.Message, &r.Source, &r.Status, &r.CreatedAt, &r.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Request{}, ErrNotFound
	}
	return r, err
}

func (s *Store) CreateRequest(ctx context.Context, r Request) (Request, error) {
	ts := now()
	if strings.TrimSpace(r.Source) == "" {
		r.Source = "site"
	}

	// Заявка с формы становится карточкой клиента и заказом: по одному и тому
	// же номеру новый клиент не заводится — всё ложится к существующему.
	client, err := s.ensureClientForPhone(ctx, r.Name, r.Phone)
	if err != nil {
		return Request{}, err
	}

	// Заказ повторяет заявку: название — «Заявка с сайта», описание — текст
	// обращения, статус «новый». Так заявка сразу видна и в разделе заказов.
	title := strings.TrimSpace(r.Name)
	if title == "" {
		title = "Заявка с сайта"
	}
	if _, err := s.CreateOrder(ctx, Order{
		ClientID:    &client.ID,
		Title:       title,
		Description: strings.TrimSpace(r.Message),
		Status:      "new",
	}); err != nil {
		return Request{}, err
	}

	res, err := s.db.ExecContext(ctx,
		`INSERT INTO requests (name, phone, message, source, status, created_at, updated_at)
		 VALUES (?, ?, ?, ?, 'new', ?, ?)`,
		r.Name, r.Phone, r.Message, r.Source, ts, ts)
	if err != nil {
		return Request{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Request{}, err
	}
	s.AddAudit(ctx, "request", id, "create", r.Name)
	return s.Request(ctx, id)
}

// ensureClientForPhone заводит клиента с указанным именем, если номер ещё не
// встречался. Номера сравниваются по последним десяти цифрам, поэтому разные
// форматы записи («+7 900…», «8900…») считаются одним клиентом.
func (s *Store) ensureClientForPhone(ctx context.Context, name, phone string) (Client, error) {
	if c, err := s.clientByPhone(ctx, phone); err == nil {
		return c, nil
	} else if !errors.Is(err, ErrNotFound) {
		return Client{}, err
	}
	return s.CreateClient(ctx, Client{Name: name, Phone: phone})
}

// clientByPhone ищет клиента по номеру, сравнивая последние десять цифр.
func (s *Store) clientByPhone(ctx context.Context, phone string) (Client, error) {
	digits := onlyDigits(phone)
	if digits == "" {
		return Client{}, ErrNotFound
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, phone, email, address, note, tag, created_at, updated_at,
		        portal_code_hash, portal_enabled, portal_last_login
		 FROM clients WHERE phone != ''`)
	if err != nil {
		return Client{}, err
	}
	defer rows.Close()
	for rows.Next() {
		c, err := scanClient(rows, false)
		if err != nil {
			return Client{}, err
		}
		if phoneMatches(c.Phone, digits) {
			return c, nil
		}
	}
	if err := rows.Err(); err != nil {
		return Client{}, err
	}
	return Client{}, ErrNotFound
}

func (s *Store) UpdateRequestStatus(ctx context.Context, id int64, status string) (Request, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE requests SET status = ?, updated_at = ? WHERE id = ?`,
		defaultStatus(status), now(), id)
	if err != nil {
		return Request{}, err
	}
	if err := affected(res); err != nil {
		return Request{}, err
	}
	s.AddAudit(ctx, "request", id, "status", status)
	return s.Request(ctx, id)
}

func (s *Store) DeleteRequest(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM requests WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "request", id, "delete", "")
	return nil
}

// ---------- Сводка ----------

func (s *Store) Stats(ctx context.Context) (Stats, error) {
	var st Stats
	err := s.db.QueryRowContext(ctx, `
		SELECT
			(SELECT COUNT(*) FROM clients),
			(SELECT COUNT(*) FROM orders),
			(SELECT COUNT(*) FROM orders WHERE status IN ('new', 'in_progress')),
			(SELECT COUNT(*) FROM orders WHERE status = 'done'),
			(SELECT COUNT(*) FROM requests WHERE status = 'new'),
			(SELECT COALESCE(SUM(price_kop), 0) FROM orders WHERE status = 'done'),
			(SELECT COALESCE(SUM(price_kop), 0) FROM orders WHERE status IN ('new', 'in_progress')),
			(`+debtSelect+`),
			(`+debtSelect+` AND o.due_date != '' AND o.due_date < ?)
	`, now()).Scan(&st.Clients, &st.Orders, &st.OrdersActive, &st.OrdersDone,
		&st.RequestsNew, &st.RevenueTotalKop, &st.RevenueActiveKop,
		&st.DebtOutstandingKop, &st.DebtOverdueKop)
	return st, err
}
