package maxbot

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Шаги заявки. Бот всегда спрашивает первое, чего ещё не знает:
// контакт → адрес → что случилось → подтверждение. Рассказал о беде
// первым делом — описание уже есть, и этот вопрос бот пропустит.
const (
	stepIdle    = ""
	stepPhone   = "phone"
	stepAddress = "address"
	stepProblem = "problem"
	stepConfirm = "confirm"
)

// Нажатия кнопок. Текст payload короткий: у MAX на него жёсткий предел.
const (
	cbOrder       = "order"
	cbOrderKeep   = "order:keep" // оформить то, о чём человек писал в группе
	cbPrices      = "prices"
	cbCallMe      = "callme"
	cbSend        = "send"
	cbEditPhone   = "edit:phone"
	cbEditAddress = "edit:address"
	cbEditProblem = "edit:problem"
	cbCancel      = "cancel"
	cbProblemPref = "p:"
	cbProblemOwn  = "own"         // описать беду своими словами
	cbAddrManual  = "addr:manual" // ввести адрес вручную, а не геопозицией
)

// callMeProblem — описание заявки «перезвоните мне»: для звонка адрес не нужен.
const callMeProblem = "Прошу перезвонить"

// Готовые ответы на «что случилось»: без ИИ клиенту проще ткнуть, чем писать.
var problemChoices = []string{
	"⚡ Нет света",
	"🔥 Искрит или пахнет гарью",
	"🔌 Розетки и выключатели",
	"💡 Люстры и освещение",
	"🧰 Проводка или щиток",
	"🚿 Сантехника, смесители",
	"💧 Течёт вода, трубы",
	"🚽 Канализация, засор",
	"🔥 Отопление, батареи",
	"🏠 Ремонт помещения",
}

// extraServices — работы, которых нет среди услуг сайта, но которые берём:
// ИИ должен знать о них, иначе на «течёт труба» ответит, что это не к нам.
const extraServices = "- Сантехника: трубы, течи, смесители, канализация и засоры, отопление (цену назовёт мастер)\n"

// crm — то, что боту нужно от хранилища.
type crm interface {
	MaxDialog(ctx context.Context, userID int64) (store.MaxDialog, error)
	SaveMaxDialog(ctx context.Context, d store.MaxDialog) error
	CreateRequest(ctx context.Context, r store.Request) (store.Request, error)
	SiteContent(ctx context.Context) (store.SiteContent, error)
}

// messenger — то, что боту нужно от MAX.
type messenger interface {
	Send(ctx context.Context, userID int64, msg NewMessage) error
	SendChat(ctx context.Context, chatID int64, msg NewMessage) error
	Answer(ctx context.Context, callbackID string, msg *NewMessage) error
}

type Bot struct {
	crm crm
	out messenger
	ai  brain // nil — ИИ не подключён, только кнопки

	// Сообщения одного человека обрабатываем по очереди: иначе два быстрых
	// нажатия прочитают один и тот же шаг и затрут друг друга.
	locks sync.Map // userID → *sync.Mutex

	// MAX повторяет доставку, если не дождался ответа, — дубли отсекаем.
	seenMu sync.Mutex
	seen   map[string]time.Time

	// Упавший ИИ не должен тормозить каждое сообщение: после ошибки
	// какое-то время работаем на кнопках, не дёргая его.
	aiMu        sync.Mutex
	aiDownUntil time.Time

	// Групповые чаты: кто бот, кому и когда уже отвечали, какие чаты видели.
	groupMu sync.Mutex
	self    User
	invited map[string]time.Time
	chats   map[int64]bool

	now func() time.Time
}

func newBot(c crm, out messenger, ai brain) *Bot {
	return &Bot{
		crm: c, out: out, ai: ai, now: time.Now,
		seen: map[string]time.Time{}, invited: map[string]time.Time{}, chats: map[int64]bool{},
	}
}

// ------------------------------- Разбор ------------------------------------

