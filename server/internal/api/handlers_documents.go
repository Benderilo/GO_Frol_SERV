package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/documents"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// ---------- Печатные документы ----------
//
// Документ живёт в трёх состояниях: черновик (редактируется), проведён
// (номер закреплён, содержимое заморожено в снимке), аннулирован (пометка,
// номер не переиспользуется). Содержимое — documents.Data, оно же печатная
// форма: сборка один раз при создании, пересчёт при правке черновика,
// печать проведённого всегда из снимка.

// documentCard — документ с разобранным содержимым для приложения.
// Сырой JSON снимка не дублируется: приложение получает готовый объект.
type documentCard struct {
	store.Document
	Content documents.Data `json:"content"`
}

func newCard(doc store.Document, content documents.Data) documentCard {
	doc.Snapshot = ""
	return documentCard{Document: doc, Content: content}
}

// documentLineBody — строка табличной части из приложения. Сырые значения:
// количество в тысячных, цена в копейках.
type documentLineBody struct {
	Name     string `json:"name"`
	Unit     string `json:"unit"`
	QtyMilli int64  `json:"qtyMilli"`
	PriceKop int64  `json:"priceKop"`
}

type documentBody struct {
	Kind       string             `json:"kind"`
	OrderID    *int64             `json:"orderId"`
	ClientID   *int64             `json:"clientId"`
	DocDate    string             `json:"docDate"`
	Title      string             `json:"title"`
	PeriodFrom string             `json:"periodFrom"`
	PeriodTo   string             `json:"periodTo"`
	ValidUntil string             `json:"validUntil"`
	Lines      []documentLineBody `json:"lines"`
}

var (
	errNoOrder      = errors.New("для этого вида документа нужен заказ")
	errNoClient     = errors.New("для акта сверки нужен клиент")
	errAlreadyNoted = errors.New("ответ уже отправлен")
)

