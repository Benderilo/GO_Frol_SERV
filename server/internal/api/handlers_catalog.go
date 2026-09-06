package api

import (
	"errors"
	"net/http"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

func (a *API) handleListCatalog(w http.ResponseWriter, r *http.Request) {
	kind := trim(r.URL.Query().Get("kind"))
	if kind != "" && !store.ValidKind(kind) {
		writeError(w, http.StatusBadRequest, "validation", "Неизвестный вид позиции")
		return
	}
	withArchived := r.URL.Query().Get("archived") == "1"

	items, err := a.store.CatalogItems(r.Context(), kind, withArchived)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleCreateCatalogItem(w http.ResponseWriter, r *http.Request) {
	var item store.CatalogItem
	if !decodeJSON(w, r, &item) {
		return
	}
	if msg, ok := validateCatalogItem(item); !ok {
		writeError(w, http.StatusBadRequest, "validation", msg)
		return
	}
	saved, err := a.store.CreateCatalogItem(r.Context(), item)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, saved)
}

func (a *API) handleUpdateCatalogItem(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var item store.CatalogItem
	if !decodeJSON(w, r, &item) {
		return
	}
	if msg, ok := validateCatalogItem(item); !ok {
		writeError(w, http.StatusBadRequest, "validation", msg)
		return
	}
	saved, err := a.store.UpdateCatalogItem(r.Context(), id, item)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, saved)
}

func (a *API) handleDeleteCatalogItem(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	err := a.store.DeleteCatalogItem(r.Context(), id)
	// Отказ по истории — не ошибка сервера: человеку надо объяснить, что делать.
	if errors.Is(err, store.ErrStockHistory) {
		writeError(w, http.StatusConflict, "stock_history",
			"По позиции есть движения склада — удалить нельзя. Отметьте её архивной, чтобы убрать из списка.")
		return
	}
	if err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func validateCatalogItem(item store.CatalogItem) (string, bool) {
	if trim(item.Name) == "" {
		return "Наименование обязательно", false
	}
	if !store.ValidKind(item.Kind) {
		return "Выберите вид: услуга, работа или материал", false
	}
	if item.PriceKop < 0 || item.CostKop < 0 {
		return "Цена не может быть отрицательной", false
	}
	return "", true
}

// ---------- Движения склада ----------

func (a *API) handleListStockMoves(w http.ResponseWriter, r *http.Request) {
	// Идентификатор позиции необязателен: без него отдаём последние движения
	// по всем материалам — это лента «что приходило и уходило».
	itemID := int64(queryInt(r, "itemId", 0))
	moves, err := a.store.StockMoves(r.Context(), itemID, queryInt(r, "limit", 200))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": moves, "count": len(moves)})
}

func (a *API) handleAddStockMove(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var move store.StockMove
	if !decodeJSON(w, r, &move) {
		return
	}
	if move.QtyMilli == 0 {
		writeError(w, http.StatusBadRequest, "validation",
			"Укажите количество: плюс на приход, минус на списание")
		return
	}
	if move.CostKop < 0 {
		writeError(w, http.StatusBadRequest, "validation", "Сумма не может быть отрицательной")
		return
	}
	if len(move.Note) > 500 {
		move.Note = move.Note[:500]
	}

	saved, err := a.store.AddStockMove(r.Context(), id, move)
	if errors.Is(err, store.ErrNegativeStock) {
		writeError(w, http.StatusConflict, "negative_stock",
			"Столько списать нельзя — на складе меньше. Сначала запишите приход.")
		return
	}
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, saved)
}

func (a *API) handleDeleteStockMove(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	err := a.store.DeleteStockMove(r.Context(), id)
	if errors.Is(err, store.ErrNegativeStock) {
		writeError(w, http.StatusConflict, "negative_stock",
			"Эту запись убрать нельзя: без неё остаток станет отрицательным — приход уже списан.")
		return
	}
	if err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
