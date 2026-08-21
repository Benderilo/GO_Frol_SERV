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
	like := "%" + q + "%"
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, phone, email, address, note, tag, created_at, updated_at
		 FROM clients
		 WHERE ? = '' OR lower(name) LIKE ? OR lower(phone) LIKE ? OR lower(email) LIKE ?
		 ORDER BY updated_at DESC LIMIT ? OFFSET ?`,
		q, like, like, like, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Client, 0, 16)
	for rows.Next() {
		var c Client
		if err := rows.Scan(&c.ID, &c.Name, &c.Phone, &c.Email, &c.Address, &c.Note, &c.Tag, &c.CreatedAt, &c.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) Client(ctx context.Context, id int64) (Client, error) {
	var c Client
	err := s.db.QueryRowContext(ctx,
		`SELECT id, name, phone, email, address, note, tag, created_at, updated_at
		 FROM clients WHERE id = ?`, id).
		Scan(&c.ID, &c.Name, &c.Phone, &c.Email, &c.Address, &c.Note, &c.Tag, &c.CreatedAt, &c.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Client{}, ErrNotFound
	}
	return c, err
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
	return s.Client(ctx, id)
}

func (s *Store) DeleteClient(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM clients WHERE id = ?`, id)
	if err != nil {
		return err
	}
	return affected(res)
}

// ---------- Заказы ----------

func (s *Store) ListOrders(ctx context.Context, status string, limit, offset int) ([]Order, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	status = strings.TrimSpace(status)
	rows, err := s.db.QueryContext(ctx,
		`SELECT o.id, o.client_id, COALESCE(c.name, ''), o.title, o.description, o.status,
		        o.price, o.due_date, o.created_at, o.updated_at
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
	return out, rows.Err()
}

func (s *Store) Order(ctx context.Context, id int64) (Order, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT o.id, o.client_id, COALESCE(c.name, ''), o.title, o.description, o.status,
		        o.price, o.due_date, o.created_at, o.updated_at
		 FROM orders o LEFT JOIN clients c ON c.id = o.client_id
		 WHERE o.id = ?`, id)
	if err != nil {
		return Order{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Order{}, ErrNotFound
	}
	return scanOrder(rows)
}

func (s *Store) CreateOrder(ctx context.Context, o Order) (Order, error) {
	ts := now()
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO orders (client_id, title, description, status, price, due_date, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		o.ClientID, o.Title, o.Description, defaultStatus(o.Status), o.Price, o.DueDate, ts, ts)
	if err != nil {
		return Order{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Order{}, err
	}
	return s.Order(ctx, id)
}

func (s *Store) UpdateOrder(ctx context.Context, id int64, o Order) (Order, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE orders SET client_id = ?, title = ?, description = ?, status = ?, price = ?, due_date = ?, updated_at = ?
		 WHERE id = ?`,
		o.ClientID, o.Title, o.Description, defaultStatus(o.Status), o.Price, o.DueDate, now(), id)
	if err != nil {
		return Order{}, err
	}
	if err := affected(res); err != nil {
		return Order{}, err
	}
	return s.Order(ctx, id)
}

func (s *Store) DeleteOrder(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM orders WHERE id = ?`, id)
	if err != nil {
		return err
	}
	return affected(res)
}

func scanOrder(rows *sql.Rows) (Order, error) {
	var o Order
	var clientID sql.NullInt64
	if err := rows.Scan(&o.ID, &clientID, &o.ClientName, &o.Title, &o.Description,
		&o.Status, &o.Price, &o.DueDate, &o.CreatedAt, &o.UpdatedAt); err != nil {
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
	return s.Request(ctx, id)
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
	return s.Request(ctx, id)
}

func (s *Store) DeleteRequest(ctx context.Context, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM requests WHERE id = ?`, id)
	if err != nil {
		return err
	}
	return affected(res)
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
			(SELECT COALESCE(SUM(price), 0) FROM orders WHERE status = 'done'),
			(SELECT COALESCE(SUM(price), 0) FROM orders WHERE status IN ('new', 'in_progress'))
	`).Scan(&st.Clients, &st.Orders, &st.OrdersActive, &st.OrdersDone,
		&st.RequestsNew, &st.RevenueTotal, &st.RevenueActive)
	return st, err
}