// handleOrderDocument отдаёт документ по заказу готовой страницей.
// Легаси-ручка для приложений, которые открывают документы из карточки
// заказа: документ создаётся сразу проведённым, а содержимое каждый раз
// пересобирается из живых данных заказа — так было с первого выпуска,
// и установленные приложения на это рассчитывают.
//
// HTML, а не PDF: страницу печатают из приложения системным диалогом,
// который сам сохраняет её в PDF. Так документ можно поправить в вёрстке
// под свою форму, не пересобирая сервер и не таща в него шрифты.
func (a *API) handleOrderDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	kind := r.PathValue("kind")
	if !store.ValidDocKind(kind) || kind == store.DocEstimate || kind == store.DocReconciliation {
		writeError(w, http.StatusBadRequest, "validation", "Неизвестный вид документа")
		return
	}

	ctx := r.Context()
	company, err := a.companyForDocument(w, ctx)
	if err != nil {
		return
	}

	order, err := a.store.Order(ctx, id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	items, err := a.store.OrderItems(ctx, id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	doc, err := a.store.IssueDocument(ctx, id, kind)
	if err != nil {
		writeStoreError(w, err)
		return
	}

	data := buildOrderDocument(company, order, items, doc, a.clientOf(r, order))
	data.Live = true
	a.writeDocumentHTML(w, data)

	// Снимок обновляем только у документов легаси-потока (или у строк,
	// приехавших из старой базы без снимка вовсе): документ, проведённый
	// через новый поток, заморожен навсегда, и пересборка из живых данных
	// отменила бы гарантию неизменности.
	var current documents.Data
	_ = json.Unmarshal([]byte(doc.Snapshot), &current)
	if doc.Snapshot == "" || current.Live {
		if snapshot, err := json.Marshal(data); err == nil {
			_ = a.store.RefreshDocumentSnapshot(ctx, doc.ID, data.Title, data.TotalKop, string(snapshot))
		}
	}
}

// handleListDocuments — журнал документов.
func (a *API) handleListDocuments(w http.ResponseWriter, r *http.Request) {
	f := store.DocumentFilter{
		Kind:   r.URL.Query().Get("kind"),
		Status: r.URL.Query().Get("status"),
		Query:  r.URL.Query().Get("q"),
		From:   r.URL.Query().Get("from"),
		To:     r.URL.Query().Get("to"),
		Limit:  queryInt(r, "limit", 100),
		Offset: queryInt(r, "offset", 0),
	}
	if v, err := parseInt64(r.URL.Query().Get("clientId")); err == nil {
		f.ClientID = v
	}
	if v, err := parseInt64(r.URL.Query().Get("orderId")); err == nil {
		f.OrderID = v
	}
	if f.Kind != "" && !store.ValidDocKind(f.Kind) {
		writeError(w, http.StatusBadRequest, "validation", "Неизвестный вид документа")
		return
	}
	items, err := a.store.ListDocuments(r.Context(), f)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

// handleCreateDocument заводит черновик. Содержимое собирается по виду:
// счёт, акт, накладная и УПД — из заказа; смета — из переданных строк
// (или из состава заказа, если указан); сверка — из взаиморасчётов клиента.
func (a *API) handleCreateDocument(w http.ResponseWriter, r *http.Request) {
	var body documentBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if !store.ValidDocKind(body.Kind) {
		writeError(w, http.StatusBadRequest, "validation", "Неизвестный вид документа")
		return
	}

	ctx := r.Context()
	company, err := a.companyForDocument(w, ctx)
	if err != nil {
		return
	}

	var client store.Client
	haveClient := false
	if body.ClientID != nil {
		if client, err = a.store.Client(ctx, *body.ClientID); err != nil {
			writeStoreError(w, err)
			return
		}
		haveClient = true
	}

	var order store.Order
	var items []store.OrderItem
	haveOrder := false
	if body.OrderID != nil {
		if order, err = a.store.Order(ctx, *body.OrderID); err != nil {
			writeStoreError(w, err)
			return
		}
		if items, err = a.store.OrderItems(ctx, *body.OrderID); err != nil {
			writeStoreError(w, err)
			return
		}
		haveOrder = true
	}
	if !haveClient && haveOrder {
		client = a.clientOf(r, order)
	}

	data, doc, err := a.composeDocument(r.Context(), company, client, order, items, haveOrder, body)
	if err != nil {
		if errors.Is(err, errNoOrder) || errors.Is(err, errNoClient) {
			writeError(w, http.StatusBadRequest, "validation", err.Error())
			return
		}
		writeStoreError(w, err)
		return
	}

	created, err := a.store.CreateDocument(ctx, doc)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, newCard(created, data))
}

// handleGetDocument — карточка документа с содержимым.
func (a *API) handleGetDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	doc, err := a.store.DocumentByID(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, newCard(doc, parseSnapshot(doc.Snapshot)))
}

// handleUpdateDocument правит черновик: дату, период и строки табличной
// части. Отформатированные суммы пересчитываются на сервере — приложение
// присылает только сырые значения. Отсутствующее поле не трогается.
func (a *API) handleUpdateDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body documentBody
	if !decodeJSON(w, r, &body) {
		return
	}

	ctx := r.Context()
	doc, err := a.store.DocumentByID(ctx, id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if doc.Status != store.DocDraft {
		writeError(w, http.StatusConflict, "not_draft", "Проведённый документ не редактируется")
		return
	}

	content := parseSnapshot(doc.Snapshot)
	if body.DocDate != "" {
		doc.DocDate = body.DocDate
	}
	if body.Title != "" {
		doc.Title = body.Title
	}
	if body.ValidUntil != "" {
		content.ValidUntil = body.ValidUntil
	}
	if body.PeriodFrom != "" || body.PeriodTo != "" {
		if body.PeriodFrom != "" {
			doc.PeriodFrom = body.PeriodFrom
		}
		if body.PeriodTo != "" {
			doc.PeriodTo = body.PeriodTo
		}
		if doc.Kind == store.DocReconciliation && doc.ClientID != nil {
			ledger, err := a.store.ClientLedgerFor(ctx, *doc.ClientID, doc.PeriodFrom, doc.PeriodTo)
			if err != nil {
				writeStoreError(w, err)
				return
			}
			applyLedger(&content, ledger)
		}
	}
	if body.Lines != nil {
		content.Lines = bodyLines(body.Lines)
	}
	content.DocDate = doc.DocDate
	content.Title = doc.Title
	content.PeriodFrom = documents.FormatShortDate(doc.PeriodFrom)
	content.PeriodTo = documents.FormatShortDate(doc.PeriodTo)
	content.Recompute()

	snapshot, err := json.Marshal(content)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	doc.Title = content.Title
	doc.TotalKop = content.TotalKop
	doc.Snapshot = string(snapshot)
	updated, err := a.store.UpdateDocument(ctx, id, doc)
	if err != nil {
		if errors.Is(err, store.ErrNotDraft) {
			writeError(w, http.StatusConflict, "not_draft", "Проведённый документ не редактируется")
			return
		}
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, newCard(updated, content))
}

