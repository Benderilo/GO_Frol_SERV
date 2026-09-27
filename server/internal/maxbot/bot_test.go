package maxbot

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

type sent struct {
	userID int64
	msg    NewMessage
}

type fakeMax struct {
	mu       sync.Mutex
	sent     []sent
	answers  []string
	uploaded []string
}

func (f *fakeMax) Send(_ context.Context, userID int64, msg NewMessage) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, sent{userID, msg})
	return nil
}

func (f *fakeMax) Answer(_ context.Context, id string, _ *NewMessage) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.answers = append(f.answers, id)
	return nil
}

func (f *fakeMax) last(t *testing.T) NewMessage {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.sent) == 0 {
		t.Fatal("бот ничего не отправил")
	}
	return f.sent[len(f.sent)-1].msg
}

// buttons — все кнопки последнего сообщения: тип и payload через «|».
func buttons(m NewMessage) []string {
	var out []string
	for _, a := range m.Attachments {
		for _, row := range a.Payload.(map[string]any)["buttons"].([][]Button) {
			for _, b := range row {
				out = append(out, b.Type+"|"+b.Payload)
			}
		}
	}
	return out
}

func has(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}

type fakeBrain struct {
	reply aiReply
	err   error
	calls int
}

func (f *fakeBrain) Think(context.Context, string, []store.ChatTurn) (aiReply, error) {
	f.calls++
	return f.reply, f.err
}

func newTestBot(t *testing.T, ai brain) (*Bot, *fakeMax, *store.Store) {
	t.Helper()
	st, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	out := &fakeMax{}
	return newBot(st, out, ai), out, st
}

const uid = 1001

var ivan = User{UserID: uid, FirstName: "Иван"}

func started() Update {
	return Update{UpdateType: "bot_started", User: &ivan}
}

var seq int

func text(s string) Update {
	seq++
	return Update{UpdateType: "message_created", Message: &Message{
		Sender:    &ivan,
		Recipient: Recipient{ChatType: "dialog"},
		Body:      MessageBody{Mid: "mid" + string(rune('a'+seq)) + s, Text: s},
	}}
}

func press(payload string) Update {
	seq++
	return Update{UpdateType: "message_callback", Callback: &Callback{
		CallbackID: "cb" + string(rune('a'+seq)) + payload, Payload: payload, User: ivan,
	}}
}

func contact(vcf string) Update {
	payload, _ := json.Marshal(map[string]any{"vcf_info": vcf})
	seq++
	return Update{UpdateType: "message_created", Message: &Message{
		Sender:    &ivan,
		Recipient: Recipient{ChatType: "dialog"},
		Body: MessageBody{Mid: "c" + string(rune('a'+seq)), Attachments: []Attachment{
			{Type: "contact", Payload: payload},
		}},
	}}
}

