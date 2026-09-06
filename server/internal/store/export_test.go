package store

import (
	"context"
	"testing"
)

// Выгрузка обещает быть резервной копией, значит проверять её надо кругом
// целиком: собрали базу, забрали всё в Backup, залили в пустую базу и сверили.
// Раньше в этом круге терялись склад, касса, состав заказов и задачи —
// они просто не попадали в выгрузку.
func TestBackupRoundTrip(t *testing.T) {
	ctx := context.Background()
	src := openTestStore(t)

	client, err := src.CreateClient(ctx, Client{Name: "Иван Петров", Phone: "+79001112233"})
	if err != nil {
		t.Fatalf("создание клиента: %v", err)
	}
	order, err := src.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Замена щита", Status: "in_progress"})
	if err != nil {
		t.Fatalf("создание заказа: %v", err)
	}
	material, err := src.CreateCatalogItem(ctx, CatalogItem{
		Kind: KindMaterial, Name: "Кабель ВВГ 3х2.5", Unit: "м", PriceKop: 12_050, CostKop: 8_000,
	})
	if err != nil {
		t.Fatalf("создание материала: %v", err)
	}
	if _, err := src.AddStockMove(ctx, material.ID, StockMove{QtyMilli: 100_000, CostKop: 800_000, Note: "закупка"}); err != nil {
		t.Fatalf("приход материала: %v", err)
	}
	if _, err := src.AddOrderItem(ctx, order.ID, OrderItem{
		CatalogID: &material.ID, QtyMilli: 12_500,
	}); err != nil {
		t.Fatalf("строка заказа: %v", err)
	}
	if _, err := src.WriteOffOrder(ctx, order.ID); err != nil {
		t.Fatalf("списание по заказу: %v", err)
	}
	if _, err := src.AddCashOp(ctx, CashOp{
		Direction: DirectionOut, AmountKop: 800_000, Method: MethodCash,
		Category: "материалы", Note: "кабель",
	}); err != nil {
		t.Fatalf("расход кассы: %v", err)
	}
	if _, err := src.AddPayment(ctx, order.ID, Payment{AmountKop: 2_000_000, Note: "аванс"}); err != nil {
		t.Fatalf("платёж: %v", err)
	}
	if _, err := src.CreateRequest(ctx, Request{Name: "Пётр", Phone: "+79005554433", Message: "Нужна проводка"}); err != nil {
		t.Fatalf("заявка: %v", err)
	}
	parent, err := src.CreateTask(ctx, Task{Title: "Позвонить по щиту", ClientID: &client.ID, Priority: "high"})
	if err != nil {
		t.Fatalf("задача: %v", err)
	}
	if _, err := src.CreateTask(ctx, Task{Title: "Заказать автоматы", ParentID: &parent.ID}); err != nil {
		t.Fatalf("подзадача: %v", err)
	}
	if _, err := src.SaveCompany(ctx, Company{ShortName: "ИП Фролов А. В.", INN: "643900000000"}); err != nil {
		t.Fatalf("реквизиты: %v", err)
	}

	backup, err := src.AllForExport(ctx)
	if err != nil {
		t.Fatalf("сбор выгрузки: %v", err)
	}
	assertBackupFull(t, backup, "в выгрузке из исходной базы")

	// Порядок записи тот же, что в обработчике загрузки: ссылки требуют,
	// чтобы клиент шёл раньше заказа, а позиция склада — раньше движения.
	dst := openTestStore(t)
	for _, c := range backup.Clients {
		if _, err := dst.UpsertClient(ctx, c); err != nil {
			t.Fatalf("загрузка клиента: %v", err)
		}
	}
	for _, item := range backup.Catalog {
		if _, err := dst.UpsertCatalogItem(ctx, item); err != nil {
			t.Fatalf("загрузка позиции склада: %v", err)
		}
	}
	for _, o := range backup.Orders {
		if _, err := dst.UpsertOrder(ctx, o); err != nil {
			t.Fatalf("загрузка заказа: %v", err)
		}
	}
	for _, m := range backup.StockMoves {
		if _, err := dst.UpsertStockMove(ctx, m); err != nil {
			t.Fatalf("загрузка движения склада: %v", err)
		}
	}
	for _, item := range backup.OrderItems {
		if _, err := dst.UpsertOrderItem(ctx, item); err != nil {
			t.Fatalf("загрузка строки заказа: %v", err)
		}
	}
	for _, r := range backup.Requests {
		if _, err := dst.UpsertRequest(ctx, r); err != nil {
			t.Fatalf("загрузка заявки: %v", err)
		}
	}
	for _, op := range backup.Cash {
		if _, err := dst.UpsertCashOp(ctx, op); err != nil {
			t.Fatalf("загрузка операции кассы: %v", err)
		}
	}
	for _, task := range backup.Tasks {
		if _, err := dst.UpsertTask(ctx, task); err != nil {
			t.Fatalf("загрузка задачи: %v", err)
		}
	}
	if _, err := dst.SaveCompany(ctx, backup.Company); err != nil {
		t.Fatalf("загрузка реквизитов: %v", err)
	}

	restored, err := dst.AllForExport(ctx)
	if err != nil {
		t.Fatalf("сбор выгрузки после загрузки: %v", err)
	}
	assertBackupFull(t, restored, "после восстановления")

	// Остаток считается из движений: если бы приход и списание задвоились
	// при загрузке, он бы разошёлся именно здесь.
	var stock int64
	for _, item := range restored.Catalog {
		if item.Kind == KindMaterial {
			stock = item.StockMilli
		}
	}
	if stock != 87_500 {
		t.Errorf("остаток материала: получено %d тысячных, ожидалось 87500", stock)
	}
	if got := len(restored.StockMoves); got != 2 {
		t.Errorf("движений склада: получено %d, ожидалось 2", got)
	}
	if got := len(restored.Cash); got != 2 {
		t.Errorf("операций кассы: получено %d, ожидалось 2", got)
	}

	// Цена заказа — сумма его строк: 12.5 м по 120.50 ₽.
	if got := restored.Orders[0].PriceKop; got != 150_625 {
		t.Errorf("стоимость заказа: получено %d копеек, ожидалось 150625", got)
	}
	if restored.Orders[0].ClientID == nil {
		t.Error("связь заказа с клиентом потеряна при восстановлении")
	}
	if restored.Company.INN != "643900000000" {
		t.Errorf("ИНН после восстановления: %q", restored.Company.INN)
	}

	var sub Task
	for _, task := range restored.Tasks {
		if task.ParentID != nil {
			sub = task
		}
	}
	if sub.Title != "Заказать автоматы" {
		t.Errorf("вложенность задач потеряна: %+v", restored.Tasks)
	}
}

