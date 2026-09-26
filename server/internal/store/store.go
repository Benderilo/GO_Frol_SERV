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
	s := &Store{db: db}

	// Порядок важен. Старую базу сначала дотягиваем до состояния, в котором
	// применима нынешняя схема: та создаёт индексы по колонкам, которых в
	// старой базе ещё нет, и, применённая первой, падает целиком — а вместе
	// с ней и весь сервер. Схема идёт второй и досоздаёт новые таблицы.
	if err := s.migrateColumns(ctx); err != nil {
		return nil, err
	}
	if err := s.rebuildDocuments(ctx); err != nil {
		return nil, err
	}
	if _, err := db.ExecContext(ctx, schemaSQL); err != nil {
		return nil, fmt.Errorf("применение схемы: %w", err)
	}
	if err := s.backfillClosedAt(ctx); err != nil {
		return nil, err
	}
	if err := s.backfillKopecks(ctx); err != nil {
		return nil, err
	}
	if err := s.migrateCashOps(ctx); err != nil {
		return nil, err
	}
	if err := s.ensureSiteContent(ctx); err != nil {
		return nil, err
	}
	if err := s.ensureCompany(ctx); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

// migrateColumns добавляет колонки, появившиеся после первого выпуска.
// CREATE TABLE IF NOT EXISTS их не создаёт, а ALTER без проверки упадёт
// на уже обновлённой базе — поэтому смотрим фактический список колонок.
//
// Таблицы, которых в базе ещё нет, пропускаем: на новом сервере их создаст
// схема, сразу с нужными колонками. Добавляя колонку, дописывайте её и сюда,
// и в schema.sql — тогда и новая база, и старая придут к одному виду.
type migratedColumn struct {
	table, column, ddl string
	// legacy — шаг только для старых баз: таблицы, к которой он относится,
	// в нынешней схеме уже нет. Такие шаги нужны, чтобы довести старую базу
	// до состояния, из которого её данные можно перенести дальше.
	legacy bool
}

// migratedColumns — колонки, появившиеся после первого выпуска. Каждая из них
// обязана быть и в schema.sql: список поднимает старую базу, схема создаёт
// новую, и разойтись они не должны — это проверяет отдельный тест.
func migratedColumns() []migratedColumn {
	return []migratedColumn{
		{"clients", "portal_code_hash", "TEXT NOT NULL DEFAULT ''", false},
		{"clients", "portal_enabled", "INTEGER NOT NULL DEFAULT 0", false},
		{"clients", "portal_last_login", "TEXT NOT NULL DEFAULT ''", false},
		{"clients", "inn", "TEXT NOT NULL DEFAULT ''", false},
		{"clients", "kpp", "TEXT NOT NULL DEFAULT ''", false},
		{"clients", "bank_name", "TEXT NOT NULL DEFAULT ''", false},
		{"clients", "bank_account", "TEXT NOT NULL DEFAULT ''", false},
		{"orders", "closed_at", "TEXT NOT NULL DEFAULT ''", false},
		{"orders", "price_kop", "INTEGER NOT NULL DEFAULT 0", false},
		{"cash_ops", "pair_id", "INTEGER REFERENCES cash_ops(id) ON DELETE SET NULL", false},
		{"cash_ops", "doc_id", "INTEGER REFERENCES documents(id) ON DELETE SET NULL", false},
		{"tasks", "parent_id", "INTEGER REFERENCES tasks(id) ON DELETE SET NULL", false},
		{"orders", "worker_id", "INTEGER REFERENCES workers(id) ON DELETE SET NULL", false},
		{"stock_moves", "cash_op_id", "INTEGER REFERENCES cash_ops(id) ON DELETE SET NULL", false},
		// Платежи переехали в cash_ops; колонку добавляем только затем,
		// чтобы перенести из неё суммы.
		{"payments", "amount_kop", "INTEGER NOT NULL DEFAULT 0", true},
	}
}

