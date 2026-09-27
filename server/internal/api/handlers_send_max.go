package api

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strings"

	"github.com/Benderilo/GO_Frol_SERV/internal/documents"
	"github.com/Benderilo/GO_Frol_SERV/internal/pdf"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// handleSendDocumentMax — «Отправить в MAX»: документ печатается в PDF
// и уходит клиенту в личный диалог с ботом с кнопками «Согласен»,
// «Не устраивает», «Есть вопрос». Ответ придёт задачей и в карточку документа.
func (a *API) handleSendDocumentMax(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if a.maxBot == nil {
		writeError(w, http.StatusConflict, "no_bot", "Бот MAX не настроен на сервере")
		return
	}
	ctx := r.Context()
	doc, err := a.store.DocumentByID(ctx, id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if doc.Status == store.DocAnnulled {
		writeError(w, http.StatusConflict, "annulled", "Аннулированный документ клиенту не отправляют")
		return
	}
	if doc, err = a.syncDraft(r, doc); err != nil {
		writeStoreError(w, err)
		return
	}
	// Счёт на ноль клиенту слать нечего: скорее всего, цену забыли вписать.
	if liveDraftKinds[doc.Kind] && doc.TotalKop <= 0 {
		writeError(w, http.StatusConflict, "zero_total",
			"В документе сумма 0 ₽. Укажите стоимость заказа или цены в его составе — документ обновится сам.")
		return
	}

	clientID := doc.ClientID
	if clientID == nil && doc.OrderID != nil {
		if order, err := a.store.Order(ctx, *doc.OrderID); err == nil {
			clientID = order.ClientID
		}
	}
	if clientID == nil {
		writeError(w, http.StatusConflict, "no_client", "У документа нет клиента — отправлять некому")
		return
	}
	userID, err := a.store.MaxUserForClient(ctx, *clientID)
	if errors.Is(err, store.ErrNoMaxContact) {
		writeError(w, http.StatusConflict, "no_max",
			"Клиент ещё не писал боту в MAX или не делился номером. Попросите его написать боту — "+
				"после этого документ можно будет отправить.")
		return
	}
	if err != nil {
		writeStoreError(w, err)
		return
	}

	data, err := a.documentData(r, doc)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	var page bytes.Buffer
	if err := documents.Render(&page, data); err != nil {
		writeStoreError(w, err)
		return
	}
	file, err := a.pdf.Render(ctx, page.Bytes())
	if err != nil {
		if errors.Is(err, pdf.ErrUnavailable) {
			writeError(w, http.StatusServiceUnavailable, "no_pdf", "На сервере не установлена печать в PDF")
			return
		}
		slog.Error("документ в PDF", "document", id, "err", err)
		writeError(w, http.StatusInternalServerError, "pdf_failed", "Не удалось собрать PDF")
		return
	}

	send, err := a.store.AddDocumentSend(ctx, doc.ID, userID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if err := a.maxBot.SendDocument(ctx, send, doc, file); err != nil {
		// Не дошло — отметку об отправке не оставляем.
		_ = a.store.DeleteDocumentSend(ctx, send.ID)
		slog.Error("документ в MAX", "document", id, "err", err)
		writeError(w, http.StatusBadGateway, "max_failed", "MAX не принял документ, попробуйте ещё раз")
		return
	}
	slog.Info("документ отправлен в MAX", "document", id, "send", send.ID)
	writeJSON(w, http.StatusOK, map[string]any{"send": send, "clientName": doc.ClientName})
}

// documentData — содержимое документа для печати: снимок или, у строк
// без снимка, пересборка из живых данных заказа.
func (a *API) documentData(r *http.Request, doc store.Document) (documents.Data, error) {
	if doc.Snapshot != "" {
		data := parseSnapshot(doc.Snapshot)
		data.Number = int(doc.Number)
		data.Year = doc.Year
		data.Annulled = doc.Status == store.DocAnnulled
		return data, nil
	}
	if doc.OrderID == nil {
		return documents.Data{}, store.ErrNotFound
	}
	ctx := r.Context()
	company, err := a.store.Company(ctx)
	if err != nil {
		return documents.Data{}, err
	}
	order, err := a.store.Order(ctx, *doc.OrderID)
	if err != nil {
		return documents.Data{}, err
	}
	items, err := a.store.OrderItems(ctx, order.ID)
	if err != nil {
		return documents.Data{}, err
	}
	data := buildOrderDocument(company, order, items, doc, a.clientOf(r, order))
	data.Live = true
	return data, nil
}

// makeReconciliation — акт сверки по запросу клиента из бота: собирается
// за период, проводится (номер в журнале документов) и печатается в PDF.
// Напоминание о нём не шлём: клиент сам его попросил.
func (a *API) makeReconciliation(ctx context.Context, clientID, maxUserID int64, from, to string) (store.Document, store.DocumentSend, []byte, error) {
	company, err := a.store.Company(ctx)
	if err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	client, err := a.store.Client(ctx, clientID)
	if err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	_, draft, err := a.composeDocument(ctx, company, client, store.Order{}, nil, false, documentBody{
		Kind:       store.DocReconciliation,
		ClientID:   &clientID,
		PeriodFrom: from,
		PeriodTo:   to,
	})
	if err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	created, err := a.store.CreateDocument(ctx, draft)
	if err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	doc, err := a.store.IssueDocumentByID(ctx, created.ID)
	if err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}

	data := parseSnapshot(doc.Snapshot)
	data.Number = int(doc.Number)
	data.Year = doc.Year
	var page bytes.Buffer
	if err := documents.Render(&page, data); err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	file, err := a.pdf.Render(ctx, page.Bytes())
	if err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	send, err := a.store.AddDocumentSend(ctx, doc.ID, maxUserID)
	if err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	if err := a.store.MarkReminded(ctx, send.ID); err != nil {
		return store.Document{}, store.DocumentSend{}, nil, err
	}
	return doc, send, file, nil
}

// handleDocumentPDF отдаёт документ файлом PDF — для сохранения на
// компьютер из веб-панели. Вёрстка та же, что у печати и отправки в MAX.
func (a *API) handleDocumentPDF(w http.ResponseWriter, r *http.Request) {
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
	if doc, err = a.syncDraft(r, doc); err != nil {
		writeStoreError(w, err)
		return
	}
	data, err := a.documentData(r, doc)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	var page bytes.Buffer
	if err := documents.Render(&page, data); err != nil {
		writeStoreError(w, err)
		return
	}
	file, err := a.pdf.Render(r.Context(), page.Bytes())
	if err != nil {
		if errors.Is(err, pdf.ErrUnavailable) {
			writeError(w, http.StatusServiceUnavailable, "no_pdf", "На сервере не установлена печать в PDF")
			return
		}
		slog.Error("документ в PDF", "document", id, "err", err)
		writeError(w, http.StatusInternalServerError, "pdf_failed", "Не удалось собрать PDF")
		return
	}
	name := fileNameFor(doc)
	w.Header().Set("Content-Type", "application/pdf")
	w.Header().Set("Content-Disposition", `attachment; filename="document.pdf"; filename*=UTF-8''`+url.PathEscape(name))
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(file)
}

// fileNameFor — «Счёт на оплату № 12.pdf».
func fileNameFor(doc store.Document) string {
	name := doc.KindTitle
	if name == "" {
		name = store.DocKindTitle(doc.Kind)
	}
	if doc.Number > 0 {
		name += " № " + strconvI64(doc.Number)
	} else {
		name += " (черновик)"
	}
	return strings.NewReplacer("/", "-", `\`, "-", `"`, "", ":", "-").Replace(name) + ".pdf"
}
