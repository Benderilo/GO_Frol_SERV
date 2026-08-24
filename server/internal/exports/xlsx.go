// Package exports собирает базу в книгу Excel и читает её обратно.
package exports

import (
	"bytes"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/xuri/excelize/v2"
)

// Названия листов. По ним же книга читается при загрузке,
// поэтому менять их нельзя, не сломав совместимость со старыми файлами.
const (
	SheetClients  = "Клиенты"
	SheetOrders   = "Заказы"
	SheetRequests = "Заявки"
)

var (
	clientHeader = []string{
		"id", "Имя", "Телефон", "E-mail", "Адрес", "Метка", "Заметка",
		"Кабинет", "Создан", "Обновлён",
	}
	orderHeader = []string{
		"id", "id клиента", "Клиент", "Название", "Описание", "Статус",
		"Стоимость", "Срок", "Фото", "Создан", "Обновлён",
	}
	requestHeader = []string{
		"id", "Имя", "Телефон", "Сообщение", "Источник", "Статус", "Создана",
	}
)

// Понятные названия статусов в выгрузке и обратный разбор при загрузке.
var (
	orderStatusNames = map[string]string{
		"new": "Новый", "in_progress": "В работе", "done": "Завершён", "canceled": "Отменён",
	}
	requestStatusNames = map[string]string{
		"new": "Новая", "in_progress": "В работе", "done": "Обработана", "spam": "Спам",
	}
)