// Handle обрабатывает одно событие от MAX. Ошибки только пишутся в журнал:
// вернуть их некому, а клиент в чате не должен видеть внутренностей.
func (b *Bot) Handle(ctx context.Context, u Update) {
	if key := updateKey(u); key != "" && b.duplicate(key) {
		return
	}
	// Бота добавили в чат — представляется. Диалога с человеком тут нет.
	if u.UpdateType == "bot_added" {
		if !u.IsChannel {
			b.onBotAdded(ctx, u.ChatID)
		}
		return
	}
	userID, user := updateUser(u)
	if userID == 0 || user.IsBot {
		return
	}
	lock := b.userLock(userID)
	lock.Lock()
	defer lock.Unlock()

	d, err := b.crm.MaxDialog(ctx, userID)
	if err != nil {
		slog.Error("MAX: чтение диалога", "user", userID, "err", err)
		return
	}
	if d.Name == "" {
		d.Name = user.DisplayName()
	}

	switch u.UpdateType {
	case "bot_started":
		b.greet(ctx, &d)
	case "message_callback":
		if u.Callback == nil {
			return
		}
		b.onCallback(ctx, &d, u.Callback, u.Message)
	case "message_created":
		if u.Message == nil {
			return
		}
		switch u.Message.Recipient.ChatType {
		case "", "dialog":
			b.onMessage(ctx, &d, u.Message.Body)
		case "chat":
			// В группе бот не болтает со всеми: отвечает, только если позвали
			// или человеку нужен мастер, и зовёт в личный чат.
			b.onGroupMessage(ctx, &d, u.Message)
		default:
			return // каналы
		}
	default:
		return
	}

	if err := b.crm.SaveMaxDialog(ctx, d); err != nil {
		slog.Error("MAX: запись диалога", "user", userID, "err", err)
	}
}

func updateUser(u Update) (int64, User) {
	switch {
	case u.Callback != nil:
		return u.Callback.User.UserID, u.Callback.User
	case u.Message != nil && u.Message.Sender != nil:
		return u.Message.Sender.UserID, *u.Message.Sender
	case u.User != nil:
		return u.User.UserID, *u.User
	}
	return 0, User{}
}

func updateKey(u Update) string {
	switch {
	case u.Callback != nil:
		return "cb:" + u.Callback.CallbackID
	case u.Message != nil && u.Message.Body.Mid != "":
		return "m:" + u.Message.Body.Mid
	}
	return ""
}

func (b *Bot) duplicate(key string) bool {
	b.seenMu.Lock()
	defer b.seenMu.Unlock()
	now := b.now()
	if _, ok := b.seen[key]; ok {
		return true
	}
	// Повторы MAX приходят в пределах часов — сутки памяти с запасом.
	if len(b.seen) > 2000 {
		for k, t := range b.seen {
			if now.Sub(t) > 24*time.Hour {
				delete(b.seen, k)
			}
		}
	}
	b.seen[key] = now
	return false
}

func (b *Bot) userLock(id int64) *sync.Mutex {
	l, _ := b.locks.LoadOrStore(id, &sync.Mutex{})
	return l.(*sync.Mutex)
}

// ------------------------------ Кнопки -------------------------------------

