package maxbot

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/config"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// WebhookPath — куда MAX присылает события.
const WebhookPath = "/api/v1/max/webhook"

// Service — бот вместе с доставкой событий.
type Service struct {
	*Bot
	client  *Client
	hookURL string // пусто — события забираем долгим опросом
	secret  string
	pending sync.WaitGroup // события в обработке — тесты дожидаются их
}

// New собирает бота по настройкам; nil — токен не задан, бот выключен.
func New(cfg *config.Config, st *store.Store) *Service {
	if cfg.MaxToken == "" {
		return nil
	}
	client := NewClient(cfg.MaxAPI, cfg.MaxToken)
	var ai brain
	if cfg.AIURL != "" {
		ai = newOllama(cfg.AIURL, cfg.AIKey, cfg.AIModel, cfg.AITimeout)
	}
	s := &Service{
		Bot:    newBot(st, client, ai),
		client: client,
		secret: webhookSecret(cfg.JWTSecret),
	}
	// Вебхук возможен только на домене с настоящим сертификатом.
	if cfg.TLSEnabled() {
		s.hookURL = "https://" + cfg.Domains[0] + WebhookPath
	}
	slog.Info("бот MAX включён", "webhook", s.hookURL != "", "ai", ai != nil, "model", cfg.AIModel)
	return s
}

// webhookSecret выводится из секрета сервера: отдельно хранить нечего,
// а посторонний без него не подсунет нам поддельную заявку.
func webhookSecret(serverSecret []byte) string {
	sum := sha256.Sum256(append([]byte("frolov-max-webhook:"), serverSecret...))
	return hex.EncodeToString(sum[:])[:48]
}

// Run доставляет события до остановки сервера.
func (s *Service) Run(ctx context.Context) {
	if s.hookURL == "" {
		s.poll(ctx)
		return
	}
	s.keepSubscribed(ctx)
}

// keepSubscribed оформляет подписку и раз в час проверяет, что она жива:
// если сервер лежал дольше восьми часов, MAX снимает её сам.
func (s *Service) keepSubscribed(ctx context.Context) {
	// Первую проверку откладываем на пару секунд: сертификат для домена
	// может выпускаться прямо сейчас, а MAX проверит адрес сразу.
	wait := 5 * time.Second
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		ok, err := s.client.Subscribed(ctx, s.hookURL)
		if err == nil && !ok {
			err = s.client.Subscribe(ctx, s.hookURL, s.secret)
			if err == nil {
				slog.Info("MAX: подписка на события оформлена", "url", s.hookURL)
			}
		}
		if err != nil {
			slog.Error("MAX: подписка на события", "err", err)
			wait = time.Minute
			continue
		}
		wait = time.Hour
	}
}

func (s *Service) poll(ctx context.Context) {
	slog.Info("MAX: домена нет — забираю события долгим опросом")
	var marker *int64
	for ctx.Err() == nil {
		updates, next, err := s.client.Updates(ctx, marker)
		if err != nil {
			if ctx.Err() == nil {
				slog.Warn("MAX: опрос событий", "err", err)
				time.Sleep(5 * time.Second)
			}
			continue
		}
		marker = next
		for _, u := range updates {
			s.handleAsync(u)
		}
	}
}

// handleAsync обрабатывает событие отдельно от запроса: ответ ИИ может
// занять десятки секунд, а MAX ждёт подтверждения доставки быстро.
func (s *Service) handleAsync(u Update) {
	s.pending.Add(1)
	go func() {
		defer s.pending.Done()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		defer func() {
			if r := recover(); r != nil {
				slog.Error("MAX: паника при обработке события", "panic", r)
			}
		}()
		s.Handle(ctx, u)
	}()
}

// ServeHTTP принимает события от MAX.
func (s *Service) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	got := r.Header.Get("X-Max-Bot-Api-Secret")
	if subtle.ConstantTimeCompare([]byte(got), []byte(s.secret)) != 1 {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var u Update
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&u); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	s.handleAsync(u)
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"ok":true}`))
}
