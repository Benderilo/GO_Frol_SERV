package api

import (
	"fmt"
	"html/template"
	"net/http"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/config"
	"github.com/Benderilo/GO_Frol_SERV/internal/media"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/Benderilo/GO_Frol_SERV/internal/web"
)

// API собирает зависимости HTTP-слоя.
type API struct {
	cfg     *config.Config
	store   *store.Store
	version string
	tmpl    *template.Template
	static  http.Handler
	media   *media.Storage
	limiter *rateLimiter
	stop    chan struct{}
}

func New(cfg *config.Config, st *store.Store, version string) (*API, error) {
	tmpl, err := web.Templates(version)
	if err != nil {
		return nil, err
	}
	staticFS, err := web.Static()
	if err != nil {
		return nil, err
	}
	storage, err := media.NewStorage(cfg.UploadsDir)
	if err != nil {
		return nil, err
	}

	a := &API{
		cfg:     cfg,
		store:   st,
		version: version,
		tmpl:    tmpl,
		static:  cacheForever(http.StripPrefix("/static/", http.FileServer(http.FS(staticFS)))),
		media:   storage,
		limiter: newRateLimiter(20, time.Minute),
		stop:    make(chan struct{}),
	}
	go a.limiter.cleanup(5*time.Minute, a.stop)
	return a, nil
}

// Close останавливает фоновые задачи HTTP-слоя.
func (a *API) Close() { close(a.stop) }