func (b *Bot) onCallback(ctx context.Context, d *store.MaxDialog, cb *Callback, msg *Message) {
	// Отвечаем на нажатие сразу: пока ответа нет, у клиента крутится кнопка.
	// Сообщение с кнопками правим: клавиатура уходит, остаётся отметка,
	// что выбрано, — старые кнопки не нажмут второй раз невпопад.
	var edit *NewMessage
	if msg != nil && msg.Body.Text != "" {
		edit = &NewMessage{Text: clip(msg.Body.Text+"\n\n→ "+buttonLabel(cb.Payload), 4000), Attachments: []OutAttachment{}}
	}
	if err := b.out.Answer(ctx, cb.CallbackID, edit); err != nil {
		slog.Warn("MAX: ответ на нажатие", "err", err)
	}

	payload := cb.Payload
	switch {
	case payload == cbOrder:
		b.startOrder(d, "")
		b.ask(ctx, d, "")
	case payload == cbOrderKeep:
		b.startOrder(d, d.Problem)
		b.ask(ctx, d, "")
	case payload == cbCallMe:
		b.startOrder(d, callMeProblem)
		b.ask(ctx, d, "")
	case payload == cbPrices:
		b.send(ctx, d.UserID, b.pricesText(ctx), menuKeyboard())
	case payload == cbSend:
		b.submit(ctx, d)
	case payload == cbEditPhone:
		d.Phone = ""
		b.ask(ctx, d, "")
	case payload == cbEditAddress:
		d.Address = ""
		b.ask(ctx, d, "")
	case payload == cbEditProblem:
		d.Problem = ""
		b.ask(ctx, d, "")
	case payload == cbCancel:
		d.Step, d.Problem = stepIdle, ""
		b.send(ctx, d.UserID, "Заявку отменил. Если понадобится мастер — я здесь.", menuKeyboard())
	case payload == cbProblemOwn:
		d.Step = stepProblem
		b.send(ctx, d.UserID, "Напишите одним сообщением, что случилось или что нужно сделать. Можно подробно: где, когда началось, что уже пробовали.",
			keyboard([]Button{callbackBtn("✖ Отменить", cbCancel)}))
	case payload == cbAddrManual:
		d.Step = stepAddress
		b.send(ctx, d.UserID, "Напишите адрес: город, улица, дом, подъезд и квартира.",
			keyboard([]Button{callbackBtn("✖ Отменить", cbCancel)}))
	case strings.HasPrefix(payload, cbProblemPref):
		var i int
		if _, err := fmt.Sscanf(payload, cbProblemPref+"%d", &i); err == nil && i >= 0 && i < len(problemChoices) {
			d.Problem = appendProblem(d.Problem, trimEmoji(problemChoices[i]))
			if d.Step == stepIdle {
				d.Step = stepProblem
			}
		}
		b.ask(ctx, d, "")
	default:
		b.greet(ctx, d)
	}
}

// ----------------------------- Сообщения -----------------------------------

func (b *Bot) onMessage(ctx context.Context, d *store.MaxDialog, body MessageBody) {
	// Контакт и геопозиция приходят вложениями — это ответы на кнопки,
	// ИИ тут не нужен.
	for _, a := range body.Attachments {
		switch a.Type {
		case "contact":
			phone, name := parseContact(a.Payload)
			if phone == "" {
				b.send(ctx, d.UserID, "Не вижу номера в контакте. Напишите его, пожалуйста, цифрами.", nil)
				return
			}
			d.Phone = phone
			if name != "" {
				d.Name = name
			}
			b.continueOrder(ctx, d)
			return
		case "location":
			lat, lon := a.Latitude, a.Longitude
			if lat == 0 && lon == 0 {
				// На случай, если координаты придут внутри payload.
				var p struct{ Latitude, Longitude float64 }
				_ = json.Unmarshal(a.Payload, &p)
				lat, lon = p.Latitude, p.Longitude
			}
			if lat == 0 && lon == 0 {
				continue
			}
			d.Address = fmt.Sprintf("геопозиция %.6f, %.6f — https://yandex.ru/maps/?pt=%.6f,%.6f&z=17",
				lat, lon, lon, lat)
			b.continueOrder(ctx, d)
			return
		}
	}

	text := strings.TrimSpace(body.Text)
	if text == "" {
		b.send(ctx, d.UserID, "Я понимаю текст, кнопки, контакт и геопозицию. Фото пришлёте мастеру, когда он свяжется.", nil)
		return
	}
	if isStartCommand(text) {
		b.greet(ctx, d)
		return
	}

	// Голый номер на шаге «телефон» — однозначный ответ, ИИ его только
	// задержал бы. Если рядом ещё и адрес или рассказ — это работа для ИИ.
	if d.Step == stepPhone {
		if match, phone := findPhone(text); phone != "" &&
			(!b.aiAvailable() || len([]rune(strings.TrimSpace(strings.Replace(text, match, "", 1)))) <= 15) {
			d.Phone = phone
			b.continueOrder(ctx, d)
			return
		}
	}

	if b.aiAvailable() {
		if err := b.talk(ctx, d, text); err == nil {
			return
		} else {
			slog.Warn("MAX: ИИ не ответил, перехожу на кнопки", "err", err)
			b.aiFailed()
		}
	}
	b.byButtons(ctx, d, text)
}

