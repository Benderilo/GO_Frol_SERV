package exports

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/xuri/excelize/v2"
)

// Книга уходит пользователю и возвращается обратно — важно, чтобы данные
// пережили этот круг без потерь: кириллица, дробная цена, статусы, ссылки.
func TestBuildThenParse(t *testing.T) {
	clientID := int64(7)
	backup := store.Backup{
		Clients: []store.Client{{
			ID: 7, Name: "Иван Петров", Phone: "+7 900 111-22-33",
			Email: "ivan@example.ru", Address: "Москва, Ленина 5",
			Tag: "постоянный", Note: "звонить после 18:00", PortalEnabled: true,
		}},
		Orders: []store.Order{{
			ID: 3, ClientID: &clientID, ClientName: "Иван Петров",
			Title: "Замена щита", Description: "Щит на 24 модуля, УЗО",
			Status: "in_progress", PriceKop: 4_850_050, DueDate: "до 1 сентября",
		}},
		Requests: []store.Request{{
			ID: 11, Name: "Пётр", Phone: "+79005554433",
			Message: "Нужна проводка", Source: "site", Status: "new",
		}},
		Payments: []store.Payment{{
			ID: 1, OrderID: 3, AmountKop: 2_000_000, Note: "аванс",
			CreatedAt: "2026-08-10T10:00:00Z", OrderTitle: "Замена щита", ClientName: "Иван Петров",
		}},
	}

	data, err := Build(backup)
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}

	got, err := Parse(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("разбор книги: %v", err)
	}
	if len(got.Warnings) != 0 {
		t.Errorf("неожиданные замечания: %v", got.Warnings)
	}

	if len(got.Clients) != 1 {
		t.Fatalf("клиентов: получено %d, ожидался 1", len(got.Clients))
	}
	c := got.Clients[0]
	if c.ID != 7 || c.Name != "Иван Петров" || c.Phone != "+7 900 111-22-33" {
		t.Errorf("клиент искажён: %+v", c)
	}
	if c.Note != "звонить после 18:00" || c.Tag != "постоянный" {
		t.Errorf("заметка или метка искажены: note=%q tag=%q", c.Note, c.Tag)
	}

	if len(got.Orders) != 1 {
		t.Fatalf("заказов: получено %d, ожидался 1", len(got.Orders))
	}
	o := got.Orders[0]
	if o.ID != 3 || o.Title != "Замена щита" {
		t.Errorf("заказ искажён: %+v", o)
	}
	if o.Status != "in_progress" {
		t.Errorf("статус заказа: получен %q, ожидался in_progress", o.Status)
	}
	if o.PriceKop != 4_850_050 {
		t.Errorf("цена: получено %d копеек, ожидалось 4850050", o.PriceKop)
	}
	if o.ClientID == nil || *o.ClientID != 7 {
		t.Errorf("связь с клиентом потеряна: %v", o.ClientID)
	}

	if len(got.Requests) != 1 {
		t.Fatalf("заявок: получено %d, ожидалась 1", len(got.Requests))
	}
	if got.Requests[0].Status != "new" {
		t.Errorf("статус заявки: получен %q, ожидался new", got.Requests[0].Status)
	}

	// В книге есть лист «Касса», поэтому платежи читаются оттуда, а не с листа
	// «Платежи»: те же приходы не должны приехать в базу дважды.
	if len(got.Payments) != 0 {
		t.Errorf("платежи прочитаны при наличии листа «Касса»: %+v", got.Payments)
	}
}

