package store

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// BackupTo снимает целостную копию базы командой VACUUM INTO.
//
// Обычное копирование файла тут не годится: при journal_mode=WAL свежие
// страницы лежат в отдельном -wal, и копия без него отстаёт на несколько
// транзакций, а при копировании на ходу может оказаться и вовсе битой.
// VACUUM INTO делает снимок средствами самой SQLite, не останавливая
// работающий сервер и не мешая ему писать.
//
// Заодно копия выходит сжатой: VACUUM пересобирает страницы без дыр.
func BackupTo(ctx context.Context, dbPath, outPath string) error {
	if _, err := os.Stat(dbPath); err != nil {
		return fmt.Errorf("исходная база: %w", err)
	}
	if dir := filepath.Dir(outPath); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o750); err != nil {
			return fmt.Errorf("каталог для копии: %w", err)
		}
	}
	// VACUUM INTO отказывается писать в существующий файл.
	if err := os.Remove(outPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("очистка цели: %w", err)
	}

	// Ждём освобождения блокировки дольше обычного: копия снимается ночью,
	// но упереться в чужую запись и сдаться сразу — худший исход для бэкапа.
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(30000)", dbPath)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return fmt.Errorf("открытие базы: %w", err)
	}
	defer db.Close()

	// Путь подставляем литералом, а не параметром: VACUUM INTO принимает
	// выражение не во всех сборках SQLite, а сюда путь приходит из systemd,
	// не от клиента. Кавычку всё же удваиваем — на случай пути с апострофом.
	quoted := "'" + strings.ReplaceAll(outPath, "'", "''") + "'"
	if _, err := db.ExecContext(ctx, "VACUUM INTO "+quoted); err != nil {
		return fmt.Errorf("VACUUM INTO: %w", err)
	}
	return nil
}
