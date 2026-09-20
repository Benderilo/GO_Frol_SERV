package store

import (
	"context"
	"sort"
	"strconv"
)

// Взаиморасчёты с клиентом — основа акта сверки и сальдо в карточке.
// Начисление (дебет) — заказы, кроме отменённых; оплата (кредит) —
// поступления денег: кассовая операция с клиентом или с его заказом
// (платёж по заказу исторически не писет client_id, поэтому смотрим обоими
// путями). Сальдо = дебет − кредит: положительное — долг клиента,
// отрицательное — переплата (аванс).

// LedgerEntry — одна операция взаиморасчётов.
type LedgerEntry struct {
	Date      string // YYYY-MM-DD
	Label     string
	DebitKop  int64
	CreditKop int64
}

// ClientLedger — операции за период и сальдо на его начало.
type ClientLedger struct {
	OpeningKop int64
	Rows       []LedgerEntry
}

// ClientLedgerFor собирает операции клиента за период [from, to] включительно.
// Пустая граница означает «с начала» и «до конца» соответственно.
func (s *Store) ClientLedgerFor(ctx context.Context, clientID int64, from, to string) (ClientLedger, error) {
	// Начисления: заказы, кроме отменённых. Дата заказа — момент создания:
	// обязательство появляется, когда работа согласована.
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, title, price_kop, substr(created_at, 1, 10)
		 FROM orders
		 WHERE client_id = ? AND status != 'canceled' AND price_kop != 0`, clientID)
	if err != nil {
		return ClientLedger{}, err
	}
	var all []LedgerEntry
	for rows.Next() {
		var id int64
		var title string
		var kop int64
		var date string
		if err := rows.Scan(&id, &title, &kop, &date); err != nil {
			rows.Close()
			return ClientLedger{}, err
		}
		all = append(all, LedgerEntry{
			Date:     date,
			Label:    "Заказ № " + strconv.FormatInt(id, 10) + " «" + title + "»",
			DebitKop: kop,
		})
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return ClientLedger{}, err
	}
	rows.Close()

	// Оплаты: поступления с клиентом напрямую или через его заказы.
	rows, err = s.db.QueryContext(ctx,
		`SELECT c.order_id, c.note, c.amount_kop, substr(c.happened_at, 1, 10)
		 FROM cash_ops c
		 LEFT JOIN orders o ON o.id = c.order_id
		 WHERE c.direction = 'in' AND (c.client_id = ? OR o.client_id = ?)`, clientID, clientID)
	if err != nil {
		return ClientLedger{}, err
	}
	for rows.Next() {
		var orderID *int64
		var note string
		var kop int64
		var date string
		if err := rows.Scan(&orderID, &note, &kop, &date); err != nil {
			rows.Close()
			return ClientLedger{}, err
		}
		label := "Оплата"
		if orderID != nil {
			label = "Оплата заказа № " + strconv.FormatInt(*orderID, 10)
		}
		if note != "" {
			label += " — " + note
		}
		all = append(all, LedgerEntry{Date: date, Label: label, CreditKop: kop})
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return ClientLedger{}, err
	}
	rows.Close()

	sort.SliceStable(all, func(i, j int) bool { return all[i].Date < all[j].Date })

	ledger := ClientLedger{Rows: make([]LedgerEntry, 0, len(all))}
	for _, e := range all {
		if from != "" && e.Date < from {
			ledger.OpeningKop += e.DebitKop - e.CreditKop
			continue
		}
		if to != "" && e.Date > to {
			continue
		}
		ledger.Rows = append(ledger.Rows, e)
	}
	return ledger, nil
}

// ClientBalance — сальдо клиента на сегодня: долг (плюс) или аванс (минус).
func (s *Store) ClientBalance(ctx context.Context, clientID int64) (int64, error) {
	ledger, err := s.ClientLedgerFor(ctx, clientID, "", "")
	if err != nil {
		return 0, err
	}
	var balance int64
	for _, e := range ledger.Rows {
		balance += e.DebitKop - e.CreditKop
	}
	return balance, nil
}