// handlePrintDocument отдаёт печатную форму готовой страницей. Проведённый
// и аннулированный документ печатается из замороженного снимка; номер и
// штамп аннулирования берутся из строки документа — источник правды один.
func (a *API) handlePrintDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	doc, err := a.store.DocumentByID(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if doc.Snapshot == "" {
		// Строки, созданные до снимков (легаси-выдача номера), печатаем
		// пересборкой из живых данных заказа — как это всегда работало.
		if doc.OrderID == nil {
			writeError(w, http.StatusConflict, "empty_document", "У документа нет содержимого")
			return
		}
		a.rebuildAndPrint(w, r, doc)
		return
	}

	data := parseSnapshot(doc.Snapshot)
	data.Number = int(doc.Number)
	data.Year = doc.Year
	data.Annulled = doc.Status == store.DocAnnulled
	a.writeDocumentHTML(w, data)
}

// handleIssueDocument проводит черновик. Проведение — это не только номер:
// накладная при проведении списывает материалы заказа со склада.
func (a *API) handleIssueDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	doc, err := a.store.IssueDocumentByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotDraft) {
			writeError(w, http.StatusConflict, "not_draft", "Документ нельзя провести")
			return
		}
		writeStoreError(w, err)
		return
	}

	// Учётный эффект: накладная списывает несписанные материалы заказа.
	// Не хватило остатка — проведение отменяем: документ с номером, под
	// которым ничего не произошло, хуже понятной ошибки.
	if doc.Kind == store.DocWaybill && doc.OrderID != nil {
		if _, err := a.store.WriteOffOrder(r.Context(), *doc.OrderID); err != nil {
			_, _ = a.store.AnnulDocument(r.Context(), doc.ID)
			writeError(w, http.StatusConflict, "stock",
				"Не хватило остатка на складе для списания: "+err.Error())
			return
		}
	}

	content := parseSnapshot(doc.Snapshot)
	content.Number = int(doc.Number)
	content.Year = doc.Year
	writeJSON(w, http.StatusOK, newCard(doc, content))
}

// handleAnnulDocument аннулирует проведённый документ и откатывает его
// учётный эффект: накладная возвращает списанные материалы на склад.
func (a *API) handleAnnulDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	doc, err := a.store.AnnulDocument(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotDraft) {
			writeError(w, http.StatusConflict, "not_draft", "Черновик можно только удалить")
			return
		}
		writeStoreError(w, err)
		return
	}
	if doc.Kind == store.DocWaybill && doc.OrderID != nil {
		if _, err := a.store.UnwriteOffOrder(r.Context(), *doc.OrderID); err != nil {
			// Документ уже аннулирован — материал вернётся при следующей
			// попытке, ошибку показываем, но статус не откатываем.
			writeStoreError(w, err)
			return
		}
	}
	content := parseSnapshot(doc.Snapshot)
	content.Number = int(doc.Number)
	content.Annulled = true
	writeJSON(w, http.StatusOK, newCard(doc, content))
}