// sampleBackup — база, в которой заполнен каждый раздел. Один набор данных
// на все проверки книги: так лист, забытый в Build, всплывёт сразу в нескольких.
func sampleBackup() store.Backup {
	orderID := int64(3)
	clientID := int64(7)
	itemID := int64(5)
	parentID := int64(9)

	return store.Backup{
		Clients: []store.Client{{ID: 7, Name: "Иван Петров", Phone: "+7 900 111-22-33"}},
		Orders:  []store.Order{{ID: 3, ClientID: &clientID, Title: "Замена щита", Status: "new"}},
		Requests: []store.Request{{
			ID: 11, Name: "Пётр", Phone: "+79005554433", Message: "Нужна проводка",
			Source: "site", Status: "new",
		}},
		Payments: []store.Payment{{
			ID: 1, OrderID: 3, AmountKop: 2_000_000, Note: "аванс",
			OrderTitle: "Замена щита", ClientName: "Иван Петров",
		}},
		Catalog: []store.CatalogItem{
			{
				ID: 5, Kind: store.KindMaterial, Name: "Кабель ВВГ 3×2,5", Unit: "м",
				PriceKop: 12_050, CostKop: 8_000, Note: "бухта 100 м", StockMilli: 87_500,
			},
			{ID: 6, Kind: store.KindWork, Name: "Монтаж розетки", Unit: "шт.", PriceKop: 45_000, Archived: true},
		},
		StockMoves: []store.StockMove{
			{ID: 1, ItemID: 5, QtyMilli: 100_000, CostKop: 800_000, Note: "закупка", ItemName: "Кабель ВВГ 3×2,5", Unit: "м"},
			{ID: 2, ItemID: 5, OrderID: &orderID, QtyMilli: -12_500, Note: "списание", ItemName: "Кабель ВВГ 3×2,5", Unit: "м"},
		},
		OrderItems: []store.OrderItem{{
			ID: 4, OrderID: 3, CatalogID: &itemID, Kind: store.KindMaterial,
			Name: "Кабель ВВГ 3×2,5", Unit: "м", QtyMilli: 12_500,
			PriceKop: 12_050, CostKop: 8_000, Sort: 1,
			TotalKop: 150_625, TotalCostKop: 100_000, OrderTitle: "Замена щита",
		}},
		Cash: []store.CashOp{
			{
				ID: 1, Direction: store.DirectionIn, AmountKop: 2_000_000, Method: store.MethodCard,
				Category: "оплата заказа", OrderID: &orderID, Note: "аванс", HappenedAt: "2026-08-10T10:00:00Z",
			},
			{
				ID: 2, Direction: store.DirectionOut, AmountKop: 800_000, Method: store.MethodCash,
				Category: "материалы", Note: "кабель", HappenedAt: "2026-08-09T09:00:00Z",
			},
		},
		Tasks: []store.Task{
			{ID: 9, Title: "Позвонить по щиту", DueDate: "2026-09-01", Priority: "high", ClientID: &clientID},
			{ID: 10, Title: "Заказать автоматы", ParentID: &parentID, Done: true, Priority: "low"},
		},
		Company: store.Company{ShortName: "ИП Фролов А. В.", INN: "643900000000", BankBIK: "043601607"},
	}
}

