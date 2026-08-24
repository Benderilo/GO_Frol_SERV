// Команда server поднимает публичный сайт и API CRM «Фролов Системы».
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/api"
	"github.com/Benderilo/GO_Frol_SERV/internal/config"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"golang.org/x/crypto/acme/autocert"
)

// version подставляется при сборке: -ldflags "-X main.version=..."
var version = "dev"

func main() {
	if err := run(); err != nil {
		slog.Error("сервер остановлен с ошибкой", "err", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	setupLogger(cfg)

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	st, err := store.Open(ctx, cfg.DatabasePath)
	if err != nil {
		return err
	}
	defer st.Close()

	created, err := st.EnsureAdmin(ctx, cfg.AdminLogin, cfg.AdminPassword)
	if err != nil {
		return err
	}
	if created {
		slog.Warn("создан администратор по умолчанию — смените пароль из приложения",
			"login", cfg.AdminLogin)
	}

	a, err := api.New(cfg, st, version)
	if err != nil {
		return err
	}
	defer a.Close()

	handler := a.Handler()
	errCh := make(chan error, 2)
	servers := make([]*http.Server, 0, 2)

	if cfg.TLSEnabled() {
		httpsSrv, httpSrv, err := tlsServers(cfg, handler)
		if err != nil {
			return err
		}
		servers = append(servers, httpsSrv, httpSrv)

		go func() {
			slog.Info("сервер запущен по HTTPS",
				"addr", cfg.HTTPSAddr, "domains", cfg.Domains, "version", version)
			// Сертификат и ключ берутся из TLSConfig, поэтому пути пустые.
			if err := httpsSrv.ListenAndServeTLS("", ""); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errCh <- err
			}
		}()
		go func() {
			slog.Info("HTTP перенаправляет на HTTPS и отвечает на проверку сертификата",
				"addr", httpSrv.Addr)
			if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errCh <- err
			}
		}()
	} else {
		srv := newServer(cfg.Addr, handler)
		servers = append(servers, srv)

		go func() {
			slog.Info("сервер запущен без TLS",
				"addr", cfg.Addr, "env", cfg.Env, "version", version, "db", cfg.DatabasePath)
			if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errCh <- err
			}
		}()
	}

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		slog.Info("получен сигнал завершения, останавливаемся")
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer shutdownCancel()
	for _, srv := range servers {
		if err := srv.Shutdown(shutdownCtx); err != nil {
			slog.Error("остановка сервера", "addr", srv.Addr, "err", err)
		}
	}
	return nil
}

// tlsServers готовит пару: HTTPS с автоматическим сертификатом и HTTP,
// который отвечает на проверку домена и уводит всех остальных на HTTPS.
func tlsServers(cfg *config.Config, handler http.Handler) (*http.Server, *http.Server, error) {
	if err := os.MkdirAll(cfg.CertDir, 0o700); err != nil {
		return nil, nil, err
	}

	manager := &autocert.Manager{
		Prompt: autocert.AcceptTOS,
		// Выпускаем сертификат только для своих имён: иначе любой,
		// направивший на нас чужой домен, тратил бы наш лимит запросов.
		HostPolicy: autocert.HostWhitelist(cfg.Domains...),
		Cache:      autocert.DirCache(cfg.CertDir),
		Email:      cfg.ACMEEmail,
	}

	httpsSrv := newServer(cfg.HTTPSAddr, handler)
	httpsSrv.TLSConfig = manager.TLSConfig()

	redirect := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		target := "https://" + hostWithoutPort(r.Host) + r.URL.RequestURI()
		http.Redirect(w, r, target, http.StatusMovedPermanently)
	})
	httpSrv := newServer(":80", manager.HTTPHandler(redirect))

	return httpsSrv, httpSrv, nil
}

func newServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       5 * time.Minute,
		WriteTimeout:      5 * time.Minute,
		IdleTimeout:       90 * time.Second,
	}
}

// hostWithoutPort убирает порт из заголовка Host: в адрес перенаправления
// он попасть не должен, иначе браузер уйдёт на https://example.ru:80.
func hostWithoutPort(host string) string {
	for i := len(host) - 1; i >= 0; i-- {
		if host[i] == ':' {
			return host[:i]
		}
		if host[i] == ']' {
			break
		}
	}
	return host
}

func setupLogger(cfg *config.Config) {
	level := slog.LevelDebug
	if cfg.IsProd() {
		level = slog.LevelInfo
	}
	var handler slog.Handler
	if cfg.IsProd() {
		handler = slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	} else {
		handler = slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	}
	slog.SetDefault(slog.New(handler))
}