// handleDocumentToOrder превращает смету в заказ: строки переезжают
// в состав заказа, документ привязывается к нему, номер остаётся в журнале.
func (a *API) handleDocumentToOrder(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	doc, err := a.store.DocumentByID(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if doc.Kind != store.DocEstimate {
		writeError(w, http.StatusBadRequest, "validation", "В заказ превращается только смета")
		return
	}
	if doc.OrderID != nil {
		writeError(w, http.StatusConflict, "has_order", "Смета уже привязана к заказу № "+parseInt64Text(*doc.OrderID))
		return
	}
	if doc.ClientID == nil {
		writeError(w, http.StatusBadRequest, "validation", "У сметы не указан клиент — выберите его в черновике")
		return
	}

	content := parseSnapshot(doc.Snapshot)
	title := doc.Title
	if title == "" || title == store.DocKindTitle(store.DocEstimate) {
		title = "Работы по смете"
		if doc.Number > 0 {
			title += fmt.Sprintf(" № %d", doc.Number)
		}
	}
	description := "Создан из сметы"
	if doc.Number > 0 {
		description += fmt.Sprintf(" № %d", doc.Number)
	}

	ctx := r.Context()
	order, err := a.store.CreateOrder(ctx, store.Order{
		ClientID:    doc.ClientID,
		Title:       title,
		Status:      "new",
		PriceKop:    doc.TotalKop,
		Description: description,
	})
	if err != nil {
		writeStoreError(w, err)
		return
	}
	for _, line := range content.Lines {
		if _, err := a.store.AddOrderItem(ctx, order.ID, store.OrderItem{
			Kind: "service", Name: line.Name, Unit: line.Unit,
			QtyMilli: line.QtyMilli, PriceKop: line.PriceKop,
		}); err != nil {
			writeStoreError(w, err)
			return
		}
	}
	updated, err := a.store.SetDocumentOrder(ctx, doc.ID, order.ID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"order": order, "document": newCard(updated, content)})
}

func parseInt64Text(v int64) string { return strconv.FormatInt(v, 10) }

// handleDeleteDocument удаляет черновик.
func (a *API) handleDeleteDocument(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteDocument(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotDraft) {
			writeError(w, http.StatusConflict, "not_draft",
				"Проведённый документ не удаляется — аннулируйте его")
			return
		}
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleClientBalance — сальдо взаиморасчётов с клиентом: долг (плюс)
// или аванс (минус). Нужно карточке клиента и акту сверки.
func (a *API) handleClientBalance(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	balance, err := a.store.ClientBalance(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"balanceKop": balance})
}

// ---------- Сборка содержимого ----------

// companyForDocument достаёт реквизиты и отклоняет запрос, если печатать
// нечем. Обезличенный документ клиенту не отдают.
func (a *API) companyForDocument(w http.ResponseWriter, ctx context.Context) (store.Company, error) {
	company, err := a.store.Company(ctx)
	if err != nil {
		writeStoreError(w, err)
		return company, errAlreadyNoted
	}
	if !company.Filled() {
		writeError(w, http.StatusConflict, "company_empty",
			"Не заполнены реквизиты ИП. Откройте «Реквизиты ИП» и впишите хотя бы наименование и ИНН.")
		return company, errAlreadyNoted
	}
	return company, nil
}

