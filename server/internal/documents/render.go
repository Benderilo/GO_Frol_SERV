package documents

import (
	"embed"
	"fmt"
	"html/template"
	"io"
	"strings"
	"time"
)

//go:embed templates/*.html
var templatesFS embed.FS

// Line — строка табличной части документа.
type Line struct {
	Number   int
	Name     string
	Quantity string
	Unit     string
	Price    string
	Total    string
}

// Party — сторона документа: кто выставил и кому.
type Party struct {
	Name    string
	INN     string
	OGRNIP  string
	Address string
	Phone   string
	Email   string
}

// Bank — реквизиты для оплаты. Пустой банк в счёте не печатаем:
// пустая рамка выглядит как незаполненный бланк.
type Bank struct {
	Name        string
	BIK         string
	Account     string
	CorrAccount string
}

func (b Bank) Filled() bool { return strings.TrimSpace(b.Account) != "" }

// Data — всё, что нужно шаблону. Суммы уже отформатированы строками:
// шаблону незачем знать про копейки, а формат должен быть один и тот же
// в счёте, в акте и на экране.
type Data struct {
	Kind      string // invoice | act
	Title     string
	Number    int
	Year      int
	IssuedAt  string // «03 сентября 2026 г.»
	OrderNo   int64
	OrderName string

	Supplier Party
	Customer Party
	Bank     Bank

	Lines      []Line
	Total      string
	TotalWords string
	ItemsCount int
	ItemsWord  string

	TaxNote    string
	FooterNote string

	SignerName  string
	SignerTitle string
}

var monthNames = [...]string{
	"января", "февраля", "марта", "апреля", "мая", "июня",
	"июля", "августа", "сентября", "октября", "ноября", "декабря",
}

// FormatDate — «3 сентября 2026 г.». Русский формат, а не ISO: документ
// читает человек, и дата в нём пишется словами.
func FormatDate(value string) string {
	t, err := time.Parse(time.RFC3339, value)
	if err != nil {
		return value
	}
	return fmt.Sprintf("%d %s %d г.", t.Day(), monthNames[t.Month()-1], t.Year())
}

// Money — «48 500,50» без знака рубля: он стоит в шапке колонки.
func Money(kop int64) string {
	sign := ""
	if kop < 0 {
		sign, kop = "−", -kop
	}
	whole := groupThousands(kop / 100)
	return fmt.Sprintf("%s%s,%02d", sign, whole, kop%100)
}

// Quantity — «2,5» без хвостовых нулей: в бланке они только мешают.
func Quantity(milli int64) string {
	sign := ""
	if milli < 0 {
		sign, milli = "−", -milli
	}
	frac := milli % 1000
	if frac == 0 {
		return fmt.Sprintf("%s%d", sign, milli/1000)
	}
	// Хвостовые нули убираем: «42,5», а не «42,500» — в бланке лишние
	// разряды читаются как другая точность.
	return sign + strings.TrimRight(fmt.Sprintf("%d,%03d", milli/1000, frac), "0")
}

func groupThousands(v int64) string {
	digits := fmt.Sprintf("%d", v)
	var out []byte
	for i, c := range []byte(digits) {
		if i > 0 && (len(digits)-i)%3 == 0 {
			out = append(out, ' ')
		}
		out = append(out, c)
	}
	return string(out)
}

// Render печатает документ в w. Шаблон выбирается по виду.
func Render(w io.Writer, data Data) error {
	tmpl, err := template.ParseFS(templatesFS, "templates/*.html")
	if err != nil {
		return fmt.Errorf("разбор шаблонов документа: %w", err)
	}
	name := "invoice.html"
	if data.Kind == "act" {
		name = "act.html"
	}
	return tmpl.ExecuteTemplate(w, name, data)
}
