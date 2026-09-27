package maxbot

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Документ клиенту в MAX: PDF-файлом и тремя кнопками под ним. Ответ
// записывается к документу и ставит задачу, чтобы не потерялся.

const (
	cbDocPrefix = "doc:"      // doc:<действие>:<id отправки>
	stepDocNote = "docnote:"  // ждём причину отказа или вопрос: docnote:<id отправки>
	docOK       = "ok"        // согласен
	docNo       = "no"        // не устраивает
	docAsk      = "ask"       // есть вопрос
	docSkip     = "skip"      // отказ без объяснений
	maxFileName = 120         // длиннее MAX обрезает имя файла некрасиво
	notReadyErr = "not.ready" // файл ещё обрабатывается — отправку повторяем
	docTextHint = "Всё устраивает?"
)

// UploadFile загружает файл в MAX и возвращает токен для вложения.
// Загрузка двухшаговая: сначала адрес, потом сам файл по нему.
func (c *Client) UploadFile(ctx context.Context, name string, data []byte) (string, error) {
	var slot struct {
		URL   string `json:"url"`
		Token string `json:"token"`
	}
	if err := c.do(ctx, http.MethodPost, "/uploads", url.Values{"type": {"file"}}, nil, &slot); err != nil {
		return "", err
	}
	if slot.URL == "" {
		return "", fmt.Errorf("MAX: не выдал адрес для загрузки")
	}

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	h := textproto.MIMEHeader{}
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="data"; filename=%q`, name))
	h.Set("Content-Type", "application/pdf")
	part, err := mw.CreatePart(h)
	if err != nil {
		return "", err
	}
	if _, err := part.Write(data); err != nil {
		return "", err
	}
	if err := mw.Close(); err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, slot.URL, &body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", c.token)
	resp, err := c.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("MAX: загрузка файла: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode/100 != 2 {
		return "", fmt.Errorf("MAX: загрузка файла: %d %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var done struct {
		Token string `json:"token"`
	}
	_ = json.Unmarshal(raw, &done)
	if done.Token == "" {
		done.Token = slot.Token
	}
	if done.Token == "" {
		return "", fmt.Errorf("MAX: загрузка файла без токена: %s", strings.TrimSpace(string(raw)))
	}
	return done.Token, nil
}

// SendDocument отправляет клиенту PDF с кнопками ответа. Файл MAX
// обрабатывает не сразу — отправку повторяем, пока он не будет готов.
func (b *Bot) SendDocument(ctx context.Context, send store.DocumentSend, doc store.Document, pdf []byte) error {
	token, err := b.out.UploadFile(ctx, fileName(doc), pdf)
	if err != nil {
		return err
	}

	text := "📄 " + store.DocumentName(doc)
	if d := ruDate(doc.DocDate); d != "" {
		text += " от " + d
	}
	text += "\n\nПосмотрите, пожалуйста. " + docTextHint
	if doc.Kind == store.DocReconciliation {
		text = "📊 " + store.DocKindTitle(doc.Kind)
		if doc.Number > 0 {
			text += " № " + strconv.FormatInt(doc.Number, 10)
		}
		if f, t := ruDate(doc.PeriodFrom), ruDate(doc.PeriodTo); f != "" && t != "" {
			text += " за " + f + " – " + t
		}
		text += "\n\nПроверьте, пожалуйста. Всё сходится?"
	}
	attachments := append([]OutAttachment{{Type: "file", Payload: map[string]string{"token": token}}},
		docKeyboard(send.ID)...)

	wait := time.Second
	for attempt := 0; ; attempt++ {
		err = b.out.Send(ctx, send.MaxUserID, NewMessage{Text: text, Attachments: attachments})
		if err == nil || !strings.Contains(err.Error(), notReadyErr) || attempt >= 4 {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(wait):
		}
		wait *= 2
	}
}

// docKeyboard — кнопки ответа на документ: под самим файлом и в напоминании.
func docKeyboard(sendID int64) []OutAttachment {
	id := strconv.FormatInt(sendID, 10)
	return keyboard(
		[]Button{callbackBtn("✅ Согласен", cbDocPrefix+docOK+":"+id)},
		[]Button{callbackBtn("❌ Не устраивает", cbDocPrefix+docNo+":"+id), callbackBtn("💬 Есть вопрос", cbDocPrefix+docAsk+":"+id)},
	)
}

// onDocCallback — клиент нажал кнопку под документом.
func (b *Bot) onDocCallback(ctx context.Context, d *store.MaxDialog, payload string) {
	parts := strings.Split(strings.TrimPrefix(payload, cbDocPrefix), ":")
	if len(parts) != 2 {
		return
	}
	sendID, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil {
		return
	}

	switch parts[0] {
	case docOK:
		_, doc, err := b.crm.DocumentReply(ctx, sendID, d.UserID, store.ReplyAccepted, "")
		if err != nil {
			b.docReplyFailed(ctx, d, err)
			return
		}
		b.send(ctx, d.UserID, "✅ Спасибо! Передал мастеру, что «"+store.DocumentName(doc)+"» вас устраивает. Он свяжется с вами.", nil)
	case docNo, docAsk:
		reply, ask := store.ReplyDeclined, "Что не устраивает? Напишите — мастер пересчитает или предложит другой вариант."
		if parts[0] == docAsk {
			reply, ask = store.ReplyQuestion, "Напишите ваш вопрос — мастер ответит."
		}
		if _, _, err := b.crm.DocumentReply(ctx, sendID, d.UserID, reply, ""); err != nil {
			b.docReplyFailed(ctx, d, err)
			return
		}
		d.Step = stepDocNote + parts[1]
		b.send(ctx, d.UserID, ask, keyboard([]Button{callbackBtn("Без комментария", cbDocPrefix+docSkip+":"+parts[1])}))
	case docSkip:
		if d.Step == stepDocNote+parts[1] {
			d.Step = stepIdle
		}
		b.send(ctx, d.UserID, "Хорошо, передал мастеру — он свяжется с вами.", nil)
	}
}

// onDocNote — клиент объяснил, что не так, или задал вопрос.
func (b *Bot) onDocNote(ctx context.Context, d *store.MaxDialog, text string) {
	sendID, err := strconv.ParseInt(strings.TrimPrefix(d.Step, stepDocNote), 10, 64)
	d.Step = stepIdle
	if err != nil {
		return
	}
	if _, err := b.crm.DocumentNote(ctx, sendID, d.UserID, clip(text, 2000)); err != nil {
		b.docReplyFailed(ctx, d, err)
		return
	}
	b.send(ctx, d.UserID, "Спасибо, передал мастеру. Он свяжется с вами.", nil)
}

func (b *Bot) docReplyFailed(ctx context.Context, d *store.MaxDialog, err error) {
	slog.Error("MAX: ответ на документ не записан", "user", d.UserID, "err", err)
	b.send(ctx, d.UserID, "Не получилось записать ответ. Попробуйте ещё раз или позвоните нам.", nil)
}

// fileName — «Счёт на оплату № 12.pdf»: так файл и будет назван у клиента.
func fileName(doc store.Document) string {
	name := doc.KindTitle
	if name == "" {
		name = store.DocKindTitle(doc.Kind)
	}
	if doc.Number > 0 {
		name += fmt.Sprintf(" № %d", doc.Number)
	}
	name = strings.NewReplacer("/", "-", `\`, "-", `"`, "", ":", "-").Replace(name)
	return clip(name, maxFileName) + ".pdf"
}

// ruDate — 2026-09-27 → 27.09.2026.
func ruDate(iso string) string {
	t, err := time.Parse("2006-01-02", iso)
	if err != nil {
		return ""
	}
	return t.Format("02.01.2006")
}