// Handler строит дерево маршрутов.
func (a *API) Handler() http.Handler {
	admin := http.NewServeMux()
	admin.HandleFunc("GET /api/v1/admin/stats", a.handleStats)
	admin.HandleFunc("GET /api/v1/admin/analytics", a.handleAnalytics)

	admin.HandleFunc("GET /api/v1/admin/orders/{id}/documents/{kind}", a.handleOrderDocument)

	admin.HandleFunc("GET /api/v1/admin/orders/{id}/items", a.handleListOrderItems)
	admin.HandleFunc("POST /api/v1/admin/orders/{id}/items", a.handleAddOrderItem)
	admin.HandleFunc("PUT /api/v1/admin/order-items/{id}", a.handleUpdateOrderItem)
	admin.HandleFunc("DELETE /api/v1/admin/order-items/{id}", a.handleDeleteOrderItem)
	admin.HandleFunc("POST /api/v1/admin/orders/{id}/writeoff", a.handleWriteOffOrder)

	admin.HandleFunc("GET /api/v1/admin/cash", a.handleListCash)
	admin.HandleFunc("POST /api/v1/admin/cash", a.handleAddCash)
	admin.HandleFunc("DELETE /api/v1/admin/cash/{id}", a.handleDeleteCash)
	admin.HandleFunc("GET /api/v1/admin/report", a.handleReport)
	admin.HandleFunc("GET /api/v1/admin/report/export", a.handleReportExport)

	admin.HandleFunc("GET /api/v1/admin/catalog", a.handleListCatalog)
	admin.HandleFunc("POST /api/v1/admin/catalog", a.handleCreateCatalogItem)
	admin.HandleFunc("PUT /api/v1/admin/catalog/{id}", a.handleUpdateCatalogItem)
	admin.HandleFunc("DELETE /api/v1/admin/catalog/{id}", a.handleDeleteCatalogItem)
	admin.HandleFunc("GET /api/v1/admin/stock", a.handleListStockMoves)
	admin.HandleFunc("POST /api/v1/admin/catalog/{id}/stock", a.handleAddStockMove)
	admin.HandleFunc("DELETE /api/v1/admin/stock/{id}", a.handleDeleteStockMove)

	admin.HandleFunc("GET /api/v1/admin/company", a.handleGetCompany)
	admin.HandleFunc("PUT /api/v1/admin/company", a.handleSaveCompany)

	admin.HandleFunc("PUT /api/v1/admin/site", a.handleSaveSite)
	admin.HandleFunc("POST /api/v1/admin/site/reset", a.handleResetSite)

	admin.HandleFunc("GET /api/v1/admin/clients", a.handleListClients)
	admin.HandleFunc("POST /api/v1/admin/clients", a.handleCreateClient)
	admin.HandleFunc("GET /api/v1/admin/clients/{id}", a.handleGetClient)
	admin.HandleFunc("PUT /api/v1/admin/clients/{id}", a.handleUpdateClient)
	admin.HandleFunc("DELETE /api/v1/admin/clients/{id}", a.handleDeleteClient)

	admin.HandleFunc("GET /api/v1/admin/orders", a.handleListOrders)
	admin.HandleFunc("POST /api/v1/admin/orders", a.handleCreateOrder)
	admin.HandleFunc("GET /api/v1/admin/orders/{id}", a.handleGetOrder)
	admin.HandleFunc("PUT /api/v1/admin/orders/{id}", a.handleUpdateOrder)
	admin.HandleFunc("DELETE /api/v1/admin/orders/{id}", a.handleDeleteOrder)

	admin.HandleFunc("GET /api/v1/admin/clients/{id}/orders", a.handleClientOrders)
	admin.HandleFunc("POST /api/v1/admin/clients/{id}/access", a.handleGrantAccess)
	admin.HandleFunc("DELETE /api/v1/admin/clients/{id}/access", a.handleRevokeAccess)

	admin.HandleFunc("GET /api/v1/admin/orders/{id}/photos", a.handleListPhotos)
	admin.HandleFunc("POST /api/v1/admin/orders/{id}/photos", a.handleUploadPhoto)
	admin.HandleFunc("PATCH /api/v1/admin/photos/{id}", a.handleUpdatePhoto)
	admin.HandleFunc("DELETE /api/v1/admin/photos/{id}", a.handleDeletePhoto)

	admin.HandleFunc("GET /api/v1/admin/orders/{id}/payments", a.handleListPayments)
	admin.HandleFunc("POST /api/v1/admin/orders/{id}/payments", a.handleCreatePayment)
	admin.HandleFunc("DELETE /api/v1/admin/payments/{id}", a.handleDeletePayment)

	admin.HandleFunc("GET /api/v1/admin/requests", a.handleListRequests)
	admin.HandleFunc("PATCH /api/v1/admin/requests/{id}", a.handleUpdateRequest)
	admin.HandleFunc("DELETE /api/v1/admin/requests/{id}", a.handleDeleteRequest)

	admin.HandleFunc("GET /api/v1/admin/export.xlsx", a.handleExport)
	admin.HandleFunc("POST /api/v1/admin/import", a.handleImport)

	admin.HandleFunc("GET /api/v1/admin/tasks", a.handleListTasks)
	admin.HandleFunc("POST /api/v1/admin/tasks", a.handleCreateTask)
	admin.HandleFunc("PUT /api/v1/admin/tasks/{id}", a.handleUpdateTask)
	admin.HandleFunc("PATCH /api/v1/admin/tasks/{id}", a.handleSetTaskDone)
	admin.HandleFunc("DELETE /api/v1/admin/tasks/{id}", a.handleDeleteTask)

	admin.HandleFunc("GET /api/v1/admin/audit", a.handleListAudit)
	admin.HandleFunc("GET /api/v1/admin/clients/duplicates", a.handleFindDuplicates)
	admin.HandleFunc("POST /api/v1/admin/clients/merge", a.handleMergeClients)

	admin.HandleFunc("GET /api/v1/auth/me", a.handleMe)
	admin.HandleFunc("POST /api/v1/auth/password", a.handleChangePassword)

	mux := http.NewServeMux()

	// Публичная часть.
	mux.HandleFunc("GET /{$}", a.handleLanding)
	mux.HandleFunc("GET /robots.txt", a.handleRobots)
	mux.HandleFunc("GET /sitemap.xml", a.handleSitemap)
	mux.Handle("GET /static/", a.static)
	mux.HandleFunc("GET /api/v1/health", a.handleHealth)
	mux.HandleFunc("GET /api/v1/site", a.handleGetSite)
	mux.HandleFunc("GET /cabinet", a.handleCabinet)
	mux.HandleFunc("GET /media/{token}", a.handleMedia)
	mux.HandleFunc("GET /media/{token}/thumb", a.handleMedia)
	mux.Handle("POST /api/v1/portal/login", a.limiter.middleware(http.HandlerFunc(a.handlePortalLogin)))
	mux.HandleFunc("POST /api/v1/portal/logout", a.handlePortalLogout)
	mux.HandleFunc("GET /api/v1/portal/me", a.handlePortalMe)
	mux.Handle("POST /api/v1/requests", a.limiter.middleware(http.HandlerFunc(a.handleCreateRequest)))
	mux.Handle("POST /api/v1/auth/login", a.limiter.middleware(http.HandlerFunc(a.handleLogin)))

	// Защищённая часть — единый префикс, один слой авторизации.
	mux.Handle("/api/v1/admin/", a.requireAuth(admin))
	mux.Handle("GET /api/v1/auth/me", a.requireAuth(admin))
	mux.Handle("POST /api/v1/auth/password", a.requireAuth(admin))

	mux.HandleFunc("/", a.handleNotFound)

	return withRecover(withLogging(withSecurityHeaders(withCORS(a.cfg.AllowedOrigins, mux))))
}

