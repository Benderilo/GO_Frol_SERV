// Package exports собирает базу в книгу Excel и читает её обратно.
package exports

import (
	"bytes"
	"fmt"
	"io"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/xuri/excelize/v2"
)

// Названия листов. По ним же книга читается при загрузке,
// поэтому менять их нельзя, не сломав совместимость со старыми файлами.
// Один лист на раздел приложения: что человек видит на экране,
// то и находит в книге отдельной таблицей.
const (
	SheetClients    = "Клиенты"
	SheetOrders     = "Заказы"
	SheetOrderItems = "Состав заказов"
	SheetRequests   = "Заявки"
	SheetPayments   = "Платежи"
	SheetCash       = "Касса"
	SheetCatalog    = "Склад"
	SheetStock      = "Движения склада"
	SheetTasks      = "Задачи"
	SheetCompany    = "Реквизиты"
)

// sheetOrder — порядок вкладок в книге: сначала то, ради чего её открывают.
var sheetOrder = []string{
	SheetClients, SheetOrders, SheetOrderItems, SheetRequests,
	SheetPayments, SheetCash, SheetCatalog, SheetStock, SheetTasks, SheetCompany,
}

var (
	clientHeader = []string{
		"id", "Имя", "Телефон", "E-mail", "Адрес", "Метка", "Заметка",
		"Кабинет", "Создан", "Обновлён",
	}
	orderHeader = []string{
		"id", "id клиента", "Клиент", "Название", "Описание", "Статус",
		"Стоимость", "Оплачено", "Срок", "Фото", "Создан", "Обновлён",
	}
	orderItemHeader = []string{
		"id", "id заказа", "Заказ", "Вид", "Наименование", "Ед.", "Количество",
		"Цена", "Сумма", "Закупка", "Сумма закупки", "Списан со склада",
		"id позиции склада", "Порядок", "Создана",
	}
	requestHeader = []string{
		"id", "Имя", "Телефон", "Сообщение", "Источник", "Статус", "Создана",
	}
	paymentHeader = []string{
		"id", "id заказа", "Заказ", "Клиент", "Сумма", "Заметка", "Дата",
	}
	cashHeader = []string{
		"id", "Направление", "Сумма", "Способ", "Статья", "id заказа", "Заказ",
		"id клиента", "Клиент", "Заметка", "Дата операции", "Записана",
	}
	catalogHeader = []string{
		"id", "Вид", "Наименование", "Ед.", "Цена", "Закупка", "Остаток",
		"Заметка", "В архиве", "Создана", "Обновлена",
	}
	stockHeader = []string{
		"id", "id позиции", "Позиция", "Ед.", "Количество", "Закупка",
		"id заказа", "Заказ", "Заметка", "Дата",
	}
	taskHeader = []string{
		"id", "Название", "Заметка", "Срок", "Готова", "Приоритет",
		"id клиента", "Клиент", "id заказа", "id родителя", "Создана", "Обновлена",
	}
	companyHeader = []string{"Поле", "Значение"}
)

// Понятные названия в выгрузке и обратный разбор при загрузке.
var (
	orderStatusNames = map[string]string{
		"new": "Новый", "in_progress": "В работе", "done": "Завершён", "canceled": "Отменён",
	}
	requestStatusNames = map[string]string{
		"new": "Новая", "in_progress": "В работе", "done": "Обработана", "spam": "Спам",
	}
	kindNames = map[string]string{
		store.KindService: "Услуга", store.KindWork: "Работа", store.KindMaterial: "Материал",
	}
	directionNames = map[string]string{
		store.DirectionIn: "Приход", store.DirectionOut: "Расход",
	}
	methodNames = map[string]string{
		store.MethodCash: "Наличные", store.MethodCard: "Карта", store.MethodAccount: "Счёт",
	}
	priorityNames = map[string]string{
		"low": "Низкий", "normal": "Обычный", "high": "Высокий",
	}
)

