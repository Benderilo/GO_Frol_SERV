package store

import (
	"context"
	"errors"
	"testing"
)

func TestWorkerCRUD(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	w, err := st.CreateWorker(ctx, WorkerUpsert{
		Name: "  Иван  ", Phone: "+7 900 111-22-33", Position: "монтажник",
		SalaryType: "day", SalaryKop: 300_000,
	})
	if err != nil {
		t.Fatalf("создание: %v", err)
	}
	if w.Name != "Иван" {
		t.Errorf("имя не обрезано от пробелов: %q", w.Name)
	}
	if !w.Active {
		t.Error("новый сотрудник должен быть активен")
	}

	// Пустой тип ставки подменяется подённым — самый частый случай.
	withDefault, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Пётр"})
	if err != nil {
		t.Fatalf("создание без типа ставки: %v", err)
	}
	if withDefault.SalaryType != "day" {
		t.Errorf("тип ставки по умолчанию %q, ожидался day", withDefault.SalaryType)
	}

	got, err := st.UpdateWorker(ctx, w.ID, WorkerUpsert{
		Name: "Иван Иванов", SalaryType: "month", SalaryKop: 9_000_000, Active: false,
	})
	if err != nil {
		t.Fatalf("правка: %v", err)
	}
	if got.SalaryType != "month" || got.SalaryKop != 9_000_000 {
		t.Errorf("ставка не обновилась: %+v", got)
	}
	if got.Active {
		t.Error("сотрудник должен стать неактивным")
	}

	// Неактивный прячется из обычного списка, но виден с флагом «все».
	active, err := st.ListWorkers(ctx, false)
	if err != nil {
		t.Fatalf("список активных: %v", err)
	}
	if len(active) != 1 || active[0].Name != "Пётр" {
		t.Errorf("в списке активных %v, ожидался только Пётр", active)
	}
	all, err := st.ListWorkers(ctx, true)
	if err != nil {
		t.Fatalf("полный список: %v", err)
	}
	if len(all) != 2 {
		t.Errorf("всего сотрудников %d, ожидалось 2", len(all))
	}

	if err := st.DeleteWorker(ctx, w.ID); err != nil {
		t.Fatalf("удаление: %v", err)
	}
	if _, err := st.GetWorker(ctx, w.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("после удаления карточка читается: %v", err)
	}
	if err := st.DeleteWorker(ctx, w.ID); !errors.Is(err, ErrNotFound) {
		t.Errorf("повторное удаление: %v, ожидалось ErrNotFound", err)
	}
}

func TestWorkerValidation(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	if _, err := st.CreateWorker(ctx, WorkerUpsert{Name: "   "}); !errors.Is(err, ErrBadName) {
		t.Errorf("пустое имя: %v, ожидалось ErrBadName", err)
	}
	if _, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван", SalaryType: "hour"}); !errors.Is(err, ErrBadName) {
		t.Errorf("чужой тип ставки: %v, ожидалось ErrBadName", err)
	}
	if _, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван", SalaryKop: -1}); !errors.Is(err, ErrBadName) {
		t.Errorf("отрицательная ставка: %v, ожидалось ErrBadName", err)
	}
}

func TestWorkDaySetIsIdempotent(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	w, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван", SalaryType: "day", SalaryKop: 300_000})
	if err != nil {
		t.Fatalf("создание: %v", err)
	}
	if err := st.SetWorkDay(ctx, w.ID, "2026-09-10", "первая смена"); err != nil {
		t.Fatalf("отметка: %v", err)
	}
	// Повторная отметка той же даты обновляет заметку, а не плодит дубль.
	if err := st.SetWorkDay(ctx, w.ID, "2026-09-10", "полдня"); err != nil {
		t.Fatalf("повторная отметка: %v", err)
	}
	days, err := st.ListWorkDays(ctx, "2026-09-01", "2026-09-30", w.ID)
	if err != nil {
		t.Fatalf("список дней: %v", err)
	}
	if len(days) != 1 {
		t.Fatalf("дней за месяц %d, ожидался 1", len(days))
	}
	if days[0].Note != "полдня" {
		t.Errorf("заметка не обновилась: %q", days[0].Note)
	}
	if days[0].WorkerName != "Иван" {
		t.Errorf("имя сотрудника не подтянулось: %q", days[0].WorkerName)
	}

	if err := st.SetWorkDay(ctx, w.ID, "10.09.2026", ""); !errors.Is(err, ErrBadName) {
		t.Errorf("дата не в том формате: %v, ожидалось ErrBadName", err)
	}
	if err := st.SetWorkDay(ctx, 999, "2026-09-10", ""); !errors.Is(err, ErrNotFound) {
		t.Errorf("отметка чужому сотруднику: %v, ожидалось ErrNotFound", err)
	}
}