// byButtons — разговор без ИИ: текст считается ответом на текущий вопрос.
func (b *Bot) byButtons(ctx context.Context, d *store.MaxDialog, text string) {
	switch d.Step {
	case stepPhone:
		b.ask(ctx, d, "Не похоже на номер телефона. Пример: +7 900 123-45-67.")
	case stepAddress:
		if len([]rune(text)) < 5 {
			b.ask(ctx, d, "Адрес слишком короткий — нужны улица и дом.")
			return
		}
		d.Address = text
		b.continueOrder(ctx, d)
	case stepProblem:
		d.Problem = appendProblem(d.Problem, text)
		b.continueOrder(ctx, d)
	case stepConfirm:
		// Дописал что-то на проверке — это уточнение к описанию.
		d.Problem = appendProblem(d.Problem, text)
		b.ask(ctx, d, "Добавил к описанию.")
	default:
		// Длинный текст с порога — почти наверняка рассказ о беде.
		if len([]rune(text)) >= 15 {
			b.startOrder(d, text)
			b.ask(ctx, d, "Понял, оформим заявку.")
			return
		}
		if d.Problem != "" {
			b.offerPending(ctx, d, "")
			return
		}
		b.send(ctx, d.UserID,
			"Я принимаю заявки на электрику и ремонт. Нажмите «Оставить заявку» или просто опишите, что случилось.",
			menuKeyboard())
	}
}

// ------------------------------- ИИ ----------------------------------------

func (b *Bot) aiAvailable() bool {
	if b.ai == nil {
		return false
	}
	b.aiMu.Lock()
	defer b.aiMu.Unlock()
	return b.now().After(b.aiDownUntil)
}

func (b *Bot) aiFailed() {
	b.aiMu.Lock()
	b.aiDownUntil = b.now().Add(3 * time.Minute)
	b.aiMu.Unlock()
}

// talk отдаёт реплику ИИ, забирает из ответа поля заявки и ставит под
// ответом кнопки текущего шага: кнопки остаются главным путём к заявке.
func (b *Bot) talk(ctx context.Context, d *store.MaxDialog, text string) error {
	history := append(append([]store.ChatTurn{}, d.History...), store.ChatTurn{Role: "user", Text: clip(text, 1000)})
	r, err := b.ai.Think(ctx, b.systemPrompt(ctx, d), history)
	if err != nil {
		return err
	}

	if p := normalizePhone(r.Phone); p != "" {
		d.Phone = p
	}
	if a := strings.TrimSpace(r.Address); len([]rune(a)) >= 5 {
		d.Address = a
	}
	if p := strings.TrimSpace(r.Problem); p != "" {
		d.Problem = clip(p, 1500)
	}
	d.History = tail(append(history, store.ChatTurn{Role: "assistant", Text: clip(r.Reply, 1000)}), 16)

	ordering := d.Step != stepIdle || r.WantsOrder || d.Problem != ""
	if !ordering {
		b.send(ctx, d.UserID, r.Reply, menuKeyboard())
		return nil
	}
	d.Step = nextStep(d)
	if d.Step == stepConfirm {
		b.send(ctx, d.UserID, r.Reply, nil)
		b.ask(ctx, d, "")
		return nil
	}
	b.send(ctx, d.UserID, r.Reply, stepKeyboard(d.Step))
	return nil
}

