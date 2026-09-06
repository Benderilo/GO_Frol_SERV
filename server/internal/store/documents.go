package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// Виды печатных документов.
const (
	DocInvoice = "invoice" // счёт на оплату
	DocAct     = "act"     // акт выполненных работ
)

// ValidDocKind отвечает, знаком ли нам такой вид документа.
func ValidDocKind(kind string) bool { return kind == DocInvoice || kind == DocAct }

// Document — выданный номер. Сам документ каждый раз собирается заново
// из текущих данных заказа: исправили опечатку — перепечатали, номер тот же.
type Document struct {
	ID       int64  `json:"id"`
	OrderID  int64  `json:"orderId"`
	Kind     string `json:"kind"`
	Year     int    `json:"year"`
	Number   int    `json:"number"`
	IssuedAt string `json:"issuedAt"`
}

// IssueDocument отдаёт номер документа по заказу, выдавая новый только
// при первом обращении. Нумерация сквозная в пределах вида и года.
func (s *Store) IssueDocument(ctx context.Context, orderID int64, kind string) (Document, error) {
	if !ValidDocKind(kind) {
		return Document{}, fmt.Errorf("%w: неизвестный вид документа", ErrNotFound)
	}

	doc, err := s.documentFor(ctx, orderID, kind)
	if err == nil {
		return doc, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return Document{}, err
	}

	if _, err := s.Order(ctx, orderID); err != nil {
		return Document{}, err
	}

	year := time.Now().Year()
	var next int
	if err := s.db.QueryRowContext(ctx,
		`SELECT COALESCE(MAX(number), 0) + 1 FROM documents WHERE kind = ? AND year = ?`,
		kind, year).Scan(&next); err != nil {
		return Document{}, err
	}

	ts := now()
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO documents (order_id, kind, year, number, issued_at) VALUES (?, ?, ?, ?, ?)`,
		orderID, kind, year, next, ts); err != nil {
		return Document{}, fmt.Errorf("выдача номера документа: %w", err)
	}
	s.AddAudit(ctx, "document", orderID, "create", fmt.Sprintf("%s №%d", kind, next))
	return s.documentFor(ctx, orderID, kind)
}

func (s *Store) documentFor(ctx context.Context, orderID int64, kind string) (Document, error) {
	var d Document
	err := s.db.QueryRowContext(ctx,
		`SELECT id, order_id, kind, year, number, issued_at
		 FROM documents WHERE order_id = ? AND kind = ?`, orderID, kind).
		Scan(&d.ID, &d.OrderID, &d.Kind, &d.Year, &d.Number, &d.IssuedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Document{}, ErrNotFound
	}
	return d, err
}