// Build собирает книгу и отдаёт её байтами.
func Build(clients []store.Client, orders []store.Order, requests []store.Request) ([]byte, error) {
	f := excelize.NewFile()
	defer f.Close()

	header, err := headerStyle(f)
	if err != nil {
		return nil, err
	}
	money, err := moneyStyle(f)
	if err != nil {
		return nil, err
	}

	// Новая книга приходит с листом Sheet1 — переиспользуем его под первый лист.
	if err := f.SetSheetName("Sheet1", SheetClients); err != nil {
		return nil, err
	}
	for _, name := range []string{SheetOrders, SheetRequests} {
		if _, err := f.NewSheet(name); err != nil {
			return nil, err
		}
	}

	if err := writeClients(f, clients, header); err != nil {
		return nil, err
	}
	if err := writeOrders(f, orders, header, money); err != nil {
		return nil, err
	}
	if err := writeRequests(f, requests, header); err != nil {
		return nil, err
	}

	var buf bytes.Buffer
	if err := f.Write(&buf); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writeClients(f *excelize.File, clients []store.Client, header int) error {
	if err := writeHeader(f, SheetClients, clientHeader, header); err != nil {
		return err
	}
	for i, c := range clients {
		row := i + 2
		values := []any{
			c.ID, c.Name, c.Phone, c.Email, c.Address, c.Tag, c.Note,
			boolText(c.PortalEnabled), c.CreatedAt, c.UpdatedAt,
		}
		if err := writeRow(f, SheetClients, row, values); err != nil {
			return err
		}
	}
	return setWidths(f, SheetClients, []float64{6, 26, 20, 24, 30, 14, 40, 12, 20, 20})
}

func writeOrders(f *excelize.File, orders []store.Order, header, money int) error {
	if err := writeHeader(f, SheetOrders, orderHeader, header); err != nil {
		return err
	}
	for i, o := range orders {
		row := i + 2
		clientID := any("")
		if o.ClientID != nil {
			clientID = *o.ClientID
		}
		values := []any{
			o.ID, clientID, o.ClientName, o.Title, o.Description,
			orderStatusNames[o.Status], o.Price, o.DueDate, o.PhotoCount,
			o.CreatedAt, o.UpdatedAt,
		}
		if err := writeRow(f, SheetOrders, row, values); err != nil {
			return err
		}
		cell, _ := excelize.CoordinatesToCellName(7, row)
		if err := f.SetCellStyle(SheetOrders, cell, cell, money); err != nil {
			return err
		}
	}
	return setWidths(f, SheetOrders, []float64{6, 11, 26, 30, 44, 14, 14, 16, 8, 20, 20})
}

func writeRequests(f *excelize.File, requests []store.Request, header int) error {
	if err := writeHeader(f, SheetRequests, requestHeader, header); err != nil {
		return err
	}
	for i, r := range requests {
		row := i + 2
		values := []any{
			r.ID, r.Name, r.Phone, r.Message, r.Source,
			requestStatusNames[r.Status], r.CreatedAt,
		}
		if err := writeRow(f, SheetRequests, row, values); err != nil {
			return err
		}
	}
	return setWidths(f, SheetRequests, []float64{6, 26, 20, 50, 12, 16, 20})
}

// ------------------------------- Загрузка ----------------------------------

// rawValues заставляет читать значения как они хранятся, без применения
// числового формата: иначе цена 48500.5 вернулась бы как «48 501 ₽».
var rawValues = excelize.Options{RawCellValue: true}

// Parsed — то, что удалось прочитать из книги.
type Parsed struct {
	Clients  []store.Client
	Orders   []store.Order
	Requests []store.Request
	Warnings []string
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
	if !sheets[SheetClients] && !sheets[SheetOrders] && !sheets[SheetRequests] {
		return Parsed{}, fmt.Errorf(
			"в книге нет ни одного нужного листа: ожидались «%s», «%s», «%s»",
			SheetClients, SheetOrders, SheetRequests)
	}

	if sheets[SheetClients] {
		rows, err := f.GetRows(SheetClients, rawValues)
		if err != nil {
			return Parsed{}, err
		}
		for i, row := range dataRows(rows) {
			line := i + 2
			name := cell(row, 1)
			if name == "" {
				out.Warnings = append(out.Warnings,
					fmt.Sprintf("%s, строка %d: пустое имя — пропущена", SheetClients, line))
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

	if sheets[SheetOrders] {
		rows, err := f.GetRows(SheetOrders, rawValues)
		if err != nil {
			return Parsed{}, err
		}
		for i, row := range dataRows(rows) {
			line := i + 2
			title := cell(row, 3)
			if title == "" {
				out.Warnings = append(out.Warnings,
					fmt.Sprintf("%s, строка %d: пустое название — пропущена", SheetOrders, line))
				continue
			}
			order := store.Order{
				ID:          parseID(cell(row, 0)),
				Title:       title,
				Description: cell(row, 4),
				Status:      statusCode(cell(row, 5), orderStatusNames, "new"),
				Price:       parsePrice(cell(row, 6)),
				DueDate:     cell(row, 7),
			}
			if id := parseID(cell(row, 1)); id > 0 {
				order.ClientID = &id
			}
			out.Orders = append(out.Orders, order)
		}
	}

	if sheets[SheetRequests] {
		rows, err := f.GetRows(SheetRequests, rawValues)
		if err != nil {
			return Parsed{}, err
		}
		for i, row := range dataRows(rows) {
			line := i + 2
			name := cell(row, 1)
			if name == "" {
				out.Warnings = append(out.Warnings,
					fmt.Sprintf("%s, строка %d: пустое имя — пропущена", SheetRequests, line))
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

	return out, nil
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

// parsePrice терпит и «48 500,50», и «48500.5».
func parsePrice(v string) float64 {
	clean := strings.NewReplacer(" ", "", " ", "", ",", ".", "₽", "").Replace(v)
	price, err := strconv.ParseFloat(clean, 64)
	if err != nil || price < 0 {
		return 0
	}
	return price
}

// statusCode переводит человекочитаемый статус обратно в код.
// Если в ячейке уже код — принимаем как есть.
func statusCode(v string, names map[string]string, fallback string) string {
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

func headerStyle(f *excelize.File) (int, error) {
	return f.NewStyle(&excelize.Style{
		Font: &excelize.Font{Bold: true, Color: "FFFFFF"},
		Fill: excelize.Fill{Type: "pattern", Pattern: 1, Color: []string{"1F2A44"}},
		Alignment: &excelize.Alignment{
			Vertical: "center", WrapText: true,
		},
	})
}

func moneyStyle(f *excelize.File) (int, error) {
	return f.NewStyle(&excelize.Style{CustomNumFmt: strPtr(`# ##0.00" ₽"`)})
}

func strPtr(v string) *string { return &v }
