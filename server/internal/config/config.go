// Package config загружает настройки сервера из переменных окружения.
package config

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Env             string        // dev | prod
	Addr            string        // адрес прослушивания обычного HTTP
	HTTPSAddr       string        // адрес для TLS, обычно :443
	Domains         []string      // домены для сертификата Let's Encrypt
	ACMEEmail       string        // почта для уведомлений удостоверяющего центра
	CertDir         string        // где хранятся выданные сертификаты
	DatabasePath    string        // путь к файлу SQLite
	UploadsDir      string        // каталог с загруженными фотографиями
	JWTSecret       []byte        // секрет для подписи токенов
	TokenTTL        time.Duration // срок жизни access-токена
	AdminLogin      string        // логин администратора при первом запуске
	AdminPassword   string        // пароль администратора при первом запуске
	AllowedOrigins  []string      // CORS
	ShutdownTimeout time.Duration
}

func Load() (*Config, error) {
	cfg := &Config{
		Env:             env("FROLOV_ENV", "dev"),
		Addr:            env("FROLOV_ADDR", ":8080"),
		HTTPSAddr:       env("FROLOV_HTTPS_ADDR", ":443"),
		ACMEEmail:       os.Getenv("FROLOV_ACME_EMAIL"),
		CertDir:         os.Getenv("FROLOV_CERT_DIR"),
		DatabasePath:    env("FROLOV_DB", "data/frolov.db"),
		UploadsDir:      os.Getenv("FROLOV_UPLOADS"),
		TokenTTL:        envDuration("FROLOV_TOKEN_TTL", 720*time.Hour),
		AdminLogin:      env("FROLOV_ADMIN_LOGIN", "admin"),
		AdminPassword:   env("FROLOV_ADMIN_PASSWORD", "admin"),
		AllowedOrigins:  strings.Split(env("FROLOV_CORS_ORIGINS", "*"), ","),
		ShutdownTimeout: envDuration("FROLOV_SHUTDOWN_TIMEOUT", 15*time.Second),
	}

	secret := os.Getenv("FROLOV_JWT_SECRET")
	if secret == "" {
		if cfg.Env == "prod" {
			return nil, fmt.Errorf("FROLOV_JWT_SECRET обязателен в prod-режиме")
		}
		buf := make([]byte, 32)
		if _, err := rand.Read(buf); err != nil {
			return nil, err
		}
		secret = hex.EncodeToString(buf)
	}
	cfg.JWTSecret = []byte(secret)

	// Домены перечисляются через запятую; пустой список означает работу без TLS.
	for _, d := range strings.Split(os.Getenv("FROLOV_DOMAIN"), ",") {
		if d = strings.TrimSpace(d); d != "" {
			cfg.Domains = append(cfg.Domains, d)
		}
	}
	if cfg.CertDir == "" {
		cfg.CertDir = filepath.Join(filepath.Dir(cfg.DatabasePath), "certs")
	}

	// По умолчанию складываем фотографии рядом с базой: так каталог данных
	// остаётся единым и его целиком видно в ReadWritePaths systemd-юнита.
	if cfg.UploadsDir == "" {
		cfg.UploadsDir = filepath.Join(filepath.Dir(cfg.DatabasePath), "uploads")
	}

	return cfg, nil
}

func (c *Config) IsProd() bool { return c.Env == "prod" }

// TLSEnabled — сертификат получаем сами, но только если известно, для какого имени.
// На голый IP удостоверяющий центр сертификат не выдаст.
func (c *Config) TLSEnabled() bool { return len(c.Domains) > 0 }

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envDuration(key string, fallback time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	if d, err := time.ParseDuration(v); err == nil {
		return d
	}
	if n, err := strconv.Atoi(v); err == nil {
		return time.Duration(n) * time.Second
	}
	return fallback
}