// Склад, состав заказа, касса, задачи и реквизиты — разделы, которых
// в выгрузке раньше не было вовсе. Проверяем, что они и пишутся, и читаются.
func TestBuildThenParseAllSections(t *testing.T) {
	backup := sampleBackup()

	data, err := Build(backup)
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}
	got, err := Parse(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("разбор книги: %v", err)
	}
	if len(got.Warnings) != 0 {
		t.Errorf("неожиданные замечания: %v", got.Warnings)
	}

	if len(got.Catalog) != 2 {
		t.Fatalf("позиций склада: получено %d, ожидалось 2", len(got.Catalog))
	}
	item := got.Catalog[0]
	if item.ID != 5 || item.Kind != store.KindMaterial || item.Name != "Кабель ВВГ 3×2,5" {
		t.Errorf("позиция склада искажена: %+v", item)
	}
	if item.Unit != "м" || item.PriceKop != 12_050 || item.CostKop != 8_000 {
		t.Errorf("цены позиции искажены: %+v", item)
	}
	if !got.Catalog[1].Archived {
		t.Errorf("отметка «в архиве» потеряна: %+v", got.Catalog[1])
	}

	if len(got.StockMoves) != 2 {
		t.Fatalf("движений склада: получено %d, ожидалось 2", len(got.StockMoves))
	}
	// Списание записано отрицательным количеством — знак терять нельзя,
	// иначе остаток после загрузки удвоится вместо того, чтобы сойтись.
	if got.StockMoves[1].QtyMilli != -12_500 {
		t.Errorf("количество списания: получено %d, ожидалось -12500", got.StockMoves[1].QtyMilli)
	}
	if got.StockMoves[0].QtyMilli != 100_000 || got.StockMoves[0].CostKop != 800_000 {
		t.Errorf("приход искажён: %+v", got.StockMoves[0])
	}
	if got.StockMoves[1].OrderID == nil || *got.StockMoves[1].OrderID != 3 {
		t.Errorf("связь списания с заказом потеряна: %v", got.StockMoves[1].OrderID)
	}

	if len(got.OrderItems) != 1 {
		t.Fatalf("строк состава: получено %d, ожидалась 1", len(got.OrderItems))
	}
	line := got.OrderItems[0]
	if line.ID != 4 || line.OrderID != 3 || line.Name != "Кабель ВВГ 3×2,5" {
		t.Errorf("строка состава искажена: %+v", line)
	}
	if line.QtyMilli != 12_500 || line.PriceKop != 12_050 || line.CostKop != 8_000 {
		t.Errorf("количество или цены строки искажены: %+v", line)
	}
	if line.CatalogID == nil || *line.CatalogID != 5 {
		t.Errorf("связь строки со справочником потеряна: %v", line.CatalogID)
	}

	if len(got.Cash) != 2 {
		t.Fatalf("операций кассы: получено %d, ожидалось 2", len(got.Cash))
	}
	in, out := got.Cash[0], got.Cash[1]
	if in.Direction != store.DirectionIn || in.Method != store.MethodCard || in.AmountKop != 2_000_000 {
		t.Errorf("приход искажён: %+v", in)
	}
	if in.OrderID == nil || *in.OrderID != 3 {
		t.Errorf("связь прихода с заказом потеряна: %v", in.OrderID)
	}
	if out.Direction != store.DirectionOut || out.Category != "материалы" {
		t.Errorf("расход искажён: %+v", out)
	}
	if out.HappenedAt != "2026-08-09T09:00:00Z" {
		t.Errorf("дата расхода: получена %q", out.HappenedAt)
	}

	if len(got.Tasks) != 2 {
		t.Fatalf("задач: получено %d, ожидалось 2", len(got.Tasks))
	}
	if got.Tasks[0].Priority != "high" || got.Tasks[0].DueDate != "2026-09-01" {
		t.Errorf("задача искажена: %+v", got.Tasks[0])
	}
	sub := got.Tasks[1]
	if !sub.Done || sub.ParentID == nil || *sub.ParentID != 9 {
		t.Errorf("подзадача искажена: %+v", sub)
	}

	if got.Company == nil {
		t.Fatal("реквизиты не прочитаны")
	}
	if got.Company.ShortName != "ИП Фролов А. В." || got.Company.INN != "643900000000" {
		t.Errorf("реквизиты искажены: %+v", got.Company)
	}
	// БИК начинается с нуля — он должен остаться строкой, а не стать числом.
	if got.Company.BankBIK != "043601607" {
		t.Errorf("БИК искажён: получен %q, ожидался 043601607", got.Company.BankBIK)
	}
}

// В книге без листа «Касса» платежи читаются со своего листа — так открываются
// файлы, выгруженные прежними версиями.
func TestParsePaymentsFromOldBook(t *testing.T) {
	data, err := Build(store.Backup{
		Orders:   []store.Order{{ID: 3, Title: "Замена щита", Status: "new"}},
		Payments: []store.Payment{{ID: 1, OrderID: 3, AmountKop: 2_000_000, Note: "аванс"}},
	})
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}
	trimmed, err := deleteSheet(data, SheetCash)
	if err != nil {
		t.Fatalf("удаление листа кассы: %v", err)
	}

	got, err := Parse(bytes.NewReader(trimmed))
	if err != nil {
		t.Fatalf("разбор книги: %v", err)
	}
	if len(got.Payments) != 1 {
		t.Fatalf("платежей: получено %d, ожидался 1", len(got.Payments))
	}
	if got.Payments[0].AmountKop != 2_000_000 || got.Payments[0].Note != "аванс" {
		t.Errorf("платёж искажён: %+v", got.Payments[0])
	}
}