func (b *Bot) systemPrompt(ctx context.Context, d *store.MaxDialog) string {
	site, _ := b.crm.SiteContent(ctx)
	var sb strings.Builder
	fmt.Fprintf(&sb, `Тебя зовут Олег, ты вежливый помощник компании «%s» (%s) в мессенджере MAX. Город: %s.
Твоя задача — принять заявку на выезд мастера. Можешь коротко поддержать разговор на любую тему,
ответить на вопрос об услугах, но каждый ответ мягко возвращай к заявке.

Правила:
- Пиши по-русски, коротко: 1–3 предложения, без markdown, без списков.
- У тебя нет интернета: погоды, новостей, курсов и прочих свежих данных ты не знаешь — так и скажи, не сочиняй.
- Не выдумывай цены и сроки сверх списка ниже: точную стоимость назовёт мастер после осмотра.
- Никогда не говори, что заявка принята или отправлена: это делает кнопка «Отправить заявку».
- Не спрашивай то, что уже известно. Спрашивай по одному: телефон, адрес, что случилось.
- Если искрит, пахнет гарью, дымится — первым делом посоветуй отключить автомат в щитке и не трогать провода.
- Не проси паспорт, оплату, коды из СМС и прочие лишние данные.

Отвечай JSON-объектом с отдельными полями:
- reply — только текст для клиента, без названий полей и служебных пометок;
- phone, address — только если клиент сам их написал в последнем сообщении, иначе "";
- problem — суть беды кратко своими словами, если клиент о ней рассказал, иначе "";
- wants_order — true, если клиенту нужен мастер или услуга.
`, site.SiteName, site.Tagline, orDefault(site.Contacts.City, "Балаково"))

	sb.WriteString("\nУслуги и цены:\n" + extraServices)
	for _, s := range site.Services {
		fmt.Fprintf(&sb, "- %s: %s (%s)\n", s.Title, s.Description, s.Price)
	}
	if site.Contacts.Phone != "" {
		fmt.Fprintf(&sb, "Телефон компании: %s. ", site.Contacts.Phone)
	}
	if site.Contacts.WorkHours != "" {
		fmt.Fprintf(&sb, "Часы работы: %s.", site.Contacts.WorkHours)
	}

	sb.WriteString("\n\nЧто уже известно о заявке:\n")
	fmt.Fprintf(&sb, "Имя: %s\nТелефон: %s\nАдрес: %s\nЧто случилось: %s\n",
		orDefault(d.Name, "—"), orDefault(d.Phone, "не указан"),
		orDefault(d.Address, "не указан"), orDefault(d.Problem, "не описано"))
	return sb.String()
}

// ------------------------------ Шаги ---------------------------------------

func (b *Bot) greet(ctx context.Context, d *store.MaxDialog) {
	site, _ := b.crm.SiteContent(ctx)
	hello := "Здравствуйте!"
	if d.Name != "" {
		hello = "Здравствуйте, " + d.Name + "!"
	}
	text := fmt.Sprintf("%s Это «%s» — %s.\n\nПомогу оставить заявку: мастер перезвонит, уточнит детали и приедет. "+
		"Нажмите кнопку или просто напишите, что случилось.",
		hello, orDefault(site.SiteName, "Фролов Системы"), strings.ToLower(orDefault(site.Tagline, "электрика и ремонт")))
	d.Step = stepIdle
	if d.Problem != "" {
		b.offerPending(ctx, d, hello)
		return
	}
	b.send(ctx, d.UserID, text, menuKeyboard())
}

// offerPending предлагает оформить то, о чём человек уже писал в общем чате.
func (b *Bot) offerPending(ctx context.Context, d *store.MaxDialog, hello string) {
	text := strings.TrimSpace(hello + " Это " + botName + ". Вы писали в общем чате: «" + clip(d.Problem, 300) + "». Оформим заявку?")
	b.send(ctx, d.UserID, text, keyboard(
		[]Button{callbackBtn("📝 Оформить заявку", cbOrderKeep)},
		[]Button{callbackBtn("✏️ Другое дело", cbOrder)},
	))
}

