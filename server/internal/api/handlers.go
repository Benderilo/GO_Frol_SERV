package api

import (
	"net/http"
	"strings"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// ---------- Служебное ----------

func (a *API) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"service": "frolov-crm",
		"version": a.version,
	})
}

func (a *API) handleStats(w http.ResponseWriter, r *http.Request) {
	st, err := a.store.Stats(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, st)
}

// ---------- Контент сайта ----------

func (a *API) handleGetSite(w http.ResponseWriter, r *http.Request) {
	content, err := a.store.SiteContent(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, content)
}

func (a *API) handleSaveSite(w http.ResponseWriter, r *http.Request) {
	// Берём текущий документ как основу: приложение может прислать частичный JSON.
	content, err := a.store.SiteContent(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if !decodeJSON(w, r, &content) {
		return
	}
	if trim(content.SiteName) == "" {
		writeError(w, http.StatusBadRequest, "validation", "Название сайта не может быть пустым")
		return
	}
	if content.Ticker.SpeedSec <= 0 {
		content.Ticker.SpeedSec = 25
	}

	saved, err := a.store.SaveSiteContent(r.Context(), content)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, saved)
}

func (a *API) handleResetSite(w http.ResponseWriter, r *http.Request) {
	saved, err := a.store.ResetSiteContent(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, saved)
}

// ---------- Клиенты ----------

func (a *API) handleListClients(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListClients(r.Context(),
		r.URL.Query().Get("q"), queryInt(r, "limit", 100), queryInt(r, "offset", 0))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleGetClient(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	item, err := a.store.Client(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleCreateClient(w http.ResponseWriter, r *http.Request) {
	var c store.Client
	if !decodeJSON(w, r, &c) {
		return
	}
	if trim(c.Name) == "" {
		writeError(w, http.StatusBadRequest, "validation", "Имя клиента обязательно")
		return
	}
	item, err := a.store.CreateClient(r.Context(), c)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (a *API) handleUpdateClient(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var c store.Client
	if !decodeJSON(w, r, &c) {
		return
	}
	if trim(c.Name) == "" {
		writeError(w, http.StatusBadRequest, "validation", "Имя клиента обязательно")
		return
	}
	item, err := a.store.UpdateClient(r.Context(), id, c)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleDeleteClient(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteClient(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- Заказы ----------

var orderStatuses = map[string]bool{"new": true, "in_progress": true, "done": true, "canceled": true}

func (a *API) handleListOrders(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListOrders(r.Context(),
		r.URL.Query().Get("status"), queryInt(r, "limit", 100), queryInt(r, "offset", 0))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleGetOrder(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	item, err := a.store.Order(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	item.Photos = withURLs(item.Photos)
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleCreateOrder(w http.ResponseWriter, r *http.Request) {
	var o store.Order
	if !decodeJSON(w, r, &o) {
		return
	}
	if msg, ok := validateOrder(o); !ok {
		writeError(w, http.StatusBadRequest, "validation", msg)
		return
	}
	item, err := a.store.CreateOrder(r.Context(), o)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (a *API) handleUpdateOrder(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var o store.Order
	if !decodeJSON(w, r, &o) {
		return
	}
	if msg, ok := validateOrder(o); !ok {
		writeError(w, http.StatusBadRequest, "validation", msg)
		return
	}
	item, err := a.store.UpdateOrder(r.Context(), id, o)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleDeleteOrder(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	// Записи о снимках уберёт каскад, а файлы на диске — нет.
	photos, err := a.store.PhotoFilesForOrder(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if err := a.store.DeleteOrder(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	for _, p := range photos {
		a.media.Remove(p.Path, p.ThumbPath)
	}
	w.WriteHeader(http.StatusNoContent)
}

func validateOrder(o store.Order) (string, bool) {
	if trim(o.Title) == "" {
		return "Название заказа обязательно", false
	}
	if o.Status != "" && !orderStatuses[o.Status] {
		return "Недопустимый статус заказа", false
	}
	if o.Price < 0 {
		return "Стоимость не может быть отрицательной", false
	}
	return "", true
}

// ---------- Заявки с сайта ----------

type publicRequestBody struct {
	Name    string `json:"name"`
	Phone   string `json:"phone"`
	Message string `json:"message"`
	// Honeypot: обычный посетитель это поле не видит и не заполняет.
	Website string `json:"website"`
}

func (a *API) handleCreateRequest(w http.ResponseWriter, r *http.Request) {
	var body publicRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if trim(body.Website) != "" {
		// Похоже на бота — отвечаем успехом, но ничего не сохраняем.
		writeJSON(w, http.StatusAccepted, map[string]string{"status": "ok"})
		return
	}
	body.Name = trim(body.Name)
	body.Phone = trim(body.Phone)
	if body.Name == "" || body.Phone == "" {
		writeError(w, http.StatusBadRequest, "validation", "Укажите имя и телефон")
		return
	}
	if len(body.Message) > 2000 {
		body.Message = body.Message[:2000]
	}

	item, err := a.store.CreateRequest(r.Context(), store.Request{
		Name:    body.Name,
		Phone:   body.Phone,
		Message: strings.TrimSpace(body.Message),
		Source:  "site",
	})
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"status": "ok", "id": item.ID})
}

func (a *API) handleListRequests(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListRequests(r.Context(),
		r.URL.Query().Get("status"), queryInt(r, "limit", 100), queryInt(r, "offset", 0))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

type requestStatusBody struct {
	Status string `json:"status"`
}

var requestStatuses = map[string]bool{"new": true, "in_progress": true, "done": true, "spam": true}

func (a *API) handleUpdateRequest(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body requestStatusBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if !requestStatuses[body.Status] {
		writeError(w, http.StatusBadRequest, "validation", "Недопустимый статус заявки")
		return
	}
	item, err := a.store.UpdateRequestStatus(r.Context(), id, body.Status)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleDeleteRequest(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteRequest(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