// Строка без id — это новая запись: id должен остаться нулевым,
// иначе загрузка перезапишет чужую строку.
func TestParseRowWithoutID(t *testing.T) {
	data, err := Build(store.Backup{
		Clients: []store.Client{{Name: "Без идентификатора", Phone: "+70000000000"}},
	})
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}

	got, err := Parse(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("разбор книги: %v", err)
	}
	if len(got.Clients) != 1 {
		t.Fatalf("клиентов: получено %d, ожидался 1", len(got.Clients))
	}
	if got.Clients[0].ID != 0 {
		t.Errorf("id должен быть нулевым, получен %d", got.Clients[0].ID)
	}
}

// Строки без обязательного поля пропускаются с замечанием, а не роняют загрузку.
func TestParseSkipsEmptyRows(t *testing.T) {
	data, err := Build(store.Backup{Clients: []store.Client{{ID: 1, Name: ""}}})
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}

	got, err := Parse(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("разбор книги: %v", err)
	}
	if len(got.Clients) != 0 {
		t.Errorf("строка без имени не должна попадать в загрузку: %+v", got.Clients)
	}
	if len(got.Warnings) != 1 {
		t.Errorf("ожидалось одно замечание, получено %d: %v", len(got.Warnings), got.Warnings)
	}
}

// Пустые реквизиты в книге не должны затирать заполненные в базе.
func TestParseKeepsEmptyCompany(t *testing.T) {
	data, err := Build(store.Backup{Clients: []store.Client{{ID: 1, Name: "Кто-то"}}})
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}
	got, err := Parse(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("разбор книги: %v", err)
	}
	if got.Company != nil {
		t.Errorf("пустой лист реквизитов не должен давать значения: %+v", got.Company)
	}
}

// Все разделы приложения должны быть в книге отдельными листами:
// пропавший лист — это раздел, который не переживёт восстановление из копии.
func TestBookHasSheetPerSection(t *testing.T) {
	data, err := Build(store.Backup{})
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}
	f, err := openBook(data)
	if err != nil {
		t.Fatalf("открытие книги: %v", err)
	}
	defer f.Close()

	got := map[string]bool{}
	for _, name := range f.GetSheetList() {
		got[name] = true
	}
	for _, want := range sheetOrder {
		if !got[want] {
			t.Errorf("в книге нет листа «%s»", want)
		}
	}
	if len(f.GetSheetList()) != len(sheetOrder) {
		t.Errorf("лишние листы в книге: %v", f.GetSheetList())
	}
}

// В строке не должно быть колонок, которых нет в шапке: лишнее значение
// уехало бы в безымянный столбец, а при загрузке прочиталось бы не из той ячейки.
func TestRowsFitHeaders(t *testing.T) {
	data, err := Build(sampleBackup())
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}
	f, err := openBook(data)
	if err != nil {
		t.Fatalf("открытие книги: %v", err)
	}
	defer f.Close()

	for _, sheet := range sheetOrder {
		rows, err := f.GetRows(sheet)
		if err != nil {
			t.Fatalf("чтение листа «%s»: %v", sheet, err)
		}
		if len(rows) < 2 {
			t.Errorf("лист «%s» пуст — в примере должна быть хотя бы одна строка", sheet)
			continue
		}
		for i, row := range rows[1:] {
			if len(row) > len(rows[0]) {
				t.Errorf("лист «%s», строка %d: %d значений при %d колонках в шапке",
					sheet, i+2, len(row), len(rows[0]))
			}
		}
	}
}

