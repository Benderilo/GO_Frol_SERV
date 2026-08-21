package api

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/golang-jwt/jwt/v5"
)

type ctxKey string

const ctxUserKey ctxKey = "user"

type loginRequest struct {
	Login    string `json:"login"`
	Password string `json:"password"`
}

type loginResponse struct {
	Token     string     `json:"token"`
	ExpiresAt string     `json:"expiresAt"`
	User      store.User `json:"user"`
}

type changePasswordRequest struct {
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

// issueToken выпускает HS256-токен со сроком жизни из конфигурации.
func (a *API) issueToken(u store.User) (string, time.Time, error) {
	expires := time.Now().Add(a.cfg.TokenTTL)
	claims := jwt.RegisteredClaims{
		Subject:   strconvI64(u.ID),
		Issuer:    "frolov-crm",
		IssuedAt:  jwt.NewNumericDate(time.Now()),
		ExpiresAt: jwt.NewNumericDate(expires),
	}
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(a.cfg.JWTSecret)
	return token, expires, err
}

func (a *API) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
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

	token, expires, err := a.issueToken(user)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось выпустить токен")
		return
	}
	writeJSON(w, http.StatusOK, loginResponse{
		Token:     token,
		ExpiresAt: expires.UTC().Format(time.RFC3339),
		User:      user,
	})
}

func (a *API) handleMe(w http.ResponseWriter, r *http.Request) {
	user, ok := userFrom(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "Требуется авторизация")
		return
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *API) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	user, ok := userFrom(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "Требуется авторизация")
		return
	}
	var req changePasswordRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if len(req.NewPassword) < 6 {
		writeError(w, http.StatusBadRequest, "validation", "Новый пароль должен быть не короче 6 символов")
		return
	}
	if _, err := a.store.CheckPassword(r.Context(), user.Login, req.CurrentPassword); err != nil {
		writeError(w, http.StatusForbidden, "bad_credentials", "Текущий пароль указан неверно")
		return
	}
	if err := a.store.ChangePassword(r.Context(), user.ID, req.NewPassword); err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// requireAuth — middleware, проверяющий Bearer-токен.
func (a *API) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		raw, found := strings.CutPrefix(header, "Bearer ")
		if !found || trim(raw) == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "Требуется авторизация")
			return
		}

		claims := &jwt.RegisteredClaims{}
		_, err := jwt.ParseWithClaims(trim(raw), claims, func(t *jwt.Token) (any, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, errors.New("неожиданный метод подписи")
			}
			return a.cfg.JWTSecret, nil
		}, jwt.WithIssuer("frolov-crm"), jwt.WithExpirationRequired())
		if err != nil {
			writeError(w, http.StatusUnauthorized, "bad_token", "Токен недействителен или истёк")
			return
		}

		id, err := parseI64(claims.Subject)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "bad_token", "Токен недействителен")
			return
		}
		user, err := a.store.UserByID(r.Context(), id)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "bad_token", "Пользователь не найден")
			return
		}

		ctx := context.WithValue(r.Context(), ctxUserKey, user)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func userFrom(ctx context.Context) (store.User, bool) {
	u, ok := ctx.Value(ctxUserKey).(store.User)
	return u, ok
}
