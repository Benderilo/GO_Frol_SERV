package maxbot

import (
	"context"
	"log/slog"
	"time"
	_ "time/tzdata" // часовой пояс Саратова — не надеемся на базу поясов в системе

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Напоминание о документе без ответа: одно, через сутки после отправки,
// и только днём по местному времени — ночью клиента не будим.
const (
	remindAfter = 24 * time.Hour
	remindEvery = 30 * time.Minute
	dayStarts   = 9  // с 9:00
	dayEnds     = 20 // до 20:00
)

// localZone — время Балаково: Саратовская область живёт по UTC+4.
var localZone = func() *time.Location {
	if z, err := time.LoadLocation("Europe/Saratov"); err == nil {
		return z
	}
	return time.FixedZone("UTC+4", 4*60*60)
}()

func daytime(t time.Time) bool {
	h := t.In(localZone).Hour()
	return h >= dayStarts && h < dayEnds
}

// RemindDue напоминает о документах, на которые клиент сутки не ответил.
// Возвращает, сколько напоминаний ушло.
func (b *Bot) RemindDue(ctx context.Context) int {
	now := b.now()
	if !daytime(now) {
		return 0
	}
	due, err := b.crm.DueReminders(ctx, now.Add(-remindAfter).UTC().Format(time.RFC3339))
	if err != nil {
		slog.Error("MAX: поиск документов без ответа", "err", err)
		return 0
	}
	sent := 0
	for _, s := range due {
		doc, err := b.crm.DocumentByID(ctx, s.DocumentID)
		if err != nil {
			slog.Error("MAX: документ для напоминания", "send", s.ID, "err", err)
			continue
		}
		text := "🔔 Напоминаю: " + store.DocumentName(doc)
		if d := ruDate(doc.DocDate); d != "" {
			text += " от " + d
		}
		text += " ждёт вашего ответа. Файл — в сообщении выше. " + docTextHint
		if err := b.out.Send(ctx, s.MaxUserID, NewMessage{Text: text, Attachments: docKeyboard(s.ID)}); err != nil {
			// Отметку ставим и при ошибке: клиент мог удалить чат с ботом,
			// и повторять каждые полчаса бессмысленно.
			slog.Warn("MAX: напоминание не ушло", "send", s.ID, "err", err)
		} else {
			sent++
		}
		if err := b.crm.MarkReminded(ctx, s.ID); err != nil {
			slog.Error("MAX: отметка напоминания", "send", s.ID, "err", err)
		}
	}
	if sent > 0 {
		slog.Info("MAX: напомнил о документах без ответа", "count", sent)
	}
	return sent
}

// remindLoop проверяет раз в полчаса. Первая проверка — через минуту
// после запуска, чтобы не мешать старту сервера.
func (s *Service) remindLoop(ctx context.Context) {
	wait := time.Minute
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		s.RemindDue(ctx)
		wait = remindEvery
	}
}
