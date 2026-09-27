package maxbot

import (
	"context"
	"errors"
	"log/slog"
	"regexp"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Акт сверки по запросу клиента. В нём заказы и оплаты — чужому его
// показывать нельзя, поэтому номер должен быть подтверждён MAX: контакт
// кнопкой «Поделиться контактом», и это контакт самого собеседника.
// Набранный текстом номер для сверки не годится — вписать можно любой.

const (
	cbRecon     = "recon"  // кнопка меню «Акт сверки»
	cbReconPref = "recon:" // recon:year | recon:12m | recon:all — период
	stepRecon   = "recon"  // ждём контакт, чтобы выдать акт сверки
)

// reconRe — клиент просит сверку своими словами.
var reconRe = regexp.MustCompile(`(?i)акт\S*\s+сверк|сверк[аиуе]|сколько\s+(я\s+)?(вам\s+)?(ещё\s+)?должен|мо[йя]\s+долг|задолженност`)

// Reconciler собирает акт сверки клиента за период, проводит его и заводит
// отправку. Собирает API: бот не умеет верстать документы и печатать PDF.
type Reconciler func(ctx context.Context, clientID, maxUserID int64, from, to string) (store.Document, store.DocumentSend, []byte, error)

// SetReconciler подключает сборку акта сверки. Без неё бот честно говорит,
// что сверка недоступна.
func (b *Bot) SetReconciler(f Reconciler) { b.recon = f }

func reconKeyboard() []OutAttachment {
	return keyboard(
		[]Button{callbackBtn("📅 С начала года", cbReconPref+"year")},
		[]Button{callbackBtn("📅 За 12 месяцев", cbReconPref+"12m"), callbackBtn("📅 За всё время", cbReconPref+"all")},
		[]Button{callbackBtn("✖ Отменить", cbCancel)},
	)
}

// askRecon — первый шаг сверки: подтвердить номер или выбрать период.
func (b *Bot) askRecon(ctx context.Context, d *store.MaxDialog, note string) {
	if b.recon == nil {
		b.send(ctx, d.UserID, "Акт сверки сейчас не собрать — позвоните нам, пришлём вручную.", menuKeyboard())
		return
	}
	if !d.PhoneVerified || d.Phone == "" {
		d.Step = stepRecon
		text := "В акте сверки ваши заказы и оплаты, поэтому сначала убедимся, что это вы. " +
			"Нажмите «📱 Поделиться контактом» — номер должен совпадать с тем, на который вы у нас заказывали."
		if note != "" {
			text = note + "\n\n" + text
		}
		b.send(ctx, d.UserID, text, keyboard(
			[]Button{{Type: "request_contact", Text: "📱 Поделиться контактом"}},
			[]Button{callbackBtn("✖ Отменить", cbCancel)},
		))
		return
	}
	d.Step = stepIdle
	b.send(ctx, d.UserID, "📊 Акт сверки на номер "+d.Phone+". За какой период?", reconKeyboard())
}

// onReconPeriod собирает акт сверки за выбранный период и присылает PDF.
func (b *Bot) onReconPeriod(ctx context.Context, d *store.MaxDialog, period string) {
	if b.recon == nil || !d.PhoneVerified || d.Phone == "" {
		b.askRecon(ctx, d, "")
		return
	}
	client, err := b.crm.ClientByPhone(ctx, d.Phone)
	if errors.Is(err, store.ErrNotFound) {
		site, _ := b.crm.SiteContent(ctx)
		text := "Не нашёл заказов на номер " + d.Phone + "."
		if site.Contacts.Phone != "" {
			text += " Если заказывали на другой номер — позвоните: " + site.Contacts.Phone
		}
		b.send(ctx, d.UserID, text, menuKeyboard())
		return
	}
	if err != nil {
		slog.Error("MAX: клиент для акта сверки", "user", d.UserID, "err", err)
		b.send(ctx, d.UserID, "Не получилось подготовить акт сверки, попробуйте позже.", menuKeyboard())
		return
	}

	from, to := reconPeriod(period, b.now())
	doc, send, pdf, err := b.recon(ctx, client.ID, d.UserID, from, to)
	if err == nil {
		err = b.SendDocument(ctx, send, doc, pdf)
	}
	if err != nil {
		slog.Error("MAX: акт сверки не собран", "user", d.UserID, "client", client.ID, "err", err)
		b.send(ctx, d.UserID, "Не получилось подготовить акт сверки, попробуйте позже или позвоните нам.", menuKeyboard())
		return
	}
	slog.Info("MAX: акт сверки отправлен по запросу", "user", d.UserID, "client", client.ID, "document", doc.ID)
}

// reconPeriod — даты начала и конца периода по местному времени.
func reconPeriod(period string, now time.Time) (from, to string) {
	local := now.In(localZone)
	to = local.Format("2006-01-02")
	switch period {
	case "12m":
		from = local.AddDate(-1, 0, 1).Format("2006-01-02")
	case "all":
		from = "2000-01-01"
	default: // year
		from = local.Format("2006") + "-01-01"
	}
	return from, to
}
