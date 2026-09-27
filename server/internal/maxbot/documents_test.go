package maxbot

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"testing"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

func (f *fakeMax) UploadFile(_ context.Context, name string, data []byte) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.uploaded = append(f.uploaded, name)
	return "tok-" + name, nil
}

// notReadyMax отвечает «файл не готов» первые n отправок.
type notReadyMax struct {
	*fakeMax
	n int
}

func (f *notReadyMax) Send(ctx context.Context, userID int64, msg NewMessage) error {
	if f.n > 0 {
		f.n--
		return errors.New(`MAX POST /messages: 400 {"code":"attachment.not.ready"}`)
	}
	return f.fakeMax.Send(ctx, userID, msg)
}

// docFixture — клиент с заказом и проведённым счётом, отправленный ему в MAX.
func docFixture(t *testing.T, st *store.Store) (store.Document, store.DocumentSend) {
	t.Helper()
	ctx := context.Background()
	req, err := st.CreateRequest(ctx, store.Request{Name: "Иван", Phone: "+79001234567", Message: "Розетки", Source: "max", MaxUserID: uid})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.AddOrderItem(ctx, req.OrderID, store.OrderItem{Name: "Замена розетки", Unit: "шт.", QtyMilli: 5000, PriceKop: 120000}); err != nil {
		t.Fatal(err)
	}
	doc, err := st.IssueDocument(ctx, req.OrderID, store.DocInvoice)
	if err != nil {
		t.Fatal(err)
	}
	if doc, err = st.DocumentByID(ctx, doc.ID); err != nil {
		t.Fatal(err)
	}
	send, err := st.AddDocumentSend(ctx, doc.ID, uid)
	if err != nil {
		t.Fatal(err)
	}
	return doc, send
}

func TestSendDocument(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	doc, send := docFixture(t, st)

	if err := bot.SendDocument(context.Background(), send, doc, []byte("%PDF-")); err != nil {
		t.Fatal(err)
	}
	m := out.last(t)
	if out.uploaded[0] != "Счёт на оплату № "+itoa(doc.Number)+".pdf" {
		t.Fatalf("имя файла: %q", out.uploaded[0])
	}
	if m.Attachments[0].Type != "file" || !strings.Contains(m.Text, "Счёт на оплату") {
		t.Fatalf("сообщение: %q %+v", m.Text, m.Attachments[0])
	}
	id := itoa(send.ID)
	for _, want := range []string{"callback|doc:ok:" + id, "callback|doc:no:" + id, "callback|doc:ask:" + id} {
		if !has(buttons(NewMessage{Attachments: m.Attachments[1:]}), want) {
			t.Fatalf("нет кнопки %s", want)
		}
	}
}

func TestSendDocumentWaitsForFile(t *testing.T) {
	_, _, st := newTestBot(t, nil)
	out := &notReadyMax{fakeMax: &fakeMax{}, n: 1}
	bot := newBot(st, out, nil)
	doc, send := docFixture(t, st)
	if err := bot.SendDocument(context.Background(), send, doc, []byte("%PDF-")); err != nil {
		t.Fatalf("после «не готов» отправка повторяется: %v", err)
	}
	if out.count() != 1 {
		t.Fatal("документ не дошёл")
	}
}

func TestDocumentAccepted(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	ctx := context.Background()
	doc, send := docFixture(t, st)

	bot.Handle(ctx, press("doc:ok:"+itoa(send.ID)))
	if m := out.last(t); !strings.Contains(m.Text, "Спасибо") {
		t.Fatalf("%q", m.Text)
	}
	last, _ := st.LastDocumentSend(ctx, doc.ID)
	if last.Reply != store.ReplyAccepted {
		t.Fatalf("ответ не записан: %+v", last)
	}
	tasks, _ := st.ListTasks(ctx, "")
	if len(tasks) != 1 || !strings.HasPrefix(tasks[0].Title, "Клиент согласен: Счёт на оплату") {
		t.Fatalf("задача: %+v", tasks)
	}
}

func TestDocumentDeclinedWithReason(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	ctx := context.Background()
	doc, send := docFixture(t, st)

	bot.Handle(ctx, press("doc:no:"+itoa(send.ID)))
	if m := out.last(t); !strings.Contains(m.Text, "Что не устраивает") {
		t.Fatalf("%q", m.Text)
	}
	bot.Handle(ctx, text("Дорого, у соседей делали за 800 за точку"))
	if m := out.last(t); !strings.Contains(m.Text, "передал мастеру") {
		t.Fatalf("%q", m.Text)
	}
	last, _ := st.LastDocumentSend(ctx, doc.ID)
	if last.Reply != store.ReplyDeclined || !strings.Contains(last.Note, "Дорого") {
		t.Fatalf("причина не записана: %+v", last)
	}
	tasks, _ := st.ListTasks(ctx, "")
	if len(tasks) != 1 || tasks[0].Priority != "high" || !strings.Contains(tasks[0].Note, "Дорого") {
		t.Fatalf("одна задача с причиной: %+v", tasks)
	}
	// Следующее сообщение — уже обычный разговор, а не причина отказа.
	d, _ := st.MaxDialog(ctx, uid)
	if d.Step != stepIdle {
		t.Fatalf("шаг остался %q", d.Step)
	}
}

func TestDocumentReplyOnlyByAddressee(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	_, send := docFixture(t, st)
	stranger := User{UserID: 999, FirstName: "Чужой"}
	u := press("doc:ok:" + itoa(send.ID))
	u.Callback.User = stranger
	bot.Handle(context.Background(), u)
	if m := out.last(t); !strings.Contains(m.Text, "Не получилось") {
		t.Fatalf("чужой ответ принят: %q", m.Text)
	}
}

func TestDocCallbackKeepsFile(t *testing.T) {
	bot, _, st := newTestBot(t, nil)
	_, send := docFixture(t, st)
	var edited *NewMessage
	spy := &answerSpy{fakeMax: &fakeMax{}, got: &edited}
	bot.out = spy
	u := press("doc:ok:" + itoa(send.ID))
	u.Message = &Message{Body: MessageBody{Text: "📄 Счёт"}}
	bot.Handle(context.Background(), u)
	if edited != nil {
		t.Fatal("правка сообщения с документом стёрла бы PDF")
	}
}

type answerSpy struct {
	*fakeMax
	got **NewMessage
}

func (a *answerSpy) Answer(_ context.Context, _ string, msg *NewMessage) error {
	*a.got = msg
	return nil
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
