package maxbot

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// fakeRecon запоминает, что просили, и возвращает готовый «акт сверки».
type fakeRecon struct {
	calls            int
	clientID, userID int64
	from, to         string
	st               *store.Store
}

func (f *fakeRecon) make(ctx context.Context, clientID, maxUserID int64, from, to string) (store.Document, store.DocumentSend, []byte, error) {
	f.calls++
	f.clientID, f.userID, f.from, f.to = clientID, maxUserID, from, to
	doc := store.Document{ID: 1, Kind: store.DocReconciliation, Number: 3, PeriodFrom: from, PeriodTo: to}
	return doc, store.DocumentSend{ID: 9, DocumentID: 1, MaxUserID: maxUserID}, []byte("%PDF-"), nil
}

// ownContact — контакт, которым человек поделился кнопкой: max_info — он сам.
func ownContact(phone string, owner int64) Update {
	payload, _ := json.Marshal(map[string]any{
		"vcf_info": "BEGIN:VCARD\r\nTEL:" + phone + "\r\nEND:VCARD",
		"max_info": map[string]any{"user_id": owner, "first_name": "Иван"},
	})
	seq++
	return Update{UpdateType: "message_created", Message: &Message{
		Sender: &ivan, Recipient: Recipient{ChatType: "dialog"},
		Body: MessageBody{Mid: "contact" + itoa(int64(seq)), Attachments: []Attachment{{Type: "contact", Payload: payload}}},
	}}
}

func reconBot(t *testing.T) (*Bot, *fakeMax, *store.Store, *fakeRecon) {
	bot, out, st := newTestBot(t, nil)
	r := &fakeRecon{st: st}
	bot.SetReconciler(r.make)
	return bot, out, st, r
}

func TestReconNeedsOwnContact(t *testing.T) {
	bot, out, st, rec := reconBot(t)
	ctx := context.Background()
	client, _ := st.CreateClient(ctx, store.Client{Name: "Иван", Phone: "+7 900 123-45-67"})

	// Номер, набранный текстом при заявке, для сверки не годится.
	bot.Handle(ctx, press(cbOrder))
	bot.Handle(ctx, text("89001234567"))
	bot.Handle(ctx, text("сделайте акт сверки"))
	if m := out.last(t); !has(buttons(m), "request_contact|") || !strings.Contains(m.Text, "убедимся, что это вы") {
		t.Fatalf("без подтверждённого номера — просьба поделиться контактом: %q", m.Text)
	}

	// Чужая карточка контакта не подходит.
	bot.Handle(ctx, ownContact("+79001234567", 555))
	if m := out.last(t); !strings.Contains(m.Text, "не ваш контакт") {
		t.Fatalf("чужой контакт принят: %q", m.Text)
	}

	// Свой контакт — выбор периода, затем акт сверки файлом.
	bot.Handle(ctx, ownContact("+79001234567", uid))
	if m := out.last(t); !has(buttons(m), "callback|recon:year") {
		t.Fatalf("после своего контакта — выбор периода: %q", m.Text)
	}
	bot.now = func() time.Time { return time.Date(2026, 9, 27, 12, 0, 0, 0, localZone) }
	bot.Handle(ctx, press("recon:year"))
	if rec.calls != 1 || rec.clientID != client.ID || rec.userID != uid || rec.from != "2026-01-01" || rec.to != "2026-09-27" {
		t.Fatalf("сборка акта: %+v", rec)
	}
	m := out.last(t)
	if !strings.Contains(m.Text, "Акт сверки № 3 за 01.01.2026 – 27.09.2026") || m.Attachments[0].Type != "file" {
		t.Fatalf("акт сверки клиенту: %q", m.Text)
	}
}

func TestReconUnknownPhone(t *testing.T) {
	bot, out, _, rec := reconBot(t)
	ctx := context.Background()
	bot.Handle(ctx, press(cbRecon))
	bot.Handle(ctx, ownContact("+79009998877", uid))
	bot.Handle(ctx, press("recon:all"))
	if rec.calls != 0 || !strings.Contains(out.last(t).Text, "Не нашёл заказов") {
		t.Fatalf("акт сверки незнакомому номеру: %d %q", rec.calls, out.last(t).Text)
	}
}

func TestReconWords(t *testing.T) {
	for s, want := range map[string]bool{
		"Пришлите акт сверки":         true,
		"нужна сверка за год":         true,
		"сколько я вам должен?":       true,
		"какая у меня задолженность":  true,
		"Олег, у меня искрит розетка": false,
		"поверка счётчика":            false,
	} {
		if got := reconRe.MatchString(s); got != want {
			t.Errorf("%q: %v, ждали %v", s, got, want)
		}
	}
}

func TestReconPeriods(t *testing.T) {
	now := time.Date(2026, 9, 27, 12, 0, 0, 0, localZone)
	for p, want := range map[string][2]string{
		"year": {"2026-01-01", "2026-09-27"},
		"12m":  {"2025-09-28", "2026-09-27"},
		"all":  {"2000-01-01", "2026-09-27"},
	} {
		if f, to := reconPeriod(p, now); f != want[0] || to != want[1] {
			t.Errorf("%s: %s – %s", p, f, to)
		}
	}
}
