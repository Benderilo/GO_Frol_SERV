package api

import (
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"github.com/Benderilo/GO_Frol_SERV/internal/media"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// withURLs проставляет адреса картинок: клиенты не должны собирать пути сами.
func withURLs(photos []store.OrderPhoto) []store.OrderPhoto {
	for i := range photos {
		photos[i].URL = "/media/" + photos[i].Token
		photos[i].ThumbURL = "/media/" + photos[i].Token + "/thumb"
	}
	return photos
}

func (a *API) handleListPhotos(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	photos, err := a.store.OrderPhotos(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": withURLs(photos), "count": len(photos)})
}

func (a *API) handleUploadPhoto(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	if _, err := a.store.Order(r.Context(), id); err != nil {
		writeStoreError(w, err)
		return
	}

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
		switch {
		case errors.Is(err, media.ErrTooLarge):
			writeError(w, http.StatusRequestEntityTooLarge, "too_large", "Файл больше 8 МБ")
		case errors.Is(err, media.ErrUnsupported):
			writeError(w, http.StatusUnsupportedMediaType, "bad_format", media.ErrUnsupported.Error())
		case errors.Is(err, media.ErrTooManyPixel):
			writeError(w, http.StatusUnsupportedMediaType, "too_many_pixels", media.ErrTooManyPixel.Error())
		default:
			slog.Error("сохранение фотографии", "err", err, "name", header.Filename)
			writeError(w, http.StatusInternalServerError, "internal", "Не удалось сохранить фотографию")
		}
		return
	}

	photo, err := a.store.AddPhoto(r.Context(), store.OrderPhoto{
		OrderID:   id,
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
		// Запись не удалась — файлы на диске оставлять незачем.
		a.media.Remove(saved.Path, saved.ThumbPath)
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, withURLs([]store.OrderPhoto{photo})[0])
}

type photoCaptionBody struct {
	Caption string `json:"caption"`
}

func (a *API) handleUpdatePhoto(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body photoCaptionBody
	if !decodeJSON(w, r, &body) {
		return
	}
	photo, err := a.store.UpdatePhotoCaption(r.Context(), id, trim(body.Caption))
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, withURLs([]store.OrderPhoto{photo})[0])
}

func (a *API) handleDeletePhoto(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	photo, err := a.store.DeletePhoto(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	a.media.Remove(photo.Path, photo.ThumbPath)
	w.WriteHeader(http.StatusNoContent)
}

// handleMedia отдаёт файл снимка. Токен в адресе непредсказуем, но этого мало:
// проверяем, что запрашивает администратор или владелец заказа.
func (a *API) handleMedia(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	if token == "" {
		writeError(w, http.StatusBadRequest, "bad_token", "Не указан идентификатор файла")
		return
	}

	photo, err := a.store.PhotoByToken(r.Context(), token)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if !a.mayViewPhoto(r, photo) {
		writeError(w, http.StatusForbidden, "forbidden", "Нет доступа к этому файлу")
		return
	}

	path := photo.Path
	if strings.HasSuffix(r.URL.Path, "/thumb") && photo.ThumbPath != "" {
		path = photo.ThumbPath
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

	w.Header().Set("Content-Type", photo.Mime)
	// Содержимое по токену неизменно, поэтому кешируем надолго и приватно.
	w.Header().Set("Cache-Control", "private, max-age=604800")
	http.ServeContent(w, r, photo.Token+".jpg", info.ModTime(), file)
}

// mayViewPhoto: администратор видит всё, клиент — только снимки своих заказов.
func (a *API) mayViewPhoto(r *http.Request, photo store.OrderPhoto) bool {
	if _, ok := a.adminFromRequest(r); ok {
		return true
	}
	clientID, ok := a.portalClientID(r)
	if !ok {
		return false
	}
	order, err := a.store.Order(r.Context(), photo.OrderID)
	if err != nil {
		return false
	}
	return order.ClientID != nil && *order.ClientID == clientID
}
