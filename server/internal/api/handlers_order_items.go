package api

import (
	"errors"
	"net/http"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

func (a *API) handleListOrderItems(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	items, err := a.store.OrderItems(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleAddOrderItem(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var item store.OrderItem
	if !decodeJSON(w, r, &item) {
		return
	}
	// Наименование обязательно только когда строку вписывают руками:
	// у строки из справочника его подставит сервер.
	if msg, ok := validateOrderItem(item, item.CatalogID == nil); !ok {
		writeError(w, http.StatusBadRequest, "validation", msg)
		return
	}
	saved, err := a.store.AddOrderItem(r.Context(), id, item)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, saved)
}

func (a *API) handleUpdateOrderItem(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var item store.OrderItem
	if !decodeJSON(w, r, &item) {
		return
	}
	if msg, ok := validateOrderItem(item, true); !ok {
		writeError(w, http.StatusBadRequest, "validation", msg)
		return
	}
	saved, err := a.store.UpdateOrderItem(r.Context(), id, item)
	if errors.Is(err, store.ErrNegativeStock) {
		writeError(w, http.StatusConflict, "negative_stock",
			"Столько материала на складе нет — уменьшите количество или запишите приход.")
		return
	}
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, saved)
}

func (a *API) handleDeleteOrderItem(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteOrderItem(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleWriteOffOrder списывает со склада материалы заказа. Отдаёт, сколько
// строк списалось: приложению есть что показать, а повторное нажатие
// не задваивает расход — уже списанные строки пропускаются.
func (a *API) handleWriteOffOrder(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	written, err := a.store.WriteOffOrder(r.Context(), id)
	if errors.Is(err, store.ErrNegativeStock) {
		writeError(w, http.StatusConflict, "negative_stock",
			"На складе не хватает материала. Запишите приход или уменьшите количество в заказе.")
		return
	}
	if err != nil {
		writeStoreError(w, err)
		return
	}
	items, err := a.store.OrderItems(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"written": written, "items": items})
}

func validateOrderItem(item store.OrderItem, needName bool) (string, bool) {
	if needName && trim(item.Name) == "" {
		return "Впишите наименование строки", false
	}
	if item.QtyMilli < 0 {
		return "Количество не может быть отрицательным", false
	}
	if item.PriceKop < 0 || item.CostKop < 0 {
		return "Цена не может быть отрицательной", false
	}
	if item.Kind != "" && !store.ValidKind(item.Kind) {
		return "Неизвестный вид позиции", false
	}
	return "", true
}