// startOrder начинает новую заявку. Телефон и адрес с прошлого раза
// сохраняются — на проверке их можно поменять одной кнопкой.
func (b *Bot) startOrder(d *store.MaxDialog, problem string) {
	d.Problem = clip(strings.TrimSpace(problem), 1500)
	d.Step = stepProblem
	d.History = nil
}

// continueOrder переходит к следующему вопросу после ответа клиента.
func (b *Bot) continueOrder(ctx context.Context, d *store.MaxDialog) {
	if d.Step == stepIdle && d.Problem == "" {
		// Прислал контакт или адрес до того, как начал заявку, — начинаем её.
		d.Step = stepProblem
	}
	b.ask(ctx, d, "")
}

// nextStep — первое, чего бот ещё не знает.
func nextStep(d *store.MaxDialog) string {
	switch {
	case d.Phone == "":
		return stepPhone
	case d.Address == "" && d.Problem != callMeProblem:
		return stepAddress
	case d.Problem == "":
		return stepProblem
	}
	return stepConfirm
}

// ask задаёт вопрос следующего шага; note — пояснение перед вопросом.
func (b *Bot) ask(ctx context.Context, d *store.MaxDialog, note string) {
	d.Step = nextStep(d)
	var text string
	switch d.Step {
	case stepProblem:
		text = "Что случилось или что нужно сделать?\n\nВыберите вариант кнопкой — или нажмите «✍️ Опишу своими словами» и напишите как есть."
	case stepPhone:
		text = "Как с вами связаться? Нажмите «Поделиться контактом» или напишите номер телефона."
	case stepAddress:
		text = "Куда приехать мастеру? Как удобнее указать адрес?\n\n📍 Отправить геопозицию — одним нажатием.\n✍️ Ввести вручную — улица, дом, квартира."
	case stepConfirm:
		text = "Проверьте заявку:\n\n" + summary(d) + "\n\nВсё верно?"
	}
	if note != "" {
		text = note + "\n\n" + text
	}
	b.send(ctx, d.UserID, text, stepKeyboard(d.Step))
}

// submit заводит заявку в CRM: клиента по номеру, заказ и саму заявку.
func (b *Bot) submit(ctx context.Context, d *store.MaxDialog) {
	if nextStep(d) != stepConfirm {
		b.ask(ctx, d, "Для заявки не хватает данных.")
		return
	}
	req, err := b.crm.CreateRequest(ctx, store.Request{
		Name:      orDefault(d.Name, "Клиент MAX"),
		Phone:     d.Phone,
		Message:   d.Problem,
		Address:   d.Address,
		Source:    "max",
		MaxUserID: d.UserID,
	})
	if err != nil {
		slog.Error("MAX: заявка не сохранилась", "user", d.UserID, "err", err)
		b.send(ctx, d.UserID, "Не получилось сохранить заявку — попробуйте ещё раз через минуту.",
			stepKeyboard(stepConfirm))
		return
	}
	slog.Info("MAX: принята заявка", "request", req.ID, "order", req.OrderID, "user", d.UserID)

	d.LastRequestID = req.ID
	d.Step, d.Problem, d.History = stepIdle, "", nil

	site, _ := b.crm.SiteContent(ctx)
	text := fmt.Sprintf("✅ Заявка №%d принята!\n\nМастер свяжется с вами по номеру %s и согласует время выезда.",
		req.ID, d.Phone)
	if site.Contacts.Phone != "" {
		text += "\nЕсли срочно — звоните: " + site.Contacts.Phone
	}
	b.send(ctx, d.UserID, text, keyboard([]Button{callbackBtn("📝 Новая заявка", cbOrder)}))
}

