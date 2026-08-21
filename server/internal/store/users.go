package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"golang.org/x/crypto/bcrypt"
)

// EnsureAdmin создаёт администратора при первом запуске.
// Существующий пароль не перетирается — сменить его можно через API.
func (s *Store) EnsureAdmin(ctx context.Context, login, password string) (created bool, err error) {
	if _, err := s.UserByLogin(ctx, login); err == nil {
		return false, nil
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return false, fmt.Errorf("хеширование пароля: %w", err)
	}
	ts := now()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO users (login, password_hash, display_name, role, created_at, updated_at)
		 VALUES (?, ?, ?, 'admin', ?, ?)`,
		login, string(hash), "Администратор", ts, ts)
	if err != nil {
		return false, fmt.Errorf("создание администратора: %w", err)
	}
	return true, nil
}

func (s *Store) UserByLogin(ctx context.Context, login string) (User, error) {
	return s.scanUser(s.db.QueryRowContext(ctx,
		`SELECT id, login, password_hash, display_name, role, created_at, updated_at
		 FROM users WHERE login = ?`, login))
}

func (s *Store) UserByID(ctx context.Context, id int64) (User, error) {
	return s.scanUser(s.db.QueryRowContext(ctx,
		`SELECT id, login, password_hash, display_name, role, created_at, updated_at
		 FROM users WHERE id = ?`, id))
}

// CheckPassword сверяет пароль и возвращает пользователя.
func (s *Store) CheckPassword(ctx context.Context, login, password string) (User, error) {
	u, err := s.UserByLogin(ctx, login)
	if err != nil {
		return User{}, err
	}
	if err := bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)); err != nil {
		return User{}, ErrNotFound
	}
	return u, nil
}

func (s *Store) ChangePassword(ctx context.Context, id int64, newPassword string) error {
	hash, err := bcrypt.GenerateFromPassword([]byte(newPassword), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?`,
		string(hash), now(), id)
	if err != nil {
		return err
	}
	return affected(res)
}

func (s *Store) scanUser(row *sql.Row) (User, error) {
	var u User
	err := row.Scan(&u.ID, &u.Login, &u.PasswordHash, &u.DisplayName, &u.Role, &u.CreatedAt, &u.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	if err != nil {
		return User{}, err
	}
	return u, nil
}

func affected(res interface{ RowsAffected() (int64, error) }) error {
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}
