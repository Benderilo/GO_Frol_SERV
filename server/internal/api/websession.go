package api

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/golang-jwt/jwt/v5"
)

// Сессия веб-панели. Логин и пароль те же, что в приложении, — таблица
// пользователей одна. Но токен другой: приложение живёт с токеном на десять
// лет в защищённом хранилище телефона, а браузеру такой отдавать нельзя.
//
//   - Токен лежит в cookie HttpOnly: скрипт страницы (и чужой, если бы он
//     туда попал) прочитать его не может.
//   - SameSite=Strict: с чужого сайта браузер cookie не пришлёт.
//   - Каждый запрос панели несёт заголовок X-Frolov-Web. Чужая страница
//     поставить его не может — CORS этот заголовок не разрешает.
//   - В токене отпечаток хеша пароля: сменили пароль (в приложении или
//     здесь) — все сессии панели закрываются сразу.
const (
	webIssuer     = "frolov-web"
	webCookie     = "frolov_admin"
	webHeader     = "X-Frolov-Web"
	webSessionTTL = 12 * time.Hour
	webRememberTT = 30 * 24 * time.Hour
	// AdminPath — адрес панели. Нигде не публикуется: ни ссылкой на сайте,
	// ни в robots.txt (там его прочитал бы кто угодно).
	AdminPath = "/bender"
)

type webClaims struct {
	jwt.RegisteredClaims
	// Pwd — отпечаток хеша пароля на момент входа.
	Pwd string `json:"pwd"`
}

type webLoginRequest struct {
	Login    string `json:"login"`
	Password string `json:"password"`
	Remember bool   `json:"remember"`
}

func passwordPrint(u store.User) string {
	sum := sha256.Sum256([]byte("frolov-web:" + u.PasswordHash))
	return hex.EncodeToString(sum[:8])
}

func (a *API) handleWebLogin(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get(webHeader) == "" {
		writeError(w, http.StatusForbidden, "forbidden", "Вход только со страницы панели")
		return
	}
	var req webLoginRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	req.Login = trim(req.Login)
	if req.Login == "" || req.Password == "" {
		writeError(w, http.StatusBadRequest, "validation", "Укажите логин и пароль")
		return
	}
	user, err := a.store.CheckPassword(r.Context(), req.Login, req.Password)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusUnauthorized, "bad_credentials", "Неверный логин или пароль")
			return
		}
		writeStoreError(w, err)
		return
	}

	ttl := webSessionTTL
	if req.Remember {
		ttl = webRememberTT
	}
	expires := time.Now().Add(ttl)
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, webClaims{
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   strconvI64(user.ID),
			Issuer:    webIssuer,
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			ExpiresAt: jwt.NewNumericDate(expires),
		},
		Pwd: passwordPrint(user),
	}).SignedString(a.cfg.JWTSecret)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось открыть сессию")
		return
	}
	cookie := &http.Cookie{
		Name:     webCookie,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		Secure:   r.TLS != nil,
	}
	// Без «запомнить» cookie сессионная: закрыли браузер — вышли.
	if req.Remember {
		cookie.Expires = expires
	}
	http.SetCookie(w, cookie)
	writeJSON(w, http.StatusOK, map[string]any{"user": user, "expiresAt": expires.UTC().Format(time.RFC3339)})
}

func (a *API) handleWebLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name: webCookie, Value: "", Path: "/", MaxAge: -1,
		HttpOnly: true, SameSite: http.SameSiteStrictMode, Secure: r.TLS != nil,
	})
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// webUser проверяет сессию панели. needHeader — требовать заголовок панели:
// для всех запросов к API; картинки <img> его не шлют и проверяются без него
// (они только читают, а cookie с чужого сайта всё равно не придёт).
func (a *API) webUser(r *http.Request, needHeader bool) (store.User, bool) {
	cookie, err := r.Cookie(webCookie)
	if err != nil || cookie.Value == "" {
		return store.User{}, false
	}
	if needHeader && r.Header.Get(webHeader) == "" {
		return store.User{}, false
	}
	claims := &webClaims{}
	_, err = jwt.ParseWithClaims(cookie.Value, claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("неожиданный метод подписи")
		}
		return a.cfg.JWTSecret, nil
	}, jwt.WithIssuer(webIssuer), jwt.WithExpirationRequired())
	if err != nil {
		return store.User{}, false
	}
	id, err := parseI64(claims.Subject)
	if err != nil {
		return store.User{}, false
	}
	user, err := a.store.UserByID(r.Context(), id)
	if err != nil {
		return store.User{}, false
	}
	// Пароль сменили после входа — сессия больше не действует.
	if subtle.ConstantTimeCompare([]byte(claims.Pwd), []byte(passwordPrint(user))) != 1 {
		return store.User{}, false
	}
	return user, true
}

// handleAdminPage отдаёт страницу панели. Данные она берёт из API сама.
func (a *API) handleAdminPage(w http.ResponseWriter, _ *http.Request) {
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	// Поисковикам — не индексировать, чужим сайтам — не встраивать.
	h.Set("X-Robots-Tag", "noindex, nofollow, noarchive")
	h.Set("X-Frame-Options", "DENY")
	h.Set("Content-Security-Policy", strings.Join([]string{
		"default-src 'self'",
		"script-src 'self'",
		// Печатные формы документов приходят со своими встроенными стилями.
		"style-src 'self' 'unsafe-inline'",
		"img-src 'self' data: blob:",
		"frame-src 'self' blob:",
		"connect-src 'self'",
		"object-src 'none'",
		"base-uri 'none'",
		"form-action 'self'",
		"frame-ancestors 'none'",
	}, "; "))
	if err := a.tmpl.ExecuteTemplate(w, "admin.html", nil); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось отрисовать страницу")
	}
}
