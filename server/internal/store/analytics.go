package store

import (
	"context"
	"fmt"
	"time"
)

// ---------- Аналитика ----------

// Analytics — расширенная сводка для экрана аналитики в приложении.
type Analytics struct {
	GeneratedAt string         `json:"generatedAt"`
	Orders      OrderStats     `json:"orders"`
	Revenue     RevenueStats   `json:"revenue"`
	Payments    PaymentStats   `json:"payments"`
	Debt        DebtStats      `json:"debt"`
	Clients     ClientStats    `json:"clients"`
	Requests    RequestStats   `json:"requests"`
	Monthly     []MonthPoint   `json:"monthly"`
	TopClients  []ClientTotals `json:"topClients"`
}

// OrderStats — распределение заказов по статусам и средний чек.
type OrderStats struct {
	Total          int64   `json:"total"`
	New            int64   `json:"new"`
	InProgress     int64   `json:"inProgress"`
	Done           int64   `json:"done"`
	Canceled       int64   `json:"canceled"`
	CompletionRate float64 `json:"completionRate"` // доля завершённых, %
	AvgPriceKop    int64   `json:"avgPriceKop"`    // средний чек завершённого заказа, копейки
	AvgCycleDays   float64 `json:"avgCycleDays"`   // средний срок от создания до закрытия, дней
}

// RevenueStats — деньги: закрытые, в работе и динамика по месяцам.
type RevenueStats struct {
	TotalKop     int64   `json:"totalKop"`     // завершённые заказы
	ActiveKop    int64   `json:"activeKop"`    // новые и в работе
	AvgOrderKop  int64   `json:"avgOrderKop"`  // средний чек завершённого
	ThisMonthKop int64   `json:"thisMonthKop"` // закрыто в текущем месяце
	LastMonthKop int64   `json:"lastMonthKop"` // закрыто в прошлом месяце
	GrowthPct    float64 `json:"growthPct"`    // изменение к прошлому месяцу, %
}

// PaymentStats — факт поступления денег по платежам.
type PaymentStats struct {
	TotalKop     int64   `json:"totalKop"`     // поступило за всё время
	ThisMonthKop int64   `json:"thisMonthKop"` // поступило в текущем месяце
	LastMonthKop int64   `json:"lastMonthKop"` // поступило в прошлом месяце
	GrowthPct    float64 `json:"growthPct"`    // изменение к прошлому месяцу, %
}

// DebtStats — что должны клиенты.
type DebtStats struct {
	OutstandingKop int64 `json:"outstandingKop"` // всего задолженность
	OverdueKop     int64 `json:"overdueKop"`     // из неё по заказам с прошедшим сроком
	OrdersCount    int64 `json:"ordersWithDebt"` // заказов с задолженностью
}

// ClientStats — база клиентов.
type ClientStats struct {
	Total        int64   `json:"total"`
	NewThisMonth int64   `json:"newThisMonth"`
	WithOrders   int64   `json:"withOrders"` // есть хотя бы один заказ
	Repeat       int64   `json:"repeat"`     // заказов два и больше
	RepeatRate   float64 `json:"repeatRate"` // доля повторных от клиентов с заказами, %
}

// RequestStats — заявки с сайта и их обработка.
type RequestStats struct {
	Total      int64   `json:"total"`
	New        int64   `json:"new"`
	InProgress int64   `json:"inProgress"`
	Done       int64   `json:"done"`
	Spam       int64   `json:"spam"`
	Conversion float64 `json:"conversion"` // обработанные от неспамовых, %
}

// MonthPoint — точка помесячного ряда за последние 12 месяцев.
// Месяц закрытия заказа берётся из closed_at: он фиксируется в момент
// перевода в «завершён» и не сдвигается последующими правками.
type MonthPoint struct {
	Month         string `json:"month"`         // «2026-08»
	OrdersCreated int64  `json:"ordersCreated"` // создано заказов
	OrdersDone    int64  `json:"ordersDone"`    // закрыто заказов
	RevenueKop    int64  `json:"revenueKop"`    // выручка закрытых
	PaymentsKop   int64  `json:"paymentsKop"`   // поступило денег
	NewClients    int64  `json:"newClients"`    // новых клиентов
	Requests      int64  `json:"requests"`      // заявок с сайта
}

