package api

import (
	"html/template"
	"net/http"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/config"
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
	limiter *rateLimiter
	stop    chan struct{}
}

func New(cfg *config.Config, st *store.Store, version string) (*API, error) {
	tmpl, err := web.Templates()
	if err != nil {
		return nil, err
	}
	staticFS, err := web.Static()
	if err != nil {
		return nil, err
	}

	a := &API{
		cfg:     cfg,
		store:   st,
		version: version,
		tmpl:    tmpl,
		static:  http.StripPrefix("/static/", http.FileServer(http.FS(staticFS))),
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

	admin.HandleFunc("GET /api/v1/admin/requests", a.handleListRequests)
	admin.HandleFunc("PATCH /api/v1/admin/requests/{id}", a.handleUpdateRequest)
	admin.HandleFunc("DELETE /api/v1/admin/requests/{id}", a.handleDeleteRequest)

	admin.HandleFunc("GET /api/v1/auth/me", a.handleMe)
	admin.HandleFunc("POST /api/v1/auth/password", a.handleChangePassword)

	mux := http.NewServeMux()

	// Публичная часть.
	mux.HandleFunc("GET /{$}", a.handleLanding)
	mux.Handle("GET /static/", a.static)
	mux.HandleFunc("GET /api/v1/health", a.handleHealth)
	mux.HandleFunc("GET /api/v1/site", a.handleGetSite)
	mux.Handle("POST /api/v1/requests", a.limiter.middleware(http.HandlerFunc(a.handleCreateRequest)))
	mux.Handle("POST /api/v1/auth/login", a.limiter.middleware(http.HandlerFunc(a.handleLogin)))

	// Защищённая часть — единый префикс, один слой авторизации.
	mux.Handle("/api/v1/admin/", a.requireAuth(admin))
	mux.Handle("GET /api/v1/auth/me", a.requireAuth(admin))
	mux.Handle("POST /api/v1/auth/password", a.requireAuth(admin))

	mux.HandleFunc("/", a.handleNotFound)

	return withRecover(withLogging(withSecurityHeaders(withCORS(a.cfg.AllowedOrigins, mux))))
}

func (a *API) handleNotFound(w http.ResponseWriter, r *http.Request) {
	writeError(w, http.StatusNotFound, "not_found", "Страница или метод не найдены")
}

// handleLanding рендерит публичную страницу актуальным содержимым из БД.
func (a *API) handleLanding(w http.ResponseWriter, r *http.Request) {
	content, err := a.store.SiteContent(r.Context())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	if err := a.tmpl.ExecuteTemplate(w, "index.html", content); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "Не удалось отрисовать страницу")
	}
}
