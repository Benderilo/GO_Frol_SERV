package store

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"
)

// Воспроизводит поломку боевого сервера: база со старой таблицей orders,
// в которой ещё нет closed_at. Схема создаёт индекс по этой колонке, поэтому
// применять её раньше миграции нельзя — сервер падал на старте с
// «применение схемы: no such column: closed_at» и уходил в цикл перезапусков.
func TestOpenUpgradesOldDatabase(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "old.db")

	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatalf("создание старой базы: %v", err)
	}
	// Таблица orders ровно в том виде, в котором она жила до closed_at.
	if _, err := db.ExecContext(ctx, `CREATE TABLE orders (
		id          INTEGER PRIMARY KEY AUTOINCREMENT,
		client_id   INTEGER,
		title       TEXT    NOT NULL,
		description TEXT    NOT NULL DEFAULT '',
		status      TEXT    NOT NULL DEFAULT 'new',
		price       REAL    NOT NULL DEFAULT 0,
		due_date    TEXT    NOT NULL DEFAULT '',
		created_at  TEXT    NOT NULL,
		updated_at  TEXT    NOT NULL
	)`); err != nil {
		t.Fatalf("создание таблицы orders: %v", err)
	}
	// Цена с копейками: именно на ней ломается перенос, если округлять неверно.
	if _, err := db.ExecContext(ctx,
		`INSERT INTO orders (title, status, price, created_at, updated_at)
		 VALUES ('старый заказ', 'done', 1999.99, '2026-01-01T00:00:00Z', '2026-02-03T09:30:00Z')`); err != nil {
		t.Fatalf("вставка заказа: %v", err)
	}
	if _, err := db.ExecContext(ctx, `CREATE TABLE payments (
		id         INTEGER PRIMARY KEY AUTOINCREMENT,
		order_id   INTEGER NOT NULL,
		amount     REAL    NOT NULL,
		note       TEXT    NOT NULL DEFAULT '',
		created_at TEXT    NOT NULL
	)`); err != nil {
		t.Fatalf("создание таблицы payments: %v", err)
	}
	if _, err := db.ExecContext(ctx,
		`INSERT INTO payments (order_id, amount, note, created_at)
		 VALUES (1, 500.05, 'аванс', '2026-01-15T00:00:00Z')`); err != nil {
		t.Fatalf("вставка платежа: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("закрытие старой базы: %v", err)
	}

	st, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open на базе прошлой версии: %v", err)
	}
	defer st.Close()

	// Колонка добавилась, и у уже завершённого заказа дата закрытия перенесена.
	var closed string
	if err := st.db.QueryRowContext(ctx,
		`SELECT closed_at FROM orders WHERE title = 'старый заказ'`).Scan(&closed); err != nil {
		t.Fatalf("чтение closed_at: %v", err)
	}
	if closed != "2026-02-03T09:30:00Z" {
		t.Errorf("closed_at не перенесён из updated_at: %q", closed)
	}

	// А таблицы, которых в старой базе не было, досоздала схема.
	for _, table := range []string{"cash_ops", "tasks", "audit_log", "order_photos", "company"} {
		ok, err := st.hasTable(ctx, table)
		if err != nil {
			t.Fatalf("проверка таблицы %s: %v", table, err)
		}
		if !ok {
			t.Errorf("таблица %s не создана", table)
		}
	}

	// Деньги переехали в копейки без потери последней копейки.
	var priceKop, amountKop int64
	if err := st.db.QueryRowContext(ctx,
		`SELECT price_kop FROM orders WHERE title = 'старый заказ'`).Scan(&priceKop); err != nil {
		t.Fatalf("чтение price_kop: %v", err)
	}
	if priceKop != 199_999 {
		t.Errorf("1999.99 ₽ превратились в %d копеек вместо 199999", priceKop)
	}
	if err := st.db.QueryRowContext(ctx,
		`SELECT amount_kop FROM cash_ops WHERE note = 'аванс'`).Scan(&amountKop); err != nil {
		t.Fatalf("чтение amount_kop: %v", err)
	}
	if amountKop != 50_005 {
		t.Errorf("500.05 ₽ превратились в %d копеек вместо 50005", amountKop)
	}

	// Платежи переехали в кассу вместе с суммой, старая таблица удалена.
	oldTable, err := st.hasTable(ctx, "payments")
	if err != nil {
		t.Fatalf("проверка таблицы payments: %v", err)
	}
	if oldTable {
		t.Error("таблица payments осталась после переноса в кассу")
	}

	// Старая рублёвая колонка удалена: две колонки с одной суммой — две правды.
	for _, c := range []struct{ table, column string }{{"orders", "price"}} {
		left, err := st.hasColumn(ctx, c.table, c.column)
		if err != nil {
			t.Fatalf("проверка %s.%s: %v", c.table, c.column, err)
		}
		if left {
			t.Errorf("колонка %s.%s осталась после переноса в копейки", c.table, c.column)
		}
	}
}

