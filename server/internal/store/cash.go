package store

import (
	"context"
	"database/sql"
	"strings"
)

// Направления движения денег и способы оплаты.
const (
	DirectionIn  = "in"
	DirectionOut = "out"

	MethodCash    = "cash"
	MethodCard    = "card"
	MethodAccount = "account"
)

func ValidDirection(v string) bool { return v == DirectionIn || v == DirectionOut }

func ValidMethod(v string) bool {
	switch v {
	case MethodCash, MethodCard, MethodAccount:
		return true
	}
	return false
}

// CashOp — строка кассы. Сумма всегда положительная, знак несёт Direction:
// так отчёт по статьям складывается без разбора знаков, а «расход −500»
// нельзя ввести случайно.
type CashOp struct {
	ID         int64  `json:"id"`
	Direction  string `json:"direction"`
	AmountKop  int64  `json:"amountKop"`
	Method     string `json:"method"`
	Category   string `json:"category"`
	OrderID    *int64 `json:"orderId"`
	ClientID   *int64 `json:"clientId"`
	Note       string `json:"note"`
	HappenedAt string `json:"happenedAt"`
	CreatedAt  string `json:"createdAt"`

	// Заполняются при чтении, чтобы список читался без обращений к другим таблицам.
	OrderTitle string `json:"orderTitle,omitempty"`
	ClientName string `json:"clientName,omitempty"`
}

// CashFilter — что показать в списке операций.
type CashFilter struct {
	From      string // включительно, RFC3339 или дата
	To        string // включительно
	Direction string // пусто — оба
	Limit     int
}

const cashSelect = `
	SELECT p.id, p.direction, p.amount_kop, p.method, p.category, p.order_id, p.client_id,
	       p.note, p.happened_at, p.created_at,
	       COALESCE(o.title, ''), COALESCE(c.name, COALESCE(oc.name, ''))
	FROM cash_ops p
	LEFT JOIN orders o ON o.id = p.order_id
	LEFT JOIN clients c ON c.id = p.client_id
	LEFT JOIN clients oc ON oc.id = o.client_id`

// CashOps отдаёт операции по фильтру, свежие сверху.
func (s *Store) CashOps(ctx context.Context, f CashFilter) ([]CashOp, error) {
	where, args := cashWhere(f)
	query := cashSelect + where + " ORDER BY p.happened_at DESC, p.id DESC LIMIT ?"
	args = append(args, limitOrDefault(f.Limit, 200))

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]CashOp, 0, 32)
	for rows.Next() {
		op, err := scanCashOp(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, op)
	}
	return out, rows.Err()
}

// CashTotals — приход, расход и остаток за период. Пустой период — за всё время.
func (s *Store) CashTotals(ctx context.Context, f CashFilter) (income, expense int64, err error) {
	f.Direction = ""
	where, args := cashWhere(f)
	err = s.db.QueryRowContext(ctx, `
		SELECT COALESCE(SUM(CASE WHEN direction = 'in' THEN amount_kop ELSE 0 END), 0),
		       COALESCE(SUM(CASE WHEN direction = 'out' THEN amount_kop ELSE 0 END), 0)
		FROM cash_ops p`+where, args...).Scan(&income, &expense)
	return income, expense, err
}

// CashBalance — сколько денег на руках на конец периода (или сейчас).
// Считается как приход минус расход с самого начала: остаток нигде
// не хранится, иначе он однажды разойдётся с историей операций.
func (s *Store) CashBalance(ctx context.Context, until string) (int64, error) {
	income, expense, err := s.CashTotals(ctx, CashFilter{To: until})
	return income - expense, err
}

func (s *Store) CashOp(ctx context.Context, id int64) (CashOp, error) {
	rows, err := s.db.QueryContext(ctx, cashSelect+" WHERE p.id = ?", id)
	if err != nil {
		return CashOp{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return CashOp{}, err
		}
		return CashOp{}, ErrNotFound
	}
	return scanCashOp(rows)
}

