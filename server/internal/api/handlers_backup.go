package api

import (
	"compress/gzip"
	"io"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// handleDownloadBackup отдаёт свежий снимок базы, сжатый gzip.
//
// Снимок делается на месте, а не берётся из ночных копий: приложение
// сохраняет его на телефон при открытии, и копия суточной давности там
// была бы бесполезнее той, что есть прямо сейчас. База маленькая, VACUUM
// INTO на ней занимает миллисекунды.
func (a *API) handleDownloadBackup(w http.ResponseWriter, r *http.Request) {
	// Временный файл нужен потому, что VACUUM INTO умеет писать только
	// в файл: отдать снимок прямо в поток SQLite не даёт.
	tmp, err := os.CreateTemp("", "frolov-snapshot-*.db")
	if err != nil {
		slog.Error("временный файл для снимка", "err", err)
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось подготовить копию")
		return
	}
	path := tmp.Name()
	tmp.Close()
	defer os.Remove(path)

	if err := store.BackupTo(r.Context(), a.cfg.DatabasePath, path); err != nil {
		slog.Error("снимок базы", "err", err)
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось снять копию базы")
		return
	}

	file, err := os.Open(path)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось прочитать копию")
		return
	}
	defer file.Close()

	name := "frolov-" + time.Now().Format("20060102-150405") + ".db.gz"
	w.Header().Set("Content-Type", "application/gzip")
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
	// Каждый снимок свой — кешировать нечего.
	w.Header().Set("Cache-Control", "no-store")

	// Заголовки уже ушли, поэтому ошибку записи остаётся только записать
	// в журнал: подменить ответ на 500 в этот момент уже нельзя.
	gz := gzip.NewWriter(w)
	if _, err := io.Copy(gz, file); err != nil {
		slog.Error("отдача снимка базы", "err", err)
		return
	}
	if err := gz.Close(); err != nil {
		slog.Error("закрытие потока снимка", "err", err)
	}
}
