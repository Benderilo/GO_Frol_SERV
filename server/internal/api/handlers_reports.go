package api

import (
	"net/http"

	"github.com/Benderilo/GO_Frol_SERV/internal/reports"
)

// ---------- Ядро отчётов ----------
//
// Отчёты живут в реестре internal/reports: список, прогон и экспорт читают
// его одинаково. Старые ручки /admin/report и /admin/report/export остаются
// для установленных приложений — они никуда не переезжают.

type reportInfo struct {
	ID          string         `json:"id"`
	Title       string         `json:"title"`
	Description string         `json:"description"`
	Params      []reports.Param `json:"params"`
}

func (a *API) handleListReports(w http.ResponseWriter, r *http.Request) {
	out := make([]reportInfo, 0, 8)
	for _, def := range reports.All() {
		out = append(out, reportInfo{
			ID:          def.ID,
			Title:       def.Title,
			Description: def.Description,
			Params:      def.Params,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out, "count": len(out)})
}

// handleRunReport выполняет отчёт и отдаёт результат секциями.
func (a *API) handleRunReport(w http.ResponseWriter, r *http.Request) {
	def := reports.ByID(r.PathValue("id"))
	if def == nil {
		writeError(w, http.StatusNotFound, "not_found", "Отчёт не найден")
		return
	}
	p, ok := a.collectReportParams(w, r)
	if !ok {
		return
	}
	result, err := def.Build(r.Context(), a.store, p)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

// handleExportReport выполняет отчёт и отдаёт книгой Excel.
func (a *API) handleExportReport(w http.ResponseWriter, r *http.Request) {
	def := reports.ByID(r.PathValue("id"))
	if def == nil {
		writeError(w, http.StatusNotFound, "not_found", "Отчёт не найден")
		return
	}
	p, ok := a.collectReportParams(w, r)
	if !ok {
		return
	}
	result, err := def.Build(r.Context(), a.store, p)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	book, err := reports.Workbook(result)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	w.Header().Set("Content-Disposition", `attachment; filename="`+def.ID+`.xlsx"`)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(book)
}

// collectReportParams читает параметры прогона и подсвечивает ошибки.
func (a *API) collectReportParams(w http.ResponseWriter, r *http.Request) (reports.Params, bool) {
	p := reports.Params{
		From: r.URL.Query().Get("from"),
		To:   r.URL.Query().Get("to"),
	}
	if v, err := parseInt64(r.URL.Query().Get("clientId")); err != nil || v < 0 {
		writeError(w, http.StatusBadRequest, "validation", "Некорректный идентификатор клиента")
		return p, false
	} else if v > 0 {
		client, err := a.store.Client(r.Context(), v)
		if err != nil {
			writeStoreError(w, err)
			return p, false
		}
		p.ClientID = client.ID
		p.ClientName = client.Name
	}
	return p, true
}
