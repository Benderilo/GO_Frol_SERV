// Package config загружает настройки сервера из переменных окружения.
package config

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Env             string        // dev | prod
	Addr            string        // адрес прослушивания, например :8080
	DatabasePath    string        // путь к файлу SQLite
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
		DatabasePath:    env("FROLOV_DB", "data/frolov.db"),
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

	return cfg, nil
}

func (c *Config) IsProd() bool { return c.Env == "prod" }

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
