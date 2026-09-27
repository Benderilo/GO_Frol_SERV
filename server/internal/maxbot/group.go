package maxbot

import (
	"context"
	"fmt"
	"log/slog"
	"regexp"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// В общем чате бот не собеседник для всех подряд. Он отвечает, только если
// его позвали («Олег», «бот», упоминание, ответ на его сообщение) или если
// человек явно ищет мастера. Заявку в группе не оформляет: там чужие люди
// и телефоны, — зовёт в личный чат и переносит туда суть беды.

// botName — как бот представляется и на что откликается.
const botName = "Олег"

// callRe — к боту обратились по имени: «Олег», «Олежа», «бот», «боту».
// Границы слова обязательны, иначе «работа» звала бы бота.
var callRe = regexp.MustCompile(`(?i)(^|[^\p{L}])(олег\p{L}*|олеж\p{L}*|бот|бота|боту|ботом|ботик)([^\p{L}]|$)`)

// orderWords — признаки того, что человеку нужен мастер. Без ИИ по ним
// бот и решает; с ИИ — только отбирает сообщения, которые стоит показать модели.
var orderWords = []string{
	"электрик", "мастер", "проводк", "розетк", "выключател", "щиток", "щитк",
	"автомат выб", "выбива", "искрит", "замыкан", "нет света", "пропал свет",
	"свет пропал", "отключили свет", "люстр", "светильник", "узо", "счётчик",
	"счетчик", "ремонт", "смет", "сколько стоит", "почём", "почем", "вызвать",
	"заявк", "починить", "не работает",
	"сантехник", "труб", "течёт", "течет", "протек", "потоп", "затопил", "канализац",
	"засор", "смесител", "унитаз", "раковин", "бойлер", "водонагревател", "батаре", "отоплен",
}

func hasOrderWords(text string) bool {
	t := strings.ToLower(text)
	for _, w := range orderWords {
		if strings.Contains(t, w) {
			return true
		}
	}
	return false
}

// Сколько молчать после приглашения тому же человеку в том же чате:
// если позвал сам — недолго (от флуда), если бот вмешался сам — долго.
const (
	calledCooldown  = 15 * time.Second
	unaskedCooldown = 30 * time.Minute
)

// addressed — обратились ли к боту: по имени, упоминанием или ответом.
func (b *Bot) addressed(m *Message) bool {
	if callRe.MatchString(m.Body.Text) {
		return true
	}
	self := b.selfUser()
	if self.Username != "" && strings.Contains(strings.ToLower(m.Body.Text), "@"+strings.ToLower(self.Username)) {
		return true
	}
	return m.Link != nil && m.Link.Type == "reply" && m.Link.Sender != nil &&
		self.UserID != 0 && m.Link.Sender.UserID == self.UserID
}

// onGroupMessage решает, отвечать ли в общем чате, и если да — зовёт в личку.
func (b *Bot) onGroupMessage(ctx context.Context, d *store.MaxDialog, m *Message) {
	chatID := m.Recipient.ChatID
	text := strings.TrimSpace(m.Body.Text)
	if chatID == 0 || text == "" {
		return
	}
	b.noteChat(chatID)

	called := b.addressed(m)
	if !called && !hasOrderWords(text) {
		return
	}
	cooldown := unaskedCooldown
	if called {
		cooldown = calledCooldown
	}
	if !b.inviteAllowed(chatID, d.UserID, cooldown) {
		return
	}

	reply, problem, wants := "", "", called || hasOrderWords(text)
	if b.aiAvailable() {
		r, err := b.ai.Think(ctx, b.groupPrompt(ctx, d.Name, called), []store.ChatTurn{{Role: "user", Text: clip(text, 1000)}})
		if err != nil {
			slog.Warn("MAX: ИИ не ответил в группе, отвечаю по шаблону", "err", err)
			b.aiFailed()
		} else {
			reply, problem, wants = r.Reply, strings.TrimSpace(r.Problem), r.WantsOrder
			// Бота не звали, а модель не увидела заказа — промолчать.
			if !called && !wants {
				b.forgetInvite(chatID, d.UserID)
				return
			}
		}
	} else if hasOrderWords(text) {
		problem = text
	}

	// Суть беды переносим в личный диалог: там не придётся повторять.
	// Незаконченную заявку не трогаем.
	if wants && problem != "" && d.Step == stepIdle {
		d.Problem = clip(problem, 1500)
	}

	if reply == "" {
		reply = inviteText(d.Name, wants)
	} else if !strings.Contains(strings.ToLower(reply), "личн") {
		// Модель сама позвала в личку — второй раз не повторяем.
		reply += "\n\nЗаявку оформлю в личном чате — там телефон и адрес не увидят посторонние."
	}
	b.sendChat(ctx, chatID, m.Body.Mid, reply, b.inviteKeyboard())
	slog.Info("MAX: ответ в группе", "chat", chatID, "user", d.UserID, "called", called, "order", wants)
}

func inviteText(name string, order bool) string {
	who := ""
	if name != "" {
		who = name + ", "
	}
	if order {
		return who + "помогу вызвать мастера. Напишите мне в личный чат — там оформлю заявку, " +
			"и телефон с адресом не увидят посторонние."
	}
	return who + "я " + botName + ", помощник по электрике, сантехнике и ремонту. " +
		"Если нужен мастер — напишите мне в личный чат, там оформлю заявку."
}

// onBotAdded представляется в чате, куда бота добавили.
func (b *Bot) onBotAdded(ctx context.Context, chatID int64) {
	if chatID == 0 {
		return
	}
	site, _ := b.crm.SiteContent(ctx)
	text := fmt.Sprintf("Здравствуйте! Я %s, помощник «%s».\n\n"+
		"Если нужен электрик, сантехник или ремонт — позовите меня: «%s» или «бот», и я приглашу в личный чат оформить заявку. "+
		"Остальные разговоры не подслушиваю и не встреваю.",
		botName, orDefault(site.SiteName, "Фролов Системы"), botName)
	b.sendChat(ctx, chatID, "", text, b.inviteKeyboard())
}

// groupPrompt — короткий ответ в общем чате и решение, нужен ли мастер.
func (b *Bot) groupPrompt(ctx context.Context, name string, called bool) string {
	site, _ := b.crm.SiteContent(ctx)
	var sb strings.Builder
	fmt.Fprintf(&sb, `Тебя зовут %s, ты помощник компании «%s» (%s) в общем групповом чате мессенджера MAX.
Человек (%s) написал сообщение в группу. `, botName, site.SiteName, site.Tagline, orDefault(name, "участник"))
	if called {
		sb.WriteString("Он обратился к тебе.\n")
	} else {
		sb.WriteString("К тебе он не обращался — ты лишь заметил сообщение.\n")
	}
	sb.WriteString(`
Правила:
- wants_order — true, только если человеку самому нужен электрик, сантехник, ремонт или мастер
  (что-то сломалось, нужна установка, спрашивает цену или вызов). Разговоры, шутки,
  пересказы, чужие истории — false.
- problem — суть беды кратко, если она есть, иначе "".
- reply — 1–2 предложения по-русски, без markdown. Не проси в группе телефон и адрес:
  заявку оформляешь только в личном чате, приглашение добавят к ответу сами.
- Цены называй только из списка ниже, ничего не складывай и не добавляй (выезд, работу и т.п.):
  точную стоимость назовёт мастер после осмотра.
- Если искрит, пахнет гарью, дымится — посоветуй отключить автомат в щитке.
- У тебя нет интернета: погоды, новостей и курсов не знаешь — не сочиняй.
- phone и address всегда "".
`)
	sb.WriteString("\nУслуги и цены:\n" + extraServices)
	for _, s := range site.Services {
		fmt.Fprintf(&sb, "- %s: %s\n", s.Title, s.Price)
	}
	if site.Contacts.WorkHours != "" {
		fmt.Fprintf(&sb, "Часы работы: %s.\n", site.Contacts.WorkHours)
	}
	return sb.String()
}

// inviteKeyboard — кнопка-ссылка в личный чат с ботом.
func (b *Bot) inviteKeyboard() []OutAttachment {
	self := b.selfUser()
	if self.Username == "" {
		return nil
	}
	return keyboard([]Button{{
		Type: "link",
		Text: "✍️ Написать " + botName + "у",
		URL:  "https://max.ru/" + self.Username + "?start=grp",
	}})
}

func (b *Bot) sendChat(ctx context.Context, chatID int64, replyTo, text string, kb []OutAttachment) {
	if kb == nil {
		kb = []OutAttachment{}
	}
	msg := NewMessage{Text: clip(text, 4000), Attachments: kb}
	if replyTo != "" {
		msg.Link = &OutLink{Type: "reply", Mid: replyTo}
	}
	if err := b.out.SendChat(ctx, chatID, msg); err != nil {
		slog.Error("MAX: отправка в чат", "chat", chatID, "err", err)
	}
}

// inviteAllowed пропускает приглашение, если этому человеку в этом чате
// недавно уже отвечали, и запоминает время ответа.
func (b *Bot) inviteAllowed(chatID, userID int64, cooldown time.Duration) bool {
	key := fmt.Sprintf("%d:%d", chatID, userID)
	b.groupMu.Lock()
	defer b.groupMu.Unlock()
	now := b.now()
	if last, ok := b.invited[key]; ok && now.Sub(last) < cooldown {
		return false
	}
	b.invited[key] = now
	return true
}

// forgetInvite снимает отметку: бот промолчал, и следующее сообщение
// человека снова стоит рассмотреть.
func (b *Bot) forgetInvite(chatID, userID int64) {
	b.groupMu.Lock()
	delete(b.invited, fmt.Sprintf("%d:%d", chatID, userID))
	b.groupMu.Unlock()
}

// noteChat один раз на чат пишет в журнал, что сообщения оттуда приходят:
// так видно, получает ли бот переписку группы или только обращения к нему.
func (b *Bot) noteChat(chatID int64) {
	b.groupMu.Lock()
	seen := b.chats[chatID]
	b.chats[chatID] = true
	b.groupMu.Unlock()
	if !seen {
		slog.Info("MAX: бот получает сообщения группы", "chat", chatID)
	}
}

// SetSelf запоминает, кто бот: по id узнаём ответы на его сообщения,
// по имени строим ссылку в личный чат.
func (b *Bot) SetSelf(u User) {
	b.groupMu.Lock()
	b.self = u
	b.groupMu.Unlock()
}

func (b *Bot) selfUser() User {
	b.groupMu.Lock()
	defer b.groupMu.Unlock()
	return b.self
}
