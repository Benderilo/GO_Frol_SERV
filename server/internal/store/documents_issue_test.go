package store

import (
	"context"
	"errors"
	"testing"
)

// Второй проведённый счёт на тот же заказ — понятная ошибка с номером
// мешающего документа, а не падение на уникальном индексе. Легаси-ручка
// при этом не подхватывает черновик: у него нет номера.
func TestIssueDuplicateForOrder(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	order, err := st.CreateOrder(ctx, Order{Title: "Щит", PriceKop: 100})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	draft, err := st.CreateDocument(ctx, Document{Kind: DocInvoice, OrderID: &order.ID, Snapshot: `{"v":1}`})
	if err != nil {
		t.Fatalf("CreateDocument: %v", err)
	}

	legacy, err := st.IssueDocument(ctx, order.ID, DocInvoice)
	if err != nil {
		t.Fatalf("IssueDocument: %v", err)
	}
	if legacy.ID == draft.ID || legacy.Status != DocIssued || legacy.Number == 0 {
		t.Fatalf("легаси-выдача должна завести проведённый документ, а не взять черновик: %+v", legacy)
	}

	_, err = st.IssueDocumentByID(ctx, draft.ID)
	if !errors.Is(err, ErrDuplicateIssued) {
		t.Fatalf("ждали ErrDuplicateIssued, получили %v", err)
	}

	// Аннулировали старый — новый проводится.
	if _, err := st.AnnulDocument(ctx, legacy.ID); err != nil {
		t.Fatalf("AnnulDocument: %v", err)
	}
	issued, err := st.IssueDocumentByID(ctx, draft.ID)
	if err != nil {
		t.Fatalf("IssueDocumentByID после аннулирования: %v", err)
	}
	if issued.Number != legacy.Number+1 {
		t.Fatalf("номер аннулированного не переиспользуется: ждали %d, получили %d", legacy.Number+1, issued.Number)
	}
}

// Откат проведения возвращает черновик и освобождает номер.
func TestRevertIssue(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	doc, err := st.CreateDocument(ctx, Document{Kind: DocWaybill, Snapshot: `{"v":1}`})
	if err != nil {
		t.Fatalf("CreateDocument: %v", err)
	}
	if _, err := st.IssueDocumentByID(ctx, doc.ID); err != nil {
		t.Fatalf("IssueDocumentByID: %v", err)
	}
	reverted, err := st.RevertIssue(ctx, doc.ID)
	if err != nil {
		t.Fatalf("RevertIssue: %v", err)
	}
	if reverted.Status != DocDraft || reverted.Number != 0 {
		t.Fatalf("после отката ждём черновик без номера: %+v", reverted)
	}
	again, err := st.IssueDocumentByID(ctx, doc.ID)
	if err != nil {
		t.Fatalf("повторное проведение: %v", err)
	}
	if again.Number != 1 {
		t.Fatalf("освобождённый номер должен вернуться: получили %d", again.Number)
	}
}
