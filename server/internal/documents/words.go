// Package documents собирает печатные документы по заказу: счёт и акт.
package documents

import (
	"fmt"
	"strings"
)

// Склонения для рублей и копеек. Порядок: 1, 2–4, 5–20.
type forms struct {
	one, few, many string
	feminine       bool
}

var (
	rubleForms  = forms{"рубль", "рубля", "рублей", false}
	kopeckForms = forms{"копейка", "копейки", "копеек", true}
	thousands   = forms{"тысяча", "тысячи", "тысяч", true}
	millions    = forms{"миллион", "миллиона", "миллионов", false}
	billions    = forms{"миллиард", "миллиарда", "миллиардов", false}
)

var (
	unitsMale   = [...]string{"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"}
	unitsFemale = [...]string{"", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"}
	teens       = [...]string{
		"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
		"пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
	}
	tens = [...]string{
		"", "", "двадцать", "тридцать", "сорок", "пятьдесят",
		"шестьдесят", "семьдесят", "восемьдесят", "девяносто",
	}
	hundreds = [...]string{
		"", "сто", "двести", "триста", "четыреста", "пятьсот",
		"шестьсот", "семьсот", "восемьсот", "девятьсот",
	}
)

// plural выбирает форму слова по числу: 1 рубль, 2 рубля, 5 рублей.
// Одиннадцать–четырнадцать — исключение: у них форма как у пяти.
func plural(n int64, f forms) string {
	if n < 0 {
		n = -n
	}
	mod100 := n % 100
	if mod100 >= 11 && mod100 <= 14 {
		return f.many
	}
	switch n % 10 {
	case 1:
		return f.one
	case 2, 3, 4:
		return f.few
	default:
		return f.many
	}
}

// tripletWords переводит число 0–999 в слова.
func tripletWords(n int64, feminine bool) []string {
	units := unitsMale
	if feminine {
		units = unitsFemale
	}
	var out []string
	if h := n / 100; h > 0 {
		out = append(out, hundreds[h])
	}
	rest := n % 100
	switch {
	case rest >= 10 && rest <= 19:
		out = append(out, teens[rest-10])
	default:
		if t := rest / 10; t > 0 {
			out = append(out, tens[t])
		}
		if u := rest % 10; u > 0 {
			out = append(out, units[u])
		}
	}
	return out
}

// numberWords переводит целое число в слова с разрядами.
func numberWords(n int64, last forms) string {
	if n == 0 {
		return "ноль"
	}
	groups := []struct {
		value int64
		form  forms
	}{
		{n / 1_000_000_000 % 1000, billions},
		{n / 1_000_000 % 1000, millions},
		{n / 1000 % 1000, thousands},
		{n % 1000, last},
	}

	var words []string
	for i, g := range groups {
		if g.value == 0 {
			continue
		}
		words = append(words, tripletWords(g.value, g.form.feminine)...)
		// Само слово разряда добавляем всем, кроме последней тройки:
		// её единица измерения («рубль») ставится отдельно, уже за числом.
		if i < len(groups)-1 {
			words = append(words, plural(g.value, g.form))
		}
	}
	return strings.Join(words, " ")
}

// MoneyWords — сумма прописью для печатного документа:
// 199999 копеек → «Одна тысяча девятьсот девяносто девять рублей 99 копеек».
//
// Копейки цифрами — так их пишут в счетах: словами они только удлиняют
// строку, а перепутать две цифры сложнее, чем длинную фразу.
func MoneyWords(kop int64) string {
	sign := ""
	if kop < 0 {
		sign = "минус "
		kop = -kop
	}
	rub, cents := kop/100, kop%100

	text := sign + numberWords(rub, rubleForms) + " " + plural(rub, rubleForms) +
		fmt.Sprintf(" %02d ", cents) + plural(cents, kopeckForms)

	// С заглавной: в счёте эта строка начинает предложение.
	runes := []rune(text)
	return strings.ToUpper(string(runes[0])) + string(runes[1:])
}

// QuantityWords не нужен, а вот число позиций прописью в счёте пишут:
// «Всего наименований 3, на сумму …».
func ItemsWord(n int) string {
	return plural(int64(n), forms{"наименование", "наименования", "наименований", false})
}
