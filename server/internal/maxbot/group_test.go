package maxbot

import (
	"context"
	"fmt"
	"strings"
	"testing"
)

const groupChat = -500

var botSelf = User{UserID: 777, Username: "id644106172584_bot", IsBot: true}

func inGroup(s string) Update {
	seq++
	return Update{UpdateType: "message_created", Message: &Message{
		Sender:    &ivan,
		Recipient: Recipient{ChatID: groupChat, ChatType: "chat"},
		Body:      MessageBody{Mid: fmt.Sprintf("g%d", seq), Text: s},
	}}
}

func (f *fakeMax) SendChat(_ context.Context, chatID int64, msg NewMessage) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, sent{chatID, msg})
	return nil
}

func (f *fakeMax) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.sent)
}

func groupBot(t *testing.T, ai brain) (*Bot, *fakeMax) {
	bot, out, _ := newTestBot(t, ai)
	bot.SetSelf(botSelf)
	return bot, out
}

func TestGroupStaysQuiet(t *testing.T) {
	bot, out := groupBot(t, nil)
	ctx := context.Background()
	for _, s := range []string{
		"Всем привет, кто завтра на шашлыки?",
		"Работаю до шести, потом свободен", // «бот» внутри слова — не зов
		"Роботы захватят мир",
	} {
		bot.Handle(ctx, inGroup(s))
	}
	if n := out.count(); n != 0 {
		t.Fatalf("бот встрял в чужой разговор: %d сообщений, последнее %q", n, out.last(t).Text)
	}
}

func TestGroupCalledByName(t *testing.T) {
	for _, call := range []string{"Олег, привет!", "бот, ты тут?", "Олежа, а вы в выходные работаете?", "@id644106172584_bot здравствуйте"} {
		bot, out := groupBot(t, nil)
		u := inGroup(call)
		bot.Handle(context.Background(), u)
		if out.count() != 1 {
			t.Fatalf("%q: бот не откликнулся", call)
		}
		m := out.last(t)
		if out.sent[0].userID != groupChat || m.Link == nil || m.Link.Mid != u.Message.Body.Mid {
			t.Fatalf("%q: ответ не в группу или не ответом на сообщение: %+v", call, m)
		}
		if !has(buttons(m), "link|") || !strings.Contains(m.Text, "личный чат") {
			t.Fatalf("%q: нет приглашения в личку: %q %v", call, m.Text, buttons(m))
		}
	}
}

func TestGroupReplyToBotIsCall(t *testing.T) {
	bot, out := groupBot(t, nil)
	u := inGroup("а сколько по времени?")
	u.Message.Link = &LinkedMessage{Type: "reply", Sender: &botSelf}
	bot.Handle(context.Background(), u)
	if out.count() != 1 {
		t.Fatal("ответ на сообщение бота — это обращение к нему")
	}
}

func TestGroupOrderMovesToPrivate(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	bot.SetSelf(botSelf)
	ctx := context.Background()

	bot.Handle(ctx, inGroup("Подскажите хорошего электрика, у меня на кухне искрит розетка"))
	m := out.last(t)
	if !strings.Contains(m.Text, "мастера") {
		t.Fatalf("заказ в группе не замечен: %q", m.Text)
	}
	url := ""
	for _, b := range m.Attachments[0].Payload.(map[string]any)["buttons"].([][]Button)[0] {
		url = b.URL
	}
	if url != "https://max.ru/id644106172584_bot?start=grp" {
		t.Fatalf("ссылка в личку: %q", url)
	}

	// Не повторяется тому же человеку сразу.
	bot.Handle(ctx, inGroup("ну так что, есть электрик?"))
	if out.count() != 1 {
		t.Fatal("бот повторил приглашение раньше времени")
	}

	// В личке — предлагает оформить то, что человек писал в группе.
	bot.Handle(ctx, started())
	m = out.last(t)
	if !strings.Contains(m.Text, "искрит розетка") || !has(buttons(m), "callback|"+cbOrderKeep) {
		t.Fatalf("в личке не подхватил заказ из группы: %q", m.Text)
	}
	bot.Handle(ctx, press(cbOrderKeep))
	if !has(buttons(out.last(t)), "request_contact|") {
		t.Fatalf("оформление продолжается с телефона: %q", out.last(t).Text)
	}
	d, _ := st.MaxDialog(ctx, uid)
	if !strings.Contains(d.Problem, "искрит розетка") {
		t.Fatalf("суть беды потерялась: %q", d.Problem)
	}
}

func TestGroupAIIgnoresSmallTalk(t *testing.T) {
	ai := &fakeBrain{reply: aiReply{Reply: "Ремонт — дело хорошее!", WantsOrder: false}}
	bot, out := groupBot(t, ai)
	ctx := context.Background()

	// Слово «ремонт» есть, но человеку мастер не нужен — модель это видит.
	bot.Handle(ctx, inGroup("Соседи опять затеяли ремонт, сверлят с утра"))
	if out.count() != 0 || ai.calls != 1 {
		t.Fatalf("болтовня в группе: сообщений %d, вызовов ИИ %d", out.count(), ai.calls)
	}

	// Позвали по имени — отвечает, даже если заказа нет.
	ai.reply = aiReply{Reply: "Здравствуйте! Я помогаю с электрикой и ремонтом.", WantsOrder: false}
	bot.Handle(ctx, inGroup("Олег, ты кто?"))
	m := out.last(t)
	if !strings.HasPrefix(m.Text, "Здравствуйте! Я помогаю") || !strings.Contains(m.Text, "личном чате") {
		t.Fatalf("%q", m.Text)
	}
}

func TestGroupAIOrder(t *testing.T) {
	ai := &fakeBrain{reply: aiReply{
		Reply: "Похоже на неисправный автомат — лучше вызвать мастера.", Problem: "Выбивает автомат", WantsOrder: true,
	}}
	bot, out, st := newTestBot(t, ai)
	bot.SetSelf(botSelf)
	ctx := context.Background()

	bot.Handle(ctx, inGroup("У кого-нибудь выбивает автомат, когда включаешь чайник? Что делать"))
	if out.count() != 1 {
		t.Fatal("заказ не замечен")
	}
	d, _ := st.MaxDialog(ctx, uid)
	if d.Problem != "Выбивает автомат" {
		t.Fatalf("суть беды из ответа ИИ: %q", d.Problem)
	}
}

func TestBotAddedIntroduces(t *testing.T) {
	bot, out := groupBot(t, nil)
	bot.Handle(context.Background(), Update{UpdateType: "bot_added", ChatID: groupChat, User: &ivan})
	m := out.last(t)
	if out.sent[0].userID != groupChat || !strings.Contains(m.Text, "Олег") {
		t.Fatalf("%q", m.Text)
	}
	bot.Handle(context.Background(), Update{UpdateType: "bot_added", ChatID: -600, User: &ivan, IsChannel: true})
	if out.count() != 1 {
		t.Fatal("в канал бот не пишет")
	}
}