// ClientTotals — клиент из топа по выручке.
type ClientTotals struct {
	ClientID       int64  `json:"clientId"`
	Name           string `json:"name"`
	OrdersTotal    int64  `json:"ordersTotal"`
	RevenueDoneKop int64  `json:"revenueDoneKop"`
	RevenueAllKop  int64  `json:"revenueAllKop"`
}

// monthKeyOffset считает ключ месяца «2006-01» на monthsBack назад.
// time.AddDate здесь не годится: 31 марта минус месяц «перепрыгнет»
// на 3 марта, и месяц посчитается неверно.
func monthKeyOffset(t time.Time, monthsBack int) string {
	year, month, _ := t.Date()
	idx := int(month) - monthsBack
	for idx <= 0 {
		idx += 12
		year--
	}
	return fmt.Sprintf("%04d-%02d", year, idx)
}

// Analytics собирает расширенную сводку. База локальная, запросы дешёвые,
// поэтому всё считается за один вызов без кеша.
func (s *Store) Analytics(ctx context.Context) (Analytics, error) {
	var out Analytics
	out.GeneratedAt = now()

	// ---------- Заказы: статусы и деньги ----------
	var revenueDoneKop, revenueActiveKop int64
	err := s.db.QueryRowContext(ctx, `
		SELECT
			(SELECT COUNT(*) FROM orders),
			(SELECT COALESCE(SUM(price_kop), 0) FROM orders WHERE status = 'done'),
			(SELECT COALESCE(SUM(price_kop), 0) FROM orders WHERE status IN ('new', 'in_progress'))
	`).Scan(&out.Orders.Total, &revenueDoneKop, &revenueActiveKop)
	if err != nil {
		return out, err
	}
	out.Revenue.TotalKop = revenueDoneKop
	out.Revenue.ActiveKop = revenueActiveKop

	rows, err := s.db.QueryContext(ctx, `SELECT status, COUNT(*) FROM orders GROUP BY status`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var status string
		var n int64
		if err := rows.Scan(&status, &n); err != nil {
			rows.Close()
			return out, err
		}
		switch status {
		case "new":
			out.Orders.New = n
		case "in_progress":
			out.Orders.InProgress = n
		case "done":
			out.Orders.Done = n
		case "canceled":
			out.Orders.Canceled = n
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	if out.Orders.Total > 0 {
		out.Orders.CompletionRate = float64(out.Orders.Done) * 100 / float64(out.Orders.Total)
	}
	if out.Orders.Done > 0 {
		out.Orders.AvgPriceKop = divRound(revenueDoneKop, out.Orders.Done)
		out.Revenue.AvgOrderKop = out.Orders.AvgPriceKop
	}

	// Средний срок жизни заказа: от создания до закрытия, в днях.
	// closed_at заполняется с этого релиза, у старых заказов он равен
	// updated_at на момент миграции — точность для старых данных приблизительная.
	err = s.db.QueryRowContext(ctx, `
		SELECT COALESCE(AVG(julianday(COALESCE(NULLIF(closed_at, ''), updated_at)) - julianday(created_at)), 0)
		FROM orders WHERE status = 'done' AND closed_at != ''`).Scan(&out.Orders.AvgCycleDays)
	if err != nil {
		return out, err
	}

	// ---------- Помесячные ряды ----------
	created := map[string]int64{}    // заказы по месяцу создания
	doneCnt := map[string]int64{}    // закрытые заказы по месяцу закрытия
	doneRevKop := map[string]int64{} // выручка закрытых по месяцу закрытия

	rows, err = s.db.QueryContext(ctx,
		`SELECT COALESCE(strftime('%Y-%m', created_at), ''), COUNT(*)
		 FROM orders GROUP BY 1`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var key string
		var n int64
		if err := rows.Scan(&key, &n); err != nil {
			rows.Close()
			return out, err
		}
		if key != "" {
			created[key] = n
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	rows, err = s.db.QueryContext(ctx,
		`SELECT COALESCE(strftime('%Y-%m', COALESCE(NULLIF(closed_at, ''), updated_at)), ''), COUNT(*), COALESCE(SUM(price_kop), 0)
		 FROM orders WHERE status = 'done' GROUP BY 1`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var key string
		var n, sumKop int64
		if err := rows.Scan(&key, &n, &sumKop); err != nil {
			rows.Close()
			return out, err
		}
		if key != "" {
			doneCnt[key] = n
			doneRevKop[key] = sumKop
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	nowUTC := time.Now().UTC()
	thisMonth := monthKeyOffset(nowUTC, 0)
	lastMonth := monthKeyOffset(nowUTC, 1)
	out.Revenue.ThisMonthKop = doneRevKop[thisMonth]
	out.Revenue.LastMonthKop = doneRevKop[lastMonth]
	out.Revenue.GrowthPct = growthPct(out.Revenue.ThisMonthKop, out.Revenue.LastMonthKop)

	// ---------- Платежи ----------
	paymentsByMonthKop := map[string]int64{}
	rows, err = s.db.QueryContext(ctx,
		`SELECT COALESCE(strftime('%Y-%m', happened_at), ''), COALESCE(SUM(amount_kop), 0)
		 FROM cash_ops WHERE direction = 'in' GROUP BY 1`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var key string
		var sumKop int64
		if err := rows.Scan(&key, &sumKop); err != nil {
			rows.Close()
			return out, err
		}
		if key != "" {
			paymentsByMonthKop[key] = sumKop
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	for _, sumKop := range paymentsByMonthKop {
		out.Payments.TotalKop += sumKop
	}
	out.Payments.ThisMonthKop = paymentsByMonthKop[thisMonth]
	out.Payments.LastMonthKop = paymentsByMonthKop[lastMonth]
	out.Payments.GrowthPct = growthPct(out.Payments.ThisMonthKop, out.Payments.LastMonthKop)

	// ---------- Задолженность ----------
	err = s.db.QueryRowContext(ctx, debtSelect).Scan(&out.Debt.OutstandingKop)
	if err != nil {
		return out, err
	}
	err = s.db.QueryRowContext(ctx,
		debtSelect+` AND o.due_date != '' AND o.due_date < ?`, now()).Scan(&out.Debt.OverdueKop)
	if err != nil {
		return out, err
	}
	err = s.db.QueryRowContext(ctx, `
		SELECT COUNT(*)
		FROM orders o
		LEFT JOIN (SELECT order_id, SUM(amount_kop) AS paid FROM cash_ops
		           WHERE direction = 'in' AND order_id IS NOT NULL GROUP BY order_id) p
		       ON p.order_id = o.id
		WHERE o.status != 'canceled' AND o.price_kop > COALESCE(p.paid, 0)`).
		Scan(&out.Debt.OrdersCount)
	if err != nil {
		return out, err
	}

	newClients := map[string]int64{}
	rows, err = s.db.QueryContext(ctx,
		`SELECT COALESCE(strftime('%Y-%m', created_at), ''), COUNT(*) FROM clients GROUP BY 1`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var key string
		var n int64
		if err := rows.Scan(&key, &n); err != nil {
			rows.Close()
			return out, err
		}
		if key != "" {
			newClients[key] = n
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	requestsByMonth := map[string]int64{}
	rows, err = s.db.QueryContext(ctx,
		`SELECT COALESCE(strftime('%Y-%m', created_at), ''), COUNT(*) FROM requests GROUP BY 1`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var key string
		var n int64
		if err := rows.Scan(&key, &n); err != nil {
			rows.Close()
			return out, err
		}
		if key != "" {
			requestsByMonth[key] = n
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	// Ряд за 12 месяцев: от старого к свежему — так читаются графики.
	out.Monthly = make([]MonthPoint, 0, 12)
	for i := 11; i >= 0; i-- {
		key := monthKeyOffset(nowUTC, i)
		out.Monthly = append(out.Monthly, MonthPoint{
			Month:         key,
			OrdersCreated: created[key],
			OrdersDone:    doneCnt[key],
			RevenueKop:    doneRevKop[key],
			PaymentsKop:   paymentsByMonthKop[key],
			NewClients:    newClients[key],
			Requests:      requestsByMonth[key],
		})
	}

	// ---------- Клиенты ----------
	err = s.db.QueryRowContext(ctx, `
		SELECT
			(SELECT COUNT(*) FROM clients),
			(SELECT COUNT(*) FROM clients WHERE strftime('%Y-%m', created_at) = ?),
			(SELECT COUNT(DISTINCT client_id) FROM orders WHERE client_id IS NOT NULL),
			(SELECT COUNT(*) FROM (
				SELECT client_id FROM orders WHERE client_id IS NOT NULL
				GROUP BY client_id HAVING COUNT(*) >= 2))
	`, thisMonth).Scan(&out.Clients.Total, &out.Clients.NewThisMonth, &out.Clients.WithOrders, &out.Clients.Repeat)
	if err != nil {
		return out, err
	}
	if out.Clients.WithOrders > 0 {
		out.Clients.RepeatRate = float64(out.Clients.Repeat) * 100 / float64(out.Clients.WithOrders)
	}

	// ---------- Заявки ----------
	rows, err = s.db.QueryContext(ctx, `SELECT status, COUNT(*) FROM requests GROUP BY status`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var status string
		var n int64
		if err := rows.Scan(&status, &n); err != nil {
			rows.Close()
			return out, err
		}
		switch status {
		case "new":
			out.Requests.New = n
		case "in_progress":
			out.Requests.InProgress = n
		case "done":
			out.Requests.Done = n
		case "spam":
			out.Requests.Spam = n
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}
	out.Requests.Total = out.Requests.New + out.Requests.InProgress + out.Requests.Done + out.Requests.Spam
	if base := out.Requests.Total - out.Requests.Spam; base > 0 {
		out.Requests.Conversion = float64(out.Requests.Done) * 100 / float64(base)
	}

	// ---------- Топ клиентов по выручке ----------
	out.TopClients = []ClientTotals{}
	rows, err = s.db.QueryContext(ctx, `
		SELECT c.id, c.name, COUNT(o.id),
		       COALESCE(SUM(CASE WHEN o.status = 'done' THEN o.price_kop ELSE 0 END), 0),
		       COALESCE(SUM(o.price_kop), 0)
		FROM clients c
		JOIN orders o ON o.client_id = c.id
		GROUP BY c.id
		ORDER BY 4 DESC, 3 DESC
		LIMIT 10`)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var t ClientTotals
		if err := rows.Scan(&t.ClientID, &t.Name, &t.OrdersTotal, &t.RevenueDoneKop, &t.RevenueAllKop); err != nil {
			rows.Close()
			return out, err
		}
		out.TopClients = append(out.TopClients, t)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return out, err
	}

	return out, nil
}

// divRound делит копейки на количество и округляет до копейки: целочисленное
// деление «в пол» на средних чеках заметно уводит сумму вниз.
func divRound(sum, count int64) int64 {
	if count == 0 {
		return 0
	}
	return (sum + count/2) / count
}

// growthPct — изменение к прошлому месяцу в процентах. Единственное место,
// где из копеек получается дробное число: это доля, а не деньги.
func growthPct(now, before int64) float64 {
	if before == 0 {
		return 0
	}
	return float64(now-before) * 100 / float64(before)
}
