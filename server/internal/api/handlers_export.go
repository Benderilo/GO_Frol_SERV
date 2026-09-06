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

// handleExport отдаёт всю базу одной книгой Excel: по листу на раздел.
func (a *API) handleExport(w http.ResponseWriter, r *http.Request) {
	backup, err := a.store.AllForExport(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}

	data, err := exports.Build(backup)
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

// importSummary — что получилось из загруженной книги. Раздел на поле,
// в том же порядке, в каком строки ложатся в базу.
type importSummary struct {
	Clients    countPair `json:"clients"`
	Catalog    countPair `json:"catalog"`
	Orders     countPair `json:"orders"`
	StockMoves countPair `json:"stockMoves"`
	OrderItems countPair `json:"orderItems"`
	Requests   countPair `json:"requests"`
	Payments   countPair `json:"payments"`
	Cash       countPair `json:"cash"`
	Tasks      countPair `json:"tasks"`
	Company    bool      `json:"company"`
	Warnings   []string  `json:"warnings"`
}

type countPair struct {
	Created int `json:"created"`
	Updated int `json:"updated"`
}

// count разносит результат одной записи по счётчикам.
func (c *countPair) count(created bool) {
	if created {
		c.Created++
	} else {
		c.Updated++
	}
}

// maxWarnings — сколько замечаний доходит до приложения. Остальные сворачиваются
// в одну строку: список на тысячу пунктов в диалоге всё равно не прочитать.
const maxWarnings = 30

// handleImport принимает книгу Excel и добавляет из неё записи.
// Ничего не удаляет: строки, которых в файле нет, остаются в базе.
//
// Порядок записи задан ссылками между разделами: клиент раньше заказа,
// позиция справочника раньше движения склада, движение раньше состава заказа
// (строка состава ссылается на движение, которым списан материал).
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
	ctx := r.Context()
	fail := func(what string, err error) {
		summary.Warnings = append(summary.Warnings, what+": "+err.Error())
	}

	for _, c := range parsed.Clients {
		created, err := a.store.UpsertClient(ctx, c)
		if err != nil {
			fail("Клиент «"+c.Name+"»", err)
			continue
		}
		summary.Clients.count(created)
	}

	for _, item := range parsed.Catalog {
		created, err := a.store.UpsertCatalogItem(ctx, item)
		if err != nil {
			fail("Позиция склада «"+item.Name+"»", err)
			continue
		}
		summary.Catalog.count(created)
	}

	for _, o := range parsed.Orders {
		created, err := a.store.UpsertOrder(ctx, o)
		if err != nil {
			fail("Заказ «"+o.Title+"»", err)
			continue
		}
		summary.Orders.count(created)
	}

	for _, m := range parsed.StockMoves {
		created, err := a.store.UpsertStockMove(ctx, m)
		if err != nil {
			fail("Движение склада по позиции №"+strconv.FormatInt(m.ItemID, 10), err)
			continue
		}
		summary.StockMoves.count(created)
	}

	for _, item := range parsed.OrderItems {
		created, err := a.store.UpsertOrderItem(ctx, item)
		if err != nil {
			fail("Строка заказа №"+strconv.FormatInt(item.OrderID, 10)+" «"+item.Name+"»", err)
			continue
		}
		summary.OrderItems.count(created)
	}

	for _, req := range parsed.Requests {
		created, err := a.store.UpsertRequest(ctx, req)
		if err != nil {
			fail("Заявка «"+req.Name+"»", err)
			continue
		}
		summary.Requests.count(created)
	}

	// Платежи приходят только из книг без листа «Касса»: иначе те же приходы
	// пришли бы дважды, и разбирает это ещё разбор книги, а не сервер.
	for _, p := range parsed.Payments {
		created, err := a.store.UpsertPayment(ctx, p)
		if err != nil {
			fail("Платёж по заказу №"+strconv.FormatInt(p.OrderID, 10), err)
			continue
		}
		summary.Payments.count(created)
	}

	for _, op := range parsed.Cash {
		created, err := a.store.UpsertCashOp(ctx, op)
		if err != nil {
			fail("Операция кассы на "+strconv.FormatInt(op.AmountKop/100, 10)+" ₽", err)
			continue
		}
		summary.Cash.count(created)
	}

	for _, t := range parsed.Tasks {
		created, err := a.store.UpsertTask(ctx, t)
		if err != nil {
			fail("Задача «"+t.Title+"»", err)
			continue
		}
		summary.Tasks.count(created)
	}
	// Второй проход по подзадачам: в правленом руками файле родитель может
	// стоять ниже своей подзадачи, и на первом проходе его ещё не было —
	// UpsertTask тогда обнулил ссылку, чтобы не потерять саму задачу.
	// Проходим только строки с id: без него запись не обновить, а завести
	// заново значило бы получить дубль.
	for _, t := range parsed.Tasks {
		if t.ID == 0 || t.ParentID == nil {
			continue
		}
		if _, err := a.store.UpsertTask(ctx, t); err != nil {
			fail("Задача «"+t.Title+"»", err)
		}
	}

	if parsed.Company != nil {
		if _, err := a.store.SaveCompany(ctx, *parsed.Company); err != nil {
			fail("Реквизиты", err)
		} else {
			summary.Company = true
		}
	}

	if len(summary.Warnings) > maxWarnings {
		extra := len(summary.Warnings) - maxWarnings
		summary.Warnings = append(summary.Warnings[:maxWarnings],
			"…и ещё "+strconv.Itoa(extra)+" замечаний")
	}

	writeJSON(w, http.StatusOK, summary)
}
