package reports

import (
	"context"
	"fmt"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// definitionList — сам реестр. Отчёт добавляется сюда и только сюда:
// список, прогон и экспорт читают реестр, отдельного кода «под экран» нет.
var definitionList = []Definition{
	{
		ID:          "period",
		Title:       "Отчёт за период",
		Description: "Касса, заказы, долги и материалы за период. Как в приложении было раньше, но рядом с остальными отчётами.",
		Params:      []Param{{Kind: ParamPeriod, Label: "Период"}},
		Build:       buildPeriod,
	},
	{
		ID:          "orders-profit",
		Title:       "Прибыль по заказам",
		Description: "Каждый заказ: выручка, себестоимость материалов и работ, заработок.",
		Params:      []Param{{Kind: ParamPeriod, Label: "Период закрытия"}},
		Build:       buildOrdersProfit,
	},
	{
		ID:          "stock",
		Title:       "Склад: остатки и движение",
		Description: "Остаток каждой позиции, приход и расход за период, стоимость остатка.",
		Params:      []Param{{Kind: ParamPeriod, Label: "Период движения"}},
		Build:       buildStock,
	},
	{
		ID:          "documents",
		Title:       "Журнал документов",
		Description: "Все счета, акты, сметы, накладные, УПД и сверки за период с номерами и суммами.",
		Params:      []Param{{Kind: ParamPeriod, Label: "Период по дате документа"}},
		Build:       buildDocuments,
	},
	{
		ID:          "client-balances",
		Title:       "Взаиморасчёты с клиентами",
		Description: "Начислено, оплачено и сальдо по каждому клиенту; по одному клиенту — подробная таблица операций.",
		Params:      []Param{{Kind: ParamClient, Label: "Клиент (необязательно)"}},
		Build:       buildClientBalances,
	},
}

// buildPeriod — перенос классического отчёта за период в единую модель.
func buildPeriod(ctx context.Context, st *store.Store, p Params) (Result, error) {
	r, err := st.PeriodReportFor(ctx, p.From, p.To)
	if err != nil {
		return Result{}, err
	}

	result := Result{Title: "Отчёт за период", Subtitle: p.subtitle()}

	cash := Section{
		Title: "Касса",
		Columns: []Column{
			{Key: "name", Label: "Показатель", Kind: KindText},
			{Key: "value", Label: "Сумма, ₽", Kind: KindMoney},
		},
		Rows: [][]Cell{
			{text("Было на начало"), money(r.OpeningKop)},
			{text("Пришло"), money(r.IncomeKop)},
			{text("Ушло"), money(r.ExpenseKop)},
			{text("Осталось на конец"), money(r.ClosingKop)},
		},
	}

	accounts, accErr := st.AccountBalances(ctx, p.From, p.To)
	if accErr == nil && len(accounts) > 0 {
		for _, a := range accounts {
			cash.Rows = append(cash.Rows, []Cell{
				text("  из них «" + accountName(a.Method) + "»"),
				money(a.BalanceKop),
			})
		}
	}
	result.Sections = append(result.Sections, cash)

	orders := Section{
		Title: "Заказы",
		Columns: []Column{
			{Key: "name", Label: "Показатель", Kind: KindText},
			{Key: "value", Label: "Значение", Kind: KindMoney},
		},
		Rows: [][]Cell{
			{text("Закрыто заказов"), count(r.OrdersClosed)},
			{text("Выручка"), money(r.RevenueKop)},
			{text("Закупка (себестоимость)"), money(r.CostKop)},
			{text("Заработок"), money(r.ProfitKop())},
		},
	}
	result.Sections = append(result.Sections, orders)

	if r.DebtKop > 0 {
		result.Sections = append(result.Sections, Section{
			Title: "Долги клиентов",
			Columns: []Column{
				{Key: "name", Label: "Показатель", Kind: KindText},
				{Key: "value", Label: "Сумма, ₽", Kind: KindMoney},
			},
			Rows: [][]Cell{
				{text(fmt.Sprintf("Задолженность (%d заказов с долгом)", r.DebtOrders)), money(r.DebtKop)},
			},
		})
	}

	if len(r.ByCategory) > 0 {
		section := Section{
			Title: "По статьям",
			Columns: []Column{
				{Key: "category", Label: "Статья", Kind: KindText},
				{Key: "direction", Label: "Направление", Kind: KindText},
				{Key: "amount", Label: "Сумма, ₽", Kind: KindMoney},
				{Key: "count", Label: "Операций", Kind: KindCount},
			},
		}
		for _, c := range r.ByCategory {
			dir := "расход"
			if c.Direction == store.DirectionIn {
				dir = "приход"
			}
			section.Rows = append(section.Rows, []Cell{
				text(c.Category), text(dir), money(c.AmountKop), count(c.Count),
			})
		}
		result.Sections = append(result.Sections, section)
	}

	if len(r.Materials) > 0 {
		section := Section{
			Title: "Материалы",
			Columns: []Column{
				{Key: "name", Label: "Материал", Kind: KindText},
				{Key: "used", Label: "Израсходовано", Kind: KindQty},
				{Key: "cost", Label: "Сумма закупки, ₽", Kind: KindMoney},
				{Key: "left", Label: "Остаток", Kind: KindQty},
			},
		}
		for _, m := range r.Materials {
			section.Rows = append(section.Rows, []Cell{
				text(m.Name), qty(m.QtyMilli), money(m.CostKop), qty(m.StockLeft),
			})
		}
		result.Sections = append(result.Sections, section)
	}

	return result, nil
}

// buildOrdersProfit — выручка и заработок по каждому заказу.
func buildOrdersProfit(ctx context.Context, st *store.Store, p Params) (Result, error) {
	rows, err := st.OrderProfits(ctx, p.From, p.To)
	if err != nil {
		return Result{}, err
	}

	section := Section{
		Title: "Заказы",
		Columns: []Column{
			{Key: "order", Label: "Заказ", Kind: KindText},
			{Key: "client", Label: "Клиент", Kind: KindText},
			{Key: "status", Label: "Статус", Kind: KindText},
			{Key: "revenue", Label: "Выручка, ₽", Kind: KindMoney},
			{Key: "cost", Label: "Себестоимость, ₽", Kind: KindMoney},
			{Key: "profit", Label: "Заработок, ₽", Kind: KindMoney},
		},
		Note: "Себестоимость — закупочные цены позиций состава. Заработок — выручка минус себестоимость; расходы кассы здесь не учтены.",
	}
	var revenue, cost int64
	for _, o := range rows {
		section.Rows = append(section.Rows, []Cell{
			text(fmt.Sprintf("№ %d · %s", o.ID, o.Title)),
			text(o.ClientName),
			text(orderStatusText(o.Status)),
			money(o.PriceKop),
			money(o.CostKop),
			money(o.PriceKop - o.CostKop),
		})
		revenue += o.PriceKop
		cost += o.CostKop
	}
	section.Total = []Cell{
		text("Итого"), text(""), text(""), money(revenue), money(cost), money(revenue - cost),
	}

	result := Result{Title: "Прибыль по заказам", Subtitle: p.subtitle()}
	if len(rows) == 0 {
		section.Note = "За период нет закрытых заказов."
	}
	result.Sections = append(result.Sections, section)
	return result, nil
}

// buildStock — остатки и движение склада.
func buildStock(ctx context.Context, st *store.Store, p Params) (Result, error) {
	rows, err := st.StockReport(ctx, p.From, p.To)
	if err != nil {
		return Result{}, err
	}

	section := Section{
		Title: "Склад",
		Columns: []Column{
			{Key: "name", Label: "Позиция", Kind: KindText},
			{Key: "unit", Label: "Ед.", Kind: KindText},
			{Key: "stock", Label: "Остаток", Kind: KindQty},
			{Key: "in", Label: "Приход за период", Kind: KindQty},
			{Key: "out", Label: "Расход за период", Kind: KindQty},
			{Key: "value", Label: "Стоимость остатка, ₽", Kind: KindMoney},
		},
	}
	var stockValue int64
	for _, r := range rows {
		section.Rows = append(section.Rows, []Cell{
			text(r.Name), text(r.Unit), qty(r.StockMilli), qty(r.InMilli), qty(r.OutMilli),
			money(r.StockValueKop),
		})
		stockValue += r.StockValueKop
	}
	section.Total = []Cell{text("Итого"), text(""), qty(0), qty(0), qty(0), money(stockValue)}

	result := Result{Title: "Склад: остатки и движение", Subtitle: p.subtitle()}
	result.Sections = append(result.Sections, section)
	return result, nil
}

// buildDocuments — журнал документов за период.
func buildDocuments(ctx context.Context, st *store.Store, p Params) (Result, error) {
	docs, err := st.ListDocuments(ctx, store.DocumentFilter{From: p.From, To: p.To, Limit: 500})
	if err != nil {
		return Result{}, err
	}

	section := Section{
		Title: "Документы",
		Columns: []Column{
			{Key: "kind", Label: "Вид", Kind: KindText},
			{Key: "number", Label: "Номер", Kind: KindText},
			{Key: "date", Label: "Дата", Kind: KindText},
			{Key: "client", Label: "Клиент", Kind: KindText},
			{Key: "status", Label: "Статус", Kind: KindText},
			{Key: "total", Label: "Сумма, ₽", Kind: KindMoney},
		},
	}
	var total int64
	for _, d := range docs {
		number := "черновик"
		if d.Number > 0 {
			number = fmt.Sprintf("%d", d.Number)
		}
		section.Rows = append(section.Rows, []Cell{
			text(d.KindTitle),
			text(number),
			text(d.DocDate),
			text(d.ClientName),
			text(docStatusText(d.Status)),
			money(d.TotalKop),
		})
		if d.Status == store.DocIssued {
			total += d.TotalKop
		}
	}
	section.Note = fmt.Sprintf("Всего документов: %d. В итог вошли только действующие.", len(docs))

	result := Result{Title: "Журнал документов", Subtitle: p.subtitle()}
	result.Sections = append(result.Sections, section)
	return result, nil
}

// buildClientBalances — сальдо всех клиентов или подробная таблица одного.
func buildClientBalances(ctx context.Context, st *store.Store, p Params) (Result, error) {
	result := Result{Title: "Взаиморасчёты с клиентами", Subtitle: p.subtitle()}

	if p.ClientID != 0 {
		ledger, err := st.ClientLedgerFor(ctx, p.ClientID, "", "")
		if err != nil {
			return Result{}, err
		}
		section := Section{
			Title: "Операции",
			Columns: []Column{
				{Key: "date", Label: "Дата", Kind: KindText},
				{Key: "label", Label: "Операция", Kind: KindText},
				{Key: "debit", Label: "Начислено, ₽", Kind: KindMoney},
				{Key: "credit", Label: "Оплачено, ₽", Kind: KindMoney},
			},
		}
		var balance int64
		for _, e := range ledger.Rows {
			balance += e.DebitKop - e.CreditKop
			section.Rows = append(section.Rows, []Cell{
				text(e.Date), text(e.Label), money(e.DebitKop), money(e.CreditKop),
			})
		}
		saldo := text("Сальдо: переплата (аванс)")
		if balance >= 0 {
			saldo = text("Сальдо: задолженность клиента")
		}
		section.Total = []Cell{saldo, text(""), money(balance), text("")}
		result.Sections = append(result.Sections, section)
		return result, nil
	}

	rows, err := st.ClientBalanceRows(ctx)
	if err != nil {
		return Result{}, err
	}
	section := Section{
		Title: "Клиенты",
		Columns: []Column{
			{Key: "client", Label: "Клиент", Kind: KindText},
			{Key: "orders", Label: "Заказов", Kind: KindCount},
			{Key: "billed", Label: "Начислено, ₽", Kind: KindMoney},
			{Key: "paid", Label: "Оплачено, ₽", Kind: KindMoney},
			{Key: "saldo", Label: "Сальдо, ₽", Kind: KindMoney},
		},
		Note: "Сальдо больше нуля — долг клиента, меньше — аванс. Начислено — цена всех заказов кроме отменённых.",
	}
	var billed, paid int64
	for _, r := range rows {
		section.Rows = append(section.Rows, []Cell{
			text(r.Name), count(r.OrdersCount), money(r.BilledKop), money(r.PaidKop), money(r.SaldoKop),
		})
		billed += r.BilledKop
		paid += r.PaidKop
	}
	section.Total = []Cell{text("Итого"), text(""), money(billed), money(paid), money(billed - paid)}
	result.Sections = append(result.Sections, section)
	return result, nil
}

func accountName(method string) string {
	switch method {
	case store.MethodCash:
		return "наличные"
	case store.MethodCard:
		return "карта"
	case store.MethodAccount:
		return "счёт"
	}
	return method
}

func orderStatusText(status string) string {
	switch status {
	case "new":
		return "новый"
	case "in_progress":
		return "в работе"
	case "done":
		return "завершён"
	case "canceled":
		return "отменён"
	}
	return status
}

func docStatusText(status string) string {
	switch status {
	case store.DocDraft:
		return "черновик"
	case store.DocIssued:
		return "действующий"
	case store.DocAnnulled:
		return "аннулирован"
	}
	return status
}