// assertBackupFull проверяет, что в выгрузке есть все разделы.
func assertBackupFull(t *testing.T, b Backup, when string) {
	t.Helper()
	sections := map[string]int{
		"клиенты":         len(b.Clients),
		"заказы":          len(b.Orders),
		"состав заказов":  len(b.OrderItems),
		"заявки":          len(b.Requests),
		"платежи":         len(b.Payments),
		"касса":           len(b.Cash),
		"склад":           len(b.Catalog),
		"движения склада": len(b.StockMoves),
		"задачи":          len(b.Tasks),
	}
	for name, count := range sections {
		if count == 0 {
			t.Errorf("%s: раздел «%s» пуст", when, name)
		}
	}
	if b.Company.ShortName == "" {
		t.Errorf("%s: реквизиты пусты", when)
	}
}

// Загрузка сохраняет id из файла: иначе восстановление из копии рассыпало бы
// ссылки между разделами — состав заказа встал бы к чужому заказу.
func TestUpsertKeepsIDs(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	created, err := st.UpsertCatalogItem(ctx, CatalogItem{
		ID: 42, Kind: KindMaterial, Name: "Гофра 20", Unit: "м", PriceKop: 3_000,
	})
	if err != nil || !created {
		t.Fatalf("вставка позиции с id: created=%v err=%v", created, err)
	}
	item, err := st.CatalogItem(ctx, 42)
	if err != nil {
		t.Fatalf("позиция с id 42 не найдена: %v", err)
	}
	if item.Name != "Гофра 20" {
		t.Errorf("позиция искажена: %+v", item)
	}

	// Повторная загрузка того же файла не должна плодить дубли.
	created, err = st.UpsertCatalogItem(ctx, CatalogItem{
		ID: 42, Kind: KindMaterial, Name: "Гофра 20 мм", Unit: "м", PriceKop: 3_500,
	})
	if err != nil || created {
		t.Fatalf("повторная загрузка должна обновлять: created=%v err=%v", created, err)
	}
	items, err := st.CatalogItems(ctx, "", true)
	if err != nil {
		t.Fatalf("справочник: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("позиций: получено %d, ожидалась 1", len(items))
	}
	if items[0].PriceKop != 3_500 {
		t.Errorf("цена не обновилась: %d", items[0].PriceKop)
	}

	created, err = st.UpsertStockMove(ctx, StockMove{ID: 7, ItemID: 42, QtyMilli: 25_000, CostKop: 60_000})
	if err != nil || !created {
		t.Fatalf("вставка движения с id: created=%v err=%v", created, err)
	}
	if created, err := st.UpsertStockMove(ctx, StockMove{ID: 7, ItemID: 42, QtyMilli: 25_000}); err != nil || created {
		t.Fatalf("повторное движение должно обновляться: created=%v err=%v", created, err)
	}
	moves, err := st.StockMovesForExport(ctx)
	if err != nil {
		t.Fatalf("движения склада: %v", err)
	}
	if len(moves) != 1 {
		t.Errorf("движений: получено %d, ожидалось 1", len(moves))
	}
}

// Ссылка в файле может указывать на запись, которой в базе нет. Строку из-за
// этого не теряем — принимаем без привязки.
func TestUpsertDropsBrokenLinks(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	missing := int64(999)
	if _, err := st.UpsertCashOp(ctx, CashOp{
		Direction: DirectionIn, AmountKop: 1_000, Method: MethodCash, OrderID: &missing,
	}); err != nil {
		t.Fatalf("операция кассы с битой ссылкой: %v", err)
	}
	ops, err := st.CashOpsForExport(ctx)
	if err != nil {
		t.Fatalf("касса: %v", err)
	}
	if len(ops) != 1 {
		t.Fatalf("операций: получено %d, ожидалась 1", len(ops))
	}
	if ops[0].OrderID != nil {
		t.Errorf("ссылка на несуществующий заказ должна обнулиться: %v", ops[0].OrderID)
	}

	if _, err := st.UpsertTask(ctx, Task{Title: "Осиротевшая", ParentID: &missing, ClientID: &missing}); err != nil {
		t.Fatalf("задача с битыми ссылками: %v", err)
	}
	tasks, err := st.ListTasks(ctx, "")
	if err != nil {
		t.Fatalf("задачи: %v", err)
	}
	if len(tasks) != 1 || tasks[0].ParentID != nil || tasks[0].ClientID != nil {
		t.Errorf("битые ссылки задачи должны обнулиться: %+v", tasks)
	}
}