func TestOrderByButtons(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	ctx := context.Background()

	bot.Handle(ctx, started())
	if m := out.last(t); !strings.Contains(m.Text, "Иван") || !has(buttons(m), "callback|"+cbOrder) {
		t.Fatalf("приветствие: %q %v", m.Text, buttons(m))
	}

	bot.Handle(ctx, press(cbOrder))
	if !has(buttons(out.last(t)), "request_contact|") {
		t.Fatalf("первым просим контакт: %v", buttons(out.last(t)))
	}

	bot.Handle(ctx, contact("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Иван Петров\r\nTEL;TYPE=cell:79001234567\r\nEND:VCARD"))
	if !has(buttons(out.last(t)), "request_geo_location|") {
		t.Fatalf("после контакта — адрес: %q", out.last(t).Text)
	}

	bot.Handle(ctx, text("ул. Ленина, 5, кв. 12"))
	if !has(buttons(out.last(t)), "callback|p:0") {
		t.Fatalf("после адреса — что случилось: %q", out.last(t).Text)
	}

	bot.Handle(ctx, press("p:0"))
	m := out.last(t)
	if !has(buttons(m), "callback|"+cbSend) || !strings.Contains(m.Text, "Нет света") {
		t.Fatalf("подтверждение: %q %v", m.Text, buttons(m))
	}

	bot.Handle(ctx, press(cbSend))
	if m := out.last(t); !strings.Contains(m.Text, "принята") {
		t.Fatalf("итог: %q", m.Text)
	}

	reqs, err := st.ListRequests(ctx, "", 10, 0)
	if err != nil || len(reqs) != 1 {
		t.Fatalf("заявок %d, %v", len(reqs), err)
	}
	r := reqs[0]
	if r.Source != "max" || r.Phone != "+7 900 123-45-67" || r.Name != "Иван Петров" ||
		r.Address != "ул. Ленина, 5, кв. 12" || r.Message != "Нет света" || r.MaxUserID != uid {
		t.Fatalf("заявка: %+v", r)
	}
	clients, _ := st.ListClients(ctx, "", 10, 0)
	if len(clients) != 1 || clients[0].Address != "ул. Ленина, 5, кв. 12" {
		t.Fatalf("клиент в CRM: %+v", clients)
	}
	orders, _ := st.ListOrders(ctx, "", 10, 0)
	if len(orders) != 1 || !strings.Contains(orders[0].Description, "Адрес: ул. Ленина") {
		t.Fatalf("заказ: %+v", orders)
	}

	// Вторая заявка: телефон и адрес помнятся, спрашиваем только суть.
	bot.Handle(ctx, press(cbOrder))
	if !has(buttons(out.last(t)), "callback|p:0") {
		t.Fatalf("повторная заявка начинается с описания: %q", out.last(t).Text)
	}
}

func TestTextFirstWithoutAI(t *testing.T) {
	bot, out, _ := newTestBot(t, nil)
	ctx := context.Background()

	bot.Handle(ctx, text("Выбило автомат и не включается, пахнет гарью"))
	if !has(buttons(out.last(t)), "request_contact|") {
		t.Fatalf("рассказ с порога начинает заявку: %q", out.last(t).Text)
	}
	bot.Handle(ctx, text("мой номер 8 (900) 765-43-21"))
	if !has(buttons(out.last(t)), "request_geo_location|") {
		t.Fatalf("номер текстом принят: %q", out.last(t).Text)
	}
	bot.Handle(ctx, text("Балаково, ул. Трнавская 10"))
	m := out.last(t)
	if !has(buttons(m), "callback|"+cbSend) || !strings.Contains(m.Text, "гарью") || !strings.Contains(m.Text, "+7 900 765-43-21") {
		t.Fatalf("описание уже есть — сразу проверка: %q", m.Text)
	}
}

func TestCallMeSkipsAddress(t *testing.T) {
	bot, out, _ := newTestBot(t, nil)
	ctx := context.Background()
	bot.Handle(ctx, press(cbCallMe))
	bot.Handle(ctx, text("+7 900 111 22 33"))
	if m := out.last(t); !has(buttons(m), "callback|"+cbSend) {
		t.Fatalf("для звонка хватает номера: %q", m.Text)
	}
}

func TestLocation(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	ctx := context.Background()
	bot.Handle(ctx, press(cbOrder))
	bot.Handle(ctx, text("89001234567"))
	seq++
	bot.Handle(ctx, Update{UpdateType: "message_created", Message: &Message{
		Sender: &ivan, Recipient: Recipient{ChatType: "dialog"},
		Body: MessageBody{Mid: "geo", Attachments: []Attachment{{Type: "location", Latitude: 52.02, Longitude: 47.78}}},
	}})
	d, _ := st.MaxDialog(ctx, uid)
	if !strings.Contains(d.Address, "52.020000, 47.780000") || !has(buttons(out.last(t)), "callback|p:0") {
		t.Fatalf("геопозиция: %q", d.Address)
	}
}

func TestWrongPhoneAsksAgain(t *testing.T) {
	bot, out, _ := newTestBot(t, nil)
	ctx := context.Background()
	bot.Handle(ctx, press(cbOrder))
	bot.Handle(ctx, text("не скажу"))
	if m := out.last(t); !strings.Contains(m.Text, "Не похоже на номер") || !has(buttons(m), "request_contact|") {
		t.Fatalf("%q", m.Text)
	}
}