// composeDocument собирает содержимое черновика по виду документа.
func (a *API) composeDocument(
	ctx context.Context,
	company store.Company,
	client store.Client,
	order store.Order,
	items []store.OrderItem,
	haveOrder bool,
	body documentBody,
) (documents.Data, store.Document, error) {
	doc := store.Document{
		Kind:       body.Kind,
		DocDate:    body.DocDate,
		PeriodFrom: body.PeriodFrom,
		PeriodTo:   body.PeriodTo,
	}
	if doc.DocDate == "" {
		doc.DocDate = todayISO()
	}
	if body.OrderID != nil {
		doc.OrderID = body.OrderID
	}
	if body.ClientID != nil {
		doc.ClientID = body.ClientID
	} else if haveOrder {
		doc.ClientID = order.ClientID
	}

	var data documents.Data
	switch body.Kind {
	case store.DocInvoice, store.DocAct, store.DocWaybill, store.DocUPD:
		if !haveOrder {
			return data, doc, errNoOrder
		}
		data = buildOrderDocument(company, order, items, store.Document{Kind: body.Kind}, client)

	case store.DocEstimate:
		data = newBlankDocument(company, body.Kind, client)
		if haveOrder {
			data.OrderNo = order.ID
			data.OrderName = order.Title
		}
		switch {
		case len(body.Lines) > 0:
			data.Lines = bodyLines(body.Lines)
		case haveOrder:
			data.Lines = orderItemLines(items, order)
		}

	case store.DocReconciliation:
		if doc.ClientID == nil {
			return data, doc, errNoClient
		}
		if doc.PeriodFrom == "" {
			doc.PeriodFrom = todayISO()[:4] + "-01-01"
		}
		if doc.PeriodTo == "" {
			doc.PeriodTo = todayISO()
		}
		ledger, err := a.store.ClientLedgerFor(ctx, *doc.ClientID, doc.PeriodFrom, doc.PeriodTo)
		if err != nil {
			return data, doc, err
		}
		data = newBlankDocument(company, body.Kind, client)
		applyLedger(&data, ledger)
	}

	data.DocDate = doc.DocDate
	if body.Title != "" {
		data.Title = body.Title
	}
	if body.ValidUntil != "" {
		data.ValidUntil = body.ValidUntil
	}
	data.Recompute()

	doc.Title = data.Title
	doc.TotalKop = data.TotalKop
	snapshot, err := json.Marshal(data)
	if err != nil {
		return data, doc, err
	}
	doc.Snapshot = string(snapshot)
	return data, doc, nil
}

// applyLedger переносит взаиморасчёты в содержимое акта сверки.
func applyLedger(data *documents.Data, ledger store.ClientLedger) {
	data.OpeningKop = ledger.OpeningKop
	data.Opening = documents.Money(ledger.OpeningKop)
	data.Ledger = make([]documents.LedgerRow, 0, len(ledger.Rows))
	for _, e := range ledger.Rows {
		data.Ledger = append(data.Ledger, documents.LedgerRow{
			Date:      documents.FormatShortDate(e.Date),
			Label:     e.Label,
			DebitKop:  e.DebitKop,
			CreditKop: e.CreditKop,
		})
	}
}

// bodyLines превращает строки из приложения в строки табличной части.
func bodyLines(body []documentLineBody) []documents.Line {
	lines := make([]documents.Line, 0, len(body))
	for _, l := range body {
		unit := l.Unit
		if unit == "" {
			unit = "усл."
		}
		qty := l.QtyMilli
		if qty == 0 {
			qty = 1000
		}
		lines = append(lines, documents.Line{
			Name: l.Name, Unit: unit, QtyMilli: qty, PriceKop: l.PriceKop,
		})
	}
	return lines
}

// newBlankDocument — шапка документа без табличной части.
func newBlankDocument(company store.Company, kind string, client store.Client) documents.Data {
	return documents.Data{
		V:           documents.SnapshotVersion,
		Kind:        kind,
		Title:       store.DocKindTitle(kind),
		Supplier:    supplierParty(company),
		Customer:    customerParty(client),
		TaxNote:     company.TaxNote,
		FooterNote:  company.FooterNote,
		SignerName:  firstNonEmpty(company.SignerName, company.ShortName),
		SignerTitle: company.SignerTitle,
		VatRate:     company.VatRate,
	}
}

// buildOrderDocument собирает документ по заказу.
func buildOrderDocument(
	company store.Company,
	order store.Order,
	items []store.OrderItem,
	doc store.Document,
	client store.Client,
) documents.Data {
	data := newBlankDocument(company, doc.Kind, client)
	data.OrderNo = order.ID
	data.OrderName = order.Title
	data.Number = int(doc.Number)
	data.Year = doc.Year
	data.DocDate = doc.DocDate
	switch doc.Kind {
	case store.DocInvoice:
		data.Title = "Счёт на оплату"
		data.Bank = companyBank(company)
	case store.DocAct:
		data.Title = "Акт выполненных работ"
	case store.DocWaybill:
		data.Title = "Товарная накладная"
	case store.DocUPD:
		data.Title = "Универсальный передаточный документ"
		data.Bank = companyBank(company)
	}
	data.Lines = orderItemLines(items, order)
	data.Recompute()
	return data
}

