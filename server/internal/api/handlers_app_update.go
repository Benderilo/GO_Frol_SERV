package api

import (
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
)

// Автообновление приложения. Свежую сборку выкладывает deploy/publish-apk.sh:
// в каталог cfg.AppDir ложатся frolov-crm.apk и latest.json с её версией.
// Приложение сверяет versionCode со своим и, если на сервере новее,
// показывает полоску «Есть обновление». Оба адреса — только после входа:
// сборку CRM посторонним раздавать незачем.

const (
	appApkName    = "frolov-crm.apk"
	appLatestName = "latest.json"
)

// AppRelease описывает выложенную сборку.
type AppRelease struct {
	VersionCode int64  `json:"versionCode"` // 0 — сборка ещё не выкладывалась
	VersionName string `json:"versionName"`
	Notes       string `json:"notes"`
	Size        int64  `json:"size"`
	SHA256      string `json:"sha256"`
	PublishedAt string `json:"publishedAt"`
	URL         string `json:"url"`
}

func (a *API) handleAppLatest(w http.ResponseWriter, r *http.Request) {
	release, err := a.appRelease()
	if err != nil {
		slog.Error("описание сборки приложения", "err", err)
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось прочитать сведения об обновлении")
		return
	}
	writeJSON(w, http.StatusOK, release)
}

// appRelease читает latest.json. Нет файла или самого APK — значит,
// выкладывать нечего: versionCode 0 приложение не примет за обновление.
func (a *API) appRelease() (AppRelease, error) {
	raw, err := os.ReadFile(filepath.Join(a.cfg.AppDir, appLatestName))
	if errors.Is(err, fs.ErrNotExist) {
		return AppRelease{}, nil
	}
	if err != nil {
		return AppRelease{}, err
	}
	var release AppRelease
	if err := json.Unmarshal(raw, &release); err != nil {
		return AppRelease{}, err
	}
	if _, err := os.Stat(filepath.Join(a.cfg.AppDir, appApkName)); err != nil {
		return AppRelease{}, nil
	}
	release.URL = "/api/v1/admin/app/apk"
	return release, nil
}

func (a *API) handleAppApk(w http.ResponseWriter, r *http.Request) {
	file, err := os.Open(filepath.Join(a.cfg.AppDir, appApkName))
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "Сборка приложения на сервер ещё не выложена")
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось прочитать файл")
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="`+appApkName+`"`)
	// Имя файла одно на все версии — кешировать нельзя.
	w.Header().Set("Cache-Control", "no-store")
	http.ServeContent(w, r, appApkName, info.ModTime(), file)
}