// companyFields — порядок строк на листе реквизитов и связь названия
// с полем структуры. Лист сделан «поле в строке», а не колонками:
// реквизит один, а полей у него два десятка, и читать их вертикально проще.
var companyFields = []struct {
	title string
	get   func(store.Company) string
	set   func(*store.Company, string)
}{
	{"Краткое наименование", func(c store.Company) string { return c.ShortName }, func(c *store.Company, v string) { c.ShortName = v }},
	{"Полное наименование", func(c store.Company) string { return c.FullName }, func(c *store.Company, v string) { c.FullName = v }},
	{"ИНН", func(c store.Company) string { return c.INN }, func(c *store.Company, v string) { c.INN = v }},
	{"ОГРНИП", func(c store.Company) string { return c.OGRNIP }, func(c *store.Company, v string) { c.OGRNIP = v }},
	{"Адрес", func(c store.Company) string { return c.Address }, func(c *store.Company, v string) { c.Address = v }},
	{"Телефон", func(c store.Company) string { return c.Phone }, func(c *store.Company, v string) { c.Phone = v }},
	{"E-mail", func(c store.Company) string { return c.Email }, func(c *store.Company, v string) { c.Email = v }},
	{"Сайт", func(c store.Company) string { return c.Site }, func(c *store.Company, v string) { c.Site = v }},
	{"Банк", func(c store.Company) string { return c.BankName }, func(c *store.Company, v string) { c.BankName = v }},
	{"БИК", func(c store.Company) string { return c.BankBIK }, func(c *store.Company, v string) { c.BankBIK = v }},
	{"Расчётный счёт", func(c store.Company) string { return c.BankAccount }, func(c *store.Company, v string) { c.BankAccount = v }},
	{"Корреспондентский счёт", func(c store.Company) string { return c.BankCorrAccount }, func(c *store.Company, v string) { c.BankCorrAccount = v }},
	{"Подписывает", func(c store.Company) string { return c.SignerName }, func(c *store.Company, v string) { c.SignerName = v }},
	{"Должность подписанта", func(c store.Company) string { return c.SignerTitle }, func(c *store.Company, v string) { c.SignerTitle = v }},
	{"Приписка про налог", func(c store.Company) string { return c.TaxNote }, func(c *store.Company, v string) { c.TaxNote = v }},
	{"Приписка в подвале", func(c store.Company) string { return c.FooterNote }, func(c *store.Company, v string) { c.FooterNote = v }},
}

// styles — оформление книги: заготовки создаются один раз на файл.
type styles struct {
	header int
	money  int
	qty    int
}

// Build собирает книгу и отдаёт её байтами.
func Build(b store.Backup) ([]byte, error) {
	f := excelize.NewFile()
	defer f.Close()

	st, err := newStyles(f)
	if err != nil {
		return nil, err
	}

	// Новая книга приходит с листом Sheet1 — переиспользуем его под первый лист.
	if err := f.SetSheetName("Sheet1", sheetOrder[0]); err != nil {
		return nil, err
	}
	for _, name := range sheetOrder[1:] {
		if _, err := f.NewSheet(name); err != nil {
			return nil, err
		}
	}

	writers := []func(*excelize.File, store.Backup, styles) error{
		writeClients, writeOrders, writeOrderItems, writeRequests,
		writePayments, writeCash, writeCatalog, writeStock, writeTasks, writeCompany,
	}
	for _, write := range writers {
		if err := write(f, b, st); err != nil {
			return nil, err
		}
	}

	var buf bytes.Buffer
	if err := f.Write(&buf); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writeClients(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetClients, clientHeader, st.header); err != nil {
		return err
	}
	for i, c := range b.Clients {
		values := []any{
			c.ID, c.Name, c.Phone, c.Email, c.Address, c.Tag, c.Note,
			boolText(c.PortalEnabled), c.CreatedAt, c.UpdatedAt,
		}
		if err := writeRow(f, SheetClients, i+2, values); err != nil {
			return err
		}
	}
	return setWidths(f, SheetClients, []float64{6, 26, 20, 24, 30, 14, 40, 12, 20, 20})
}