func (s *Store) migrateColumns(ctx context.Context) error {
	for _, c := range migratedColumns() {
		table, err := s.hasTable(ctx, c.table)
		if err != nil {
			return err
		}
		if !table {
			continue
		}
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

// backfillClosedAt: раньше момент закрытия заказа не фиксировался и месяц
// закрытия определялся по updated_at. Для уже завершённых заказов переносим
// эту дату в closed_at один раз — дальше её ведёт UpdateOrder.
func (s *Store) backfillClosedAt(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx,
		`UPDATE orders SET closed_at = updated_at WHERE status = 'done' AND closed_at = ''`); err != nil {
		return fmt.Errorf("миграция closed_at: %w", err)
	}
	return nil
}

// backfillKopecks переводит деньги из рублей с дробной частью в целые копейки.
// Старая колонка удаляется сразу: две колонки с одной и той же суммой —
// это две правды, и рано или поздно они разойдутся.
//
// Идемпотентно: на новой базе старых колонок нет и делать нечего.
func (s *Store) backfillKopecks(ctx context.Context) error {
	money := []struct{ table, old, new string }{
		{"orders", "price", "price_kop"},
		{"payments", "amount", "amount_kop"},
	}
	for _, m := range money {
		exists, err := s.hasColumn(ctx, m.table, m.old)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		// ROUND до целых копеек: 1999.99 * 100 в двоичной дроби даёт
		// 199998.99999..., и CAST без округления отнял бы копейку.
		stmt := fmt.Sprintf(
			"UPDATE %s SET %s = CAST(ROUND(%s * 100) AS INTEGER) WHERE %s = 0 AND %s <> 0",
			m.table, m.new, m.old, m.new, m.old)
		if _, err := s.db.ExecContext(ctx, stmt); err != nil {
			return fmt.Errorf("перенос %s.%s в копейки: %w", m.table, m.old, err)
		}
		if _, err := s.db.ExecContext(ctx,
			fmt.Sprintf("ALTER TABLE %s DROP COLUMN %s", m.table, m.old)); err != nil {
			return fmt.Errorf("удаление %s.%s после переноса: %w", m.table, m.old, err)
		}
	}
	return nil
}

// migrateCashOps переносит платежи по заказам в общую книгу денег.
// Раньше платежи лежали отдельной таблицей и умели только приходить;
// теперь это строки кассы со ссылкой на заказ, рядом с расходами.
//
// Идемпотентно: старой таблицы нет — переносить нечего.
func (s *Store) migrateCashOps(ctx context.Context) error {
	old, err := s.hasTable(ctx, "payments")
	if err != nil {
		return err
	}
	if !old {
		return nil
	}
	// Идентификаторы сохраняем: на них могли остаться ссылки в переписке
	// и в выгруженных книгах Excel.
	if _, err := s.db.ExecContext(ctx, `
		INSERT INTO cash_ops (id, direction, amount_kop, method, category, order_id, note, happened_at, created_at)
		SELECT id, 'in', amount_kop, 'cash', '', order_id, note, created_at, created_at FROM payments`); err != nil {
		return fmt.Errorf("перенос платежей в кассу: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `DROP TABLE payments`); err != nil {
		return fmt.Errorf("удаление старой таблицы платежей: %w", err)
	}
	return nil
}

// rebuildDocuments перестраивает таблицу документов до нынешнего вида:
// раньше это был журнал выданных номеров (только order_id/kind/year/number),
// теперь — полноценные документы со статусом, датой и замороженным снимком
// содержимого. ALTER здесь не помогает: порядок колонок и NOT NULL на
// order_id не изменить по частям, поэтому создаём таблицу заново и переносим
// данные. Идемпотентно: по признаку «нет колонки status».
//
// Выполняется до применения схемы: та создаёт индексы по колонкам, которых
// в старой таблице ещё нет, и, применённая первой, падает целиком.
func (s *Store) rebuildDocuments(ctx context.Context) error {
	exists, err := s.hasTable(ctx, "documents")
	if err != nil {
		return err
	}
	if !exists {
		return nil
	}
	migrated, err := s.hasColumn(ctx, "documents", "status")
	if err != nil {
		return err
	}
	if migrated {
		return nil
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		CREATE TABLE documents_new (
			id          INTEGER PRIMARY KEY AUTOINCREMENT,
			order_id    INTEGER REFERENCES orders(id) ON DELETE CASCADE,
			client_id   INTEGER REFERENCES clients(id) ON DELETE SET NULL,
			kind        TEXT    NOT NULL,
			status      TEXT    NOT NULL DEFAULT 'draft',
			year        INTEGER NOT NULL DEFAULT 0,
			number      INTEGER,
			title       TEXT    NOT NULL DEFAULT '',
			total_kop   INTEGER NOT NULL DEFAULT 0,
			doc_date    TEXT    NOT NULL DEFAULT '',
			period_from TEXT    NOT NULL DEFAULT '',
			period_to   TEXT    NOT NULL DEFAULT '',
			snapshot    TEXT    NOT NULL DEFAULT '',
			issued_at   TEXT    NOT NULL DEFAULT '',
			created_at  TEXT    NOT NULL,
			updated_at  TEXT    NOT NULL
		)`); err != nil {
		return fmt.Errorf("перестройка documents: %w", err)
	}
	// Ранее выданные номера остаются действующими документами. Клиента
	// подтягиваем из заказа — он нужен журналу и фильтрам.
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO documents_new
			(id, order_id, client_id, kind, status, year, number, title, total_kop,
			 doc_date, snapshot, issued_at, created_at, updated_at)
		SELECT d.id, d.order_id, o.client_id, d.kind, 'issued', d.year, d.number, '',
		       o.price_kop, substr(d.issued_at, 1, 10), '', d.issued_at, d.issued_at, d.issued_at
		FROM documents d
		JOIN orders o ON o.id = d.order_id`); err != nil {
		return fmt.Errorf("перенос documents: %w", err)
	}
	// Заказ мог быть удалён — каскад старой схемы уже унёс такие строки,
	// но подстраховка от «висящих» номеров не помешает.
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO documents_new
			(id, order_id, client_id, kind, status, year, number, issued_at, created_at, updated_at)
		SELECT d.id, d.order_id, NULL, d.kind, 'issued', d.year, d.number, d.issued_at, d.issued_at, d.issued_at
		FROM documents d
		WHERE NOT EXISTS (SELECT 1 FROM documents_new n WHERE n.id = d.id)`); err != nil {
		return fmt.Errorf("перенос documents без заказа: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `DROP TABLE documents`); err != nil {
		return fmt.Errorf("удаление старой documents: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `ALTER TABLE documents_new RENAME TO documents`); err != nil {
		return fmt.Errorf("переименование documents: %w", err)
	}
	return tx.Commit()
}

// hasTable отвечает, есть ли таблица в базе. Нужно до ALTER: у отсутствующей
// таблицы PRAGMA table_info молча возвращает пустой список, из-за чего
// колонка выглядела бы отсутствующей, а ALTER падал бы на нет таблицы.
func (s *Store) hasTable(ctx context.Context, table string) (bool, error) {
	var name string
	err := s.db.QueryRowContext(ctx,
		`SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?`, table).Scan(&name)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("проверка таблицы %s: %w", table, err)
	}
	return true, nil
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
