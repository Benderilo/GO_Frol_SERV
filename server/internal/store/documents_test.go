package store

import (
	"context"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

// Жизненный цикл документа: черновик редактируется, проведение закрепляет
// номер и запрещает правку, аннулирование помечает недействительным,
// а номер аннулированного не достаётся следующему.
func TestDocumentLifecycle(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	draft, err := st.CreateDocument(ctx, Document{
		Kind: DocEstimate, Title: "Смета", TotalKop: 100_000,
		Snapshot: `{"v":1,"totalKop":100000}`,
	})
	if err != nil {
		t.Fatalf("CreateDocument: %v", err)
	}
	if draft.Status != DocDraft || draft.Number != 0 {
		t.Fatalf("новый документ должен быть черновиком без номера: %+v", draft)
	}

	edited, err := st.UpdateDocument(ctx, draft.ID, Document{
		Title: "Смета-2", TotalKop: 200_000, DocDate: draft.DocDate,
		Snapshot: `{"v":1,"totalKop":200000}`,
	})
	if err != nil {
		t.Fatalf("UpdateDocument: %v", err)
	}
	if edited.Title != "Смета-2" || edited.TotalKop != 200_000 {
		t.Fatalf("правка черновика не сохранилась: %+v", edited)
	}

	issued, err := st.IssueDocumentByID(ctx, draft.ID)
	if err != nil {
		t.Fatalf("IssueDocumentByID: %v", err)
	}
	if issued.Status != DocIssued || issued.Number != 1 || issued.Year == 0 {
		t.Fatalf("после проведения ждём статус issued и номер 1: %+v", issued)
	}

	// Повторное проведение идемпотентно — номер не меняется.
	again, err := st.IssueDocumentByID(ctx, draft.ID)
	if err != nil {
		t.Fatalf("повторное проведение: %v", err)
	}
	if again.Number != issued.Number {
		t.Fatalf("номер изменился при повторном проведении: %d -> %d", issued.Number, again.Number)
	}

	// Проведённый документ не редактируется и не удаляется.
	if _, err := st.UpdateDocument(ctx, draft.ID, Document{Title: "x"}); err == nil {
		t.Error("правка проведённого должна падать")
	}
	if err := st.DeleteDocument(ctx, draft.ID); err == nil {
		t.Error("удаление проведённого должно падать")
	}

	annulled, err := st.AnnulDocument(ctx, draft.ID)
	if err != nil {
		t.Fatalf("AnnulDocument: %v", err)
	}
	if annulled.Status != DocAnnulled {
		t.Fatalf("после аннулирования статус annulled: %+v", annulled)
	}

	// Номер аннулированного не переиспользуется: следующая смета получает 2.
	next, err := st.CreateDocument(ctx, Document{Kind: DocEstimate, Snapshot: "{}"})
	if err != nil {
		t.Fatalf("вторая смета: %v", err)
	}
	nextIssued, err := st.IssueDocumentByID(ctx, next.ID)
	if err != nil {
		t.Fatalf("проведение второй сметы: %v", err)
	}
	if nextIssued.Number != 2 {
		t.Fatalf("номер аннулированного документа не должен переиспользоваться, получен %d", nextIssued.Number)
	}
}

// Нумерация независима по видам: счёт и акт одного года растут каждый сам
// по себе, а повторное обращение по той же паре «заказ + вид» возвращает
// существующий документ.
func TestIssueDocumentLegacyNumbering(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	client, err := st.CreateClient(ctx, Client{Name: "Иван"})
	if err != nil {
		t.Fatalf("CreateClient: %v", err)
	}
	first, err := st.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Щит", PriceKop: 100})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	second, err := st.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Розетки", PriceKop: 100})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}

	invoice1, err := st.IssueDocument(ctx, first.ID, DocInvoice)
	if err != nil {
		t.Fatalf("IssueDocument: %v", err)
	}
	invoiceAgain, err := st.IssueDocument(ctx, first.ID, DocInvoice)
	if err != nil {
		t.Fatalf("повторное IssueDocument: %v", err)
	}
	if invoiceAgain.ID != invoice1.ID || invoiceAgain.Number != invoice1.Number {
		t.Fatalf("повторное обращение должно вернуть тот же документ: %+v vs %+v", invoiceAgain, invoice1)
	}

	invoice2, err := st.IssueDocument(ctx, second.ID, DocInvoice)
	if err != nil {
		t.Fatalf("IssueDocument второй заказ: %v", err)
	}
	if invoice2.Number != invoice1.Number+1 {
		t.Fatalf("номер счёта должен расти: %d после %d", invoice2.Number, invoice1.Number)
	}

	act1, err := st.IssueDocument(ctx, first.ID, DocAct)
	if err != nil {
		t.Fatalf("IssueDocument акт: %v", err)
	}
	if act1.Number != 1 {
		t.Fatalf("нумерация акта независима от счёта, ждём 1: %d", act1.Number)
	}

	if invoice1.ClientID == nil || *invoice1.ClientID != client.ID {
		t.Fatalf("клиент из заказа должен попасть в документ: %+v", invoice1)
	}
	if invoice1.DocDate == "" {
		t.Error("дата документа должна проставляться при выдаче номера")
	}
}

