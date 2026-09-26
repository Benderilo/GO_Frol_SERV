package web

import "testing"

func TestPrettyPhone(t *testing.T) {
	cases := map[string]string{
		"89271465666":        "+7 (927) 146-56-66",
		"79271465666":        "+7 (927) 146-56-66",
		"+7 (927) 146-56-66": "+7 (927) 146-56-66",
		"+7 927 146 56 66":   "+7 (927) 146-56-66",
		"8 (927) 146-56-66":  "+7 (927) 146-56-66",
		"123":                "123",        // слишком короткий — как есть
		"8927146566":         "8927146566", // 10 цифр — как есть
		"abc":                "abc",        // не номер — как есть
		"":                   "",
	}
	for in, want := range cases {
		if got := prettyPhone(in); got != want {
			t.Errorf("prettyPhone(%q) = %q, хотим %q", in, got, want)
		}
	}
}

func TestE164Phone(t *testing.T) {
	cases := map[string]string{
		"89271465666":        "+79271465666",
		"+7 (927) 146-56-66": "+79271465666",
		"123":                "123",
		"":                   "",
	}
	for in, want := range cases {
		if got := e164Phone(in); got != want {
			t.Errorf("e164Phone(%q) = %q, хотим %q", in, got, want)
		}
	}
}