func writeOrders(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetOrders, orderHeader, st.header); err != nil {
		return err
	}
	for i, o := range b.Orders {
		row := i + 2
		values := []any{
			o.ID, idOrBlank(o.ClientID), o.ClientName, o.Title, o.Description,
			orderStatusNames[o.Status], rubles(o.PriceKop), rubles(o.PaidKop), o.DueDate, o.PhotoCount,
			o.CreatedAt, o.UpdatedAt,
		}
		if err := writeRow(f, SheetOrders, row, values); err != nil {
			return err
		}
		if err := styleCells(f, SheetOrders, row, st.money, 7, 8); err != nil {
			return err
		}
	}
	return setWidths(f, SheetOrders, []float64{6, 11, 26, 30, 44, 14, 14, 14, 16, 8, 20, 20})
}

// writeOrderItems — из чего сложилась цена каждого заказа. Без этого листа
// выгрузка теряла самое подробное, что есть в базе: перечень работ и материалов.
func writeOrderItems(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetOrderItems, orderItemHeader, st.header); err != nil {
		return err
	}
	for i, it := range b.OrderItems {
		row := i + 2
		values := []any{
			it.ID, it.OrderID, it.OrderTitle, kindNames[it.Kind], it.Name, it.Unit,
			units(it.QtyMilli), rubles(it.PriceKop), rubles(it.TotalKop),
			rubles(it.CostKop), rubles(it.TotalCostKop), boolText(it.WrittenOff()),
			idOrBlank(it.CatalogID), it.Sort, it.CreatedAt,
		}
		if err := writeRow(f, SheetOrderItems, row, values); err != nil {
			return err
		}
		if err := styleCells(f, SheetOrderItems, row, st.qty, 7); err != nil {
			return err
		}
		if err := styleCells(f, SheetOrderItems, row, st.money, 8, 9, 10, 11); err != nil {
			return err
		}
	}
	return setWidths(f, SheetOrderItems,
		[]float64{6, 11, 28, 12, 34, 8, 12, 14, 14, 14, 14, 16, 16, 9, 20})
}

func writeRequests(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetRequests, requestHeader, st.header); err != nil {
		return err
	}
	for i, r := range b.Requests {
		values := []any{
			r.ID, r.Name, r.Phone, r.Message, r.Source,
			requestStatusNames[r.Status], r.CreatedAt,
		}
		if err := writeRow(f, SheetRequests, i+2, values); err != nil {
			return err
		}
	}
	return setWidths(f, SheetRequests, []float64{6, 26, 20, 50, 12, 16, 20})
}

// writePayments — приходы по заказам. Это выборка из кассы, а не отдельная
// таблица: платёж и есть операция кассы со ссылкой на заказ. Лист оставлен
// потому, что оплату по заказу ищут именно так; при загрузке он пропускается,
// если в книге есть полный лист «Касса», — иначе те же деньги легли бы дважды.
func writePayments(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetPayments, paymentHeader, st.header); err != nil {
		return err
	}
	for i, p := range b.Payments {
		row := i + 2
		values := []any{p.ID, p.OrderID, p.OrderTitle, p.ClientName, rubles(p.AmountKop), p.Note, p.CreatedAt}
		if err := writeRow(f, SheetPayments, row, values); err != nil {
			return err
		}
		if err := styleCells(f, SheetPayments, row, st.money, 5); err != nil {
			return err
		}
	}
	return setWidths(f, SheetPayments, []float64{6, 11, 30, 26, 14, 30, 20})
}

// writeCash — вся касса: и приходы, и расходы, за всё время.
func writeCash(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetCash, cashHeader, st.header); err != nil {
		return err
	}
	for i, op := range b.Cash {
		row := i + 2
		values := []any{
			op.ID, directionNames[op.Direction], rubles(op.AmountKop), methodNames[op.Method],
			op.Category, idOrBlank(op.OrderID), op.OrderTitle, idOrBlank(op.ClientID),
			op.ClientName, op.Note, op.HappenedAt, op.CreatedAt,
		}
		if err := writeRow(f, SheetCash, row, values); err != nil {
			return err
		}
		if err := styleCells(f, SheetCash, row, st.money, 3); err != nil {
			return err
		}
	}
	return setWidths(f, SheetCash, []float64{6, 14, 14, 12, 22, 11, 28, 11, 26, 30, 20, 20})
}

