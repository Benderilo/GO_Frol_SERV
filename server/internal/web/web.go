// Package web хранит встроенные в бинарник шаблоны и статику публичного сайта.
package web

import (
	"embed"
	"html/template"
	"io/fs"
	"strings"
)

//go:embed templates/*.html
var templatesFS embed.FS

//go:embed static
var staticFS embed.FS

// Templates разбирает шаблоны публичного сайта.
// version подставляется в ссылки на статику, чтобы браузеры не держали
// старые CSS и JS после обновления сервера.
func Templates(version string) (*template.Template, error) {
	return template.New("").Funcs(funcs(version)).ParseFS(templatesFS, "templates/*.html")
}

// Static отдаёт содержимое каталога static как файловую систему.
func Static() (fs.FS, error) {
	return fs.Sub(staticFS, "static")
}

func funcs(version string) template.FuncMap {
	return template.FuncMap{
		// safeCSS позволяет подставить цвет из настроек в inline-стиль.
		"safeCSS": func(v string) template.CSS { return template.CSS(v) },
		// asset добавляет к пути метку версии: /static/app.js?v=abc123
		"asset": func(path string) string { return path + "?v=" + version },
		// phonePretty — номер для показа человеку, phoneE164 — для tel: и schema.org.
		"phonePretty": prettyPhone,
		"phoneE164":   e164Phone,
	}
}

// prettyPhone приводит российский номер к виду +7 (927) 146-56-66.
// Всё, что не 11 цифр с ведущей 8 или 7, возвращает без изменений.
func prettyPhone(s string) string {
	d := onlyDigits(s)
	if len(d) == 11 && (d[0] == '8' || d[0] == '7') {
		return "+7 (" + d[1:4] + ") " + d[4:7] + "-" + d[7:9] + "-" + d[9:11]
	}
	return s
}

// e164Phone — машинный вид номера: +79271465666.
func e164Phone(s string) string {
	d := onlyDigits(s)
	if len(d) == 11 && (d[0] == '8' || d[0] == '7') {
		return "+7" + d[1:]
	}
	return s
}

func onlyDigits(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}
