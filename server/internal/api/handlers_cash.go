package api

import (
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/exports"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

func (a *API) handleListCash(w http.ResponseWriter, r *http.Request) {
	filter := cashFilterFrom(r)
	ops, err := a.store.CashOps(r.Context(), filter)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	income, expense, err := a.store.CashTotals(r.Context(), filter)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	// Остаток отдаём всегда общий, а не за период: «сколько денег на руках»
	// не зависит от того, какой отрезок сейчас выбран на экране.
	balance, err := a.store.CashBalance(r.Context(), "")
	if err != nil {
		writeStoreError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"items":      ops,
		"count":      len(ops),
		"incomeKop":  income,
		"expenseKop": expense,
		"balanceKop": balance,
	})
}

func (a *API) handleAddCash(w http.ResponseWriter, r *http.Request) {
	var op store.CashOp
	if !decodeJSON(w, r, &op) {
		return
	}
	if op.AmountKop <= 0 {
		writeError(w, http.StatusBadRequest, "validation",
			"Сумма должна быть больше нуля. Расход задаётся направлением, а не минусом.")
		return
	}
	if op.Direction != "" && !store.ValidDirection(op.Direction) {
		writeError(w, http.StatusBadRequest, "validation", "Направление — приход или расход")
		return
	}
	if op.Method != "" && !store.ValidMethod(op.Method) {
		writeError(w, http.StatusBadRequest, "validation", "Способ — наличные, карта или счёт")
		return
	}
	if len(op.Note) > 500 {
		op.Note = op.Note[:500]
	}
	if len(op.Category) > 100 {
		op.Category = op.Category[:100]
	}

	saved, err := a.store.AddCashOp(r.Context(), op)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, saved)
}

func (a *API) handleDeleteCash(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteCashOp(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- Отчёт за период ----------

func (a *API) handleReport(w http.ResponseWriter, r *http.Request) {
	from, to := periodFrom(r)
	report, err := a.store.PeriodReportFor(r.Context(), from, to)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	// Заработок считается из отчёта, но в JSON его удобнее иметь готовым:
	// иначе каждый экран пересчитывал бы его сам и однажды разошёлся.
	writeJSON(w, http.StatusOK, map[string]any{
		"report":    report,
		"profitKop": report.ProfitKop(),
	})
}

// handleReportExport отдаёт отчёт книгой Excel — тем же механизмом,
// которым выгружается база: свод, операции кассы и расход материалов.
func (a *API) handleReportExport(w http.ResponseWriter, r *http.Request) {
	from, to := periodFrom(r)
	report, err := a.store.PeriodReportFor(r.Context(), from, to)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	// Операции берём за тот же период и без ограничения по числу строк:
	// в отчёте нужен весь месяц, а не последние двести записей.
	ops, err := a.store.CashOps(r.Context(), store.CashFilter{From: from, To: to, Limit: 1000})
	if err != nil {
		writeStoreError(w, err)
		return
	}

	data, err := exports.BuildReport(report, ops)
	if err != nil {
		slog.Error("сборка отчёта", "err", err)
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось собрать файл отчёта")
		return
	}

	name := "отчёт-" + reportPeriodName(from, to) + ".xlsx"
	w.Header().Set("Content-Type",
		"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	w.Header().Set("Content-Disposition", `attachment; filename="report.xlsx"; filename*=UTF-8''`+url.PathEscape(name))
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func reportPeriodName(from, to string) string {
	switch {
	case from != "" && to != "":
		return from + "—" + to
	case from != "":
		return "с-" + from
	case to != "":
		return "по-" + to
	default:
		return time.Now().Format("2006-01-02")
	}
}

func cashFilterFrom(r *http.Request) store.CashFilter {
	from, to := periodFrom(r)
	return store.CashFilter{
		From:      from,
		To:        to,
		Direction: trim(r.URL.Query().Get("direction")),
		Limit:     queryInt(r, "limit", 200),
	}
}

func periodFrom(r *http.Request) (string, string) {
	return trim(r.URL.Query().Get("from")), trim(r.URL.Query().Get("to"))
}
