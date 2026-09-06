package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
)

// ---------- Платежи ----------

// debtSelect — общая часть запросов про задолженность: незакрытые заказы,
// где оплачено меньше цены. Просрочка уточняется по due_date снаружи.
const debtSelect = `
	SELECT COALESCE(SUM(o.price_kop - p.paid), 0)
	FROM orders o
	LEFT JOIN (SELECT order_id, SUM(amount_kop) AS paid FROM cash_ops
	                 WHERE direction = 'in' AND order_id IS NOT NULL GROUP BY order_id) p
	       ON p.order_id = o.id
	WHERE o.status != 'canceled' AND o.price_kop > COALESCE(p.paid, 0)`

// AddPayment фиксирует поступление денег по заказу.
func (s *Store) AddPayment(ctx context.Context, orderID int64, p Payment) (Payment, error) {
	if _, err := s.Order(ctx, orderID); err != nil {
		return Payment{}, err
	}
	ts := now()
	if p.CreatedAt != "" {
		ts = p.CreatedAt
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO cash_ops (direction, amount_kop, method, order_id, note, happened_at, created_at)
		 VALUES ('in', ?, 'cash', ?, ?, ?, ?)`,
		p.AmountKop, orderID, p.Note, ts, now())
	if err != nil {
		return Payment{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Payment{}, err
	}
	s.AddAudit(ctx, "payment", id, "create", moneyText(p.AmountKop))
	return s.Payment(ctx, id)
}

// Payment — один платёж по его id.
func (s *Store) Payment(ctx context.Context, id int64) (Payment, error) {
	var p Payment
	err := s.db.QueryRowContext(ctx,
		`SELECT id, COALESCE(order_id, 0), amount_kop, note, happened_at
		 FROM cash_ops WHERE id = ? AND direction = 'in'`, id).
		Scan(&p.ID, &p.OrderID, &p.AmountKop, &p.Note, &p.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Payment{}, ErrNotFound
	}
	return p, err
}

// OrderPayments — все поступления по заказу, свежие сверху.
func (s *Store) OrderPayments(ctx context.Context, orderID int64) ([]Payment, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, COALESCE(order_id, 0), amount_kop, note, happened_at
		 FROM cash_ops WHERE order_id = ? AND direction = 'in' ORDER BY id DESC`, orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Payment, 0, 4)
	for rows.Next() {
		var p Payment
		if err := rows.Scan(&p.ID, &p.OrderID, &p.AmountKop, &p.Note, &p.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// DeletePayment убирает ошибочно внесённое поступление.
func (s *Store) DeletePayment(ctx context.Context, id int64) error {
	p, err := s.Payment(ctx, id)
	if err != nil {
		return err
	}
	res, err := s.db.ExecContext(ctx, `DELETE FROM cash_ops WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "payment", id, "delete", moneyText(p.AmountKop))
	return nil
}

// PaymentsForExport — все платежи, для листа «Платежи» в выгрузке.
// Название заказа и клиента подтягиваются, чтобы лист читался без базы.
func (s *Store) PaymentsForExport(ctx context.Context) ([]Payment, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT p.id, COALESCE(p.order_id, 0), p.amount_kop, p.note, p.happened_at,
		        COALESCE(o.title, ''), COALESCE(c.name, '')
		 FROM cash_ops p
		 LEFT JOIN orders o ON o.id = p.order_id
		 LEFT JOIN clients c ON c.id = o.client_id
		 WHERE p.direction = 'in'
		 ORDER BY p.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Payment, 0, 64)
	for rows.Next() {
		var p Payment
		if err := rows.Scan(&p.ID, &p.OrderID, &p.AmountKop, &p.Note, &p.CreatedAt,
			&p.OrderTitle, &p.ClientName); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// UpsertPayment вставляет платёж с сохранением id (резервная копия из Excel)
// или обновляет сумму и заметку существующего. Историю платежей загрузка
// не перетирает: меняется только совпадающая запись по id.
func (s *Store) UpsertPayment(ctx context.Context, p Payment) (created bool, err error) {
	if p.OrderID == 0 {
		return false, ErrNotFound
	}
	if _, err := s.Order(ctx, p.OrderID); err != nil {
		return false, err
	}

	if p.ID == 0 {
		_, err := s.AddPayment(ctx, p.OrderID, p)
		return err == nil, err
	}

	if _, err := s.Payment(ctx, p.ID); err == nil {
		_, err := s.db.ExecContext(ctx,
			`UPDATE cash_ops SET order_id = ?, amount_kop = ?, note = ? WHERE id = ?`,
			p.OrderID, p.AmountKop, p.Note, p.ID)
		return false, err
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	ts := now()
	if p.CreatedAt != "" {
		ts = p.CreatedAt
	}
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO cash_ops (id, direction, amount_kop, method, order_id, note, happened_at, created_at)
		 VALUES (?, 'in', ?, 'cash', ?, ?, ?, ?)`,
		p.ID, p.OrderID, p.AmountKop, p.Note, ts)
	return true, err
}

// moneyText — сумма для журнала действий: «12500 ₽», «1250.50 ₽».
// На вход копейки: копейки печатаем только когда они есть.
func moneyText(kop int64) string {
	sign := ""
	if kop < 0 {
		sign, kop = "-", -kop
	}
	if kop%100 == 0 {
		return fmt.Sprintf("%s%d ₽", sign, kop/100)
	}
	return fmt.Sprintf("%s%d.%02d ₽", sign, kop/100, kop%100)
}