// writeCatalog — справочник со складом: услуги, работы и материалы с остатком.
func writeCatalog(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetCatalog, catalogHeader, st.header); err != nil {
		return err
	}
	for i, it := range b.Catalog {
		row := i + 2
		// Остаток есть только у материала — у услуги пустая ячейка
		// читается честнее, чем ноль, которого никто не считал.
		stock := any("")
		if it.Kind == store.KindMaterial {
			stock = units(it.StockMilli)
		}
		values := []any{
			it.ID, kindNames[it.Kind], it.Name, it.Unit, rubles(it.PriceKop),
			rubles(it.CostKop), stock, it.Note, boolText(it.Archived),
			it.CreatedAt, it.UpdatedAt,
		}
		if err := writeRow(f, SheetCatalog, row, values); err != nil {
			return err
		}
		if err := styleCells(f, SheetCatalog, row, st.money, 5, 6); err != nil {
			return err
		}
		if it.Kind == store.KindMaterial {
			if err := styleCells(f, SheetCatalog, row, st.qty, 7); err != nil {
				return err
			}
		}
	}
	return setWidths(f, SheetCatalog, []float64{6, 12, 34, 8, 14, 14, 12, 34, 10, 20, 20})
}

// writeStock — история склада: приход положительным количеством, списание
// отрицательным. Остаток на листе «Склад» — это сумма здешних строк.
func writeStock(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetStock, stockHeader, st.header); err != nil {
		return err
	}
	for i, m := range b.StockMoves {
		row := i + 2
		values := []any{
			m.ID, m.ItemID, m.ItemName, m.Unit, units(m.QtyMilli), rubles(m.CostKop),
			idOrBlank(m.OrderID), m.OrderTitle, m.Note, m.CreatedAt,
		}
		if err := writeRow(f, SheetStock, row, values); err != nil {
			return err
		}
		if err := styleCells(f, SheetStock, row, st.qty, 5); err != nil {
			return err
		}
		if err := styleCells(f, SheetStock, row, st.money, 6); err != nil {
			return err
		}
	}
	return setWidths(f, SheetStock, []float64{6, 11, 34, 8, 12, 14, 11, 28, 30, 20})
}

func writeTasks(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetTasks, taskHeader, st.header); err != nil {
		return err
	}
	for i, t := range b.Tasks {
		values := []any{
			t.ID, t.Title, t.Note, t.DueDate, boolText(t.Done), priorityNames[t.Priority],
			idOrBlank(t.ClientID), t.ClientName, idOrBlank(t.OrderID), idOrBlank(t.ParentID),
			t.CreatedAt, t.UpdatedAt,
		}
		if err := writeRow(f, SheetTasks, i+2, values); err != nil {
			return err
		}
	}
	return setWidths(f, SheetTasks, []float64{6, 34, 40, 20, 10, 12, 11, 26, 11, 12, 20, 20})
}

func writeCompany(f *excelize.File, b store.Backup, st styles) error {
	if err := writeHeader(f, SheetCompany, companyHeader, st.header); err != nil {
		return err
	}
	for i, field := range companyFields {
		if err := writeRow(f, SheetCompany, i+2, []any{field.title, field.get(b.Company)}); err != nil {
			return err
		}
	}
	return setWidths(f, SheetCompany, []float64{30, 60})
}

// ------------------------------- Загрузка ----------------------------------

// rawValues заставляет читать значения как они хранятся, без применения
// числового формата: иначе цена 48500.5 вернулась бы как «48 501 ₽».
var rawValues = excelize.Options{RawCellValue: true}

// Parsed — то, что удалось прочитать из книги.
type Parsed struct {
	Clients    []store.Client
	Orders     []store.Order
	OrderItems []store.OrderItem
	Requests   []store.Request
	Payments   []store.Payment
	Cash       []store.CashOp
	Catalog    []store.CatalogItem
	StockMoves []store.StockMove
	Tasks      []store.Task
	Company    *store.Company
	Warnings   []string
}