func TestAIFillsOrder(t *testing.T) {
	ai := &fakeBrain{reply: aiReply{
		Reply:      "Понял, мастер приедет посмотреть розетку.",
		Phone:      "+79001112233",
		Address:    "ул. Гагарина, 3",
		Problem:    "Не работает розетка на кухне",
		WantsOrder: true,
	}}
	bot, out, st := newTestBot(t, ai)
	ctx := context.Background()

	bot.Handle(ctx, text("Здравствуйте, на кухне розетка не работает, Гагарина 3, звоните 89001112233"))
	m := out.last(t)
	if !has(buttons(m), "callback|"+cbSend) || !strings.Contains(m.Text, "Гагарина") {
		t.Fatalf("всё известно — проверка: %q %v", m.Text, buttons(m))
	}
	d, _ := st.MaxDialog(ctx, uid)
	if len(d.History) != 2 || d.History[1].Role != "assistant" {
		t.Fatalf("история разговора: %+v", d.History)
	}
}

func TestAIChatsThenButtons(t *testing.T) {
	ai := &fakeBrain{reply: aiReply{Reply: "Погода отличная! А электрика у вас в порядке?"}}
	bot, out, _ := newTestBot(t, ai)
	bot.Handle(context.Background(), text("какая сегодня погода?"))
	m := out.last(t)
	if m.Text != ai.reply.Reply || !has(buttons(m), "callback|"+cbOrder) {
		t.Fatalf("болтовня с кнопкой заявки: %q %v", m.Text, buttons(m))
	}
}

func TestAIFailureFallsBackToButtons(t *testing.T) {
	ai := &fakeBrain{err: errors.New("нет связи")}
	bot, out, _ := newTestBot(t, ai)
	ctx := context.Background()

	bot.Handle(ctx, text("привет"))
	if m := out.last(t); !has(buttons(m), "callback|"+cbOrder) {
		t.Fatalf("без ИИ — меню: %q", m.Text)
	}
	bot.Handle(ctx, text("ещё раз"))
	if ai.calls != 1 {
		t.Fatalf("после сбоя ИИ не дёргаем какое-то время, вызовов: %d", ai.calls)
	}
}

func TestDuplicateCallbackIgnored(t *testing.T) {
	bot, out, _ := newTestBot(t, nil)
	u := press(cbPrices)
	bot.Handle(context.Background(), u)
	bot.Handle(context.Background(), u)
	if len(out.sent) != 1 {
		t.Fatalf("повтор доставки обработан дважды: %d", len(out.sent))
	}
}

func TestNotifyStatus(t *testing.T) {
	bot, out, _ := newTestBot(t, nil)
	ctx := context.Background()
	bot.NotifyStatus(ctx, store.Request{ID: 7, Source: "site", Status: "in_progress"})
	bot.NotifyStatus(ctx, store.Request{ID: 7, Source: "max", MaxUserID: uid, Status: "spam"})
	if len(out.sent) != 0 {
		t.Fatal("сайт и спам не уведомляем")
	}
	bot.NotifyStatus(ctx, store.Request{ID: 7, Source: "max", MaxUserID: uid, Phone: "+7 900", Status: "in_progress"})
	if m := out.last(t); !strings.Contains(m.Text, "№7 в работе") || out.sent[0].userID != uid {
		t.Fatalf("%q", m.Text)
	}
}

func TestWebhookSecret(t *testing.T) {
	bot, _, _ := newTestBot(t, nil)
	s := &Service{Bot: bot, secret: webhookSecret([]byte("x"))}

	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, WebhookPath, strings.NewReader(`{}`)))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("без секрета: %d", rec.Code)
	}

	req := httptest.NewRequest(http.MethodPost, WebhookPath, strings.NewReader(`{"update_type":"x"}`))
	req.Header.Set("X-Max-Bot-Api-Secret", s.secret)
	rec = httptest.NewRecorder()
	s.ServeHTTP(rec, req)
	s.pending.Wait()
	if rec.Code != http.StatusOK {
		t.Fatalf("с секретом: %d", rec.Code)
	}
}

