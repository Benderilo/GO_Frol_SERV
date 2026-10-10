package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

// MaxDialog — разговор одного человека с ботом в MAX: что он уже сообщил
// для заявки и какой вопрос ему задан сейчас.
type MaxDialog struct {
	UserID int64
	Name   string
	Phone  string
	// PhoneVerified — номер подтверждён MAX (свой контакт, а не набранный
	// текстом или чужая карточка). Нужен для акта сверки: там деньги клиента.
	PhoneVerified bool
	Address       string
	// Геопозиция из кнопки MAX; 0, 0 — не присылал. Адрес текстом при ней
	// не обязателен, но может уточнять подъезд и квартиру.
	Lat, Lon float64
	Problem  string
	// Step — какой ответ бот ждёт: phone, address, problem, confirm или пусто.
	Step string
	// History — последние реплики для ИИ; без ИИ не используется.
	History       []ChatTurn
	LastRequestID int64
	UpdatedAt     string
}

// ChatTurn — одна реплика: role — user или assistant.
type ChatTurn struct {
	Role string `json:"role"`
	Text string `json:"text"`
}

// MaxDialog возвращает разговор с человеком; для нового — пустой, с его id.
func (s *Store) MaxDialog(ctx context.Context, userID int64) (MaxDialog, error) {
	d := MaxDialog{UserID: userID}
	var history string
	err := s.db.QueryRowContext(ctx,
		`SELECT name, phone, phone_verified, address, lat, lon, problem, step, history, last_request_id, updated_at
		 FROM max_dialogs WHERE user_id = ?`, userID).
		Scan(&d.Name, &d.Phone, &d.PhoneVerified, &d.Address, &d.Lat, &d.Lon, &d.Problem, &d.Step, &history, &d.LastRequestID, &d.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return d, nil
	}
	if err != nil {
		return MaxDialog{}, err
	}
	// Испорченная история не повод терять заявку — начинаем её заново.
	_ = json.Unmarshal([]byte(history), &d.History)
	return d, nil
}

// SaveMaxDialog записывает разговор целиком.
func (s *Store) SaveMaxDialog(ctx context.Context, d MaxDialog) error {
	history, err := toJSON(d.History)
	if err != nil {
		return err
	}
	if d.History == nil {
		history = "[]"
	}
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO max_dialogs (user_id, name, phone, phone_verified, address, lat, lon, problem, step, history, last_request_id, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(user_id) DO UPDATE SET
		   name = excluded.name, phone = excluded.phone, phone_verified = excluded.phone_verified,
		   address = excluded.address, lat = excluded.lat, lon = excluded.lon,
		   problem = excluded.problem, step = excluded.step, history = excluded.history,
		   last_request_id = excluded.last_request_id, updated_at = excluded.updated_at`,
		d.UserID, d.Name, d.Phone, d.PhoneVerified, d.Address, d.Lat, d.Lon, d.Problem, d.Step, history, d.LastRequestID, now())
	return err
}
