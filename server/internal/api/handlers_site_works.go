package api

import (
	"net/http"
	"strings"

	"github.com/Benderilo/GO_Frol_SERV/internal/media"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Фотографии работ на витрине сайта. Загружает и правит их администратор,
// а смотрят все — поэтому отдаются по отдельному публичному адресу /works/,
// а не через /media/, где доступ проверяется по заказу.

func withWorkURLs(works []store.SiteWork) []store.SiteWork {
	for i := range works {
		works[i].URL = "/works/" + works[i].Token
		works[i].ThumbURL = "/works/" + works[i].Token + "/thumb"
	}
	return works
}

func (a *API) handleListSiteWorks(w http.ResponseWriter, r *http.Request) {
	works, err := a.store.SiteWorks(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": withWorkURLs(works), "count": len(works)})
}

func (a *API) handleUploadSiteWork(w http.ResponseWriter, r *http.Request) {
	// Тело ограничиваем до разбора формы, иначе большой файл успеет
	// осесть во временном каталоге.
	r.Body = http.MaxBytesReader(w, r.Body, media.MaxUploadBytes+1<<20)
	if err := r.ParseMultipartForm(4 << 20); err != nil {
		writeError(w, http.StatusBadRequest, "bad_form",
			"Не удалось прочитать файл — возможно, он больше 8 МБ")
		return
	}
	defer r.MultipartForm.RemoveAll()

	file, header, err := r.FormFile("photo")
	if err != nil {
		writeError(w, http.StatusBadRequest, "no_file", "Не приложен файл в поле photo")
		return
	}
	defer file.Close()

	saved, err := a.media.Save(file)
	if err != nil {
		writeMediaError(w, err, header.Filename)
		return
	}

	work, err := a.store.AddSiteWork(r.Context(), store.SiteWork{
		Token:     saved.Token,
		Path:      saved.Path,
		ThumbPath: saved.ThumbPath,
		Mime:      saved.Mime,
		Size:      saved.Size,
		Width:     saved.Width,
		Height:    saved.Height,
		Caption:   trim(r.FormValue("caption")),
	})
	if err != nil {
		a.media.Remove(saved.Path, saved.ThumbPath)
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, withWorkURLs([]store.SiteWork{work})[0])
}

func (a *API) handleUpdateSiteWork(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body photoCaptionBody
	if !decodeJSON(w, r, &body) {
		return
	}
	work, err := a.store.UpdateSiteWorkCaption(r.Context(), id, trim(body.Caption))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, withWorkURLs([]store.SiteWork{work})[0])
}

type siteWorkMoveBody struct {
	Delta int `json:"delta"` // -1 — левее в карусели, +1 — правее
}

func (a *API) handleMoveSiteWork(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body siteWorkMoveBody
	if !decodeJSON(w, r, &body) {
		return
	}
	works, err := a.store.MoveSiteWork(r.Context(), id, body.Delta)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": withWorkURLs(works), "count": len(works)})
}

func (a *API) handleDeleteSiteWork(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	work, err := a.store.DeleteSiteWork(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	a.media.Remove(work.Path, work.ThumbPath)
	w.WriteHeader(http.StatusNoContent)
}

// handleWorkMedia отдаёт снимок работы любому посетителю сайта.
func (a *API) handleWorkMedia(w http.ResponseWriter, r *http.Request) {
	work, err := a.store.SiteWorkByToken(r.Context(), r.PathValue("token"))
	if err != nil {
		writeStoreError(w, err)
		return
	}

	path := work.Path
	if strings.HasSuffix(r.URL.Path, "/thumb") && work.ThumbPath != "" {
		path = work.ThumbPath
	}
	file, err := a.media.Open(path)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "Файл не найден")
		return
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось прочитать файл")
		return
	}

	w.Header().Set("Content-Type", work.Mime)
	// Содержимое по токену неизменно — кешируем надолго и публично.
	w.Header().Set("Cache-Control", "public, max-age=2592000, immutable")
	http.ServeContent(w, r, work.Token+".jpg", info.ModTime(), file)
}
