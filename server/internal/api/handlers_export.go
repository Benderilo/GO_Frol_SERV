package api

import (
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/exports"
)

// maxImportBytes — книга Excel с несколькими тысячами строк весит сотни килобайт,
// поэтому предел щедрый, но не безграничный: файл читается в память целиком.
const maxImportBytes = 16 << 20

// handleExport отдаёт всю базу одной книгой Excel.
func (a *API) handleExport(w http.ResponseWriter, r *http.Request) {
	clients, orders, requests, err := a.store.AllForExport(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}

	data, err := exports.Build(clients, orders, requests)
	if err != nil {
		slog.Error("сборка выгрузки", "err", err)
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось собрать файл выгрузки")
		return
	}

	name := "frolov-crm-" + time.Now().Format("2006-01-02") + ".xlsx"
	w.Header().Set("Content-Type",
		"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Header().Set("Cache-Control", "no-store")
	if _, err := w.Write(data); err != nil {
		slog.Error("отправка выгрузки", "err", err)
	}
}

// importSummary — что получилось из загруженной книги.
type importSummary struct {
	Clients  countPair `json:"clients"`
	Orders   countPair `json:"orders"`
	Requests countPair `json:"requests"`
	Warnings []string  `json:"warnings"`
}

type countPair struct {
	Created int `json:"created"`
	Updated int `json:"updated"`
}

// handleImport принимает книгу Excel и добавляет из неё записи.
// Ничего не удаляет: строки, которых в файле нет, остаются в базе.
func (a *API) handleImport(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, maxImportBytes+1<<20)
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		writeError(w, http.StatusBadRequest, "bad_form",
			"Не удалось прочитать файл — возможно, он больше 16 МБ")
		return
	}
	defer r.MultipartForm.RemoveAll()

	file, _, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, "no_file", "Не приложен файл в поле file")
		return
	}
	defer file.Close()

	parsed, err := exports.Parse(file)
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_file", err.Error())
		return
	}

	summary := importSummary{Warnings: parsed.Warnings}
	if summary.Warnings == nil {
		summary.Warnings = []string{}
	}

	// Клиенты идут первыми: заказы ссылаются на них.
	for _, c := range parsed.Clients {
		created, err := a.store.UpsertClient(r.Context(), c)
		if err != nil {
			summary.Warnings = append(summary.Warnings, "Клиент «"+c.Name+"»: "+err.Error())
			continue
		}
		if created {
			summary.Clients.Created++
		} else {
			summary.Clients.Updated++
		}
	}

	for _, o := range parsed.Orders {
		created, err := a.store.UpsertOrder(r.Context(), o)
		if err != nil {
			summary.Warnings = append(summary.Warnings, "Заказ «"+o.Title+"»: "+err.Error())
			continue
		}
		if created {
			summary.Orders.Created++
		} else {
			summary.Orders.Updated++
		}
	}

	for _, req := range parsed.Requests {
		created, err := a.store.UpsertRequest(r.Context(), req)
		if err != nil {
			summary.Warnings = append(summary.Warnings, "Заявка «"+req.Name+"»: "+err.Error())
			continue
		}
		if created {
			summary.Requests.Created++
		} else {
			summary.Requests.Updated++
		}
	}

	// Предупреждений может быть очень много — до приложения доводим первые.
	if len(summary.Warnings) > 30 {
		extra := len(summary.Warnings) - 30
		summary.Warnings = append(summary.Warnings[:30],
			"…и ещё "+strconv.Itoa(extra)+" замечаний")
	}

	writeJSON(w, http.StatusOK, summary)
}