// Журнал: фильтры по виду, статусу и клиенту.
func TestListDocumentsFilters(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	client, err := st.CreateClient(ctx, Client{Name: "Пётр"})
	if err != nil {
		t.Fatalf("CreateClient: %v", err)
	}
	order, err := st.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Проводка", PriceKop: 10})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	if _, err := st.IssueDocument(ctx, order.ID, DocInvoice); err != nil {
		t.Fatalf("IssueDocument: %v", err)
	}
	draft, err := st.CreateDocument(ctx, Document{
		Kind: DocEstimate, ClientID: &client.ID, Snapshot: "{}",
	})
	if err != nil {
		t.Fatalf("CreateDocument: %v", err)
	}

	all, err := st.ListDocuments(ctx, DocumentFilter{})
	if err != nil {
		t.Fatalf("ListDocuments: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("в журнале должно быть два документа: %+v", all)
	}

	onlyInvoices, err := st.ListDocuments(ctx, DocumentFilter{Kind: DocInvoice})
	if err != nil {
		t.Fatalf("ListDocuments: %v", err)
	}
	if len(onlyInvoices) != 1 || onlyInvoices[0].Kind != DocInvoice {
		t.Fatalf("фильтр по виду: %+v", onlyInvoices)
	}

	onlyDrafts, err := st.ListDocuments(ctx, DocumentFilter{Status: DocDraft})
	if err != nil {
		t.Fatalf("ListDocuments: %v", err)
	}
	if len(onlyDrafts) != 1 || onlyDrafts[0].ID != draft.ID {
		t.Fatalf("фильтр по статусу: %+v", onlyDrafts)
	}

	byClient, err := st.ListDocuments(ctx, DocumentFilter{ClientID: client.ID})
	if err != nil {
		t.Fatalf("ListDocuments: %v", err)
	}
	if len(byClient) != 2 {
		t.Fatalf("фильтр по клиенту: %+v", byClient)
	}
	for _, d := range byClient {
		if d.ClientName != "Пётр" {
			t.Fatalf("имя клиента должно приезжать в журнал: %+v", d)
		}
	}
}