// cacheForever разрешает долгий кеш: ссылки на статику версионированы,
// поэтому после обновления сервера браузер запросит новый адрес.
func cacheForever(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("v") != "" {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "public, max-age=300")
		}
		next.ServeHTTP(w, r)
	})
}

func (a *API) handleNotFound(w http.ResponseWriter, r *http.Request) {
	writeError(w, http.StatusNotFound, "not_found", "Страница или метод не найдены")
}

// handleCabinet отдаёт страницу личного кабинета клиента.
// Данные страница подгружает сама через /api/v1/portal/me.
func (a *API) handleCabinet(w http.ResponseWriter, r *http.Request) {
	content, err := a.store.SiteContent(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	if err := a.tmpl.ExecuteTemplate(w, "cabinet.html", content); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось отрисовать страницу")
	}
}

// landingData оборачивает содержимое сайта SEO-параметрами: каноническим
// адресом и городом. Встраивание сохраняет старые обращения шаблона .Hero,
// .Services и т.д.
type landingData struct {
	store.SiteContent
	Canonical string
	City      string
	Region    string
}

// handleLanding рендерит публичную страницу актуальным содержимым из БД.
func (a *API) handleLanding(w http.ResponseWriter, r *http.Request) {
	content, err := a.store.SiteContent(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	data := landingData{
		SiteContent: content,
		Canonical:   a.siteBaseURL(),
		City:        content.Contacts.City,
		Region:      "Саратовская область",
	}
	// Сайт мог быть сохранён до появления поля «Город» — подставляем разумное.
	if data.City == "" {
		data.City = "Балаково"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	if err := a.tmpl.ExecuteTemplate(w, "index.html", data); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось отрисовать страницу")
	}
}

// siteBaseURL — канонический адрес сайта для SEO-служебных файлов.
// Пустой, если домен ещё не настроен: тогда canonical просто не печатаем.
func (a *API) siteBaseURL() string {
	if len(a.cfg.Domains) == 0 {
		return ""
	}
	return "https://" + a.cfg.Domains[0]
}

// handleRobots разрешает индексацию всего, кроме кабинета клиента.
func (a *API) handleRobots(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	sb := strings.Builder{}
	sb.WriteString("User-agent: *\nAllow: /\nDisallow: /cabinet\nDisallow: /api/\n")
	if base := a.siteBaseURL(); base != "" {
		sb.WriteString("Sitemap: " + base + "/sitemap.xml\n")
	}
	_, _ = w.Write([]byte(sb.String()))
}

// handleSitemap — минимальная карта: лендинг одной страницей.
func (a *API) handleSitemap(w http.ResponseWriter, r *http.Request) {
	base := a.siteBaseURL()
	if base == "" {
		writeError(w, http.StatusNotFound, "not_found", "Домен не настроен")
		return
	}
	content, err := a.store.SiteContent(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	lastmod := content.UpdatedAt
	if len(lastmod) > 10 {
		lastmod = lastmod[:10] // YYYY-MM-DD из RFC3339
	}
	fmt.Fprintf(w, `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>%s/</loc>
    <lastmod>%s</lastmod>
    <changefreq>weekly</changefreq>
    <priority>1.0</priority>
  </url>
</urlset>`, base, lastmod)
}
