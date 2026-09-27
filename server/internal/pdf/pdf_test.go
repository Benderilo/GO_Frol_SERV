package pdf

import (
	"bytes"
	"context"
	"testing"
)

// Печатает настоящим браузером, если он есть на машине (Edge под Windows,
// chrome-headless-shell на сервере); иначе пропускается.
func TestRender(t *testing.T) {
	r := New("")
	if r == nil {
		t.Skip("браузер для печати не найден")
	}
	out, err := r.Render(context.Background(),
		[]byte(`<!doctype html><meta charset="utf-8"><style>@page{size:A4}</style><h1>Счёт № 12</h1><p>Итого: 1 200 ₽</p>`))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(out, []byte("%PDF-")) {
		t.Fatalf("не PDF: %q", out[:min(len(out), 20)])
	}
}

func TestUnavailable(t *testing.T) {
	var r *Renderer
	if _, err := r.Render(context.Background(), nil); err != ErrUnavailable {
		t.Fatalf("без браузера — ErrUnavailable, а не %v", err)
	}
}