// Parse читает книгу. Лишние листы игнорируются, отсутствующие — не ошибка:
// можно загрузить файл только с клиентами.
func Parse(r io.Reader) (Parsed, error) {
	f, err := excelize.OpenReader(r)
	if err != nil {
		return Parsed{}, fmt.Errorf("не удалось открыть файл: %w", err)
	}
	defer f.Close()

	var out Parsed
	sheets := map[string]bool{}
	for _, name := range f.GetSheetList() {
		sheets[name] = true
	}
	known := false
	for _, name := range sheetOrder {
		if sheets[name] {
			known = true
			break
		}
	}
	if !known {
		return Parsed{}, fmt.Errorf(
			"в книге нет ни одного нужного листа: ожидались «%s»",
			strings.Join(sheetOrder, "», «"))
	}

	// Порядок разбора не важен — важен порядок записи в базу, он в api.
	readers := []struct {
		sheet string
		read  func(*Parsed, [][]string)
	}{
		{SheetClients, readClients},
		{SheetOrders, readOrders},
		{SheetOrderItems, readOrderItems},
		{SheetRequests, readRequests},
		{SheetCash, readCash},
		{SheetCatalog, readCatalog},
		{SheetStock, readStock},
		{SheetTasks, readTasks},
		{SheetCompany, readCompany},
	}
	for _, rd := range readers {
		if !sheets[rd.sheet] {
			continue
		}
		rows, err := f.GetRows(rd.sheet, rawValues)
		if err != nil {
			return Parsed{}, err
		}
		rd.read(&out, rows)
	}

	// Платежи — выборка из кассы. Если полный лист кассы в книге есть,
	// второй раз те же приходы не читаем.
	if sheets[SheetPayments] && !sheets[SheetCash] {
		rows, err := f.GetRows(SheetPayments, rawValues)
		if err != nil {
			return Parsed{}, err
		}
		readPayments(&out, rows)
	}

	return out, nil
}

func readClients(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		name := cell(row, 1)
		if name == "" {
			out.skip(SheetClients, line, "пустое имя")
			continue
		}
		out.Clients = append(out.Clients, store.Client{
			ID:      parseID(cell(row, 0)),
			Name:    name,
			Phone:   cell(row, 2),
			Email:   cell(row, 3),
			Address: cell(row, 4),
			Tag:     cell(row, 5),
			Note:    cell(row, 6),
		})
	}
}

func readOrders(out *Parsed, rows [][]string) {
	// В новых книгах между стоимостью и сроком есть колонка «Оплачено».
	// Старые файлы без неё тоже принимаем: ищем срок по шапке.
	dueIdx := 7
	if len(rows) > 0 && headerHas(rows[0], "Оплачено") {
		dueIdx = 8
	}
	for i, row := range dataRows(rows) {
		line := i + 2
		title := cell(row, 3)
		if title == "" {
			out.skip(SheetOrders, line, "пустое название")
			continue
		}
		out.Orders = append(out.Orders, store.Order{
			ID:          parseID(cell(row, 0)),
			ClientID:    optionalID(cell(row, 1)),
			Title:       title,
			Description: cell(row, 4),
			Status:      statusCode(cell(row, 5), orderStatusNames, "new"),
			PriceKop:    parsePrice(cell(row, 6)),
			DueDate:     dateCell(row, dueIdx),
		})
	}
}

func readOrderItems(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		orderID := parseID(cell(row, 1))
		name := cell(row, 4)
		if orderID == 0 {
			out.skip(SheetOrderItems, line, "не указан заказ")
			continue
		}
		if name == "" {
			out.skip(SheetOrderItems, line, "пустое наименование")
			continue
		}
		out.OrderItems = append(out.OrderItems, store.OrderItem{
			ID:        parseID(cell(row, 0)),
			OrderID:   orderID,
			CatalogID: optionalID(cell(row, 12)),
			Kind:      codeOf(cell(row, 3), kindNames, store.KindService),
			Name:      name,
			Unit:      cell(row, 5),
			QtyMilli:  parseQty(cell(row, 6)),
			PriceKop:  parsePrice(cell(row, 7)),
			CostKop:   parsePrice(cell(row, 9)),
			Sort:      parseID(cell(row, 13)),
		})
	}
}

