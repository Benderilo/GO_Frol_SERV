package main

import "testing"

// В адрес перенаправления порт попасть не должен: браузер ушёл бы
// на https://example.ru:80 и получил бы ошибку.
func TestHostWithoutPort(t *testing.T) {
	cases := map[string]string{
		"example.ru":       "example.ru",
		"example.ru:80":    "example.ru",
		"example.ru:8080":  "example.ru",
		"91.184.246.64:80": "91.184.246.64",
		"[::1]:80":         "[::1]",
		"[2001:db8::1]":    "[2001:db8::1]",
		"":                 "",
	}
	for input, want := range cases {
		if got := hostWithoutPort(input); got != want {
			t.Errorf("hostWithoutPort(%q) = %q, ожидалось %q", input, got, want)
		}
	}
}