func (s *Store) AddCashOp(ctx context.Context, op CashOp) (CashOp, error) {
	if !ValidDirection(op.Direction) {
		op.Direction = DirectionIn
	}
	if !ValidMethod(op.Method) {
		op.Method = MethodCash
	}
	happened := strings.TrimSpace(op.HappenedAt)
	if happened == "" {
		happened = now()
	}

	res, err := s.db.ExecContext(ctx,
		`INSERT INTO cash_ops (direction, amount_kop, method, category, order_id, client_id, note, happened_at, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		op.Direction, op.AmountKop, op.Method, strings.TrimSpace(op.Category),
		op.OrderID, op.ClientID, op.Note, happened, now())
	if err != nil {
		return CashOp{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return CashOp{}, err
	}
	s.AddAudit(ctx, "cash", id, "create", cashText(op))
	return s.CashOp(ctx, id)
}

func (s *Store) DeleteCashOp(ctx context.Context, id int64) error {
	op, err := s.CashOp(ctx, id)
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
	s.AddAudit(ctx, "cash", id, "delete", cashText(op))
	return nil
}

// CategoryTotal — сколько прошло по статье за период.
type CategoryTotal struct {
	Category  string `json:"category"`
	Direction string `json:"direction"`
	AmountKop int64  `json:"amountKop"`
	Count     int64  `json:"count"`
}

// CashByCategory — разбивка за период. Пустая статья показывается как «без статьи»:
// такие записи есть всегда, и прятать их значило бы терять часть суммы.
func (s *Store) CashByCategory(ctx context.Context, f CashFilter) ([]CategoryTotal, error) {
	where, args := cashWhere(f)
	rows, err := s.db.QueryContext(ctx, `
		SELECT CASE WHEN TRIM(category) = '' THEN 'без статьи' ELSE category END,
		       direction, COALESCE(SUM(amount_kop), 0), COUNT(*)
		FROM cash_ops p`+where+`
		GROUP BY 1, 2
		ORDER BY 2, 3 DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]CategoryTotal, 0, 16)
	for rows.Next() {
		var t CategoryTotal
		if err := rows.Scan(&t.Category, &t.Direction, &t.AmountKop, &t.Count); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// CashByMethod — сколько прошло наличными, картой и по счёту.
func (s *Store) CashByMethod(ctx context.Context, f CashFilter) ([]CategoryTotal, error) {
	where, args := cashWhere(f)
	rows, err := s.db.QueryContext(ctx, `
		SELECT method, direction, COALESCE(SUM(amount_kop), 0), COUNT(*)
		FROM cash_ops p`+where+`
		GROUP BY 1, 2
		ORDER BY 2, 3 DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]CategoryTotal, 0, 8)
	for rows.Next() {
		var t CategoryTotal
		if err := rows.Scan(&t.Category, &t.Direction, &t.AmountKop, &t.Count); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// cashWhere собирает условие по периоду и направлению.
// Границы включительные: «с 1 по 30 сентября» должно захватывать оба дня,
// поэтому к верхней дате дописываем конец суток.
func cashWhere(f CashFilter) (string, []any) {
	var parts []string
	var args []any
	if from := strings.TrimSpace(f.From); from != "" {
		parts = append(parts, "p.happened_at >= ?")
		args = append(args, from)
	}
	if to := strings.TrimSpace(f.To); to != "" {
		parts = append(parts, "p.happened_at <= ?")
		args = append(args, dayEnd(to))
	}
	if ValidDirection(f.Direction) {
		parts = append(parts, "p.direction = ?")
		args = append(args, f.Direction)
	}
	if len(parts) == 0 {
		return "", args
	}
	return " WHERE " + strings.Join(parts, " AND "), args
}

// dayEnd дописывает конец суток к голой дате: «2026-09-30» превращается
// в «2026-09-30T23:59:59Z», иначе операции последнего дня в отчёт не попадут.
func dayEnd(value string) string {
	if len(value) == 10 && !strings.Contains(value, "T") {
		return value + "T23:59:59Z"
	}
	return value
}

func scanCashOp(rows *sql.Rows) (CashOp, error) {
	var op CashOp
	var orderID, clientID sql.NullInt64
	if err := rows.Scan(&op.ID, &op.Direction, &op.AmountKop, &op.Method, &op.Category,
		&orderID, &clientID, &op.Note, &op.HappenedAt, &op.CreatedAt,
		&op.OrderTitle, &op.ClientName); err != nil {
		return CashOp{}, err
	}
	if orderID.Valid {
		id := orderID.Int64
		op.OrderID = &id
	}
	if clientID.Valid {
		id := clientID.Int64
		op.ClientID = &id
	}
	return op, nil
}

func cashText(op CashOp) string {
	sign := "+"
	if op.Direction == DirectionOut {
		sign = "-"
	}
	text := sign + moneyText(op.AmountKop)
	if c := strings.TrimSpace(op.Category); c != "" {
		text += " · " + c
	}
	return text
}
