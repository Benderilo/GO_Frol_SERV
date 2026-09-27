// Package maxbot — бот «Фролов Системы» в мессенджере MAX: принимает заявки
// кнопками, а если подключён ИИ — ещё и разговаривает, сводя разговор к заявке.
package maxbot

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ------------------------------ Входящие -----------------------------------

// Update — событие от MAX. Заполнены только поля своего типа.
type Update struct {
	UpdateType string    `json:"update_type"` // message_created | message_callback | bot_started
	Timestamp  int64     `json:"timestamp"`
	Message    *Message  `json:"message,omitempty"`
	Callback   *Callback `json:"callback,omitempty"`
	// bot_started: кто нажал «Начать» и с какой меткой из ссылки.
	ChatID  int64  `json:"chat_id,omitempty"`
	User    *User  `json:"user,omitempty"`
	Payload string `json:"payload,omitempty"`
}

type User struct {
	UserID    int64  `json:"user_id"`
	FirstName string `json:"first_name"`
	LastName  string `json:"last_name"`
	Name      string `json:"name"`
	Username  string `json:"username"`
	IsBot     bool   `json:"is_bot"`
}

// DisplayName — как обращаться к человеку.
func (u User) DisplayName() string {
	if n := strings.TrimSpace(u.FirstName + " " + u.LastName); n != "" {
		return n
	}
	return strings.TrimSpace(u.Name)
}

type Message struct {
	Sender    *User       `json:"sender,omitempty"`
	Recipient Recipient   `json:"recipient"`
	Body      MessageBody `json:"body"`
}

type Recipient struct {
	ChatID   int64  `json:"chat_id"`
	ChatType string `json:"chat_type"` // dialog | chat | channel
	UserID   int64  `json:"user_id"`
}

type MessageBody struct {
	Mid         string       `json:"mid"`
	Text        string       `json:"text"`
	Attachments []Attachment `json:"attachments"`
}

// Attachment — вложение входящего сообщения. У контакта полезное лежит
// в payload (vcf_info), у геопозиции — прямо в полях latitude/longitude.
type Attachment struct {
	Type      string          `json:"type"`
	Payload   json.RawMessage `json:"payload,omitempty"`
	Latitude  float64         `json:"latitude,omitempty"`
	Longitude float64         `json:"longitude,omitempty"`
}

type Callback struct {
	CallbackID string `json:"callback_id"`
	Payload    string `json:"payload"`
	User       User   `json:"user"`
}

// ------------------------------ Исходящие ----------------------------------

// NewMessage — тело отправки и правки. Attachments без omitempty: пустой
// массив при правке снимает старую клавиатуру, а отсутствие поля — нет.
type NewMessage struct {
	Text        string          `json:"text"`
	Attachments []OutAttachment `json:"attachments"`
}

type OutAttachment struct {
	Type    string `json:"type"`
	Payload any    `json:"payload"`
}

// Button — кнопка клавиатуры под сообщением.
type Button struct {
	Type    string `json:"type"` // callback | request_contact | request_geo_location | link
	Text    string `json:"text"`
	Payload string `json:"payload,omitempty"`
	URL     string `json:"url,omitempty"`
}

func callbackBtn(text, payload string) Button {
	return Button{Type: "callback", Text: text, Payload: payload}
}

// keyboard собирает клавиатуру из рядов; пустые ряды выбрасываются.
func keyboard(rows ...[]Button) []OutAttachment {
	clean := make([][]Button, 0, len(rows))
	for _, r := range rows {
		if len(r) > 0 {
			clean = append(clean, r)
		}
	}
	if len(clean) == 0 {
		return []OutAttachment{}
	}
	return []OutAttachment{{
		Type:    "inline_keyboard",
		Payload: map[string]any{"buttons": clean},
	}}
}

// -------------------------------- Клиент -----------------------------------

// Client ходит в Bot API MAX. Токен — в заголовке Authorization.
type Client struct {
	base  string
	token string
	http  *http.Client
}

func NewClient(base, token string) *Client {
	return &Client{
		base:  strings.TrimRight(base, "/"),
		token: token,
		// Долгий опрос держит соединение до 30 секунд — берём с запасом.
		http: &http.Client{Timeout: 45 * time.Second},
	}
}

// Send пишет человеку в личный диалог с ботом.
func (c *Client) Send(ctx context.Context, userID int64, msg NewMessage) error {
	q := url.Values{"user_id": {strconv.FormatInt(userID, 10)}}
	return c.do(ctx, http.MethodPost, "/messages", q, msg, nil)
}

// Answer отвечает на нажатие кнопки: правит сообщение, под которым она была.
func (c *Client) Answer(ctx context.Context, callbackID string, msg *NewMessage) error {
	body := map[string]any{}
	if msg != nil {
		body["message"] = msg
	}
	q := url.Values{"callback_id": {callbackID}}
	return c.do(ctx, http.MethodPost, "/answers", q, body, nil)
}

// Subscribe включает доставку событий на наш адрес. Повторный вызов с тем же
// адресом безопасен — MAX просто обновляет подписку.
func (c *Client) Subscribe(ctx context.Context, hookURL, secret string) error {
	body := map[string]any{
		"url":          hookURL,
		"secret":       secret,
		"update_types": updateTypes,
	}
	return c.do(ctx, http.MethodPost, "/subscriptions", nil, body, nil)
}

// Subscribed проверяет, что подписка на адрес жива: после восьми часов
// недоступности MAX снимает её сам.
func (c *Client) Subscribed(ctx context.Context, hookURL string) (bool, error) {
	var out struct {
		Subscriptions []struct {
			URL string `json:"url"`
		} `json:"subscriptions"`
	}
	if err := c.do(ctx, http.MethodGet, "/subscriptions", nil, nil, &out); err != nil {
		return false, err
	}
	for _, s := range out.Subscriptions {
		if s.URL == hookURL {
			return true, nil
		}
	}
	return false, nil
}

// Updates — долгий опрос для запуска без домена (локальная отладка).
func (c *Client) Updates(ctx context.Context, marker *int64) ([]Update, *int64, error) {
	q := url.Values{
		"timeout": {"30"},
		"types":   {strings.Join(updateTypes, ",")},
	}
	if marker != nil {
		q.Set("marker", strconv.FormatInt(*marker, 10))
	}
	var out struct {
		Updates []Update `json:"updates"`
		Marker  *int64   `json:"marker"`
	}
	if err := c.do(ctx, http.MethodGet, "/updates", q, nil, &out); err != nil {
		return nil, marker, err
	}
	if out.Marker == nil {
		out.Marker = marker
	}
	return out.Updates, out.Marker, nil
}

var updateTypes = []string{"message_created", "message_callback", "bot_started"}

func (c *Client) do(ctx context.Context, method, path string, q url.Values, in, out any) error {
	var body io.Reader
	if in != nil {
		raw, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(raw)
	}
	target := c.base + path
	if len(q) > 0 {
		target += "?" + q.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, method, target, body)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", c.token)
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("MAX %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("MAX %s %s: %d %s", method, path, resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	// На часть методов MAX отвечает 200 с success=false — это тоже ошибка.
	var status struct {
		Success *bool  `json:"success"`
		Message string `json:"message"`
	}
	if json.Unmarshal(raw, &status) == nil && status.Success != nil && !*status.Success {
		return fmt.Errorf("MAX %s %s: %s", method, path, status.Message)
	}
	if out != nil {
		if err := json.Unmarshal(raw, out); err != nil {
			return fmt.Errorf("MAX %s %s: разбор ответа: %w", method, path, err)
		}
	}
	return nil
}