// Деньги и количество должны читаться человеком как числа, а не как каша.
// Пробел в коде формата Excel — литерал, а не разделитель разрядов: с ним
// 48 500,50 ₽ показывалось как «485 00.50 ₽».
func TestMoneyAndQtyFormat(t *testing.T) {
	data, err := Build(store.Backup{
		Orders: []store.Order{{ID: 1, Title: "Щит", Status: "new", PriceKop: 4_850_050}},
		Catalog: []store.CatalogItem{{
			ID: 5, Kind: store.KindMaterial, Name: "Кабель", Unit: "м",
			PriceKop: 12_050, StockMilli: 1_234_500,
		}},
	})
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}
	f, err := openBook(data)
	if err != nil {
		t.Fatalf("открытие книги: %v", err)
	}
	defer f.Close()

	// GetRows без RawCellValue отдаёт то же, что человек увидит в ячейке.
	price, err := f.GetCellValue(SheetOrders, "G2")
	if err != nil {
		t.Fatalf("чтение стоимости: %v", err)
	}
	if !strings.HasPrefix(price, "48,500.50") {
		t.Errorf("стоимость показана как %q, а разряды должны отделяться от копеек", price)
	}
	stock, err := f.GetCellValue(SheetCatalog, "G2")
	if err != nil {
		t.Fatalf("чтение остатка: %v", err)
	}
	if stock != "1,234.5" {
		t.Errorf("остаток показан как %q, ожидалось 1,234.5", stock)
	}
}

// Книгу правят в Excel на компьютере: дописывают строки, меняют ячейки,
// перебивают даты. Проверяем, что после такой правки загрузка понимает файл.
func TestParseHandEditedBook(t *testing.T) {
	data, err := Build(sampleBackup())
	if err != nil {
		t.Fatalf("сборка книги: %v", err)
	}
	f, err := openBook(data)
	if err != nil {
		t.Fatalf("открытие книги: %v", err)
	}

	// Новая позиция склада: id человек не заполняет — его назначит сервер.
	set := func(sheet, cell string, v any) {
		if err := f.SetCellValue(sheet, cell, v); err != nil {
			t.Fatalf("правка %s!%s: %v", sheet, cell, err)
		}
	}
	set(SheetCatalog, "A4", "")
	set(SheetCatalog, "B4", "Материал")
	set(SheetCatalog, "C4", "Гофра 20")
	set(SheetCatalog, "D4", "м")
	set(SheetCatalog, "E4", "30,50") // цена с запятой, как её наберут руками
	// Правка существующей строки.
	set(SheetCatalog, "C2", "Кабель ВВГ 3х2,5 (новое имя)")
	// Перебитая дата: Excel хранит её числом со своим форматом.
	dateStyle, err := f.NewStyle(&excelize.Style{NumFmt: 14})
	if err != nil {
		t.Fatalf("стиль даты: %v", err)
	}
	set(SheetCash, "K2", time.Date(2026, 8, 10, 0, 0, 0, 0, time.UTC))
	if err := f.SetCellStyle(SheetCash, "K2", "K2", dateStyle); err != nil {
		t.Fatalf("формат даты: %v", err)
	}

	var buf bytes.Buffer
	if err := f.Write(&buf); err != nil {
		t.Fatalf("запись книги: %v", err)
	}
	f.Close()

	got, err := Parse(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("разбор книги: %v", err)
	}
	if len(got.Warnings) != 0 {
		t.Errorf("неожиданные замечания: %v", got.Warnings)
	}

	if len(got.Catalog) != 3 {
		t.Fatalf("позиций склада: получено %d, ожидалось 3", len(got.Catalog))
	}
	added := got.Catalog[2]
	if added.ID != 0 {
		t.Errorf("у дописанной строки id должен остаться нулевым, получен %d", added.ID)
	}
	if added.Name != "Гофра 20" || added.Kind != store.KindMaterial || added.PriceKop != 3_050 {
		t.Errorf("дописанная позиция искажена: %+v", added)
	}
	if got.Catalog[0].Name != "Кабель ВВГ 3х2,5 (новое имя)" {
		t.Errorf("правка имени не прочитана: %q", got.Catalog[0].Name)
	}

	// Дата-число должна вернуться датой, иначе в базу уехало бы «46244»
	// и отбор за период перестал бы её находить.
	if got.Cash[0].HappenedAt != "2026-08-10" {
		t.Errorf("перебитая дата: получено %q, ожидалось 2026-08-10", got.Cash[0].HappenedAt)
	}
	if got.Cash[1].HappenedAt != "2026-08-09T09:00:00Z" {
		t.Errorf("нетронутая дата искажена: %q", got.Cash[1].HappenedAt)
	}
}