func readRequests(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		name := cell(row, 1)
		if name == "" {
			out.skip(SheetRequests, line, "пустое имя")
			continue
		}
		out.Requests = append(out.Requests, store.Request{
			ID:      parseID(cell(row, 0)),
			Name:    name,
			Phone:   cell(row, 2),
			Message: cell(row, 3),
			Source:  cell(row, 4),
			Status:  statusCode(cell(row, 5), requestStatusNames, "new"),
		})
	}
}

func readPayments(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		orderID := parseID(cell(row, 1))
		if orderID == 0 {
			out.skip(SheetPayments, line, "не указан заказ")
			continue
		}
		amountKop := parsePrice(cell(row, 4))
		if amountKop <= 0 {
			out.skip(SheetPayments, line, "сумма должна быть больше нуля")
			continue
		}
		out.Payments = append(out.Payments, store.Payment{
			ID:        parseID(cell(row, 0)),
			OrderID:   orderID,
			AmountKop: amountKop,
			Note:      cell(row, 5),
			CreatedAt: dateCell(row, 6),
		})
	}
}

func readCash(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		amountKop := parsePrice(cell(row, 2))
		if amountKop <= 0 {
			out.skip(SheetCash, line, "сумма должна быть больше нуля")
			continue
		}
		out.Cash = append(out.Cash, store.CashOp{
			ID:         parseID(cell(row, 0)),
			Direction:  codeOf(cell(row, 1), directionNames, store.DirectionIn),
			AmountKop:  amountKop,
			Method:     codeOf(cell(row, 3), methodNames, store.MethodCash),
			Category:   cell(row, 4),
			OrderID:    optionalID(cell(row, 5)),
			ClientID:   optionalID(cell(row, 7)),
			Note:       cell(row, 9),
			HappenedAt: dateCell(row, 10),
		})
	}
}

func readCatalog(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		name := cell(row, 2)
		if name == "" {
			out.skip(SheetCatalog, line, "пустое наименование")
			continue
		}
		// Остаток не загружаем: он считается из движений склада.
		// Приняв его как число, мы получили бы вторую правду об остатке.
		out.Catalog = append(out.Catalog, store.CatalogItem{
			ID:       parseID(cell(row, 0)),
			Kind:     codeOf(cell(row, 1), kindNames, store.KindService),
			Name:     name,
			Unit:     cell(row, 3),
			PriceKop: parsePrice(cell(row, 4)),
			CostKop:  parsePrice(cell(row, 5)),
			Note:     cell(row, 7),
			Archived: parseBool(cell(row, 8)),
		})
	}
}

func readStock(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		itemID := parseID(cell(row, 1))
		if itemID == 0 {
			out.skip(SheetStock, line, "не указана позиция склада")
			continue
		}
		qty := parseQty(cell(row, 4))
		if qty == 0 {
			out.skip(SheetStock, line, "нулевое количество")
			continue
		}
		out.StockMoves = append(out.StockMoves, store.StockMove{
			ID:        parseID(cell(row, 0)),
			ItemID:    itemID,
			OrderID:   optionalID(cell(row, 6)),
			QtyMilli:  qty,
			CostKop:   parsePrice(cell(row, 5)),
			Note:      cell(row, 8),
			CreatedAt: dateCell(row, 9),
		})
	}
}

func readTasks(out *Parsed, rows [][]string) {
	for i, row := range dataRows(rows) {
		line := i + 2
		title := cell(row, 1)
		if title == "" {
			out.skip(SheetTasks, line, "пустое название")
			continue
		}
		out.Tasks = append(out.Tasks, store.Task{
			ID:       parseID(cell(row, 0)),
			ClientID: optionalID(cell(row, 6)),
			OrderID:  optionalID(cell(row, 8)),
			ParentID: optionalID(cell(row, 9)),
			Title:    title,
			Note:     cell(row, 2),
			DueDate:  cell(row, 3),
			Done:     parseBool(cell(row, 4)),
			Priority: codeOf(cell(row, 5), priorityNames, "normal"),
		})
	}
}

