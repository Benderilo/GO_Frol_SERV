package maxbot

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Живая проверка на настоящей модели:
//
//	FROLOV_AI_TEST_URL=http://localhost:11434 FROLOV_AI_TEST_MODEL=qwen3.8:27b go test -run Live -v ./internal/maxbot
func TestLiveAI(t *testing.T) {
	url := os.Getenv("FROLOV_AI_TEST_URL")
	if url == "" {
		t.Skip("FROLOV_AI_TEST_URL не задан")
	}
	ai := newOllama(url, os.Getenv("FROLOV_AI_TEST_KEY"), os.Getenv("FROLOV_AI_TEST_MODEL"), 3*time.Minute)
	bot, out, _ := newTestBot(t, ai)
	ctx := context.Background()
	for _, msg := range []string{
		"Добрый день! Какая завтра погода?",
		"У меня на кухне искрит розетка, что делать?",
		"Телефон 89271234567, адрес Балаково, ул. Ленина 20, кв 5",
	} {
		bot.Handle(ctx, text(msg))
		m := out.last(t)
		t.Logf("\nКлиент: %s\nБот: %s\nКнопки: %v", msg, m.Text, buttons(m))
	}
	d, _ := bot.crm.(*store.Store).MaxDialog(ctx, uid)
	t.Logf("Заявка: тел=%q адрес=%q суть=%q шаг=%q", d.Phone, d.Address, d.Problem, d.Step)
}

// Живая проверка поведения в группе: болтовня, заказ, обращение по имени.
func TestLiveAIGroup(t *testing.T) {
	url := os.Getenv("FROLOV_AI_TEST_URL")
	if url == "" {
		t.Skip("FROLOV_AI_TEST_URL не задан")
	}
	ai := newOllama(url, os.Getenv("FROLOV_AI_TEST_KEY"), os.Getenv("FROLOV_AI_TEST_MODEL"), 3*time.Minute)
	for _, msg := range []string{
		"Соседи опять затеяли ремонт, сверлят с утра",
		"Кто знает хорошего электрика? Дома выбивает автомат, когда включаю чайник",
		"Олег, а вы в выходные работаете?",
		"Сколько стоит поменять розетки? Штук пять",
	} {
		bot, out, _ := newTestBot(t, ai)
		bot.SetSelf(botSelf)
		bot.Handle(context.Background(), inGroup(msg))
		if out.count() == 0 {
			t.Logf("\nГруппа: %s\nБот: (молчит)", msg)
			continue
		}
		t.Logf("\nГруппа: %s\nБот: %s", msg, out.last(t).Text)
	}
}