// Число, которое датой быть не может, остаётся как есть: срок заказа
// человек пишет и словами, и цифрами.
func TestDateCellLeavesPlainNumbers(t *testing.T) {
	cases := map[string]string{
		"46244":                "2026-08-10",
		"2026-08-10T10:00:00Z": "2026-08-10T10:00:00Z",
		"до 1 сентября":        "до 1 сентября",
		"5":                    "5",
		"":                     "",
		"100000":               "100000",
	}
	for input, want := range cases {
		if got := dateCell([]string{input}, 0); got != want {
			t.Errorf("dateCell(%q) = %q, ожидалось %q", input, got, want)
		}
	}
}

// Цену человек может поправить руками — принимаем и запятую, и пробелы.
// parsePrice отдаёт копейки: рубль с дробной частью должен превратиться
// в целое число копеек без потери последней копейки.
func TestParsePrice(t *testing.T) {
	cases := map[string]int64{
		"48500.5":   4_850_050,
		"48 500,50": 4_850_050,
		"48500 ₽":   4_850_000,
		"1999.99":   199_999,
		"0.01":      1,
		"":          0,
		"мусор":     0,
		"-100":      0,
	}
	for input, want := range cases {
		if got := parsePrice(input); got != want {
			t.Errorf("parsePrice(%q) = %d, ожидалось %d", input, got, want)
		}
	}
}

// Количество, в отличие от денег, бывает отрицательным: это списание со склада.
func TestParseQty(t *testing.T) {
	cases := map[string]int64{
		"5":      5_000,
		"0,333":  333,
		"1.5":    1_500,
		"-12,5":  -12_500,
		"1 000":  1_000_000,
		"":       0,
		"неясно": 0,
	}
	for input, want := range cases {
		if got := parseQty(input); got != want {
			t.Errorf("parseQty(%q) = %d, ожидалось %d", input, got, want)
		}
	}
}

// Статус в файле может быть и кодом, и русским названием.
func TestStatusCode(t *testing.T) {
	cases := map[string]string{
		"В работе":    "in_progress",
		"в работе":    "in_progress",
		"in_progress": "in_progress",
		"Завершён":    "done",
		"":            "new",
		"непонятно":   "new",
	}
	for input, want := range cases {
		if got := statusCode(input, orderStatusNames, "new"); got != want {
			t.Errorf("statusCode(%q) = %q, ожидалось %q", input, got, want)
		}
	}
}

// Вид позиции, направление и способ оплаты разбираются так же, как статусы.
func TestCodeOfDictionaries(t *testing.T) {
	if got := codeOf("Материал", kindNames, store.KindService); got != store.KindMaterial {
		t.Errorf("вид позиции: получено %q, ожидалось material", got)
	}
	if got := codeOf("расход", directionNames, store.DirectionIn); got != store.DirectionOut {
		t.Errorf("направление: получено %q, ожидалось out", got)
	}
	if got := codeOf("Счёт", methodNames, store.MethodCash); got != store.MethodAccount {
		t.Errorf("способ оплаты: получено %q, ожидалось account", got)
	}
	if got := codeOf("", methodNames, store.MethodCash); got != store.MethodCash {
		t.Errorf("пустая ячейка должна давать значение по умолчанию, получено %q", got)
	}
}

func TestParseBool(t *testing.T) {
	for _, yes := range []string{"да", "Да", "ДА", "1", "true", "+"} {
		if !parseBool(yes) {
			t.Errorf("parseBool(%q) = false, ожидалось true", yes)
		}
	}
	for _, no := range []string{"нет", "", "0", "мусор"} {
		if parseBool(no) {
			t.Errorf("parseBool(%q) = true, ожидалось false", no)
		}
	}
}

// openBook открывает собранную книгу для проверок.
func openBook(data []byte) (*excelize.File, error) {
	return excelize.OpenReader(bytes.NewReader(data))
}

// deleteSheet убирает лист из готовой книги: так получается файл, какие
// выгружали прежние версии, — без листа, появившегося позже.
func deleteSheet(data []byte, sheet string) ([]byte, error) {
	f, err := openBook(data)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	if err := f.DeleteSheet(sheet); err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if err := f.Write(&buf); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