// База с журналом номеров старого выпуска должна подняться новым сервером:
// номера сохраняются, документы становятся «проведёнными», клиент
// подтягивается из заказа.
func TestRebuildDocumentsMigration(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "old.db")

	// Старая схема: только выдача номеров, order_id обязателен.
	old, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	client, err := old.CreateClient(ctx, Client{Name: "Ольга"})
	if err != nil {
		t.Fatalf("CreateClient: %v", err)
	}
	order, err := old.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Освещение", PriceKop: 5000})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	// Возвращаем таблицу к старому виду и вставляем строку по-старому.
	if _, err := old.db.ExecContext(ctx, `DROP TABLE documents`); err != nil {
		t.Fatalf("DROP documents: %v", err)
	}
	if _, err := old.db.ExecContext(ctx, `
		CREATE TABLE documents (
			id        INTEGER PRIMARY KEY AUTOINCREMENT,
			order_id  INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
			kind      TEXT    NOT NULL,
			year      INTEGER NOT NULL,
			number    INTEGER NOT NULL,
			issued_at TEXT    NOT NULL
		)`); err != nil {
		t.Fatalf("CREATE старой documents: %v", err)
	}
	if _, err := old.db.ExecContext(ctx,
		`INSERT INTO documents (order_id, kind, year, number, issued_at)
		 VALUES (?, 'invoice', 2025, 7, '2025-11-03T10:00:00Z')`, order.ID); err != nil {
		t.Fatalf("INSERT старой строки: %v", err)
	}
	old.Close()

	migrated, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("повторное открытие с миграцией: %v", err)
	}
	defer migrated.Close()

	docs, err := migrated.ListDocuments(ctx, DocumentFilter{})
	if err != nil {
		t.Fatalf("ListDocuments: %v", err)
	}
	if len(docs) != 1 {
		t.Fatalf("после миграции должен быть один документ: %+v", docs)
	}
	d := docs[0]
	if d.Kind != DocInvoice || d.Status != DocIssued || d.Number != 7 || d.Year != 2025 {
		t.Fatalf("данные миграции разошлись: %+v", d)
	}
	if d.DocDate != "2025-11-03" {
		t.Fatalf("дата документа должна приехать из даты выдачи: %q", d.DocDate)
	}
	if d.ClientID == nil || *d.ClientID != client.ID || d.ClientName != "Ольга" {
		t.Fatalf("клиент должен подтянуться из заказа: %+v", d)
	}

	// Повторное открытие ничего не ломает: миграция идемпотентна.
	if err := migrated.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	again, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("третье открытие: %v", err)
	}
	defer again.Close()
	docs, err = again.ListDocuments(ctx, DocumentFilter{})
	if err != nil || len(docs) != 1 || docs[0].Number != 7 {
		t.Fatalf("миграция не идемпотентна: %v %+v", err, docs)
	}
}

// Взаиморасчёты: заказы — дебет, оплаты — кредит, сальдо на начало периода
// считается по операциям до него.
func TestClientLedger(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	client, err := st.CreateClient(ctx, Client{Name: "Мария"})
	if err != nil {
		t.Fatalf("CreateClient: %v", err)
	}
	order, err := st.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Щиток", PriceKop: 500_000})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	if _, err := st.AddPayment(ctx, order.ID, Payment{AmountKop: 200_000}); err != nil {
		t.Fatalf("AddPayment: %v", err)
	}

	balance, err := st.ClientBalance(ctx, client.ID)
	if err != nil {
		t.Fatalf("ClientBalance: %v", err)
	}
	if balance != 300_000 {
		t.Fatalf("сальдо = 5000 − 2000 = 3000 ₽, получено %d", balance)
	}

	ledger, err := st.ClientLedgerFor(ctx, client.ID, "2100-01-01", "2200-01-01")
	if err != nil {
		t.Fatalf("ClientLedgerFor: %v", err)
	}
	if ledger.OpeningKop != 300_000 {
		t.Fatalf("сальдо на начало далёкого периода = весь долг: %d", ledger.OpeningKop)
	}
	if len(ledger.Rows) != 0 {
		t.Fatalf("в далёком будущем операций быть не должно: %+v", ledger.Rows)
	}

	// Второй заказ после границы — в период не попадает, но в сальдо — да.
	if _, err := st.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Розетки", PriceKop: 100_000}); err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	ledger, err = st.ClientLedgerFor(ctx, client.ID, "2100-01-01", "2200-01-01")
	if err != nil {
		t.Fatalf("ClientLedgerFor: %v", err)
	}
	if ledger.OpeningKop != 400_000 {
		t.Fatalf("сальдо на начало должно видеть оба заказа: %d", ledger.OpeningKop)
	}
}

