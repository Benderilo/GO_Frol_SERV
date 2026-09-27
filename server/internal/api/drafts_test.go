package api

import (
	"context"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Benderilo/GO_Frol_SERV/internal/pdf"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Случай из жизни: счёт завели, когда цена заказа ещё была нулевой, потом
// цену вписали. В PDF клиенту и в проведённый счёт должна попасть новая сумма.
func TestDraftFollowsOrder(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	a := &API{store: st}
	r := httptest.NewRequest("GET", "/", nil)

	client, _ := st.CreateClient(ctx, store.Client{Name: "Дмитрий", Phone: "+79001112233"})
	order, _ := st.CreateOrder(ctx, store.Order{ClientID: &client.ID, Title: "Замена автомата", Status: "new"})

	company, _ := st.Company(ctx)
	_, doc, err := a.composeDocument(ctx, company, client, order, nil, true,
		documentBody{Kind: store.DocInvoice, OrderID: &order.ID, DocDate: "2026-09-27"})
	if err != nil {
		t.Fatal(err)
	}
	draft, err := st.CreateDocument(ctx, doc)
	if err != nil {
		t.Fatal(err)
	}
	if draft.TotalKop != 0 {
		t.Fatalf("черновик на пустой заказ: %d", draft.TotalKop)
	}

	// Вписали цену — черновик подтягивает её сам, дата черновика остаётся.
	order.PriceKop = 350000
	if _, err := st.UpdateOrder(ctx, order.ID, order); err != nil {
		t.Fatal(err)
	}
	synced, err := a.syncDraft(r, draft)
	if err != nil {
		t.Fatal(err)
	}
	data := parseSnapshot(synced.Snapshot)
	if synced.TotalKop != 350000 || data.Total != "3 500,00" || data.DocDate != "2026-09-27" {
		t.Fatalf("черновик не обновился: %d %q %q", synced.TotalKop, data.Total, data.DocDate)
	}

	// Проведённый документ заморожен: правка заказа его уже не меняет.
	issued, err := st.IssueDocumentByID(ctx, synced.ID)
	if err != nil {
		t.Fatal(err)
	}
	order.PriceKop = 1
	_, _ = st.UpdateOrder(ctx, order.ID, order)
	after, err := a.syncDraft(r, issued)
	if err != nil {
		t.Fatal(err)
	}
	if after.TotalKop != 350000 {
		t.Fatalf("проведённый счёт изменился: %d", after.TotalKop)
	}
}

// Акт сверки для бота: проводится с номером, печатается в PDF, напоминание
// о нём не запланировано — клиент сам его попросил.
func TestMakeReconciliation(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	a := &API{store: st, pdf: pdf.New("")}
	if a.pdf == nil {
		t.Skip("браузер для печати не найден")
	}
	client, _ := st.CreateClient(ctx, store.Client{Name: "Дмитрий", Phone: "+79001112233"})
	_, _ = st.CreateOrder(ctx, store.Order{ClientID: &client.ID, Title: "Щиток", Status: "done", PriceKop: 500000})

	doc, send, file, err := a.makeReconciliation(ctx, client.ID, 42, "2026-01-01", "2026-12-31")
	if err != nil {
		t.Fatal(err)
	}
	if doc.Kind != store.DocReconciliation || doc.Status != store.DocIssued || doc.Number == 0 {
		t.Fatalf("акт сверки: %+v", doc)
	}
	if !strings.HasPrefix(string(file), "%PDF-") {
		t.Fatal("не PDF")
	}
	if last, _ := st.LastDocumentSend(ctx, doc.ID); last == nil || last.ID != send.ID || last.RemindedAt == "" {
		t.Fatalf("отправка без напоминания: %+v", last)
	}
}
