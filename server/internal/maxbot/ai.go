package maxbot

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// aiReply — что ИИ ответил и что понял из слов клиента. Пустое поле —
// «клиент этого не сообщал», а не «стереть».
type aiReply struct {
	Reply      string `json:"reply"`
	Phone      string `json:"phone"`
	Address    string `json:"address"`
	Problem    string `json:"problem"`
	WantsOrder bool   `json:"wants_order"`
}

// brain — собеседник. Настоящий ходит в Ollama, в тестах — подставной.
type brain interface {
	Think(ctx context.Context, system string, history []store.ChatTurn) (aiReply, error)
}

// ollama говорит с любым сервером API Ollama: своим или ollama.com.
type ollama struct {
	url, key, model string
	http            *http.Client
}

func newOllama(url, key, model string, timeout time.Duration) *ollama {
	return &ollama{url: url, key: key, model: model, http: &http.Client{Timeout: timeout}}
}

// Ответ просим строго по схеме: так из свободного разговора получаются
// поля заявки, а не текст, который потом пришлось бы разбирать.
var replySchema = map[string]any{
	"type": "object",
	"properties": map[string]any{
		"reply":       map[string]any{"type": "string"},
		"phone":       map[string]any{"type": "string"},
		"address":     map[string]any{"type": "string"},
		"problem":     map[string]any{"type": "string"},
		"wants_order": map[string]any{"type": "boolean"},
	},
	"required": []string{"reply", "phone", "address", "problem", "wants_order"},
}

func (o *ollama) Think(ctx context.Context, system string, history []store.ChatTurn) (aiReply, error) {
	type msg struct {
		Role    string `json:"role"`
		Content string `json:"content"`
	}
	msgs := []msg{{Role: "system", Content: system}}
	for _, t := range history {
		msgs = append(msgs, msg{Role: t.Role, Content: t.Text})
	}
	raw, err := json.Marshal(map[string]any{
		"model":    o.model,
		"messages": msgs,
		"stream":   false,
		"format":   replySchema,
		"options":  map[string]any{"temperature": 0.4},
	})
	if err != nil {
		return aiReply{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, o.url+"/api/chat", bytes.NewReader(raw))
	if err != nil {
		return aiReply{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	if o.key != "" {
		req.Header.Set("Authorization", "Bearer "+o.key)
	}
	resp, err := o.http.Do(req)
	if err != nil {
		return aiReply{}, fmt.Errorf("ИИ: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return aiReply{}, fmt.Errorf("ИИ: %d %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var out struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return aiReply{}, fmt.Errorf("ИИ: разбор ответа: %w", err)
	}
	return parseReply(out.Message.Content)
}

var fieldLeak = regexp.MustCompile(`(?i)[;,\s]*(phone|address|problem|wants_order)\s*:`)

// parseReply разбирает ответ модели. Модель, забывшая про схему, всё равно
// сказала что-то осмысленное — такой текст отдаём клиенту как есть.
func parseReply(content string) (aiReply, error) {
	content = strings.TrimSpace(content)
	if content == "" {
		return aiReply{}, fmt.Errorf("ИИ: пустой ответ")
	}
	var r aiReply
	if err := json.Unmarshal([]byte(content), &r); err != nil {
		return aiReply{Reply: content}, nil
	}
	// Модель иногда дублирует поля в тексте: «…; phone: ; address: …».
	if loc := fieldLeak.FindStringIndex(r.Reply); loc != nil {
		r.Reply = r.Reply[:loc[0]]
	}
	r.Reply = strings.TrimSpace(r.Reply)
	if r.Reply == "" {
		return aiReply{}, fmt.Errorf("ИИ: ответ без текста")
	}
	return r, nil
}
