package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"math/big"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

// Алфавит кода доступа: без похожих друг на друга символов,
// чтобы код можно было продиктовать по телефону.
const codeAlphabet = "ACEFHJKLMNPRTUVWXY34679"

const codeLength = 8

// GenerateAccessCode выдаёт клиенту новый код входа в кабинет.
// Открытый код возвращается ровно один раз — в базе только его хеш.
func (s *Store) GenerateAccessCode(ctx context.Context, clientID int64) (string, error) {
	if _, err := s.Client(ctx, clientID); err != nil {
		return "", err
	}

	code, err := randomCode()
	if err != nil {
		return "", err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(code), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}

	res, err := s.db.ExecContext(ctx,
		`UPDATE clients SET portal_code_hash = ?, portal_enabled = 1, updated_at = ? WHERE id = ?`,
		string(hash), now(), clientID)
	if err != nil {
		return "", err
	}
	if err := affected(res); err != nil {
		return "", err
	}
	return code, nil
}

// RevokeAccess закрывает клиенту вход в кабинет.
func (s *Store) RevokeAccess(ctx context.Context, clientID int64) error {
	res, err := s.db.ExecContext(ctx,
		`UPDATE clients SET portal_code_hash = '', portal_enabled = 0, updated_at = ? WHERE id = ?`,
		now(), clientID)
	if err != nil {
		return err
	}
	return affected(res)
}

// ClientByPhoneAndCode проверяет вход в кабинет.
// Телефон сравниваем по цифрам: клиент может ввести его в любом формате.
func (s *Store) ClientByPhoneAndCode(ctx context.Context, phone, code string) (Client, error) {
	digits := onlyDigits(phone)
	if digits == "" || strings.TrimSpace(code) == "" {
		return Client{}, ErrNotFound
	}

	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, phone, email, address, note, tag, created_at, updated_at,
		        portal_code_hash, portal_enabled, portal_last_login
		 FROM clients WHERE portal_enabled = 1 AND portal_code_hash != ''`)
	if err != nil {
		return Client{}, err
	}
	defer rows.Close()

	normalized := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(code), " ", ""))
	for rows.Next() {
		c, err := scanClientRow(rows)
		if err != nil {
			return Client{}, err
		}
		if !phoneMatches(c.Phone, digits) {
			continue
		}
		if bcrypt.CompareHashAndPassword([]byte(c.PortalCodeHash), []byte(normalized)) == nil {
			return c, nil
		}
	}
	if err := rows.Err(); err != nil {
		return Client{}, err
	}
	return Client{}, ErrNotFound
}

// ClientForPortal отдаёт клиента по id, только если доступ ещё открыт.
func (s *Store) ClientForPortal(ctx context.Context, id int64) (Client, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, phone, email, address, note, tag, created_at, updated_at,
		        portal_code_hash, portal_enabled, portal_last_login
		 FROM clients WHERE id = ? AND portal_enabled = 1`, id)
	if err != nil {
		return Client{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Client{}, ErrNotFound
	}
	return scanClientRow(rows)
}

func (s *Store) TouchPortalLogin(ctx context.Context, clientID int64) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE clients SET portal_last_login = ? WHERE id = ?`, now(), clientID)
	return err
}

// ClientOrders — заказы одного клиента, с фотографиями.
func (s *Store) ClientOrders(ctx context.Context, clientID int64) ([]Order, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT o.id, o.client_id, COALESCE(c.name, ''), o.title, o.description, o.status,
		        o.price, o.due_date, o.created_at, o.updated_at
		 FROM orders o LEFT JOIN clients c ON c.id = o.client_id
		 WHERE o.client_id = ?
		 ORDER BY o.updated_at DESC`, clientID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Order, 0, 8)
	for rows.Next() {
		o, err := scanOrder(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	for i := range out {
		photos, err := s.OrderPhotos(ctx, out[i].ID)
		if err != nil {
			return nil, err
		}
		out[i].Photos = photos
		out[i].PhotoCount = len(photos)
	}
	return out, nil
}

func scanClientRow(rows *sql.Rows) (Client, error) {
	var c Client
	var enabled int
	err := rows.Scan(&c.ID, &c.Name, &c.Phone, &c.Email, &c.Address, &c.Note, &c.Tag,
		&c.CreatedAt, &c.UpdatedAt, &c.PortalCodeHash, &enabled, &c.PortalLastLogin)
	if errors.Is(err, sql.ErrNoRows) {
		return Client{}, ErrNotFound
	}
	c.PortalEnabled = enabled == 1
	return c, err
}

// phoneMatches сравнивает номера по последним десяти цифрам:
// +7 (900) 000-00-00, 89000000000 и 79000000000 — один и тот же номер.
func phoneMatches(stored, inputDigits string) bool {
	a := tailDigits(onlyDigits(stored), 10)
	b := tailDigits(inputDigits, 10)
	return a != "" && a == b
}

func onlyDigits(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func tailDigits(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}

func randomCode() (string, error) {
	limit := big.NewInt(int64(len(codeAlphabet)))
	var b strings.Builder
	for i := 0; i < codeLength; i++ {
		n, err := rand.Int(rand.Reader, limit)
		if err != nil {
			return "", err
		}
		b.WriteByte(codeAlphabet[n.Int64()])
	}
	return b.String(), nil
}