func TestRemoveWorkDay(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	w, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван"})
	if err != nil {
		t.Fatalf("создание: %v", err)
	}
	if err := st.SetWorkDay(ctx, w.ID, "2026-09-10", ""); err != nil {
		t.Fatalf("отметка: %v", err)
	}
	if err := st.RemoveWorkDay(ctx, w.ID, "2026-09-10"); err != nil {
		t.Fatalf("снятие отметки: %v", err)
	}
	// Снимать неотмеченное — ошибка: иначе опечатка в дате пройдёт молча.
	if err := st.RemoveWorkDay(ctx, w.ID, "2026-09-10"); !errors.Is(err, ErrNotFound) {
		t.Errorf("повторное снятие: %v, ожидалось ErrNotFound", err)
	}
}

// Подённому платим за отмеченные дни, окладнику — оклад независимо от дней.
func TestWorkerAccrual(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	daily, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван", SalaryType: "day", SalaryKop: 300_000})
	if err != nil {
		t.Fatalf("подённый: %v", err)
	}
	for _, d := range []string{"2026-09-01", "2026-09-05", "2026-08-31"} {
		if err := st.SetWorkDay(ctx, daily.ID, d, ""); err != nil {
			t.Fatalf("отметка %s: %v", d, err)
		}
	}
	acc, err := st.WorkerAccrual(ctx, daily.ID, "2026-09")
	if err != nil {
		t.Fatalf("начисление: %v", err)
	}
	if acc.Days != 2 {
		t.Errorf("дней в сентябре %d, ожидалось 2 — август не в счёт", acc.Days)
	}
	if acc.AmountKop != 600_000 {
		t.Errorf("начислено %d, ожидалось 600000 (2 × 3000 ₽)", acc.AmountKop)
	}

	monthly, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Пётр", SalaryType: "month", SalaryKop: 9_000_000})
	if err != nil {
		t.Fatalf("окладник: %v", err)
	}
	if err := st.SetWorkDay(ctx, monthly.ID, "2026-09-03", ""); err != nil {
		t.Fatalf("отметка окладнику: %v", err)
	}
	acc, err = st.WorkerAccrual(ctx, monthly.ID, "2026-09")
	if err != nil {
		t.Fatalf("начисление окладнику: %v", err)
	}
	if acc.AmountKop != 9_000_000 {
		t.Errorf("окладнику начислено %d, ожидался оклад 9000000", acc.AmountKop)
	}
	if acc.Days != 1 {
		t.Errorf("дней у окладника %d, ожидался 1", acc.Days)
	}

	if _, err := st.WorkerAccrual(ctx, daily.ID, "сентябрь"); !errors.Is(err, ErrBadName) {
		t.Errorf("месяц не в том формате: %v, ожидалось ErrBadName", err)
	}
}

// Выплата зарплаты — расход кассы: её видно в общем списке операций,
// и она уменьшает остаток наличных.
func TestWorkerPayout(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	w, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван", SalaryType: "day", SalaryKop: 300_000})
	if err != nil {
		t.Fatalf("создание: %v", err)
	}
	if err := st.PayoutWorker(ctx, w.ID, 600_000, "2026-09"); err != nil {
		t.Fatalf("выплата: %v", err)
	}

	ops, err := st.CashOps(ctx, CashFilter{})
	if err != nil {
		t.Fatalf("список операций кассы: %v", err)
	}
	if len(ops) != 1 {
		t.Fatalf("операций в кассе %d, ожидалась 1", len(ops))
	}
	op := ops[0]
	if op.Direction != DirectionOut || op.AmountKop != 600_000 || op.Method != MethodCash {
		t.Errorf("выплата записалась не так: %+v", op)
	}
	if op.Category != "Зарплата" {
		t.Errorf("статья %q, ожидалась «Зарплата»", op.Category)
	}

	balance, err := st.CashBalance(ctx, "")
	if err != nil {
		t.Fatalf("остаток: %v", err)
	}
	if balance != -600_000 {
		t.Errorf("остаток %d, ожидалось -600000", balance)
	}

	if err := st.PayoutWorker(ctx, w.ID, 0, "2026-09"); !errors.Is(err, ErrBadName) {
		t.Errorf("нулевая выплата: %v, ожидалось ErrBadName", err)
	}
	if err := st.PayoutWorker(ctx, 999, 100_000, "2026-09"); !errors.Is(err, ErrNotFound) {
		t.Errorf("выплата чужому сотруднику: %v, ожидалось ErrNotFound", err)
	}
}

// Удаление сотрудника стирает его отметки дней (ON DELETE CASCADE),
// иначе в отчётах остались бы дни без хозяина.
func TestDeleteWorkerRemovesWorkDays(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	w, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Иван"})
	if err != nil {
		t.Fatalf("создание: %v", err)
	}
	if err := st.SetWorkDay(ctx, w.ID, "2026-09-10", ""); err != nil {
		t.Fatalf("отметка: %v", err)
	}
	if err := st.DeleteWorker(ctx, w.ID); err != nil {
		t.Fatalf("удаление: %v", err)
	}
	days, err := st.ListWorkDays(ctx, "", "", 0)
	if err != nil {
		t.Fatalf("список дней: %v", err)
	}
	if len(days) != 0 {
		t.Errorf("после удаления сотрудника остались отметки: %v", days)
	}
}
