package reports

import (
	"bytes"
	"context"
	"testing"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Посев общей витрины: клиент, закрытый заказ с материалом, оплата, расход
// кассы, приход и списание на складе, выданный документ. Этого хватает
// каждому отчёту реестра.
func seedReports(t *testing.T, st *store.Store) {
	t.Helper()
	ctx := context.Background()

	client, err := st.CreateClient(ctx, store.Client{Name: "Мария"})
	if err != nil {
		t.Fatalf("CreateClient: %v", err)
	}
	item, err := st.CreateCatalogItem(ctx, store.CatalogItem{
		Kind: "material", Name: "Кабель ВВГ", Unit: "м",
		PriceKop: 8000, CostKop: 5000,
	})
	if err != nil {
		t.Fatalf("CreateCatalogItem: %v", err)
	}
	if _, err := st.AddStockMove(ctx, item.ID, store.StockMove{
		QtyMilli: 100_000, CostKop: 5000, Note: "закупка",
	}); err != nil {
		t.Fatalf("AddStockMove: %v", err)
	}

	order, err := st.CreateOrder(ctx, store.Order{
		ClientID: &client.ID, Title: "Проводка", PriceKop: 500_000, Status: "done",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	if _, err := st.AddOrderItem(ctx, order.ID, store.OrderItem{
		CatalogID: &item.ID, Kind: "material", Name: "Кабель ВВГ", Unit: "м",
		QtyMilli: 20_000, PriceKop: 8000, CostKop: 5000,
	}); err != nil {
		t.Fatalf("AddOrderItem: %v", err)
	}
	if _, err := st.AddPayment(ctx, order.ID, store.Payment{AmountKop: 300_000}); err != nil {
		t.Fatalf("AddPayment: %v", err)
	}
	if _, err := st.AddCashOp(ctx, store.CashOp{
		Direction: store.DirectionOut, AmountKop: 50_000,
		Method: store.MethodCard, Category: "инструмент",
	}); err != nil {
		t.Fatalf("AddCashOp: %v", err)
	}
	doc, err := st.CreateDocument(ctx, store.Document{
		Kind: store.DocInvoice, OrderID: &order.ID, ClientID: &client.ID,
		TotalKop: 500_000, DocDate: "2026-09-01", Snapshot: "{}",
	})
	if err != nil {
		t.Fatalf("CreateDocument: %v", err)
	}
	if _, err := st.IssueDocumentByID(ctx, doc.ID); err != nil {
		t.Fatalf("IssueDocumentByID: %v", err)
	}
}

// Каждый отчёт реестра обязан прогоняться на одних и тех же данных и дать
// непустой результат: сломанную сборку проще поймать здесь, чем на телефоне.
func TestRegistryReportsRun(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, t.TempDir()+"/reports.db")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer st.Close()
	seedReports(t, st)

	for _, def := range All() {
		t.Run(def.ID, func(t *testing.T) {
			result, err := def.Build(ctx, st, Params{From: "2000-01-01", To: "2099-12-31"})
			if err != nil {
				t.Fatalf("Build: %v", err)
			}
			if result.Title == "" || len(result.Sections) == 0 {
				t.Fatalf("пустой результат: %+v", result)
			}
			for _, section := range result.Sections {
				if len(section.Columns) == 0 {
					t.Errorf("секция %q без колонок", section.Title)
				}
				for _, row := range section.Rows {
					if len(row) != len(section.Columns) {
						t.Errorf("секция %q: строка из %d ячеек при %d колонках",
							section.Title, len(row), len(section.Columns))
					}
				}
			}
			if _, err := Workbook(result); err != nil {
				t.Errorf("Workbook: %v", err)
			}
		})
	}
}

// Сальдо клиента: заказ 5000 ₽ минус оплата 3000 ₽ = долг 2000 ₽.
func TestClientBalancesNumbers(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, t.TempDir()+"/balances.db")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer st.Close()
	seedReports(t, st)

	result, err := ByID("client-balances").Build(ctx, st, Params{})
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	if len(result.Sections) == 0 || len(result.Sections[0].Rows) == 0 {
		t.Fatalf("нет строк: %+v", result)
	}
	row := result.Sections[0].Rows[0]
	// Цена заказа пересчитана из состава: 20 м по 80 ₽ = 1600 ₽.
	// Оплачено 3000 ₽ — сальдо отрицательное, у клиента аванс 1400 ₽.
	if row[2].ValueKop != 160_000 || row[3].ValueKop != 300_000 || row[4].ValueKop != -140_000 {
		t.Fatalf("сальдо разошлось: %+v", row)
	}
}

// Книга Excel собирается и не пустая.
func TestWorkbookNotEmpty(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, t.TempDir()+"/wb.db")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer st.Close()
	seedReports(t, st)

	result, err := ByID("period").Build(ctx, st, Params{})
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	book, err := Workbook(result)
	if err != nil {
		t.Fatalf("Workbook: %v", err)
	}
	if !bytes.Contains(book, []byte("xl/worksheets/sheet1.xml")) {
		t.Error("в книге нет листа")
	}
}