func readCompany(out *Parsed, rows [][]string) {
	byTitle := map[string]string{}
	for _, row := range dataRows(rows) {
		byTitle[cell(row, 0)] = cell(row, 1)
	}
	company := store.DefaultCompany()
	filled := false
	for _, field := range companyFields {
		if v, ok := byTitle[field.title]; ok {
			field.set(&company, v)
			if v != "" {
				filled = true
			}
		}
	}
	// Пустой лист реквизитов не должен затирать заполненные в базе:
	// такая книга получается, если её собрали до заполнения реквизитов.
	if filled {
		out.Company = &company
	}
}

// skip записывает замечание о пропущенной строке.
func (p *Parsed) skip(sheet string, line int, reason string) {
	p.Warnings = append(p.Warnings,
		fmt.Sprintf("%s, строка %d: %s — пропущена", sheet, line, reason))
}

// headerHas проверяет, есть ли колонка с таким названием в шапке.
func headerHas(header []string, title string) bool {
	for _, h := range header {
		if strings.TrimSpace(h) == title {
			return true
		}
	}
	return false
}

// ------------------------------- Мелочи ------------------------------------

func dataRows(rows [][]string) [][]string {
	if len(rows) <= 1 {
		return nil
	}
	return rows[1:]
}

// cell безопасно достаёт значение: excelize обрезает хвостовые пустые ячейки.
func cell(row []string, idx int) string {
	if idx >= len(row) {
		return ""
	}
	return strings.TrimSpace(row[idx])
}

func parseID(v string) int64 {
	id, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
	if err != nil || id < 0 {
		return 0
	}
	return id
}

// optionalID — ссылка, которой может не быть: пустая ячейка даёт nil.
func optionalID(v string) *int64 {
	if id := parseID(v); id > 0 {
		return &id
	}
	return nil
}

// idOrBlank — обратное: пустая ячейка вместо нуля, чтобы в книге
// не мелькали нули там, где связи просто нет.
func idOrBlank(id *int64) any {
	if id == nil {
		return ""
	}
	return *id
}

// parsePrice отдаёт копейки. Терпит и «48 500,50», и «48500.5». Это единственное место, где деньги
// проходят через дробное число: сразу за разбором округляем до копейки.
func parsePrice(v string) int64 {
	clean := strings.NewReplacer(" ", "", " ", "", ",", ".", "₽", "").Replace(v)
	price, err := strconv.ParseFloat(clean, 64)
	if err != nil || price < 0 {
		return 0
	}
	return int64(math.Round(price * 100))
}

// parseQty отдаёт тысячные доли единицы. В отличие от денег знак тут значим:
// списание со склада записано отрицательным количеством.
func parseQty(v string) int64 {
	clean := strings.NewReplacer(" ", "", " ", "", ",", ".").Replace(v)
	qty, err := strconv.ParseFloat(clean, 64)
	if err != nil {
		return 0
	}
	return int64(math.Round(qty * 1000))
}

// Границы, в которых число считаем датой Excel: с 2000-го по 2100-й год.
// Уже вне их — это просто число, и трогать его нельзя.
const (
	minDateSerial = 36526 // 2000-01-01
	maxDateSerial = 73051 // 2100-01-01
)

// dateCell читает ячейку с датой. Excel хранит дату числом — порядковым
// номером дня, — и стоит человеку перебить её в книге руками, как вместо
// «2026-08-10» приезжает «46244». Такое число узнаём и разворачиваем обратно
// в дату: в базе даты сравниваются как строки, и число ломало бы и отбор
// за период, и подсветку просрочки.
func dateCell(row []string, idx int) string {
	v := cell(row, idx)
	serial, err := strconv.ParseFloat(v, 64)
	if err != nil || serial < minDateSerial || serial > maxDateSerial {
		return v
	}
	moment, err := excelize.ExcelDateToTime(serial, false)
	if err != nil {
		return v
	}
	// Голая дата остаётся голой: дописанная полночь только мешала бы читать.
	if moment.Hour() == 0 && moment.Minute() == 0 && moment.Second() == 0 {
		return moment.Format("2006-01-02")
	}
	return moment.UTC().Format(time.RFC3339)
}

// rubles — копейки в рубли для записи в книгу: там суммы читает человек.
func rubles(kop int64) float64 { return float64(kop) / 100 }

// units — тысячные доли в единицы: 1500 → 1.5 метра.
func units(milli int64) float64 { return float64(milli) / 1000 }