func TestNormalizePhone(t *testing.T) {
	cases := map[string]string{
		"+7 (900) 123-45-67": "+7 900 123-45-67",
		"89001234567":        "+7 900 123-45-67",
		"79001234567":        "+7 900 123-45-67",
		"9001234567":         "+7 900 123-45-67",
		"звоните 8 845 355-12-34 вечером":            "+7 845 355-12-34",
		"Телефон 89271234567, адрес Ленина 20, кв 5": "+7 927 123-45-67",
		"дом 20 кв 5":  "",
		"123":          "",
		"ул. Ленина 5": "",
		"":             "",
	}
	for in, want := range cases {
		if got := normalizePhone(in); got != want {
			t.Errorf("%q → %q, ждали %q", in, got, want)
		}
	}
}

func TestParseContact(t *testing.T) {
	raw := []byte(`{"vcf_info":"BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Иван\r\nTEL;TYPE=cell:+79005554433\r\nEND:VCARD","max_info":{"user_id":1,"first_name":"Ваня"}}`)
	phone, name := parseContact(raw)
	if phone != "+7 900 555-44-33" || name != "Иван" {
		t.Fatalf("%q %q", phone, name)
	}
	phone, name = parseContact([]byte(`{"vcf_phone":"89005554433","max_info":{"user_id":1,"first_name":"Ваня"}}`))
	if phone != "+7 900 555-44-33" || name != "Ваня" {
		t.Fatalf("запасные поля: %q %q", phone, name)
	}
}

func TestParseReply(t *testing.T) {
	r, err := parseReply(`{"reply":"Здравствуйте","phone":"","address":"","problem":"","wants_order":false}`)
	if err != nil || r.Reply != "Здравствуйте" {
		t.Fatal(r, err)
	}
	r, err = parseReply("просто текст")
	if err != nil || r.Reply != "просто текст" {
		t.Fatal(r, err)
	}
	r, _ = parseReply(`{"reply":"Отключите автомат.; phone: ; address: ; wants_order: true","phone":""}`)
	if r.Reply != "Отключите автомат." {
		t.Fatalf("хвост полей в тексте: %q", r.Reply)
	}
	if _, err := parseReply("  "); err == nil {
		t.Fatal("пустой ответ — ошибка")
	}
}

func TestAddressChoiceAndOwnProblem(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	ctx := context.Background()
	bot.Handle(ctx, press(cbOrder))
	bot.Handle(ctx, text("89001234567"))

	// Адрес: выбор из двух — геопозиция или вручную.
	m := out.last(t)
	if !has(buttons(m), "request_geo_location|") || !has(buttons(m), "callback|"+cbAddrManual) {
		t.Fatalf("выбор способа адреса: %v", buttons(m))
	}
	bot.Handle(ctx, press(cbAddrManual))
	m = out.last(t)
	if !strings.Contains(m.Text, "Напишите адрес") || has(buttons(m), "request_geo_location|") {
		t.Fatalf("после «вручную» геопозицию второй раз не предлагаем: %q %v", m.Text, buttons(m))
	}
	bot.Handle(ctx, text("Балаково, ул. Мира 7, кв 3"))

	// Что случилось: кнопки сантехники и явное «своими словами».
	m = out.last(t)
	if !has(buttons(m), "callback|"+cbProblemOwn) || !strings.Contains(m.Text, "Опишу своими словами") {
		t.Fatalf("нет кнопки своего описания: %q", m.Text)
	}
	plumbing := false
	for i, c := range problemChoices {
		if strings.Contains(c, "Канализация") && has(buttons(m), fmt.Sprintf("callback|p:%d", i)) {
			plumbing = true
		}
	}
	if !plumbing {
		t.Fatalf("нет кнопки канализации: %v", buttons(m))
	}
	bot.Handle(ctx, press(cbProblemOwn))
	bot.Handle(ctx, text("Под ванной капает из-под сифона, вода на полу"))
	if m := out.last(t); !has(buttons(m), "callback|"+cbSend) || !strings.Contains(m.Text, "сифона") {
		t.Fatalf("своё описание не принято: %q", m.Text)
	}
	d, _ := st.MaxDialog(ctx, uid)
	if d.Address != "Балаково, ул. Мира 7, кв 3" {
		t.Fatalf("адрес: %q", d.Address)
	}
}
