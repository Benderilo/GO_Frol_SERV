package api

import (
	"context"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Benderilo/GO_Frol_SERV/internal/config"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

func webServer(t *testing.T) (*httptest.Server, *store.Store) {
	t.Helper()
	dir := t.TempDir()
	st, err := store.Open(context.Background(), filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if _, err := st.EnsureAdmin(context.Background(), "boss", "secret-1"); err != nil {
		t.Fatal(err)
	}
	a, err := New(&config.Config{JWTSecret: []byte("test"), UploadsDir: filepath.Join(dir, "up")}, st, "test")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(a.Close)
	srv := httptest.NewServer(a.Handler())
	t.Cleanup(srv.Close)
	return srv, st
}

func webDo(t *testing.T, c *http.Client, method, url, body string, panel bool) int {
	t.Helper()
	req, _ := http.NewRequest(method, url, strings.NewReader(body))
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if panel {
		req.Header.Set(webHeader, "1")
	}
	res, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode
}

func TestWebSession(t *testing.T) {
	srv, _ := webServer(t)
	jar, _ := cookiejar.New(nil)
	c := &http.Client{Jar: jar}
	login := `{"login":"boss","password":"secret-1"}`

	// Вход — только со страницы панели и только с верным паролем.
	if code := webDo(t, c, "POST", srv.URL+"/api/v1/auth/web-login", login, false); code != http.StatusForbidden {
		t.Fatalf("вход без заголовка панели: %d", code)
	}
	if code := webDo(t, c, "POST", srv.URL+"/api/v1/auth/web-login", `{"login":"boss","password":"nope"}`, true); code != http.StatusUnauthorized {
		t.Fatalf("неверный пароль: %d", code)
	}
	if code := webDo(t, c, "POST", srv.URL+"/api/v1/auth/web-login", login, true); code != http.StatusOK {
		t.Fatalf("вход: %d", code)
	}

	// С cookie и заголовком — пускает; cookie без заголовка — нет
	// (так выглядел бы запрос, подделанный чужой страницей).
	if code := webDo(t, c, "GET", srv.URL+"/api/v1/admin/stats", "", true); code != http.StatusOK {
		t.Fatalf("API панели: %d", code)
	}
	if code := webDo(t, c, "GET", srv.URL+"/api/v1/admin/stats", "", false); code != http.StatusUnauthorized {
		t.Fatalf("cookie без заголовка: %d", code)
	}

	// Смена пароля закрывает сессию — пароль общий с приложением.
	if code := webDo(t, c, "POST", srv.URL+"/api/v1/auth/password",
		`{"currentPassword":"secret-1","newPassword":"secret-2"}`, true); code != http.StatusOK {
		t.Fatalf("смена пароля: %d", code)
	}
	if code := webDo(t, c, "GET", srv.URL+"/api/v1/admin/stats", "", true); code != http.StatusUnauthorized {
		t.Fatalf("сессия пережила смену пароля: %d", code)
	}
	if code := webDo(t, c, "POST", srv.URL+"/api/v1/auth/web-login", `{"login":"boss","password":"secret-2"}`, true); code != http.StatusOK {
		t.Fatalf("вход новым паролем: %d", code)
	}

	// Выход — cookie стёрта.
	webDo(t, c, "POST", srv.URL+"/api/v1/auth/web-logout", "", true)
	if code := webDo(t, c, "GET", srv.URL+"/api/v1/admin/stats", "", true); code != http.StatusUnauthorized {
		t.Fatalf("после выхода: %d", code)
	}
}

func TestAdminPageHidden(t *testing.T) {
	srv, _ := webServer(t)
	res, err := http.Get(srv.URL + AdminPath)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("страница панели: %d", res.StatusCode)
	}
	if !strings.Contains(res.Header.Get("X-Robots-Tag"), "noindex") || res.Header.Get("X-Frame-Options") != "DENY" ||
		!strings.Contains(res.Header.Get("Content-Security-Policy"), "script-src 'self'") {
		t.Fatalf("заголовки защиты: %v", res.Header)
	}
	// Адрес панели нигде не публикуется — в robots.txt его прочитал бы кто угодно.
	robots, err := http.Get(srv.URL + "/robots.txt")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(robots.Body)
	robots.Body.Close()
	if strings.Contains(string(body), AdminPath) {
		t.Fatal("адрес панели попал в robots.txt")
	}
}
