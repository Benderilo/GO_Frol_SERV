package api

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/golang-jwt/jwt/v5"
)

const (
	portalIssuer     = "frolov-portal"
	portalCookieName = "frolov_portal"
	portalTTL        = 30 * 24 * time.Hour
)

type portalLoginRequest struct {
	Phone string `json:"phone"`
	Code  string `json:"code"`
}

type portalClient struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Phone   string `json:"phone"`
	Email   string `json:"email"`
	Address string `json:"address"`
}

// ---------------------------- Вход и выход ---------------------------------

func (a *API) handlePortalLogin(w http.ResponseWriter, r *http.Request) {
	var req portalLoginRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if trim(req.Phone) == "" || trim(req.Code) == "" {
		writeError(w, http.StatusBadRequest, "validation", "Укажите телефон и код доступа")
		return
	}

	client, err := a.store.ClientByPhoneAndCode(r.Context(), req.Phone, req.Code)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusUnauthorized, "bad_credentials",
				"Телефон или код не подошли. Код выдаёт менеджер.")
			return
		}
		writeStoreError(w, err)
		return
	}

	token, expires, err := a.issuePortalToken(client.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось открыть сессию")
		return
	}
	if err := a.store.TouchPortalLogin(r.Context(), client.ID); err != nil {
		writeStoreError(w, err)
		return
	}

	http.SetCookie(w, &http.Cookie{
		Name:     portalCookieName,
		Value:    token,
		Path:     "/",
		Expires:  expires,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		// Secure включаем только на https: иначе на http кука не сохранится.
		Secure: r.TLS != nil,
	})
	writeJSON(w, http.StatusOK, map[string]any{"client": publicClient(client)})
}

func (a *API) handlePortalLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name:     portalCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handlePortalMe отдаёт клиенту его карточку и его заказы с фотографиями.
func (a *API) handlePortalMe(w http.ResponseWriter, r *http.Request) {
	clientID, ok := a.portalClientID(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "Войдите в кабинет")
		return
	}

	client, err := a.store.ClientForPortal(r.Context(), clientID)
	if err != nil {
		// Доступ могли отозвать, пока сессия жила.
		writeError(w, http.StatusUnauthorized, "revoked", "Доступ в кабинет закрыт")
		return
	}

	orders, err := a.store.ClientOrders(r.Context(), clientID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	for i := range orders {
		orders[i].Photos = withURLs(orders[i].Photos)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"client": publicClient(client),
		"orders": orders,
	})
}

// ------------------------------ Служебное ----------------------------------

func (a *API) issuePortalToken(clientID int64) (string, time.Time, error) {
	expires := time.Now().Add(portalTTL)
	claims := jwt.RegisteredClaims{
		Subject:   strconvI64(clientID),
		Issuer:    portalIssuer,
		IssuedAt:  jwt.NewNumericDate(time.Now()),
		ExpiresAt: jwt.NewNumericDate(expires),
	}
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(a.cfg.JWTSecret)
	return token, expires, err
}

// portalClientID достаёт id клиента из куки кабинета.
func (a *API) portalClientID(r *http.Request) (int64, bool) {
	cookie, err := r.Cookie(portalCookieName)
	if err != nil || trim(cookie.Value) == "" {
		return 0, false
	}

	claims := &jwt.RegisteredClaims{}
	_, err = jwt.ParseWithClaims(cookie.Value, claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("неожиданный метод подписи")
		}
		return a.cfg.JWTSecret, nil
	}, jwt.WithIssuer(portalIssuer), jwt.WithExpirationRequired())
	if err != nil {
		return 0, false
	}

	id, err := parseI64(claims.Subject)
	if err != nil || id <= 0 {
		return 0, false
	}
	return id, true
}

// adminFromRequest повторяет проверку админского токена без прерывания запроса —
// нужна там, где доступ может дать либо администратор, либо клиент.
func (a *API) adminFromRequest(r *http.Request) (store.User, bool) {
	raw, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found || trim(raw) == "" {
		return store.User{}, false
	}

	claims := &jwt.RegisteredClaims{}
	_, err := jwt.ParseWithClaims(trim(raw), claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("неожиданный метод подписи")
		}
		return a.cfg.JWTSecret, nil
	}, jwt.WithIssuer("frolov-crm"), jwt.WithExpirationRequired())
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
	return user, true
}

// publicClient прячет всё, что клиенту в кабинете видеть незачем:
// внутренние заметки, метку и хеш кода.
func publicClient(c store.Client) portalClient {
	return portalClient{
		ID:      strconvI64(c.ID),
		Name:    c.Name,
		Phone:   c.Phone,
		Email:   c.Email,
		Address: c.Address,
	}
}

// ------------------------- Управление доступом -----------------------------

func (a *API) handleGrantAccess(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	client, err := a.store.Client(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if trim(client.Phone) == "" {
		writeError(w, http.StatusBadRequest, "no_phone",
			"У клиента не указан телефон — по нему выполняется вход в кабинет")
		return
	}

	code, err := a.store.GenerateAccessCode(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	// Открытый код отдаём единственный раз: в базе только его хеш.
	writeJSON(w, http.StatusOK, map[string]any{
		"code":  code,
		"phone": client.Phone,
	})
}

func (a *API) handleRevokeAccess(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.RevokeAccess(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) handleClientOrders(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	orders, err := a.store.ClientOrders(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	for i := range orders {
		orders[i].Photos = withURLs(orders[i].Photos)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": orders, "count": len(orders)})
}
