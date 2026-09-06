package store

import (
	"context"
	"strings"
)

// PeriodReport — что произошло за период. Собирается из тех же таблиц,
// по которым работает приложение: отдельного «отчётного» хранилища нет,
// и разойтись отчёту с данными не с чем.
type PeriodReport struct {
	From string `json:"from"`
	To   string `json:"to"`

	// Касса: сколько было на начало, сколько пришло и ушло, сколько осталось.
	OpeningKop int64 `json:"openingKop"`
	IncomeKop  int64 `json:"incomeKop"`
	ExpenseKop int64 `json:"expenseKop"`
	ClosingKop int64 `json:"closingKop"`

	ByCategory []CategoryTotal `json:"byCategory"`
	ByMethod   []CategoryTotal `json:"byMethod"`

	// Выручка — по заказам, закрытым в периоде. Отличается от прихода денег:
	// заказ могли закрыть в сентябре, а оплатить в октябре.
	RevenueKop   int64 `json:"revenueKop"`
	OrdersClosed int64 `json:"ordersClosed"`
	CostKop      int64 `json:"costKop"`

	// Долг клиентов на конец периода — по всем незакрытым заказам.
	DebtKop    int64 `json:"debtKop"`
	DebtOrders int64 `json:"debtOrders"`

	Materials []MaterialUsage `json:"materials"`
}

// ProfitKop — сколько заработано на закрытых заказах: выручка минус закупка.
// Это не прибыль: расходы кассы сюда не входят, они отдельной строкой.
func (r PeriodReport) ProfitKop() int64 { return r.RevenueKop - r.CostKop }

// MaterialUsage — сколько материала израсходовано за период и на какую сумму.
type MaterialUsage struct {
	Name      string `json:"name"`
	Unit      string `json:"unit"`
	QtyMilli  int64  `json:"qtyMilli"`
	CostKop   int64  `json:"costKop"`
	StockLeft int64  `json:"stockLeftMilli"`
}

// PeriodReportFor собирает отчёт. Границы включительные, обе можно опустить.
func (s *Store) PeriodReportFor(ctx context.Context, from, to string) (PeriodReport, error) {
	out := PeriodReport{From: from, To: to}
	period := CashFilter{From: from, To: to}

	income, expense, err := s.CashTotals(ctx, period)
	if err != nil {
		return out, err
	}
	out.IncomeKop, out.ExpenseKop = income, expense

	// Остаток на начало — всё, что было до первого дня периода.
	if strings.TrimSpace(from) != "" {
		var beforeIn, beforeOut int64
		if err := s.db.QueryRowContext(ctx, `
			SELECT COALESCE(SUM(CASE WHEN direction = 'in' THEN amount_kop ELSE 0 END), 0),
			       COALESCE(SUM(CASE WHEN direction = 'out' THEN amount_kop ELSE 0 END), 0)
			FROM cash_ops WHERE happened_at < ?`, from).Scan(&beforeIn, &beforeOut); err != nil {
			return out, err
		}
		out.OpeningKop = beforeIn - beforeOut
	}
	out.ClosingKop = out.OpeningKop + income - expense

	if out.ByCategory, err = s.CashByCategory(ctx, period); err != nil {
		return out, err
	}
	if out.ByMethod, err = s.CashByMethod(ctx, period); err != nil {
		return out, err
	}

	// Выручка и себестоимость по заказам, закрытым в периоде.
	revenueWhere, revenueArgs := periodWhere("o.closed_at", from, to)
	if err := s.db.QueryRowContext(ctx, `
		SELECT COUNT(*), COALESCE(SUM(o.price_kop), 0),
		       COALESCE(SUM((SELECT COALESCE(SUM((i.qty_milli * i.cost_kop + 500) / 1000), 0)
		                     FROM order_items i WHERE i.order_id = o.id)), 0)
		FROM orders o
		WHERE o.status = 'done' AND o.closed_at != ''`+revenueWhere,
		revenueArgs...).Scan(&out.OrdersClosed, &out.RevenueKop, &out.CostKop); err != nil {
		return out, err
	}

	// Долг — состояние на сейчас, а не за период: он не «происходит»,
	// он просто есть, и в отчёте нужен как остаток на конец.
	if err := s.db.QueryRowContext(ctx, debtSelect).Scan(&out.DebtKop); err != nil {
		return out, err
	}
	if err := s.db.QueryRowContext(ctx, `
		SELECT COUNT(*)
		FROM orders o
		LEFT JOIN (SELECT order_id, SUM(amount_kop) AS paid FROM cash_ops
		           WHERE direction = 'in' AND order_id IS NOT NULL GROUP BY order_id) p
		       ON p.order_id = o.id
		WHERE o.status != 'canceled' AND o.price_kop > COALESCE(p.paid, 0)`).
		Scan(&out.DebtOrders); err != nil {
		return out, err
	}

	if out.Materials, err = s.materialUsage(ctx, from, to); err != nil {
		return out, err
	}
	return out, nil
}

// materialUsage — что списано со склада за период. Приход не считаем:
// вопрос «сколько материала ушло в работу» отдельный от «сколько закупили».
func (s *Store) materialUsage(ctx context.Context, from, to string) ([]MaterialUsage, error) {
	where, args := periodWhere("m.created_at", from, to)
	rows, err := s.db.QueryContext(ctx, `
		SELECT i.name, i.unit,
		       COALESCE(SUM(-m.qty_milli), 0),
		       COALESCE(SUM(m.cost_kop), 0),
		       COALESCE((SELECT SUM(a.qty_milli) FROM stock_moves a WHERE a.item_id = i.id), 0)
		FROM stock_moves m
		JOIN catalog_items i ON i.id = m.item_id
		WHERE m.qty_milli < 0`+where+`
		GROUP BY i.id
		ORDER BY 4 DESC, 1`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]MaterialUsage, 0, 16)
	for rows.Next() {
		var u MaterialUsage
		if err := rows.Scan(&u.Name, &u.Unit, &u.QtyMilli, &u.CostKop, &u.StockLeft); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// periodWhere добавляет ограничение по периоду к уже начатому WHERE.
func periodWhere(column, from, to string) (string, []any) {
	var parts []string
	var args []any
	if f := strings.TrimSpace(from); f != "" {
		parts = append(parts, " AND "+column+" >= ?")
		args = append(args, f)
	}
	if t := strings.TrimSpace(to); t != "" {
		parts = append(parts, " AND "+column+" <= ?")
		args = append(args, dayEnd(t))
	}
	return strings.Join(parts, ""), args
}
