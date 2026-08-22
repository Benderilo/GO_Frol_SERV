// Package store — доступ к данным поверх SQLite (драйвер на чистом Go, без cgo).
package store

import (
	"context"
	"database/sql"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaSQL string

var ErrNotFound = errors.New("не найдено")

type Store struct {
	db *sql.DB
}

// Open открывает (и при необходимости создаёт) базу, применяет схему.
func Open(ctx context.Context, path string) (*Store, error) {
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o750); err != nil {
			return nil, fmt.Errorf("создание каталога БД: %w", err)
		}
	}

	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("открытие БД: %w", err)
	}
	// SQLite не любит параллельную запись — держим один писатель.
	db.SetMaxOpenConns(1)
	db.SetConnMaxLifetime(0)

	if err := db.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("проверка соединения с БД: %w", err)
	}
	if _, err := db.ExecContext(ctx, schemaSQL); err != nil {
		return nil, fmt.Errorf("применение схемы: %w", err)
	}

	s := &Store{db: db}
	if err := s.migrate(ctx); err != nil {
		return nil, err
	}
	if err := s.ensureSiteContent(ctx); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

// migrate добавляет колонки, появившиеся после первого выпуска.
// CREATE TABLE IF NOT EXISTS их не создаёт, а ALTER без проверки упадёт
// на уже обновлённой базе — поэтому смотрим фактический список колонок.
func (s *Store) migrate(ctx context.Context) error {
	columns := []struct{ table, column, ddl string }{
		{"clients", "portal_code_hash", "TEXT NOT NULL DEFAULT ''"},
		{"clients", "portal_enabled", "INTEGER NOT NULL DEFAULT 0"},
		{"clients", "portal_last_login", "TEXT NOT NULL DEFAULT ''"},
	}
	for _, c := range columns {
		exists, err := s.hasColumn(ctx, c.table, c.column)
		if err != nil {
			return err
		}
		if exists {
			continue
		}
		stmt := fmt.Sprintf("ALTER TABLE %s ADD COLUMN %s %s", c.table, c.column, c.ddl)
		if _, err := s.db.ExecContext(ctx, stmt); err != nil {
			return fmt.Errorf("миграция %s.%s: %w", c.table, c.column, err)
		}
	}
	return nil
}

func (s *Store) hasColumn(ctx context.Context, table, column string) (bool, error) {
	rows, err := s.db.QueryContext(ctx, fmt.Sprintf("PRAGMA table_info(%s)", table))
	if err != nil {
		return false, err
	}
	defer rows.Close()

	for rows.Next() {
		var (
			cid       int
			name      string
			ctype     string
			notNull   int
			dfltValue sql.NullString
			pk        int
		)
		if err := rows.Scan(&cid, &name, &ctype, &notNull, &dfltValue, &pk); err != nil {
			return false, err
		}
		if name == column {
			return true, nil
		}
	}
	return false, rows.Err()
}

func now() string { return time.Now().UTC().Format(time.RFC3339) }

func toJSON(v any) (string, error) {
	b, err := json.Marshal(v)
	return string(b), err
}
