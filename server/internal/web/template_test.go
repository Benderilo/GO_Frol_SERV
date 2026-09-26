package web

import (
	"strings"
	"testing"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Рендер лендинга с выставленным canonical: SEO-теги, favicon, лампочка
// и машиночитаемые часы должны попасть в разметку.
func TestLandingRender(t *testing.T) {
	tmpl, err := Templates("test-version")
	if err != nil {
		t.Fatalf("разбор шаблонов: %v", err)
	}

	content := store.DefaultSiteContent()
	content.Contacts.Phone = "89271465666"
	data := struct {
		store.SiteContent
		Canonical string
		City      string
		Region    string
	}{
		SiteContent: content,
		Canonical:   "https://example.ru",
		City:        "Балаково",
		Region:      "Саратовская область",
	}

	var sb strings.Builder
	if err := tmpl.ExecuteTemplate(&sb, "index.html", data); err != nil {
		t.Fatalf("рендер index.html: %v", err)
	}
	html := sb.String()

	for _, want := range []string{
		`<meta property="og:image" content="https://example.ru/static/og.png?v=test-version">`,
		`<meta property="og:site_name"`,
		`<link rel="icon" href="/favicon.ico"`,
		`"openingHours": "Mo-Su 08:00-21:00"`,
		// html/template экранирует «+»: в HTML — &#43;, внутри script — юникод-последовательность.
		`"telephone": "\u002b79271465666"`,
		`tel:&#43;79271465666`,
		`&#43;7 (927) 146-56-66`,
		`class="hero__bulb"`,
	} {
		if !strings.Contains(html, want) {
			t.Errorf("в разметке нет %q", want)
		}
	}

	// Свободный текст часов в JSON-LD не должен попадать — только схема.
	if strings.Contains(html, `"openingHours": "Пн`) {
		t.Error("в JSON-LD попал отображаемый текст часов вместо схемы")
	}
}
