// Package pdf печатает HTML-страницу в PDF headless-браузером. Документы
// свёрстаны для печати (@page A4), и браузер отдаёт ровно то, что клиент
// увидел бы на бумаге: своей PDF-вёрстки поддерживать не нужно.
package pdf

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// ErrUnavailable — браузер для печати не найден: PDF собрать нечем.
var ErrUnavailable = errors.New("печать в PDF недоступна: не найден chrome-headless-shell")

// Renderer печатает по одному документу за раз: на сервере с гигабайтом
// памяти два браузера разом — лишний риск, а документ печатается за секунды.
type Renderer struct {
	bin     string
	sem     chan struct{}
	timeout time.Duration
}

// New находит браузер: явный путь из настроек или известные места.
// Возвращает nil, если печатать нечем, — остальной сервер работает.
func New(bin string) *Renderer {
	if bin == "" {
		bin = detect()
	}
	if bin == "" {
		return nil
	}
	if _, err := os.Stat(bin); err != nil {
		return nil
	}
	return &Renderer{bin: bin, sem: make(chan struct{}, 1), timeout: 90 * time.Second}
}

// Где искать браузер: на сервере его кладёт установка, на рабочей
// машине под Windows сгодится Edge или Chrome.
func detect() string {
	candidates := []string{"/opt/frolov-crm/chrome/chrome-headless-shell"}
	if runtime.GOOS == "windows" {
		candidates = append(candidates,
			`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`,
			`C:\Program Files\Microsoft\Edge\Application\msedge.exe`,
			`C:\Program Files\Google\Chrome\Application\chrome.exe`,
		)
	}
	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			return c
		}
	}
	return ""
}

// Render печатает страницу в PDF.
func (r *Renderer) Render(ctx context.Context, html []byte) ([]byte, error) {
	if r == nil {
		return nil, ErrUnavailable
	}
	select {
	case r.sem <- struct{}{}:
		defer func() { <-r.sem }()
	case <-ctx.Done():
		return nil, ctx.Err()
	}

	dir, err := os.MkdirTemp("", "frolov-pdf-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)

	page := filepath.Join(dir, "doc.html")
	out := filepath.Join(dir, "doc.pdf")
	if err := os.WriteFile(page, html, 0o600); err != nil {
		return nil, err
	}

	ctx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, r.bin,
		"--headless",
		// Песочнице браузера нужны пространства имён, а служба их
		// запрещает. Печатаем только собственный HTML — без неё можно.
		"--no-sandbox",
		"--disable-gpu",
		"--disable-dev-shm-usage",
		"--disable-extensions",
		"--disable-crash-reporter",
		"--no-first-run",
		"--no-default-browser-check",
		"--no-pdf-header-footer",
		"--user-data-dir="+filepath.Join(dir, "profile"),
		"--print-to-pdf="+out,
		fileURL(page),
	)
	// Браузер пишет в домашний каталог, а у службы он только для чтения.
	cmd.Env = append(os.Environ(), "HOME="+dir)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("печать в PDF: %w: %s", err, tailOf(string(output), 400))
	}
	pdf, err := os.ReadFile(out)
	if err != nil || len(pdf) == 0 {
		return nil, fmt.Errorf("печать в PDF: браузер не создал файл: %s", tailOf(string(output), 400))
	}
	return pdf, nil
}

func fileURL(path string) string {
	p := filepath.ToSlash(path)
	if !strings.HasPrefix(p, "/") {
		p = "/" + p // C:/… → /C:/…
	}
	return (&url.URL{Scheme: "file", Path: p}).String()
}

func tailOf(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return "…" + s[len(s)-n:]
}