// Перевод между счетами: две связанные операции, балансы сходятся,
// удаление убирает обе половины.
func TestTransferCash(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	if _, err := st.AddCashOp(ctx, CashOp{
		Direction: DirectionIn, AmountKop: 1_000_000, Method: MethodCash,
	}); err != nil {
		t.Fatalf("AddCashOp: %v", err)
	}

	if _, _, err := st.TransferCash(ctx, MethodCash, MethodCash, 100, ""); err == nil {
		t.Error("перевод сам в себя должен падать")
	}

	out, in, err := st.TransferCash(ctx, MethodCash, MethodAccount, 400_000, "на счёт")
	if err != nil {
		t.Fatalf("TransferCash: %v", err)
	}
	if out.PairID == nil || in.PairID == nil || *out.PairID != in.ID || *in.PairID != out.ID {
		t.Fatalf("половины перевода не связаны: %+v %+v", out, in)
	}

	accounts, err := st.AccountBalances(ctx, "", "")
	if err != nil {
		t.Fatalf("AccountBalances: %v", err)
	}
	byMethod := map[string]int64{}
	for _, acc := range accounts {
		byMethod[acc.Method] = acc.BalanceKop
	}
	if byMethod[MethodCash] != 600_000 || byMethod[MethodAccount] != 400_000 {
		t.Fatalf("балансы после перевода: %+v", byMethod)
	}

	if err := st.DeleteCashOp(ctx, out.ID); err != nil {
		t.Fatalf("DeleteCashOp: %v", err)
	}
	accounts, err = st.AccountBalances(ctx, "", "")
	if err != nil {
		t.Fatalf("AccountBalances: %v", err)
	}
	for _, acc := range accounts {
		if acc.Method == MethodAccount && acc.BalanceKop != 0 {
			t.Fatalf("после удаления перевода приход должен исчезнуть: %+v", acc)
		}
	}
}

// Накладная при проведении списывает склад, аннулирование возвращает.
func TestWaybillWriteOffAndReturn(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	item, err := st.CreateCatalogItem(ctx, CatalogItem{
		Kind: "material", Name: "Кабель", Unit: "м", PriceKop: 8000, CostKop: 5000,
	})
	if err != nil {
		t.Fatalf("CreateCatalogItem: %v", err)
	}
	if _, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: 100_000, CostKop: 5000}); err != nil {
		t.Fatalf("AddStockMove: %v", err)
	}
	client, err := st.CreateClient(ctx, Client{Name: "Илья"})
	if err != nil {
		t.Fatalf("CreateClient: %v", err)
	}
	order, err := st.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Монтаж", PriceKop: 100_000})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	catalogID := item.ID
	if _, err := st.AddOrderItem(ctx, order.ID, OrderItem{
		CatalogID: &catalogID, Kind: "material", Name: "Кабель", Unit: "м",
		QtyMilli: 30_000, PriceKop: 8000, CostKop: 5000,
	}); err != nil {
		t.Fatalf("AddOrderItem: %v", err)
	}

	// Списание и возврат — как их зовут эффекты накладной.
	if _, err := st.WriteOffOrder(ctx, order.ID); err != nil {
		t.Fatalf("WriteOffOrder: %v", err)
	}
	items, err := st.OrderItems(ctx, order.ID)
	if err != nil {
		t.Fatalf("OrderItems: %v", err)
	}
	if !items[0].WrittenOff() {
		t.Fatal("после списания строка должна быть помечена")
	}
	full, err := st.CatalogItem(ctx, item.ID)
	if err != nil {
		t.Fatalf("CatalogItem: %v", err)
	}
	if full.StockMilli != 70_000 {
		t.Fatalf("остаток после списания: %d", full.StockMilli)
	}

	returned, err := st.UnwriteOffOrder(ctx, order.ID)
	if err != nil {
		t.Fatalf("UnwriteOffOrder: %v", err)
	}
	if returned != 1 {
		t.Fatalf("должна вернуться одна строка: %d", returned)
	}
	full, err = st.CatalogItem(ctx, item.ID)
	if err != nil {
		t.Fatalf("CatalogItem: %v", err)
	}
	if full.StockMilli != 100_000 {
		t.Fatalf("остаток после возврата: %d", full.StockMilli)
	}
	items, err = st.OrderItems(ctx, order.ID)
	if err != nil {
		t.Fatalf("OrderItems: %v", err)
	}
	if items[0].WrittenOff() {
		t.Fatal("после возврата отметка о списании должна сняться")
	}
}
