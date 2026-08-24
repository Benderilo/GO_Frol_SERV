package exports

import (
	"bytes"
	"testing"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Книга уходит пользователю и возвращается обратно — важно, чтобы данные
// пережили этот круг без потерь: кириллица, дробная цена, статусы, ссылки.
func TestBuildThenParse(t *testing.T) {
	clientID := int64(7)
	clients := []store.Client{{
		ID: 7, Name: "Иван Петров", Phone: "+7 900 111-22-33",
		Email: "ivan@example.ru", Address: "Москва, Ленина 5",
		Tag: "постоянный", Note: "звонить после 18:00", PortalEnabled: true,
	}}
	orders := []store.Order{{
		ID: 3, ClientID: &clientID, ClientName: "Иван Петров",
		Title: "Замена щита", Description: "Щит на 24 модуля, УЗО",
		Status: "in_progress", Price: 48500.5, DueDate: "до 1 сентября",
	}}
	requests := []store.Request{{
		ID: 11, Name: "Пётр", Phone: "+79005554433",
		Message: "Нужна проводка", Source: "site", Status: "new",
	}}

	data, err := Build(clients, orders, requests)
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
	if o.Price != 48500.5 {
		t.Errorf("цена: получена %v, ожидалась 48500.5", o.Price)
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
}

// Строка без id — это новая запись: id должен остаться нулевым,
// иначе загрузка перезапишет чужую строку.
func TestParseRowWithoutID(t *testing.T) {
	data, err := Build(
		[]store.Client{{Name: "Без идентификатора", Phone: "+70000000000"}},
		nil, nil,
	)
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
	data, err := Build([]store.Client{{ID: 1, Name: ""}}, nil, nil)
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

// Цену человек может поправить руками — принимаем и запятую, и пробелы.
func TestParsePrice(t *testing.T) {
	cases := map[string]float64{
		"48500.5":   48500.5,
		"48 500,50": 48500.5,
		"48500 ₽":   48500,
		"":          0,
		"мусор":     0,
		"-100":      0,
	}
	for input, want := range cases {
		if got := parsePrice(input); got != want {
			t.Errorf("parsePrice(%q) = %v, ожидалось %v", input, got, want)
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
