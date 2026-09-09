package media

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	// MaxDocumentBytes — предел на файл архива. Больше снимка: сюда кладут
	// сканы и книги Excel, но памяти на сервере всё те же 955 МБ, поэтому
	// файл пишется потоком на диск, а не читается целиком.
	MaxDocumentBytes = 25 << 20 // 25 МБ

	// maxExtLen отсекает «расширения» вроде хвоста ссылки: в имени на диске
	// нужен короткий суффикс, а настоящее имя всё равно хранится в БД.
	maxExtLen = 12
)

var ErrEmptyFile = errors.New("файл пустой")

// SaveDocument кладёт файл на диск как есть — без пережатия и разбора формата.
// Архив принимает любые типы, и портить содержимое здесь нечем.
//
// Возвращается Saved с токеном, относительным путём и фактическим размером;
// поля изображения (ширина, высота) остаются нулевыми.
func (s *Storage) SaveDocument(r io.Reader, fileName string) (Saved, error) {
	token, err := randomToken()
	if err != nil {
		return Saved{}, err
	}

	now := time.Now()
	dir := filepath.Join(s.root, "files", now.Format("2006"), now.Format("01"))
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return Saved{}, err
	}

	path := filepath.Join(dir, token+documentExt(fileName))
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o640)
	if err != nil {
		return Saved{}, err
	}

	// Читаем на байт больше предела: так превышение видно, а лишнего в памяти нет.
	written, copyErr := io.Copy(f, io.LimitReader(r, MaxDocumentBytes+1))
	closeErr := f.Close()
	switch {
	case copyErr != nil:
		os.Remove(path)
		return Saved{}, copyErr
	case closeErr != nil:
		os.Remove(path)
		return Saved{}, closeErr
	case written > MaxDocumentBytes:
		os.Remove(path)
		return Saved{}, ErrTooLarge
	case written == 0:
		os.Remove(path)
		return Saved{}, ErrEmptyFile
	}

	return Saved{
		Token: token,
		Path:  relative(s.root, path),
		Size:  written,
	}, nil
}

// documentExt берёт из имени расширение и приводит его к безопасному виду:
// только буквы и цифры. Имя на диске собирается из токена, и всё, что могло
// бы увести запись в соседний каталог, отбрасывается.
func documentExt(fileName string) string {
	ext := strings.ToLower(filepath.Ext(filepath.Base(fileName)))
	if len(ext) < 2 || len(ext) > maxExtLen {
		return ""
	}
	for _, r := range ext[1:] {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') {
			return ""
		}
	}
	return ext
}
