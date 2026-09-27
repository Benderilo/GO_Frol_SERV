package api

import (
	"net/http"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// liveDraftKinds — черновики, содержимое которых целиком берётся из заказа.
// Строк в них не правят (правят заказ), поэтому пока документ не проведён,
// он обязан показывать заказ таким, какой он сейчас. Смета и акт сверки
// живут своими строками и сюда не входят.
var liveDraftKinds = map[string]bool{
	store.DocInvoice: true,
	store.DocAct:     true,
	store.DocWaybill: true,
	store.DocUPD:     true,
}

// syncDraft пересобирает черновик по заказу из текущих данных заказа.
// Без этого черновик застывал в момент создания: счёт завели, потом вписали
// цену в заказ — а в PDF клиенту и в проведённый документ уходил ноль.
// Проведённые и аннулированные документы не трогаются: они заморожены.
func (a *API) syncDraft(r *http.Request, doc store.Document) (store.Document, error) {
	if doc.Status != store.DocDraft || doc.OrderID == nil || !liveDraftKinds[doc.Kind] {
		return doc, nil
	}
	ctx := r.Context()
	company, err := a.store.Company(ctx)
	if err != nil {
		return doc, err
	}
	order, err := a.store.Order(ctx, *doc.OrderID)
	if err != nil {
		return doc, err
	}
	items, err := a.store.OrderItems(ctx, order.ID)
	if err != nil {
		return doc, err
	}
	client := a.clientOf(r, order)
	if doc.ClientID != nil {
		if c, err := a.store.Client(ctx, *doc.ClientID); err == nil {
			client = c
		}
	}

	// Дата и название — то, что правят в самом черновике, — сохраняются.
	old := parseSnapshot(doc.Snapshot)
	_, fresh, err := a.composeDocument(ctx, company, client, order, items, true, documentBody{
		Kind:       doc.Kind,
		OrderID:    doc.OrderID,
		ClientID:   doc.ClientID,
		DocDate:    doc.DocDate,
		PeriodFrom: doc.PeriodFrom,
		PeriodTo:   doc.PeriodTo,
		Title:      old.Title,
		ValidUntil: old.ValidUntil,
	})
	if err != nil {
		return doc, err
	}
	if fresh.Snapshot == doc.Snapshot {
		return doc, nil
	}
	if err := a.store.RefreshDocumentSnapshot(ctx, doc.ID, fresh.Title, fresh.TotalKop, fresh.Snapshot); err != nil {
		return doc, err
	}
	return a.store.DocumentByID(ctx, doc.ID)
}
