package store

import (
	"context"
	"strings"
)

// Запросы, которыми пользуется только ядро отчётов. Лежат рядом с доменами,
// чтобы SQL не разбредался по пакету reports: хранилище знает про копейки
// и тысячные, отчёты — про секции и колонки.

// OrderProfitRow — строка отчёта «Прибыль по заказам».
type OrderProfitRow struct {
	ID         int64
	ClientName string
	Title      string
	Status     string
	PriceKop   int64
	CostKop    int64
	PaidKop    int64
}

// OrderProfits — заказы, закрытые в периоде. Пустой период — все заказы,
// свежими первыми: такой список тоже полезен — «что вообще есть».
func (s *Store) OrderProfits(ctx context.Context, from, to string) ([]OrderProfitRow, error) {
	where, args := periodWhere("o.closed_at", from, to)
	if from == "" && to == "" {
		where = " AND o.status != 'canceled'"
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT o.id, COALESCE(c.name, ''), o.title, o.status, o.price_kop,
		       COALESCE((SELECT SUM((i.qty_milli * i.cost_kop + 500) / 1000)
		                  FROM order_items i WHERE i.order_id = o.id), 0),
		       COALESCE((SELECT SUM(p.amount_kop) FROM cash_ops p
		                  WHERE p.order_id = o.id AND p.direction = 'in'), 0)
		FROM orders o
		LEFT JOIN clients c ON c.id = o.client_id
		WHERE 1=1`+where+`
		ORDER BY o.closed_at DESC, o.updated_at DESC
		LIMIT 500`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]OrderProfitRow, 0, 16)
	for rows.Next() {
		var r OrderProfitRow
		if err := rows.Scan(&r.ID, &r.ClientName, &r.Title, &r.Status, &r.PriceKop, &r.CostKop, &r.PaidKop); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// StockReportRow — строка отчёта по складу.
type StockReportRow struct {
	Name         string
	Unit         string
	Kind         string
	StockMilli   int64 // остаток на сейчас, всей историей
	InMilli      int64 // приход за период
	OutMilli     int64 // расход за период (положительным числом)
	CostKop      int64 // закупочная цена единицы
	StockValueKop int64 // стоимость остатка по закупочной
}

// StockReport — остатки и движение по каждой позиции.
func (s *Store) StockReport(ctx context.Context, from, to string) ([]StockReportRow, error) {
	// Движения считаем подзапросами с собственным периодом: общий WHERE
	// отсёк бы позиции без движения за период, а остаток нужен по всем.
	moveWhere, moveArgs := periodWhere("m.created_at", from, to)
	rows, err := s.db.QueryContext(ctx, `
		SELECT i.name, i.unit, i.kind, i.cost_kop,
		       COALESCE((SELECT SUM(a.qty_milli) FROM stock_moves a WHERE a.item_id = i.id), 0),
		       COALESCE((SELECT SUM(CASE WHEN m.qty_milli > 0 THEN m.qty_milli ELSE 0 END)
		                  FROM stock_moves m WHERE m.item_id = i.id`+moveWhere+`), 0),
		       COALESCE((SELECT SUM(CASE WHEN m.qty_milli < 0 THEN -m.qty_milli ELSE 0 END)
		                  FROM stock_moves m WHERE m.item_id = i.id`+moveWhere+`), 0)
		FROM catalog_items i
		WHERE i.archived = 0
		ORDER BY i.kind, i.name`, append(moveArgs, moveArgs...)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]StockReportRow, 0, 16)
	for rows.Next() {
		var r StockReportRow
		if err := rows.Scan(&r.Name, &r.Unit, &r.Kind, &r.CostKop, &r.StockMilli, &r.InMilli, &r.OutMilli); err != nil {
			return nil, err
		}
		// Стоимость остатка: qty (тысячные) * цена (копейки) / 1000.
		abs := r.StockMilli
		if abs < 0 {
			abs = -abs
		}
		value := (abs * r.CostKop) / 1000
		if r.StockMilli < 0 {
			value = -value
		}
		r.StockValueKop = value
		out = append(out, r)
	}
	return out, rows.Err()
}

// moveWhereSub переделывает условие periodWhere для подзапроса с алиасом m.
func moveWhereSub(where string) string {
	return strings.ReplaceAll(where, "m.created_at", "m.created_at")
}

// ClientBalanceRow — строка отчёта «Взаиморасчёты с клиентами».
type ClientBalanceRow struct {
	ID          int64
	Name        string
	OrdersCount int64
	BilledKop   int64 // цена всех заказов, кроме отменённых
	PaidKop     int64 // все поступления: напрямую и через заказы
	SaldoKop    int64
}

// ClientBalanceRows — сальдо по всем клиентам, у кого были заказы или оплаты.
func (s *Store) ClientBalanceRows(ctx context.Context) ([]ClientBalanceRow, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.id, c.name,
		       (SELECT COUNT(*) FROM orders o WHERE o.client_id = c.id AND o.status != 'canceled'),
		       (SELECT COALESCE(SUM(o.price_kop), 0) FROM orders o
		          WHERE o.client_id = c.id AND o.status != 'canceled'),
		       (SELECT COALESCE(SUM(p.amount_kop), 0) FROM cash_ops p
		          LEFT JOIN orders po ON po.id = p.order_id
		          WHERE p.direction = 'in' AND (p.client_id = c.id OR po.client_id = c.id))
		FROM clients c
		ORDER BY c.name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]ClientBalanceRow, 0, 16)
	for rows.Next() {
		var r ClientBalanceRow
		if err := rows.Scan(&r.ID, &r.Name, &r.OrdersCount, &r.BilledKop, &r.PaidKop); err != nil {
			return nil, err
		}
		if r.OrdersCount == 0 && r.PaidKop == 0 {
			continue
		}
		r.SaldoKop = r.BilledKop - r.PaidKop
		out = append(out, r)
	}
	return out, rows.Err()
}

// AccountBalance — остаток на одном счёте хранения денег.
type AccountBalance struct {
	Method    string `json:"method"`
	InKop     int64  `json:"inKop"`
	OutKop    int64  `json:"outKop"`
	BalanceKop int64 `json:"balanceKop"`
}

// AccountBalances — остатки кассы, карты и счёта. Пустой период — с начала
// времён (тогда это балансы «сейчас»); с границами — приход/расход за период
// и остаток на его конец.
func (s *Store) AccountBalances(ctx context.Context, from, to string) ([]AccountBalance, error) {
	var where string
	var args []any
	if f := strings.TrimSpace(from); f != "" {
		where += " AND p.happened_at >= ?"
		args = append(args, f)
	}
	if t := strings.TrimSpace(to); t != "" {
		where += " AND p.happened_at <= ?"
		args = append(args, dayEnd(t))
	}

	// Остаток на конец периода = движение внутри периода минус то, что
	// ушло до его начала. Вторым запросом вычитаем «до периода».
	rows, err := s.db.QueryContext(ctx, `
		SELECT p.method,
		       COALESCE(SUM(CASE WHEN p.direction = 'in' THEN p.amount_kop ELSE 0 END), 0),
		       COALESCE(SUM(CASE WHEN p.direction = 'out' THEN p.amount_kop ELSE 0 END), 0)
		FROM cash_ops p
		WHERE 1=1`+where+`
		GROUP BY p.method`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	byMethod := map[string]*AccountBalance{}
	order := []string{}
	for rows.Next() {
		var m string
		var in, out int64
		if err := rows.Scan(&m, &in, &out); err != nil {
			return nil, err
		}
		byMethod[m] = &AccountBalance{Method: m, InKop: in, OutKop: out}
		order = append(order, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	if strings.TrimSpace(from) != "" {
		before, err := s.db.QueryContext(ctx, `
			SELECT p.method,
			       COALESCE(SUM(CASE WHEN p.direction = 'in' THEN p.amount_kop ELSE 0 END), 0),
			       COALESCE(SUM(CASE WHEN p.direction = 'out' THEN p.amount_kop ELSE 0 END), 0)
			FROM cash_ops p
			WHERE p.happened_at < ?
			GROUP BY p.method`, from)
		if err != nil {
			return nil, err
		}
		for before.Next() {
			var m string
			var in, out int64
			if err := before.Scan(&m, &in, &out); err != nil {
				before.Close()
				return nil, err
			}
			if acc, ok := byMethod[m]; ok {
				acc.InKop += in
				acc.OutKop += out
			} else {
				byMethod[m] = &AccountBalance{Method: m, InKop: in, OutKop: out}
				order = append(order, m)
			}
		}
		before.Close()
	}

	out := make([]AccountBalance, 0, len(order))
	for _, m := range []string{MethodCash, MethodCard, MethodAccount} {
		acc, ok := byMethod[m]
		if !ok {
			continue
		}
		acc.BalanceKop = acc.InKop - acc.OutKop
		out = append(out, *acc)
	}
	return out, nil
}
