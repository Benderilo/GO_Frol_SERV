package api

import (
	"net/http"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// ---------- Платежи по заказам ----------

func (a *API) handleListPayments(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	items, err := a.store.OrderPayments(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleCreatePayment(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var p store.Payment
	if !decodeJSON(w, r, &p) {
		return
	}
	if p.AmountKop <= 0 {
		writeError(w, http.StatusBadRequest, "validation", "Сумма платежа должна быть больше нуля")
		return
	}
	if len(p.Note) > 500 {
		p.Note = p.Note[:500]
	}
	item, err := a.store.AddPayment(r.Context(), id, p)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (a *API) handleDeletePayment(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeletePayment(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