// statusCode переводит человекочитаемый статус обратно в код.
func statusCode(v string, names map[string]string, fallback string) string {
	return codeOf(v, names, fallback)
}

// codeOf ищет код по русскому названию из словаря. Если в ячейке уже код —
// принимаем как есть. Пустая ячейка и незнакомое слово дают значение
// по умолчанию: терять из-за них строку незачем.
func codeOf(v string, names map[string]string, fallback string) string {
	v = strings.TrimSpace(v)
	if v == "" {
		return fallback
	}
	if _, ok := names[v]; ok {
		return v
	}
	for code, label := range names {
		if strings.EqualFold(label, v) {
			return code
		}
	}
	return fallback
}

func boolText(v bool) string {
	if v {
		return "да"
	}
	return "нет"
}

// parseBool принимает и то, что пишем сами, и то, что человек напишет руками.
func parseBool(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "да", "yes", "true", "1", "+":
		return true
	}
	return false
}

func writeHeader(f *excelize.File, sheet string, titles []string, style int) error {
	for i, title := range titles {
		cellName, err := excelize.CoordinatesToCellName(i+1, 1)
		if err != nil {
			return err
		}
		if err := f.SetCellStr(sheet, cellName, title); err != nil {
			return err
		}
		if err := f.SetCellStyle(sheet, cellName, cellName, style); err != nil {
			return err
		}
	}
	// Шапка остаётся на месте при прокрутке — с длинной базой это заметно удобнее.
	return f.SetPanes(sheet, &excelize.Panes{
		Freeze:      true,
		Split:       false,
		XSplit:      0,
		YSplit:      1,
		TopLeftCell: "A2",
		ActivePane:  "bottomLeft",
	})
}

func writeRow(f *excelize.File, sheet string, row int, values []any) error {
	for i, v := range values {
		cellName, err := excelize.CoordinatesToCellName(i+1, row)
		if err != nil {
			return err
		}
		if err := f.SetCellValue(sheet, cellName, v); err != nil {
			return err
		}
	}
	return nil
}

// styleCells вешает оформление на перечисленные колонки одной строки.
func styleCells(f *excelize.File, sheet string, row, style int, cols ...int) error {
	for _, col := range cols {
		cellName, err := excelize.CoordinatesToCellName(col, row)
		if err != nil {
			return err
		}
		if err := f.SetCellStyle(sheet, cellName, cellName, style); err != nil {
			return err
		}
	}
	return nil
}

func setWidths(f *excelize.File, sheet string, widths []float64) error {
	for i, w := range widths {
		col, err := excelize.ColumnNumberToName(i + 1)
		if err != nil {
			return err
		}
		if err := f.SetColWidth(sheet, col, col, w); err != nil {
			return err
		}
	}
	return nil
}

func newStyles(f *excelize.File) (styles, error) {
	header, err := f.NewStyle(&excelize.Style{
		Font: &excelize.Font{Bold: true, Color: "FFFFFF"},
		Fill: excelize.Fill{Type: "pattern", Pattern: 1, Color: []string{"1F2A44"}},
		Alignment: &excelize.Alignment{
			Vertical: "center", WrapText: true,
		},
	})
	if err != nil {
		return styles{}, err
	}
	// Разряды разделяются запятой — это код формата, а не то, что увидит
	// человек: Excel подставляет разделитель своей локали, и в русской
	// получается «48 500,50 ₽». Пробел в самом коде формата разделителем
	// не работает: он литерал, и 48500.50 выходило «485 00.50 ₽».
	money, err := f.NewStyle(&excelize.Style{CustomNumFmt: strPtr(`#,##0.00" ₽"`)})
	if err != nil {
		return styles{}, err
	}
	// Дробная часть количества показывается, только если она есть:
	// «5», а не «5,000», но «0,333» не округляется до «0».
	qty, err := f.NewStyle(&excelize.Style{CustomNumFmt: strPtr(`#,##0.###`)})
	if err != nil {
		return styles{}, err
	}
	return styles{header: header, money: money, qty: qty}, nil
}

func strPtr(v string) *string { return &v }
