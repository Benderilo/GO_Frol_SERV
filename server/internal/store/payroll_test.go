package store

import (
	"context"
	"errors"
	"testing"
)

func TestPayrollAdvanceAndSalary(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	// Подённый: 3000 ₽ в день, 2 дня в первой половине и 1 во второй.
	ivan, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван", SalaryType: "day", SalaryKop: 300_000})
	if err != nil {
		t.Fatalf("сотрудник: %v", err)
	}
	for _, d := range []string{"2026-09-03", "2026-09-15", "2026-09-20"} {
		if err := st.SetWorkDay(ctx, ivan.ID, d, ""); err != nil {
			t.Fatalf("день %s: %v", d, err)
		}
	}
	// Окладник: 60 000 ₽, аванс — половина.
	petr, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Пётр", SalaryType: "month", SalaryKop: 6_000_000})
	if err != nil {
		t.Fatalf("сотрудник: %v", err)
	}

	p, err := st.Payroll(ctx, "2026-09")
	if err != nil {
		t.Fatalf("ведомость: %v", err)
	}
	if p.AdvanceDate != "2026-09-20" || p.SalaryDate != "2026-10-05" || p.DatesCustom {
		t.Errorf("даты по умолчанию: %s / %s (custom=%v)", p.AdvanceDate, p.SalaryDate, p.DatesCustom)
	}
	rows := map[int64]PayrollRow{}
	for _, r := range p.Rows {
		rows[r.WorkerID] = r
	}
	iv := rows[ivan.ID]
	if iv.Days != 3 || iv.AdvanceDays != 2 || iv.AccruedKop != 900_000 ||
		iv.AdvancePlanKop != 600_000 || iv.SalaryPlanKop != 300_000 {
		t.Errorf("расчёт Ивана: %+v", iv)
	}
	if pt := rows[petr.ID]; pt.AccruedKop != 6_000_000 || pt.AdvancePlanKop != 3_000_000 {
		t.Errorf("расчёт Петра: %+v", pt)
	}

	// Аванс выдали чуть больше плана — зарплата уменьшается на переплату.
	adv, err := st.AddSalaryPayment(ctx, SalaryPaymentInput{
		WorkerID: ivan.ID, Month: "2026-09", Kind: PayAdvance, AmountKop: 700_000, PaidOn: "2026-09-19",
	})
	if err != nil {
		t.Fatalf("аванс: %v", err)
	}
	op, err := st.CashOp(ctx, adv.CashOpID)
	if err != nil {
		t.Fatalf("расход в кассе: %v", err)
	}
	if op.Direction != DirectionOut || op.AmountKop != 700_000 || op.Category != "Зарплата" ||
		op.HappenedAt[:10] != "2026-09-19" {
		t.Errorf("строка кассы: %+v", op)
	}
	p, _ = st.Payroll(ctx, "2026-09")
	for _, r := range p.Rows {
		if r.WorkerID == ivan.ID && (r.AdvanceLeftKop != 0 || r.SalaryLeftKop != 200_000 || r.BalanceKop != 200_000) {
			t.Errorf("после аванса: %+v", r)
		}
	}

	// Перенос даты выплаты правит и кассу.
	upd, err := st.UpdateSalaryPayment(ctx, adv.ID, SalaryPaymentInput{
		Month: "2026-09", Kind: PayAdvance, AmountKop: 600_000, PaidOn: "2026-09-18",
	})
	if err != nil {
		t.Fatalf("правка выплаты: %v", err)
	}
	op, _ = st.CashOp(ctx, upd.CashOpID)
	if op.AmountKop != 600_000 || op.HappenedAt[:10] != "2026-09-18" {
		t.Errorf("касса не последовала за правкой: %+v", op)
	}

	// Удаление строки в кассе убирает и выплату.
	if err := st.DeleteCashOp(ctx, op.ID); err != nil {
		t.Fatalf("удаление в кассе: %v", err)
	}
	if _, err := st.SalaryPayment(ctx, adv.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("выплата пережила удаление расхода: %v", err)
	}

	// Даты месяца переносятся и сбрасываются обратно.
	if err := st.SetPayrollDates(ctx, "2026-09", "2026-09-18", ""); err != nil {
		t.Fatalf("перенос дат: %v", err)
	}
	p, _ = st.Payroll(ctx, "2026-09")
	if p.AdvanceDate != "2026-09-18" || p.SalaryDate != "2026-10-05" || !p.DatesCustom {
		t.Errorf("перенесённые даты: %s / %s", p.AdvanceDate, p.SalaryDate)
	}
	if err := st.SetPayrollDates(ctx, "2026-09", "", ""); err != nil {
		t.Fatalf("сброс дат: %v", err)
	}
	if _, err := st.SavePayrollSettings(ctx, PayrollSettings{AdvanceDay: 31, SalaryDay: 10}); err != nil {
		t.Fatalf("дни по умолчанию: %v", err)
	}
	// 31-е в сентябре — последний день месяца.
	p, _ = st.Payroll(ctx, "2026-09")
	if p.AdvanceDate != "2026-09-30" || p.SalaryDate != "2026-10-10" || p.DatesCustom {
		t.Errorf("новые дни по умолчанию: %s / %s", p.AdvanceDate, p.SalaryDate)
	}

	if _, err := st.AddSalaryPayment(ctx, SalaryPaymentInput{WorkerID: ivan.ID, Month: "2026-09", AmountKop: 0}); err == nil {
		t.Error("нулевая выплата не должна проходить")
	}
}
