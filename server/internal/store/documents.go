package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Виды печатных документов.
const (
	DocInvoice        = "invoice"        // счёт на оплату
	DocAct            = "act"            // акт выполненных работ
	DocEstimate       = "estimate"       // смета / коммерческое предложение
	DocWaybill        = "waybill"        // товарная накладная
	DocUPD            = "upd"            // универсальный передаточный документ
	DocReconciliation = "reconciliation" // акт сверки взаиморасчётов
)

// docKinds — реестр видов: валидация значения и человеческое название
// в одном месте. Новый вид добавляется сюда и получает шаблон в пакете
// documents — больше нигде перечень не дублируется.
var docKinds = map[string]string{
	DocInvoice:        "Счёт на оплату",
	DocAct:            "Акт выполненных работ",
	DocEstimate:       "Смета",
	DocWaybill:        "Накладная",
	DocUPD:            "УПД",
	DocReconciliation: "Акт сверки",
}

// ValidDocKind отвечает, знаком ли нам такой вид документа.
func ValidDocKind(kind string) bool { _, ok := docKinds[kind]; return ok }

// DocKindTitle — русское название вида для списков и журнала.
func DocKindTitle(kind string) string { return docKinds[kind] }

// Статусы документа. Черновик редактируется свободно; проведение закрепляет
// номер и замораживает содержимое; аннулирование помечает недействительным,
// номер после этого не переиспользуется.
const (
	DocDraft    = "draft"
	DocIssued   = "issued"
	DocAnnulled = "annulled"
)

// ErrNotDraft — попытка изменить или удалить уже проведённый документ.
var ErrNotDraft = errors.New("документ проведён и не редактируется")

// Document — запись журнала документов. Содержимое печатной формы лежит
// в Snapshot (JSON) и наружу отдаётся только в карточке одного документа.
type Document struct {
	ID         int64   `json:"id"`
	OrderID    *int64  `json:"orderId"`
	ClientID   *int64  `json:"clientId"`
	ClientName string  `json:"clientName"`
	OrderTitle string  `json:"orderTitle"`
	Kind       string  `json:"kind"`
	KindTitle  string  `json:"kindTitle"`
	Status     string  `json:"status"`
	Year       int     `json:"year"`
	Number     int64   `json:"number"` // 0 у черновика
	Title      string  `json:"title"`
	TotalKop   int64   `json:"totalKop"`
	DocDate    string  `json:"docDate"` // YYYY-MM-DD
	PeriodFrom string  `json:"periodFrom"`
	PeriodTo   string  `json:"periodTo"`
	Snapshot   string  `json:"snapshot,omitempty"`
	IssuedAt   string  `json:"issuedAt"`
	CreatedAt  string  `json:"createdAt"`
	UpdatedAt  string  `json:"updatedAt"`
}

// DocumentFilter — параметры журнала документов.
type DocumentFilter struct {
	Kind     string
	Status   string
	ClientID int64
	OrderID  int64
	From     string // по дате документа, включительно
	To       string
	Query    string // по названию, клиенту и номеру
	Limit    int
	Offset   int
}

const documentColumns = `d.id, d.order_id, d.client_id, COALESCE(c.name, ''), COALESCE(o.title, ''),
	d.kind, d.status, d.year, COALESCE(d.number, 0), d.title, d.total_kop, d.doc_date,
	d.period_from, d.period_to, d.issued_at, d.created_at, d.updated_at`

func scanDocument(rows *sql.Rows) (Document, error) {
	var d Document
	err := rows.Scan(&d.ID, &d.OrderID, &d.ClientID, &d.ClientName, &d.OrderTitle,
		&d.Kind, &d.Status, &d.Year, &d.Number, &d.Title, &d.TotalKop, &d.DocDate,
		&d.PeriodFrom, &d.PeriodTo, &d.IssuedAt, &d.CreatedAt, &d.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Document{}, ErrNotFound
	}
	d.KindTitle = DocKindTitle(d.Kind)
	return d, err
}

