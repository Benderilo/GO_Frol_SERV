package api

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// ---------- Зарплаты: ведомость, даты выплат, аванс и зарплата ----------

// writePayrollError: ошибки проверки ввода хранилище помечает ErrBadName,
// а общий ответ про «имя» тут сбил бы с толку — отдаём сам текст причины.
func writePayrollError(w http.ResponseWriter, err error) {
	if errors.Is(err, store.ErrBadName) {
		writeError(w, http.StatusBadRequest, "validation",
			strings.TrimPrefix(err.Error(), store.ErrBadName.Error()+": "))
		return
	}
	writeStoreError(w, err)
}

// handlePayroll отдаёт ведомость за месяц из query (по умолчанию — текущий).
func (a *API) handlePayroll(w http.ResponseWriter, r *http.Request) {
	month := trim(r.URL.Query().Get("month"))
	if month == "" {
		month = time.Now().UTC().Format("2006-01")
	}
	p, err := a.store.Payroll(r.Context(), month)
	if err != nil {
		writePayrollError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func (a *API) handleSavePayrollSettings(w http.ResponseWriter, r *http.Request) {
	var body store.PayrollSettings
	if !decodeJSON(w, r, &body) {
		return
	}
	ps, err := a.store.SavePayrollSettings(r.Context(), body)
	if err != nil {
		writePayrollError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, ps)
}

// handleSetPayrollDates переносит даты выплат месяца; обе пустые — сброс
// к дням по умолчанию. Отвечает обновлённой ведомостью.
func (a *API) handleSetPayrollDates(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AdvanceDate string `json:"advanceDate"`
		SalaryDate  string `json:"salaryDate"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	month := r.PathValue("month")
	if err := a.store.SetPayrollDates(r.Context(), month, body.AdvanceDate, body.SalaryDate); err != nil {
		writePayrollError(w, err)
		return
	}
	p, err := a.store.Payroll(r.Context(), month)
	if err != nil {
		writePayrollError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func (a *API) handleAddSalaryPayment(w http.ResponseWriter, r *http.Request) {
	var body store.SalaryPaymentInput
	if !decodeJSON(w, r, &body) {
		return
	}
	p, err := a.store.AddSalaryPayment(r.Context(), body)
	if err != nil {
		writePayrollError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, p)
}

func (a *API) handleUpdateSalaryPayment(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body store.SalaryPaymentInput
	if !decodeJSON(w, r, &body) {
		return
	}
	p, err := a.store.UpdateSalaryPayment(r.Context(), id, body)
	if err != nil {
		writePayrollError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func (a *API) handleDeleteSalaryPayment(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteSalaryPayment(r.Context(), id); err != nil {
		writePayrollError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