// NotifyStatus сообщает клиенту, что заявку взяли в работу или закрыли.
// Вызывается, когда статус меняют в приложении.
func (b *Bot) NotifyStatus(ctx context.Context, r store.Request) {
	if r.Source != "max" || r.MaxUserID == 0 {
		return
	}
	var text string
	switch r.Status {
	case "in_progress":
		text = fmt.Sprintf("🔧 Заявка №%d в работе. Мастер свяжется с вами по номеру %s.", r.ID, r.Phone)
	case "done":
		text = fmt.Sprintf("✅ Заявка №%d обработана. Спасибо, что выбрали нас!", r.ID)
	default:
		return
	}
	b.send(ctx, r.MaxUserID, text, keyboard([]Button{callbackBtn("📝 Новая заявка", cbOrder)}))
}

func (b *Bot) send(ctx context.Context, userID int64, text string, kb []OutAttachment) {
	if kb == nil {
		kb = []OutAttachment{}
	}
	if err := b.out.Send(ctx, userID, NewMessage{Text: clip(text, 4000), Attachments: kb}); err != nil {
		slog.Error("MAX: отправка сообщения", "user", userID, "err", err)
	}
}

func (b *Bot) pricesText(ctx context.Context) string {
	site, _ := b.crm.SiteContent(ctx)
	var sb strings.Builder
	sb.WriteString("Что мы делаем:\n")
	for _, s := range site.Services {
		fmt.Fprintf(&sb, "\n• %s — %s", s.Title, s.Price)
	}
	sb.WriteString("\n\nТочную стоимость мастер назовёт после осмотра. Оставить заявку?")
	return sb.String()
}

// ---------------------------- Клавиатуры -----------------------------------

func menuKeyboard() []OutAttachment {
	return keyboard(
		[]Button{callbackBtn("📝 Оставить заявку", cbOrder)},
		[]Button{callbackBtn("💡 Услуги и цены", cbPrices), callbackBtn("📞 Перезвоните мне", cbCallMe)},
	)
}

func stepKeyboard(step string) []OutAttachment {
	cancel := []Button{callbackBtn("✖ Отменить", cbCancel)}
	switch step {
	case stepProblem:
		rows := make([][]Button, 0, 8)
		for i := 0; i < len(problemChoices); i += 2 {
			row := []Button{callbackBtn(problemChoices[i], fmt.Sprintf("%s%d", cbProblemPref, i))}
			if i+1 < len(problemChoices) {
				row = append(row, callbackBtn(problemChoices[i+1], fmt.Sprintf("%s%d", cbProblemPref, i+1)))
			}
			rows = append(rows, row)
		}
		rows = append(rows, []Button{callbackBtn("✍️ Опишу своими словами", cbProblemOwn)})
		return keyboard(append(rows, cancel)...)
	case stepPhone:
		return keyboard([]Button{{Type: "request_contact", Text: "📱 Поделиться контактом"}}, cancel)
	case stepAddress:
		// Один выбор: геопозиция или адрес вручную.
		return keyboard(
			[]Button{{Type: "request_geo_location", Text: "📍 Отправить геопозицию"}},
			[]Button{callbackBtn("✍️ Ввести адрес вручную", cbAddrManual)},
			cancel,
		)
	case stepConfirm:
		return keyboard(
			[]Button{callbackBtn("✅ Отправить заявку", cbSend)},
			[]Button{callbackBtn("📞 Телефон", cbEditPhone), callbackBtn("📍 Адрес", cbEditAddress), callbackBtn("📝 Описание", cbEditProblem)},
			cancel,
		)
	}
	return menuKeyboard()
}

// ------------------------------ Мелочи -------------------------------------

func summary(d *store.MaxDialog) string {
	return fmt.Sprintf("👤 %s\n📞 %s\n📍 %s\n📝 %s",
		orDefault(d.Name, "—"), d.Phone, d.Address, d.Problem)
}

// phoneRe — кусок текста, похожий на номер: цифры вперемешку с пробелами,
// скобками и дефисами. Начинается и кончается цифрой, чтобы не прихватить
// соседние слова.
var phoneRe = regexp.MustCompile(`\+?\d[\d\s\-()]{8,}\d`)