// ListDocuments — журнал документов с фильтрами. Пустой фильтр — всё
// свежими первыми.
func (s *Store) ListDocuments(ctx context.Context, f DocumentFilter) ([]Document, error) {
	if f.Limit <= 0 || f.Limit > 500 {
		f.Limit = 100
	}
	where := []string{"1=1"}
	args := []any{}
	if f.Kind != "" {
		where = append(where, "d.kind = ?")
		args = append(args, f.Kind)
	}
	if f.Status != "" {
		where = append(where, "d.status = ?")
		args = append(args, f.Status)
	}
	if f.ClientID != 0 {
		where = append(where, "d.client_id = ?")
		args = append(args, f.ClientID)
	}
	if f.OrderID != 0 {
		where = append(where, "d.order_id = ?")
		args = append(args, f.OrderID)
	}
	if f.From != "" {
		where = append(where, "d.doc_date >= ?")
		args = append(args, f.From)
	}
	if f.To != "" {
		where = append(where, "d.doc_date <= ?")
		args = append(args, f.To)
	}
	if q := strings.TrimSpace(f.Query); q != "" {
		where = append(where, `(d.title LIKE ? OR c.name LIKE ? OR CAST(d.number AS TEXT) = ?)`)
		like := "%" + q + "%"
		args = append(args, like, like, q)
	}

	rows, err := s.db.QueryContext(ctx,
		`SELECT `+documentColumns+`
		 FROM documents d
		 LEFT JOIN clients c ON c.id = d.client_id
		 LEFT JOIN orders o ON o.id = d.order_id
		 WHERE `+strings.Join(where, " AND ")+`
		 ORDER BY d.updated_at DESC, d.id DESC
		 LIMIT ? OFFSET ?`,
		append(args, f.Limit, f.Offset)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Document, 0, 16)
	for rows.Next() {
		d, err := scanDocument(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// DocumentByID — карточка документа, вместе со снимком содержимого.
func (s *Store) DocumentByID(ctx context.Context, id int64) (Document, error) {
	query := `SELECT ` + documentColumns + `, d.snapshot
		 FROM documents d
		 LEFT JOIN clients c ON c.id = d.client_id
		 LEFT JOIN orders o ON o.id = d.order_id
		 WHERE d.id = ?`
	rows, err := s.db.QueryContext(ctx, query, id)
	if err != nil {
		return Document{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Document{}, ErrNotFound
	}
	var d Document
	err = rows.Scan(&d.ID, &d.OrderID, &d.ClientID, &d.ClientName, &d.OrderTitle,
		&d.Kind, &d.Status, &d.Year, &d.Number, &d.Title, &d.TotalKop, &d.DocDate,
		&d.PeriodFrom, &d.PeriodTo, &d.IssuedAt, &d.CreatedAt, &d.UpdatedAt, &d.Snapshot)
	if errors.Is(err, sql.ErrNoRows) {
		return Document{}, ErrNotFound
	}
	d.KindTitle = DocKindTitle(d.Kind)
	return d, err
}

// CreateDocument заводит черновик. Содержимое (снимок) передаётся уже
// собранным: HTTP-слой знает, как строить печатную форму, хранилищу достаточно
// сохранить её вместе с итогом.
func (s *Store) CreateDocument(ctx context.Context, d Document) (Document, error) {
	if !ValidDocKind(d.Kind) {
		return Document{}, fmt.Errorf("%w: неизвестный вид документа", ErrNotFound)
	}
	if d.Status == "" {
		d.Status = DocDraft
	}
	if d.DocDate == "" {
		d.DocDate = time.Now().Format("2006-01-02")
	}
	ts := now()
	d.CreatedAt, d.UpdatedAt = ts, ts

	res, err := s.db.ExecContext(ctx,
		`INSERT INTO documents (order_id, client_id, kind, status, title, total_kop,
		                        doc_date, period_from, period_to, snapshot, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		d.OrderID, d.ClientID, d.Kind, d.Status, d.Title, d.TotalKop,
		d.DocDate, d.PeriodFrom, d.PeriodTo, d.Snapshot, ts, ts)
	if err != nil {
		return Document{}, fmt.Errorf("создание документа: %w", err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Document{}, err
	}
	s.AddAudit(ctx, "document", id, "create", docAuditText(d))
	return s.DocumentByID(ctx, id)
}

// UpdateDocument правит черновик: заменяет дату, период и снимок содержимого.
func (s *Store) UpdateDocument(ctx context.Context, id int64, d Document) (Document, error) {
	current, err := s.DocumentByID(ctx, id)
	if err != nil {
		return Document{}, err
	}
	if current.Status != DocDraft {
		return Document{}, ErrNotDraft
	}
	if _, err := s.db.ExecContext(ctx,
		`UPDATE documents
		 SET title = ?, total_kop = ?, doc_date = ?, period_from = ?, period_to = ?,
		     snapshot = ?, updated_at = ?
		 WHERE id = ?`,
		d.Title, d.TotalKop, d.DocDate, d.PeriodFrom, d.PeriodTo, d.Snapshot, now(), id); err != nil {
		return Document{}, fmt.Errorf("правка документа: %w", err)
	}
	s.AddAudit(ctx, "document", id, "update", docAuditText(d))
	return s.DocumentByID(ctx, id)
}

// IssueDocumentByID проводит черновик: присваивает номер по виду и году,
// фиксирует дату. Содержимое к этому моменту уже собрано в снимке. Номер
// берётся следующий по максимуму — аннулированные номера занимают свои
// места и не переиспользуются.
func (s *Store) IssueDocumentByID(ctx context.Context, id int64) (Document, error) {
	current, err := s.DocumentByID(ctx, id)
	if err != nil {
		return Document{}, err
	}
	switch current.Status {
	case DocIssued:
		return current, nil // уже проведён — идемпотентно
	case DocAnnulled:
		return Document{}, ErrNotDraft
	}

	year := time.Now().Year()
	if current.DocDate != "" {
		if t, err := time.Parse("2006-01-02", current.DocDate); err == nil {
			year = t.Year()
		}
	}
	var next int64
	if err := s.db.QueryRowContext(ctx,
		`SELECT COALESCE(MAX(number), 0) + 1 FROM documents WHERE kind = ? AND year = ?`,
		current.Kind, year).Scan(&next); err != nil {
		return Document{}, err
	}

	ts := now()
	if _, err := s.db.ExecContext(ctx,
		`UPDATE documents
		 SET status = 'issued', year = ?, number = ?, issued_at = ?, updated_at = ?
		 WHERE id = ?`,
		year, next, ts, ts, id); err != nil {
		return Document{}, fmt.Errorf("проведение документа: %w", err)
	}
	issued, err := s.DocumentByID(ctx, id)
	if err != nil {
		return Document{}, err
	}
	s.AddAudit(ctx, "document", id, "issue", docAuditText(issued))
	return issued, nil
}

// AnnulDocument помечает документ аннулированным. Проводить компенсирующие
// учётные записи (возврат склада, сторно долга) будет слой выше, когда у вида
// появятся учётные эффекты.
func (s *Store) AnnulDocument(ctx context.Context, id int64) (Document, error) {
	current, err := s.DocumentByID(ctx, id)
	if err != nil {
		return Document{}, err
	}
	if current.Status == DocAnnulled {
		return current, nil
	}
	if current.Status == DocDraft {
		return Document{}, fmt.Errorf("%w: черновик можно только удалить", ErrNotDraft)
	}
	if _, err := s.db.ExecContext(ctx,
		`UPDATE documents SET status = 'annulled', updated_at = ? WHERE id = ?`,
		now(), id); err != nil {
		return Document{}, fmt.Errorf("аннулирование документа: %w", err)
	}
	annulled, err := s.DocumentByID(ctx, id)
	if err != nil {
		return Document{}, err
	}
	s.AddAudit(ctx, "document", id, "annul", docAuditText(annulled))
	return annulled, nil
}

// DeleteDocument убирает черновик. Проведённые документы не удаляются —
// только аннулируются: журнал обязан сохранять историю.
func (s *Store) DeleteDocument(ctx context.Context, id int64) error {
	current, err := s.DocumentByID(ctx, id)
	if err != nil {
		return err
	}
	if current.Status != DocDraft {
		return ErrNotDraft
	}
	res, err := s.db.ExecContext(ctx, `DELETE FROM documents WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "document", id, "delete", docAuditText(current))
	return nil
}

// IssueDocument отдаёт номер документа по заказу, выдавая новый только
// при первом обращении. Нумерация сквозная в пределах вида и года.
// Легаси-путь для приложений, которые открывают счёт и акт прямо из карточки
// заказа: документ создаётся сразу проведённым.
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

	order, err := s.Order(ctx, orderID)
	if err != nil {
		return Document{}, err
	}

	year := time.Now().Year()
	var next int64
	if err := s.db.QueryRowContext(ctx,
		`SELECT COALESCE(MAX(number), 0) + 1 FROM documents WHERE kind = ? AND year = ?`,
		kind, year).Scan(&next); err != nil {
		return Document{}, err
	}

	ts := now()
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO documents (order_id, client_id, kind, status, year, number, title, doc_date, issued_at, created_at, updated_at)
		 VALUES (?, ?, ?, 'issued', ?, ?, '', ?, ?, ?, ?)`,
		orderID, order.ClientID, kind, year, next, ts[:10], ts, ts, ts); err != nil {
		return Document{}, fmt.Errorf("выдача номера документа: %w", err)
	}
	doc, err = s.documentFor(ctx, orderID, kind)
	if err != nil {
		return Document{}, err
	}
	s.AddAudit(ctx, "document", doc.ID, "create", docAuditText(doc))
	return doc, nil
}

// documentFor ищет действующий документ вида по заказу.
func (s *Store) documentFor(ctx context.Context, orderID int64, kind string) (Document, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+documentColumns+`
		 FROM documents d
		 LEFT JOIN clients c ON c.id = d.client_id
		 LEFT JOIN orders o ON o.id = d.order_id
		 WHERE d.order_id = ? AND d.kind = ? AND d.status != 'annulled'`,
		orderID, kind)
	if err != nil {
		return Document{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Document{}, ErrNotFound
	}
	return scanDocument(rows)
}

// RefreshDocumentSnapshot обновляет заголовок и снимок содержания у уже
// проведённого документа. Единственный потребитель — легаси-ручка счёта
// и акта по заказу: она всегда собирала документ из живых данных, и её
// поведение менять нельзя — установленные приложения на него рассчитывают.
func (s *Store) RefreshDocumentSnapshot(ctx context.Context, id int64, title string, totalKop int64, snapshot string) error {
	if _, err := s.db.ExecContext(ctx,
		`UPDATE documents
		 SET title = ?, total_kop = ?, snapshot = ?, updated_at = ?
		 WHERE id = ?`,
		title, totalKop, snapshot, now(), id); err != nil {
		return fmt.Errorf("обновление снимка документа: %w", err)
	}
	return nil
}

// SetDocumentOrder привязывает документ к заказу — так смета превращается
// в заказ, не теряя истории: номер сметы остаётся в журнале.
func (s *Store) SetDocumentOrder(ctx context.Context, id, orderID int64) (Document, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE documents SET order_id = ?, updated_at = ? WHERE id = ?`,
		orderID, now(), id)
	if err != nil {
		return Document{}, fmt.Errorf("привязка документа к заказу: %w", err)
	}
	if err := affected(res); err != nil {
		return Document{}, err
	}
	return s.DocumentByID(ctx, id)
}

// docAuditText — строка для журнала действий.
func docAuditText(d Document) string {
	if d.Number > 0 {
		return fmt.Sprintf("%s №%d", DocKindTitle(d.Kind), d.Number)
	}
	return DocKindTitle(d.Kind) + " (черновик)"
}
