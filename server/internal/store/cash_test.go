package store

import (
	"context"
	"testing"
)

func addOp(t *testing.T, st *Store, direction string, kop int64, category, when string) CashOp {
	t.Helper()
	op, err := st.AddCashOp(context.Background(), CashOp{
		Direction: direction, AmountKop: kop, Category: category, HappenedAt: when,
	})
	if err != nil {
		t.Fatalf("операция %s %d: %v", direction, kop, err)
	}
	return op
}

// Остаток нигде не хранится, а считается из операций. Проверяем именно это.
func TestCashBalanceIsSumOfOps(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	addOp(t, st, DirectionIn, 500_000, "оплата", "2026-09-01T10:00:00Z")
	addOp(t, st, DirectionOut, 120_050, "материалы", "2026-09-02T10:00:00Z")
	addOp(t, st, DirectionOut, 30_000, "бензин", "2026-09-03T10:00:00Z")

	balance, err := st.CashBalance(ctx, "")
	if err != nil {
		t.Fatalf("остаток: %v", err)
	}
	if balance != 500_000-120_050-30_000 {
		t.Errorf("остаток %d, ожидалось %d", balance, 349_950)
	}
}

// Границы периода включительные с обеих сторон: операция последнего дня
// обязана попасть в отчёт, иначе месяц каждый раз недосчитывается.
func TestCashPeriodIncludesBothEnds(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	addOp(t, st, DirectionIn, 100_00, "", "2026-08-31T23:00:00Z") // до периода
	addOp(t, st, DirectionIn, 200_00, "", "2026-09-01T00:30:00Z") // первый день
	addOp(t, st, DirectionIn, 400_00, "", "2026-09-30T23:30:00Z") // последний день
	addOp(t, st, DirectionIn, 800_00, "", "2026-10-01T00:10:00Z") // после периода

	income, _, err := st.CashTotals(ctx, CashFilter{From: "2026-09-01", To: "2026-09-30"})
	if err != nil {
		t.Fatalf("итоги: %v", err)
	}
	if income != 200_00+400_00 {
		t.Errorf("приход за сентябрь %d, ожидалось %d", income, 600_00)
	}
}

// Отчёт за период: остаток на начало берётся из того, что было до него.
func TestPeriodReportOpeningBalance(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	addOp(t, st, DirectionIn, 1_000_00, "", "2026-08-15T10:00:00Z")
	addOp(t, st, DirectionOut, 300_00, "", "2026-08-20T10:00:00Z")
	addOp(t, st, DirectionIn, 500_00, "оплата", "2026-09-10T10:00:00Z")
	addOp(t, st, DirectionOut, 200_00, "материалы", "2026-09-12T10:00:00Z")

	report, err := st.PeriodReportFor(ctx, "2026-09-01", "2026-09-30")
	if err != nil {
		t.Fatalf("отчёт: %v", err)
	}
	if report.OpeningKop != 700_00 {
		t.Errorf("остаток на начало %d, ожидалось %d", report.OpeningKop, 700_00)
	}
	if report.IncomeKop != 500_00 || report.ExpenseKop != 200_00 {
		t.Errorf("приход/расход %d/%d, ожидалось %d/%d",
			report.IncomeKop, report.ExpenseKop, 500_00, 200_00)
	}
	if report.ClosingKop != 700_00+500_00-200_00 {
		t.Errorf("остаток на конец %d, ожидалось %d", report.ClosingKop, 1_000_00)
	}

	// Разбивка по статьям должна покрывать весь оборот периода.
	var byCategory int64
	for _, c := range report.ByCategory {
		byCategory += c.AmountKop
	}
	if byCategory != report.IncomeKop+report.ExpenseKop {
		t.Errorf("сумма по статьям %d не сходится с оборотом %d",
			byCategory, report.IncomeKop+report.ExpenseKop)
	}
}

// Оплата по заказу — такая же строка кассы: она и долг закрывает,
// и в остаток попадает. Двух разных сумм тут быть не должно.
func TestOrderPaymentIsCashIncome(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	order, err := st.CreateOrder(ctx, Order{Title: "Монтаж", Status: "in_progress", PriceKop: 500_000})
	if err != nil {
		t.Fatalf("создание заказа: %v", err)
	}
	if _, err := st.AddPayment(ctx, order.ID, Payment{AmountKop: 200_000}); err != nil {
		t.Fatalf("платёж: %v", err)
	}

	got, err := st.Order(ctx, order.ID)
	if err != nil {
		t.Fatalf("чтение заказа: %v", err)
	}
	if got.PaidKop != 200_000 {
		t.Errorf("оплачено по заказу %d, ожидалось 200000", got.PaidKop)
	}

	balance, err := st.CashBalance(ctx, "")
	if err != nil {
		t.Fatalf("остаток: %v", err)
	}
	if balance != 200_000 {
		t.Errorf("остаток кассы %d, ожидалось 200000 — платёж по заказу это тоже деньги", balance)
	}

	// И расход рядом с ним уменьшает остаток, но не трогает оплату заказа.
	addOp(t, st, DirectionOut, 50_000, "материалы", "")
	got, _ = st.Order(ctx, order.ID)
	if got.PaidKop != 200_000 {
		t.Errorf("расход изменил оплату по заказу: %d", got.PaidKop)
	}
	balance, _ = st.CashBalance(ctx, "")
	if balance != 150_000 {
		t.Errorf("остаток после расхода %d, ожидалось 150000", balance)
	}
}

// Расход материалов в отчёте — только списания, приход не в счёт:
// «сколько ушло в работу» и «сколько закупили» — разные вопросы.
func TestReportCountsOnlyMaterialWriteOffs(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	item, err := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Кабель", Unit: "м"})
	if err != nil {
		t.Fatalf("материал: %v", err)
	}
	if _, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: 100_000}); err != nil {
		t.Fatalf("приход: %v", err)
	}
	if _, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: -30_000, CostKop: 240_000}); err != nil {
		t.Fatalf("списание: %v", err)
	}

	report, err := st.PeriodReportFor(ctx, "", "")
	if err != nil {
		t.Fatalf("отчёт: %v", err)
	}
	if len(report.Materials) != 1 {
		t.Fatalf("материалов в отчёте %d, ожидался 1", len(report.Materials))
	}
	used := report.Materials[0]
	if used.QtyMilli != 30_000 {
		t.Errorf("израсходовано %d, ожидалось 30000 — приход не должен попадать", used.QtyMilli)
	}
	if used.StockLeft != 70_000 {
		t.Errorf("остаток %d, ожидалось 70000", used.StockLeft)
	}
}

func TestCashOpDefaults(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	// Неизвестное направление и способ подменяются разумными,
	// а не роняют запись: касса не место для строгости ради строгости.
	op, err := st.AddCashOp(ctx, CashOp{AmountKop: 1000, Direction: "куда-то", Method: "чем-то"})
	if err != nil {
		t.Fatalf("операция: %v", err)
	}
	if op.Direction != DirectionIn || op.Method != MethodCash {
		t.Errorf("умолчания не подставились: %+v", op)
	}
	if op.HappenedAt == "" {
		t.Error("дата операции не проставлена")
	}
}
