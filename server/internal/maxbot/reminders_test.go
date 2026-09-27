package maxbot

import (
	"context"
	"strings"
	"testing"
	"time"
)

// atLocal — момент через days суток в hour:00 по времени Балаково.
func atLocal(days, hour int) time.Time {
	d := time.Now().In(localZone).AddDate(0, 0, days)
	return time.Date(d.Year(), d.Month(), d.Day(), hour, 0, 0, 0, localZone)
}

func TestRemindOnceNextDay(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	ctx := context.Background()
	doc, send := docFixture(t, st)

	// Сразу после отправки — рано.
	bot.now = time.Now
	if n := bot.RemindDue(ctx); n != 0 {
		t.Fatalf("напомнил раньше суток: %d", n)
	}

	// Через двое суток, днём — напоминает с теми же кнопками.
	bot.now = func() time.Time { return atLocal(2, 12) }
	if n := bot.RemindDue(ctx); n != 1 {
		t.Fatalf("напоминаний %d, ждали 1", n)
	}
	m := out.last(t)
	if !strings.Contains(m.Text, "Напоминаю") || !strings.Contains(m.Text, "Счёт на оплату") ||
		!has(buttons(m), "callback|doc:ok:"+itoa(send.ID)) {
		t.Fatalf("напоминание: %q %v", m.Text, buttons(m))
	}
	if out.sent[len(out.sent)-1].userID != uid {
		t.Fatal("напоминание не тому")
	}

	// Второго напоминания не будет.
	if n := bot.RemindDue(ctx); n != 0 {
		t.Fatalf("напомнил повторно: %d", n)
	}
	last, _ := st.LastDocumentSend(ctx, doc.ID)
	if last.RemindedAt == "" {
		t.Fatal("отметка о напоминании не записана")
	}
}

func TestNoRemindAtNight(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	docFixture(t, st)
	for _, hour := range []int{23, 3, 8} {
		bot.now = func() time.Time { return atLocal(2, hour) }
		if n := bot.RemindDue(context.Background()); n != 0 || out.count() != 0 {
			t.Fatalf("в %d:00 бот написал клиенту", hour)
		}
	}
	bot.now = func() time.Time { return atLocal(2, 9) }
	if n := bot.RemindDue(context.Background()); n != 1 {
		t.Fatal("в 9:00 утра напоминание должно уйти")
	}
}

func TestNoRemindAfterReply(t *testing.T) {
	bot, out, st := newTestBot(t, nil)
	_, send := docFixture(t, st)
	bot.Handle(context.Background(), press("doc:ok:"+itoa(send.ID)))
	before := out.count()

	bot.now = func() time.Time { return atLocal(2, 12) }
	if n := bot.RemindDue(context.Background()); n != 0 || out.count() != before {
		t.Fatal("напомнил о документе, на который уже ответили")
	}
}

func TestNoRemindForOldSend(t *testing.T) {
	bot, _, st := newTestBot(t, nil)
	ctx := context.Background()
	doc, _ := docFixture(t, st)
	// Документ отправили повторно: напоминание — только о последней отправке.
	if _, err := st.AddDocumentSend(ctx, doc.ID, uid); err != nil {
		t.Fatal(err)
	}
	bot.now = func() time.Time { return atLocal(2, 12) }
	if n := bot.RemindDue(ctx); n != 1 {
		t.Fatalf("напоминаний %d, ждали одно — о последней отправке", n)
	}
}
