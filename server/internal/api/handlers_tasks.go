package api

import (
	"net/http"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// ---------- Задачи / напоминания ----------

func (a *API) handleListTasks(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListTasks(r.Context(), r.URL.Query().Get("filter"))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleCreateTask(w http.ResponseWriter, r *http.Request) {
	var t store.Task
	if !decodeJSON(w, r, &t) {
		return
	}
	if trim(t.Title) == "" {
		writeError(w, http.StatusBadRequest, "validation", "Текст задачи обязателен")
		return
	}
	item, err := a.store.CreateTask(r.Context(), t)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (a *API) handleUpdateTask(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var t store.Task
	if !decodeJSON(w, r, &t) {
		return
	}
	if trim(t.Title) == "" {
		writeError(w, http.StatusBadRequest, "validation", "Текст задачи обязателен")
		return
	}
	item, err := a.store.UpdateTask(r.Context(), id, t)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleSetTaskDone(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body struct {
		Done bool `json:"done"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	item, err := a.store.SetTaskDone(r.Context(), id, body.Done)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *API) handleDeleteTask(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if err := a.store.DeleteTask(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- Журнал действий ----------

func (a *API) handleListAudit(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.ListAudit(r.Context(), queryInt(r, "limit", 100))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

// ---------- Поиск и объединение дублей клиентов ----------

func (a *API) handleFindDuplicates(w http.ResponseWriter, r *http.Request) {
	items, err := a.store.FindClientDuplicates(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "count": len(items)})
}

func (a *API) handleMergeClients(w http.ResponseWriter, r *http.Request) {
	var body struct {
		KeepID   int64   `json:"keepId"`
		MergeIDs []int64 `json:"mergeIds"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.KeepID <= 0 || len(body.MergeIDs) == 0 {
		writeError(w, http.StatusBadRequest, "validation", "Укажите основную карточку и дубли для объединения")
		return
	}
	if err := a.store.MergeClients(r.Context(), body.KeepID, body.MergeIDs); err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
