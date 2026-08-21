// Package web хранит встроенные в бинарник шаблоны и статику публичного сайта.
package web

import (
	"embed"
	"html/template"
	"io/fs"
)

//go:embed templates/*.html
var templatesFS embed.FS

//go:embed static
var staticFS embed.FS

// Templates разбирает шаблоны публичного сайта.
func Templates() (*template.Template, error) {
	return template.New("").Funcs(funcs()).ParseFS(templatesFS, "templates/*.html")
}

// Static отдаёт содержимое каталога static как файловую систему.
func Static() (fs.FS, error) {
	return fs.Sub(staticFS, "static")
}

func funcs() template.FuncMap {
	return template.FuncMap{
		// safeCSS позволяет подставить цвет из настроек в inline-стиль.
		"safeCSS": func(v string) template.CSS { return template.CSS(v) },
	}
}