func companyBank(company store.Company) documents.Bank {
	return documents.Bank{
		Name:        company.BankName,
		BIK:         company.BankBIK,
		Account:     company.BankAccount,
		CorrAccount: company.BankCorrAccount,
	}
}

// orderItemLines — строки из состава заказа с сырыми суммами: количества
// в тысячных, цены в копейках. У заказа без состава печатаем одну строку
// с его названием и ценой: пустая таблица в счёте выглядит как ошибка,
// а работа-то была.
func orderItemLines(items []store.OrderItem, order store.Order) []documents.Line {
	lines := make([]documents.Line, 0, len(items)+1)
	for _, item := range items {
		lines = append(lines, documents.Line{
			Name:     item.Name,
			Unit:     item.Unit,
			QtyMilli: item.QtyMilli,
			PriceKop: item.PriceKop,
			TotalKop: item.TotalKop,
		})
	}
	if len(lines) == 0 {
		lines = append(lines, documents.Line{
			Name: order.Title, Unit: "усл.", QtyMilli: 1000,
			PriceKop: order.PriceKop, TotalKop: order.PriceKop,
		})
	}
	return lines
}

func supplierParty(company store.Company) documents.Party {
	return documents.Party{
		Name:    firstNonEmpty(company.FullName, company.ShortName),
		INN:     company.INN,
		KPP:     company.KPP,
		OGRNIP:  company.OGRNIP,
		Address: company.Address,
		Phone:   company.Phone,
		Email:   company.Email,
	}
}

func customerParty(client store.Client) documents.Party {
	return documents.Party{
		Name:        client.Name,
		INN:         client.INN,
		KPP:         client.KPP,
		Address:     client.Address,
		Phone:       client.Phone,
		Email:       client.Email,
		BankName:    client.BankName,
		BankAccount: client.BankAccount,
	}
}

func parseSnapshot(snapshot string) documents.Data {
	var data documents.Data
	if snapshot != "" {
		_ = json.Unmarshal([]byte(snapshot), &data)
	}
	return data
}

func todayISO() string { return time.Now().Format("2006-01-02") }

// writeDocumentHTML печатает документ в ответ.
func (a *API) writeDocumentHTML(w http.ResponseWriter, data documents.Data) {
	var page bytes.Buffer
	if err := documents.Render(&page, data); err != nil {
		writeStoreError(w, err)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(page.Bytes())
}

// rebuildAndPrint пересобирает документ из живых данных заказа и печатает.
// Вызывается для строк без снимка — так жили документы до появления снимков.
func (a *API) rebuildAndPrint(w http.ResponseWriter, r *http.Request, doc store.Document) {
	ctx := r.Context()
	company, err := a.store.Company(ctx)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	order, err := a.store.Order(ctx, *doc.OrderID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	items, err := a.store.OrderItems(ctx, order.ID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	data := buildOrderDocument(company, order, items, doc, a.clientOf(r, order))
	data.Live = true
	if snapshot, err := json.Marshal(data); err == nil {
		_ = a.store.RefreshDocumentSnapshot(ctx, doc.ID, data.Title, data.TotalKop, string(snapshot))
	}
	a.writeDocumentHTML(w, data)
}

// clientOf достаёт данные клиента заказа. Клиента может не быть —
// тогда в документе останется только то, что известно по заказу.
func (a *API) clientOf(r *http.Request, order store.Order) store.Client {
	if order.ClientID == nil {
		return store.Client{Name: order.ClientName}
	}
	client, err := a.store.Client(r.Context(), *order.ClientID)
	if err != nil {
		return store.Client{Name: order.ClientName}
	}
	return client
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if trim(v) != "" {
			return v
		}
	}
	return ""
}