// На новом сервере база создаётся с нуля: миграции пропускаются, схема
// поднимает всё сама. Проверяем, что новый порядок не сломал и этот путь.
func TestOpenCreatesFreshDatabase(t *testing.T) {
	ctx := context.Background()
	st, err := Open(ctx, filepath.Join(t.TempDir(), "fresh.db"))
	if err != nil {
		t.Fatalf("Open на пустом каталоге: %v", err)
	}
	defer st.Close()

	for _, table := range []string{"users", "clients", "orders", "cash_ops", "tasks", "audit_log", "company"} {
		ok, err := st.hasTable(ctx, table)
		if err != nil {
			t.Fatalf("проверка таблицы %s: %v", table, err)
		}
		if !ok {
			t.Errorf("таблица %s не создана", table)
		}
	}
	ok, err := st.hasColumn(ctx, "orders", "closed_at")
	if err != nil {
		t.Fatalf("проверка колонки closed_at: %v", err)
	}
	if !ok {
		t.Error("в новой базе нет orders.closed_at")
	}
}

// Реквизиты — единственная строка в базе, и читаться они должны с подсказками
// из заготовки: у сохранённого раньше JSON новых полей нет, и без слияния
// с DefaultCompany они приезжали бы пустыми.
func TestCompanyRoundTrip(t *testing.T) {
	ctx := context.Background()
	st, err := Open(ctx, filepath.Join(t.TempDir(), "company.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer st.Close()

	got, err := st.Company(ctx)
	if err != nil {
		t.Fatalf("чтение заготовки: %v", err)
	}
	if got.TaxNote != "НДС не облагается" {
		t.Errorf("подсказка про налог потерялась: %q", got.TaxNote)
	}
	if got.Filled() {
		t.Error("незаполненные реквизиты не должны считаться готовыми к печати")
	}

	got.ShortName = "ИП Фролов А. В."
	got.INN = "0012345678"
	got.BankAccount = "40802810900000000123"
	saved, err := st.SaveCompany(ctx, got)
	if err != nil {
		t.Fatalf("сохранение: %v", err)
	}
	if !saved.Filled() {
		t.Error("наименование и ИНН заданы, а Filled() говорит обратное")
	}
	// Ведущий ноль в ИНН обязан выжить: это идентификатор, а не число.
	if saved.INN != "0012345678" {
		t.Errorf("ИНН изменился при сохранении: %q", saved.INN)
	}
	if saved.UpdatedAt == "" {
		t.Error("время правки не проставлено")
	}

	reread, err := st.Company(ctx)
	if err != nil {
		t.Fatalf("повторное чтение: %v", err)
	}
	if reread.ShortName != "ИП Фролов А. В." || reread.BankAccount != "40802810900000000123" {
		t.Errorf("после перечитывания данные разошлись: %+v", reread)
	}
}

// Схема обязана описывать текущий вид базы целиком. Список миграций нужен
// только для старых баз, и колонку легко дописать в него, забыв про схему, —
// тогда новый сервер поднимется без этой колонки и сломается на первом же
// запросе. Проверяем, что в новой базе есть всё, что знает миграция.
func TestFreshDatabaseHasEveryMigratedColumn(t *testing.T) {
	ctx := context.Background()
	st, err := Open(ctx, filepath.Join(t.TempDir(), "columns.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer st.Close()

	for _, c := range migratedColumns() {
		if c.legacy {
			// Шаг для старых баз: его таблицы в нынешней схеме быть не должно.
			// Проверяем и это — иначе опечатка в пометке осталась бы незамеченной.
			exists, err := st.hasTable(ctx, c.table)
			if err != nil {
				t.Fatalf("проверка таблицы %s: %v", c.table, err)
			}
			if exists {
				t.Errorf("таблица %s помечена устаревшей, но схема её создаёт", c.table)
			}
			continue
		}
		ok, err := st.hasColumn(ctx, c.table, c.column)
		if err != nil {
			t.Fatalf("проверка %s.%s: %v", c.table, c.column, err)
		}
		if !ok {
			t.Errorf("в новой базе нет %s.%s — колонка есть в списке миграций, но не в schema.sql",
				c.table, c.column)
		}
	}
}
