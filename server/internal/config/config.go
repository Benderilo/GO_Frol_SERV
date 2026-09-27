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
	Env          string        // dev | prod
	Addr         string        // адрес прослушивания обычного HTTP
	HTTPSAddr    string        // адрес для TLS, обычно :443
	Domains      []string      // домены для сертификата Let's Encrypt
	ACMEEmail    string        // почта для уведомлений удостоверяющего центра
	CertDir      string        // где хранятся выданные сертификаты
	DatabasePath string        // путь к файлу SQLite
	UploadsDir   string        // каталог с загруженными фотографиями
	JWTSecret    []byte        // секрет для подписи токенов
	TokenTTL     time.Duration // срок жизни access-токена; по умолчанию 10 лет —
	//              вход в приложение не сбрасывается сам, только сменой пароля
	//              на сервере или ротацией секрета
	AdminLogin      string   // логин администратора при первом запуске
	AdminPassword   string   // пароль администратора при первом запуске
	AllowedOrigins  []string // CORS
	ShutdownTimeout time.Duration

	// Бот в MAX. Без токена бот не запускается, остальное работает как было.
	MaxToken string // токен из кабинета партнёра MAX
	// Адрес Bot API. Документация MAX называет platform-api2, но его сертификат
	// выдан корнем Минцифры, которого нет в системных хранилищах, — сервер
	// ему не доверяет. Старый адрес отвечает тем же API с обычным сертификатом.
	MaxAPI string

	// ИИ для бота — любой сервер с API Ollama (/api/chat): свой или ollama.com.
	// Без адреса бот ведёт клиента одними кнопками.
	AIURL     string
	AIKey     string // ключ для ollama.com; для своего сервера обычно не нужен
	AIModel   string
	AITimeout time.Duration
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
		TokenTTL:        envDuration("FROLOV_TOKEN_TTL", 87600*time.Hour),
		AdminLogin:      env("FROLOV_ADMIN_LOGIN", "admin"),
		AdminPassword:   env("FROLOV_ADMIN_PASSWORD", "admin"),
		AllowedOrigins:  strings.Split(env("FROLOV_CORS_ORIGINS", "*"), ","),
		ShutdownTimeout: envDuration("FROLOV_SHUTDOWN_TIMEOUT", 15*time.Second),
		MaxToken:        os.Getenv("FROLOV_MAX_TOKEN"),
		MaxAPI:          strings.TrimRight(env("FROLOV_MAX_API", "https://platform-api.max.ru"), "/"),
		AIURL:           strings.TrimRight(os.Getenv("FROLOV_AI_URL"), "/"),
		AIKey:           os.Getenv("FROLOV_AI_KEY"),
		AIModel:         env("FROLOV_AI_MODEL", "gpt-oss:120b"),
		AITimeout:       envDuration("FROLOV_AI_TIMEOUT", 45*time.Second),
	}
	cfg.MaxToken = strings.TrimSpace(cfg.MaxToken)
	cfg.AIURL = strings.TrimSpace(cfg.AIURL)
	cfg.AIKey = strings.TrimSpace(cfg.AIKey)

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
