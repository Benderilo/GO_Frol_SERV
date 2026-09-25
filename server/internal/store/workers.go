package store

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"
)

// ---------- Сотрудники, ставки и рабочие дни ----------

const workerSelect = `SELECT id, name, phone, position, salary_type, salary_kop, active, created_at, updated_at
	 FROM workers`

// ListWorkers отдаёт сотрудников по имени; неактивные — только по запросу:
// уволенный не должен мешаться в ежедневных списках, но история его остаётся.
func (s *Store) ListWorkers(ctx context.Context, includeInactive bool) ([]Worker, error) {
	query := workerSelect
	if !includeInactive {
		query += ` WHERE active = 1`
	}
	rows, err := s.db.QueryContext(ctx, query+` ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Worker, 0, 16)
	for rows.Next() {
		w, err := scanWorker(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, w)
	}
	return out, rows.Err()
}

func (s *Store) GetWorker(ctx context.Context, id int64) (Worker, error) {
	rows, err := s.db.QueryContext(ctx, workerSelect+` WHERE id = ?`, id)
	if err != nil {
		return Worker{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return Worker{}, err
		}
		return Worker{}, ErrNotFound
	}
	return scanWorker(rows)
}

func (s *Store) CreateWorker(ctx context.Context, u WorkerUpsert) (Worker, error) {
	if err := checkWorker(&u); err != nil {
		return Worker{}, err
	}
	ts := now()
	// Новый сотрудник всегда активен: отметку «не работает» имеет смысл
	// ставить только тому, кто уже есть в списке.
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO workers (name, phone, position, salary_type, salary_kop, active, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, 1, ?, ?)`,
		u.Name, u.Phone, u.Position, u.SalaryType, u.SalaryKop, ts, ts)
	if err != nil {
		return Worker{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Worker{}, err
	}
	s.AddAudit(ctx, "worker", id, "create", u.Name)
	return s.GetWorker(ctx, id)
}

func (s *Store) UpdateWorker(ctx context.Context, id int64, u WorkerUpsert) (Worker, error) {
	if err := checkWorker(&u); err != nil {
		return Worker{}, err
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE workers SET name = ?, phone = ?, position = ?, salary_type = ?, salary_kop = ?, active = ?, updated_at = ?
		 WHERE id = ?`,
		u.Name, u.Phone, u.Position, u.SalaryType, u.SalaryKop, boolInt(u.Active), now(), id)
	if err != nil {
		return Worker{}, err
	}
	if err := affected(res); err != nil {
		return Worker{}, err
	}
	s.AddAudit(ctx, "worker", id, "update", u.Name)
	return s.GetWorker(ctx, id)
}

// DeleteWorker удаляет карточку вместе с отметками дней (ON DELETE CASCADE).
// Выплаты в кассе на сотрудника не ссылаются и потому переживают удаление.
func (s *Store) DeleteWorker(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM workers WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "worker", id, "delete", "")
	return nil
}

// checkWorker приводит карточку к каноническому виду и отсекает то,
// что потом пришлось бы разбирать руками: пустое имя, чужой тип ставки.
func checkWorker(u *WorkerUpsert) error {
	u.Name = strings.TrimSpace(u.Name)
	u.Phone = strings.TrimSpace(u.Phone)
	u.Position = strings.TrimSpace(u.Position)
	u.SalaryType = strings.TrimSpace(u.SalaryType)
	if u.Name == "" {
		return fmt.Errorf("%w: имя сотрудника обязательно", ErrBadName)
	}
	if u.SalaryType == "" {
		u.SalaryType = "day"
	}
	if u.SalaryType != "day" && u.SalaryType != "month" {
		return fmt.Errorf("%w: тип ставки — day или month", ErrBadName)
	}
	if u.SalaryKop < 0 {
		return fmt.Errorf("%w: ставка не может быть отрицательной", ErrBadName)
	}
	return nil
}

func scanWorker(rows *sql.Rows) (Worker, error) {
	var w Worker
	var active int
	if err := rows.Scan(&w.ID, &w.Name, &w.Phone, &w.Position, &w.SalaryType,
		&w.SalaryKop, &active, &w.CreatedAt, &w.UpdatedAt); err != nil {
		return Worker{}, err
	}
	w.Active = active == 1
	return w, nil
}

// ListWorkDays отдаёт отметки за период [from, to] включительно;
// workerID = 0 — по всем сотрудникам.
func (s *Store) ListWorkDays(ctx context.Context, from, to string, workerID int64) ([]WorkDay, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT d.id, d.worker_id, COALESCE(w.name, ''), d.work_date, d.note, d.created_at
		 FROM work_days d LEFT JOIN workers w ON w.id = d.worker_id
		 WHERE (? = '' OR d.work_date >= ?) AND (? = '' OR d.work_date <= ?)
		       AND (? = 0 OR d.worker_id = ?)
		 ORDER BY d.work_date DESC, d.id DESC`,
		from, from, to, to, workerID, workerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]WorkDay, 0, 32)
	for rows.Next() {
		var d WorkDay
		if err := rows.Scan(&d.ID, &d.WorkerID, &d.WorkerName, &d.WorkDate, &d.Note, &d.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// SetWorkDay отмечает выход сотрудника. Повторная отметка той же даты
// обновляет заметку: приложение может присылать весь день целиком
// сколько угодно раз, дублей не будет.
func (s *Store) SetWorkDay(ctx context.Context, workerID int64, date, note string) error {
	if _, err := time.Parse("2006-01-02", date); err != nil {
		return fmt.Errorf("%w: дата должна быть в формате ГГГГ-ММ-ДД", ErrBadName)
	}
	if _, err := s.GetWorker(ctx, workerID); err != nil {
		return err
	}
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO work_days (worker_id, work_date, note, created_at)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT(worker_id, work_date) DO UPDATE SET note = excluded.note`,
		workerID, date, strings.TrimSpace(note), now()); err != nil {
		return err
	}
	s.AddAudit(ctx, "worker", workerID, "workday", date)
	return nil
}

func (s *Store) RemoveWorkDay(ctx context.Context, workerID int64, date string) error {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM work_days WHERE worker_id = ? AND work_date = ?`, workerID, date)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "worker", workerID, "workday_remove", date)
	return nil
}

// WorkerAccrual считает начисление за месяц («YYYY-MM»): для подённой ставки —
// дни, умноженные на ставку, для окладника — сам оклад, а дни справочно.
func (s *Store) WorkerAccrual(ctx context.Context, workerID int64, month string) (WorkerAccrual, error) {
	month = strings.TrimSpace(month)
	if _, err := time.Parse("2006-01", month); err != nil {
		return WorkerAccrual{}, fmt.Errorf("%w: месяц должен быть в формате ГГГГ-ММ", ErrBadName)
	}
	w, err := s.GetWorker(ctx, workerID)
	if err != nil {
		return WorkerAccrual{}, err
	}
	var days int
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM work_days WHERE worker_id = ? AND substr(work_date, 1, 7) = ?`,
		workerID, month).Scan(&days); err != nil {
		return WorkerAccrual{}, err
	}
	acc := WorkerAccrual{WorkerID: workerID, Month: month, Days: days}
	if w.SalaryType == "month" {
		acc.AmountKop = w.SalaryKop
	} else {
		acc.AmountKop = int64(days) * w.SalaryKop
	}
	return acc, nil
}

// PayoutWorker проводит выплату зарплаты расходом кассы: из наличных,
// со статьёй «Зарплата». Отдельной таблицы выплат нет — деньги и так
// видны в кассе и в отчётах, а двух правд про одни деньги быть не должно.
func (s *Store) PayoutWorker(ctx context.Context, workerID int64, amountKop int64, month string) error {
	if amountKop <= 0 {
		return fmt.Errorf("%w: сумма выплаты должна быть больше нуля", ErrBadName)
	}
	month = strings.TrimSpace(month)
	if _, err := time.Parse("2006-01", month); err != nil {
		return fmt.Errorf("%w: месяц должен быть в формате ГГГГ-ММ", ErrBadName)
	}
	w, err := s.GetWorker(ctx, workerID)
	if err != nil {
		return err
	}
	if _, err := s.AddCashOp(ctx, CashOp{
		Direction: DirectionOut,
		AmountKop: amountKop,
		Method:    MethodCash,
		Category:  "Зарплата",
		Note:      fmt.Sprintf("Зарплата: %s, %s", w.Name, month),
	}); err != nil {
		return err
	}
	s.AddAudit(ctx, "worker", workerID, "payout",
		fmt.Sprintf("%s: %s, %s", w.Name, moneyText(amountKop), month))
	return nil
}
