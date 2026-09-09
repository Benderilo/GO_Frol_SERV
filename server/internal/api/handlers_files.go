package api

import (
	"errors"
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/Benderilo/GO_Frol_SERV/internal/media"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// folderListing — всё, что нужно экрану файлов за один запрос: где мы,
// как сюда пришли, какие внутри папки и файлы.
type folderListing struct {
	Folder  *store.Folder      `json:"folder"`
	Path    []store.Folder     `json:"path"`
	Folders []store.Folder     `json:"folders"`
	Files   []store.StoredFile `json:"files"`
}

// folderParam читает адрес папки из запроса. Пусто или 0 — корень (nil).
func folderParam(raw string) (*int64, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" || raw == "0" {
		return nil, true
	}
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 {
		return nil, false
	}
	return &id, true
}

func (a *API) handleListFiles(w http.ResponseWriter, r *http.Request) {
	folder, ok := folderParam(r.URL.Query().Get("folder"))
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный адрес папки")
		return
	}

	listing := folderListing{Path: []store.Folder{}}
	if folder != nil {
		current, err := a.store.Folder(r.Context(), *folder)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		path, err := a.store.FolderPath(r.Context(), *folder)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		listing.Folder = &current
		listing.Path = path
	}

	folders, err := a.store.Folders(r.Context(), folder)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	files, err := a.store.FilesIn(r.Context(), folder)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	listing.Folders = folders
	listing.Files = files
	writeJSON(w, http.StatusOK, listing)
}

type folderBody struct {
	Name     string `json:"name"`
	ParentID *int64 `json:"parentId"`
}

func (a *API) handleCreateFolder(w http.ResponseWriter, r *http.Request) {
	var body folderBody
	if !decodeJSON(w, r, &body) {
		return
	}
	folder, err := a.store.CreateFolder(r.Context(), body.ParentID, body.Name)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, folder)
}

type nameBody struct {
	Name string `json:"name"`
}

func (a *API) handleRenameFolder(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body nameBody
	if !decodeJSON(w, r, &body) {
		return
	}
	folder, err := a.store.RenameFolder(r.Context(), id, body.Name)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, folder)
}

func (a *API) handleDeleteFolder(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	removed, err := a.store.DeleteFolder(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	for _, f := range removed {
		a.media.Remove(f.Path)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) handleUploadFile(w http.ResponseWriter, r *http.Request) {
	// Тело ограничиваем до разбора формы: иначе крупный файл успеет
	// осесть во временном каталоге целиком.
	r.Body = http.MaxBytesReader(w, r.Body, media.MaxDocumentBytes+1<<20)
	if err := r.ParseMultipartForm(4 << 20); err != nil {
		writeError(w, http.StatusBadRequest, "bad_form",
			"Не удалось прочитать файл — возможно, он больше 25 МБ")
		return
	}
	defer r.MultipartForm.RemoveAll()

	folder, ok := folderParam(r.FormValue("folderId"))
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный адрес папки")
		return
	}

	file, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, "no_file", "Не приложен файл в поле file")
		return
	}
	defer file.Close()

	name := trim(r.FormValue("name"))
	if name == "" {
		name = filepath.Base(header.Filename)
	}

	saved, err := a.media.SaveDocument(file, header.Filename)
	if err != nil {
		switch {
		case errors.Is(err, media.ErrTooLarge):
			writeError(w, http.StatusRequestEntityTooLarge, "too_large", "Файл больше 25 МБ")
		case errors.Is(err, media.ErrEmptyFile):
			writeError(w, http.StatusBadRequest, "empty_file", "Файл пустой")
		default:
			slog.Error("сохранение файла архива", "err", err, "name", header.Filename)
			writeError(w, http.StatusInternalServerError, "internal", "Не удалось сохранить файл")
		}
		return
	}

	stored, err := a.store.AddFile(r.Context(), store.StoredFile{
		FolderID: folder,
		Token:    saved.Token,
		Name:     name,
		Path:     saved.Path,
		Mime:     fileMime(header.Header.Get("Content-Type"), name),
		Size:     saved.Size,
	})
	if err != nil {
		// Запись не удалась — держать файл на диске незачем.
		a.media.Remove(saved.Path)
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, stored)
}

func (a *API) handleRenameFile(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	var body nameBody
	if !decodeJSON(w, r, &body) {
		return
	}
	stored, err := a.store.RenameFile(r.Context(), id, body.Name)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, stored)
}

func (a *API) handleDeleteFile(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	stored, err := a.store.DeleteFile(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	a.media.Remove(stored.Path)
	w.WriteHeader(http.StatusNoContent)
}

// handleDownloadFile отдаёт содержимое. Маршрут лежит под общим префиксом
// /api/v1/admin/, то есть уже закрыт проверкой токена — отдельного
// непредсказуемого адреса, как у снимков, файлу архива не нужно.
func (a *API) handleDownloadFile(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_id", "Некорректный идентификатор")
		return
	}
	stored, err := a.store.StoredFile(r.Context(), id)
	if err != nil {
		writeStoreError(w, err)
		return
	}

	file, err := a.media.Open(stored.Path)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "Файл не найден на диске")
		return
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось прочитать файл")
		return
	}

	w.Header().Set("Content-Type", stored.Mime)
	// filename* с кодировкой: имена в архиве русские, и без неё браузер
	// сохранит файл под мусорным набором байт.
	w.Header().Set("Content-Disposition",
		`attachment; filename*=UTF-8''`+url.PathEscape(stored.Name))
	w.Header().Set("Cache-Control", "private, no-store")
	http.ServeContent(w, r, stored.Name, info.ModTime(), file)
}

// fileMime выбирает тип файла: присланный клиентом, иначе по расширению,
// иначе поток байт — его понимает любой просмотрщик.
func fileMime(declared, name string) string {
	declared = trim(declared)
	if declared != "" && declared != "application/octet-stream" {
		return declared
	}
	if byExt := mime.TypeByExtension(strings.ToLower(filepath.Ext(name))); byExt != "" {
		return byExt
	}
	return "application/octet-stream"
}
