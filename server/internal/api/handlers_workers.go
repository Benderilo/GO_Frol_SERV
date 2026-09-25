package api

import (
	"errors"
	"net/http"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// ---------- Сотрудники, ставки и рабочие дни ----------

func (a *API) handleListWorkers(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListWorkers(r.Context(), r.URL.Query().Get("all") == "1")
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleCreateWorker(w http.ResponseWriter, r *http.Request) {
	var u store.WorkerUpsert
	if !decodeJSON(w, r, &u) {
		return
	}
	item, err := a.store.CreateWorker(r.Context(), u)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

// handleGetWorker отдаёт карточку вместе с начислением за месяц из query
// (по умолчанию — текущий): карточка рабочего без суммы к выплате бесполезна.
func (a *API) handleGetWorker(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	worker, err := a.store.GetWorker(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	month := trim(r.URL.Query().Get("month"))
	if month == "" {
		month = time.Now().UTC().Format("2006-01")
	}
	accrual, err := a.store.WorkerAccrual(r.Context(), id, month)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"worker": worker, "accrual": accrual})
}

func (a *API) handleUpdateWorker(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var u store.WorkerUpsert
	if !decodeJSON(w, r, &u) {
		return
	}
	item, err := a.store.UpdateWorker(r.Context(), id, u)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleDeleteWorker(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteWorker(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) handleListWorkDays(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	workerID, err := parseInt64(q.Get("workerId"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	from, to := trim(q.Get("from")), trim(q.Get("to"))
	// Без периода показываем текущий месяц: именно его смотрят чаще всего.
	if from == "" || to == "" {
		month := time.Now().UTC().Format("2006-01")
		if from == "" {
			from = month + "-01"
		}
		if to == "" {
			to = month + "-31"
		}
	}
	items, err := a.store.ListWorkDays(r.Context(), from, to, workerID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleSetWorkDay(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body struct {
		Note string `json:"note"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	if err := a.store.SetWorkDay(r.Context(), id, r.PathValue("date"), body.Note); err != nil {
		if errors.Is(err, store.ErrBadName) {
			writeError(w, http.StatusBadRequest, "bad_date", "Дата должна быть в формате ГГГГ-ММ-ДД")
			return
		}
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (a *API) handleRemoveWorkDay(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.RemoveWorkDay(r.Context(), id, r.PathValue("date")); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) handlePayoutWorker(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body struct {
		AmountKop int64  `json:"amountKop"`
		Month     string `json:"month"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.AmountKop <= 0 {
		writeError(w, http.StatusBadRequest, "validation", "Сумма выплаты должна быть больше нуля")
		return
	}
	month := trim(body.Month)
	if month == "" {
		month = time.Now().UTC().Format("2006-01")
	}
	if err := a.store.PayoutWorker(r.Context(), id, body.AmountKop, month); err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
