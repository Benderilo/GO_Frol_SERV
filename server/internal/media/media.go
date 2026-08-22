// Package media принимает загруженные изображения: проверяет, ужимает и кладёт на диск.
package media

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"os"
	"path/filepath"
	"time"

	"golang.org/x/image/draw"
)

const (
	// MaxUploadBytes — предел на один файл. На сервере 955 МБ памяти,
	// поэтому крупные снимки отсекаем до чтения в память.
	MaxUploadBytes = 8 << 20 // 8 МБ

	// maxPixels ограничивает разрешение: декодирование съедает ~4 байта
	// на пиксель, и 50-мегапиксельный файл положил бы процесс.
	maxPixels = 30_000_000

	maxSide   = 1600 // длинная сторона основного изображения
	thumbSide = 480  // длинная сторона превью
	jpegQual  = 82
)

var (
	ErrTooLarge     = errors.New("файл слишком большой")
	ErrUnsupported  = errors.New("поддерживаются только JPEG, PNG и WebP")
	ErrTooManyPixel = errors.New("слишком большое разрешение изображения")
)

// Saved описывает то, что легло на диск.
type Saved struct {
	Token     string
	Path      string
	ThumbPath string
	Mime      string
	Size      int64
	Width     int
	Height    int
}

// Storage кладёт файлы в подкаталоги вида uploads/2026/08.
type Storage struct {
	root string
}

func NewStorage(root string) (*Storage, error) {
	if err := os.MkdirAll(root, 0o750); err != nil {
		return nil, fmt.Errorf("создание каталога загрузок: %w", err)
	}
	return &Storage{root: root}, nil
}

// Root нужен обработчикам, чтобы построить абсолютный путь к файлу.
func (s *Storage) Root() string { return s.root }

// Save читает изображение, ужимает его и сохраняет вместе с превью.
// reader читается не более MaxUploadBytes+1 байт.
func (s *Storage) Save(reader io.Reader) (Saved, error) {
	raw, err := io.ReadAll(io.LimitReader(reader, MaxUploadBytes+1))
	if err != nil {
		return Saved{}, err
	}
	if len(raw) > MaxUploadBytes {
		return Saved{}, ErrTooLarge
	}

	// Сначала только заголовок: так узнаём размер, не разворачивая картинку.
	cfg, format, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil {
		return Saved{}, ErrUnsupported
	}
	if format != "jpeg" && format != "png" && format != "webp" {
		return Saved{}, ErrUnsupported
	}
	if cfg.Width*cfg.Height > maxPixels {
		return Saved{}, ErrTooManyPixel
	}

	src, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return Saved{}, ErrUnsupported
	}

	token, err := randomToken()
	if err != nil {
		return Saved{}, err
	}

	now := time.Now()
	dir := filepath.Join(s.root, now.Format("2006"), now.Format("01"))
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return Saved{}, err
	}

	main := resize(src, maxSide)
	mainPath := filepath.Join(dir, token+".jpg")
	if err := writeJPEG(mainPath, main); err != nil {
		return Saved{}, err
	}

	thumbPath := filepath.Join(dir, token+"_t.jpg")
	if err := writeJPEG(thumbPath, resize(src, thumbSide)); err != nil {
		os.Remove(mainPath)
		return Saved{}, err
	}

	info, err := os.Stat(mainPath)
	if err != nil {
		return Saved{}, err
	}

	bounds := main.Bounds()
	return Saved{
		Token: token,
		// В БД держим относительные пути: каталог данных можно перенести.
		Path:      relative(s.root, mainPath),
		ThumbPath: relative(s.root, thumbPath),
		Mime:      "image/jpeg",
		Size:      info.Size(),
		Width:     bounds.Dx(),
		Height:    bounds.Dy(),
	}, nil
}

// Remove стирает файлы снимка; отсутствующий файл ошибкой не считается.
func (s *Storage) Remove(paths ...string) {
	for _, p := range paths {
		if p == "" {
			continue
		}
		os.Remove(filepath.Join(s.root, filepath.FromSlash(p)))
	}
}

// Open отдаёт файл по относительному пути из БД.
func (s *Storage) Open(rel string) (*os.File, error) {
	if rel == "" {
		return nil, os.ErrNotExist
	}
	clean := filepath.Clean(filepath.FromSlash(rel))
	if filepath.IsAbs(clean) || len(clean) > 1 && clean[0] == '.' && clean[1] == '.' {
		return nil, os.ErrPermission
	}
	return os.Open(filepath.Join(s.root, clean))
}

// resize вписывает изображение в квадрат side×side, сохраняя пропорции.
// Картинки меньше предела не увеличиваем.
func resize(src image.Image, side int) image.Image {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= side && h <= side {
		return src
	}
	if w >= h {
		h = h * side / w
		w = side
	} else {
		w = w * side / h
		h = side
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, b, draw.Over, nil)
	return dst
}

func writeJPEG(path string, img image.Image) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o640)
	if err != nil {
		return err
	}
	defer f.Close()
	return jpeg.Encode(f, img, &jpeg.Options{Quality: jpegQual})
}

func relative(root, path string) string {
	rel, err := filepath.Rel(root, path)
	if err != nil {
		return path
	}
	return filepath.ToSlash(rel)
}

func randomToken() (string, error) {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}
