package api

import (
	"bytes"
	"errors"
	"log/slog"
	"net/http"

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