// normalizePhone находит в тексте российский номер и приводит к +7 XXX XXX-XX-XX.
func normalizePhone(s string) string {
	_, phone := findPhone(s)
	return phone
}

// findPhone возвращает найденный кусок текста и номер из него.
func findPhone(s string) (match, phone string) {
	for _, m := range phoneRe.FindAllString(s, -1) {
		var digits []byte
		for i := 0; i < len(m); i++ {
			if m[i] >= '0' && m[i] <= '9' {
				digits = append(digits, m[i])
			}
		}
		if len(digits) == 11 && (digits[0] == '7' || digits[0] == '8') {
			digits = digits[1:]
		}
		if len(digits) != 10 {
			continue
		}
		// Российские номера начинаются с 3, 4, 8 или 9.
		if digits[0] != '9' && digits[0] != '8' && digits[0] != '3' && digits[0] != '4' {
			continue
		}
		return m, fmt.Sprintf("+7 %s %s-%s-%s", digits[0:3], digits[3:6], digits[6:8], digits[8:10])
	}
	return "", ""
}

var (
	vcfTel  = regexp.MustCompile(`(?im)^TEL[^:\r\n]*:([^\r\n]+)`)
	vcfName = regexp.MustCompile(`(?im)^FN[^:\r\n]*:([^\r\n]+)`)
)

// parseContact достаёт номер и имя из вложения «контакт»: vCard лежит
// в vcf_info, профиль MAX — в max_info.
func parseContact(raw []byte) (phone, name string) {
	var p struct {
		VcfInfo  string `json:"vcf_info"`
		VcfPhone string `json:"vcf_phone"`
		MaxInfo  *User  `json:"max_info"`
	}
	_ = json.Unmarshal(raw, &p)
	if m := vcfTel.FindStringSubmatch(p.VcfInfo); m != nil {
		phone = normalizePhone(m[1])
	}
	if phone == "" {
		phone = normalizePhone(p.VcfPhone)
	}
	if phone == "" {
		phone = normalizePhone(p.VcfInfo)
	}
	if m := vcfName.FindStringSubmatch(p.VcfInfo); m != nil {
		name = strings.TrimSpace(strings.Trim(m[1], `"`))
	}
	if name == "" && p.MaxInfo != nil {
		name = p.MaxInfo.DisplayName()
	}
	return phone, name
}

// buttonLabel — подпись нажатой кнопки для отметки под сообщением.
func buttonLabel(payload string) string {
	var i int
	if _, err := fmt.Sscanf(payload, cbProblemPref+"%d", &i); err == nil && i >= 0 && i < len(problemChoices) {
		return problemChoices[i]
	}
	for _, kb := range [][]OutAttachment{menuKeyboard(), stepKeyboard(stepConfirm), stepKeyboard(stepProblem), stepKeyboard(stepAddress)} {
		for _, row := range kb[0].Payload.(map[string]any)["buttons"].([][]Button) {
			for _, btn := range row {
				if btn.Payload == payload {
					return btn.Text
				}
			}
		}
	}
	if payload == cbOrder {
		return "📝 Новая заявка"
	}
	return "✓"
}

func isStartCommand(s string) bool {
	s = strings.ToLower(strings.TrimSpace(s))
	return s == "/start" || s == "start" || s == "начать" || s == "меню"
}

func appendProblem(old, add string) string {
	add = strings.TrimSpace(add)
	if old == "" {
		return clip(add, 1500)
	}
	return clip(old+". "+add, 1500)
}

// trimEmoji убирает значок перед текстом кнопки — в CRM он лишний.
func trimEmoji(s string) string {
	if i := strings.IndexByte(s, ' '); i > 0 && i <= 8 {
		return s[i+1:]
	}
	return s
}

func clip(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

func tail(h []store.ChatTurn, n int) []store.ChatTurn {
	if len(h) <= n {
		return h
	}
	return h[len(h)-n:]
}

func orDefault(s, def string) string {
	if strings.TrimSpace(s) == "" {
		return def
	}
	return s
}
