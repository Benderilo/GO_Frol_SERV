package documents

import (
	"embed"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"strings"
	"time"
)

//go:embed templates/*.html
var templatesFS embed.FS

// Line — строка табличной части документа. Отформатированные поля печатает
// шаблон; сырые (в копейках и тысячных долях) нужны приложению для правки
// черновика — по ним сервер пересчитывает формат при сохранении.
type Line struct {
	Number   int    `json:"number"`
	Name     string `json:"name"`
	Quantity string `json:"quantity"`
	Unit     string `json:"unit"`
	Price    string `json:"price"`
	Total    string `json:"total"`
	Vat      string `json:"vat"` // «в т.ч. НДС» — печатается при ставке больше нуля

	QtyMilli int64 `json:"qtyMilli"`
	PriceKop int64 `json:"priceKop"`
	TotalKop int64 `json:"totalKop"`
	VatKop   int64 `json:"vatKop"`
}

// Party — сторона документа: кто выставил и кому.
type Party struct {
	Name    string `json:"name"`
	INN     string `json:"inn"`
	KPP     string `json:"kpp"`
	OGRNIP  string `json:"ogrnip"`
	Address string `json:"address"`
	Phone   string `json:"phone"`
	Email   string `json:"email"`
	// Банк контрагента — для накладной и УПД. У исполнителя-ИП пусто,
	// его банк печатается отдельной таблицей реквизитов.
	BankName    string `json:"bankName"`
	BankAccount string `json:"bankAccount"`
}

// Bank — реквизиты для оплаты. Пустой банк в счёте не печатаем:
// пустая рамка выглядит как незаполненный бланк.
type Bank struct {
	Name        string `json:"name"`
	BIK         string `json:"bik"`
	Account     string `json:"account"`
	CorrAccount string `json:"corrAccount"`
}

func (b Bank) Filled() bool { return strings.TrimSpace(b.Account) != "" }

// LedgerRow — строка акта сверки: операция взаиморасчётов с нарастающим
// итогом. Дебет — начисление (заказ, документ), кредит — оплата.
type LedgerRow struct {
	Date    string `json:"date"`    // «12.05.2026»
	Label   string `json:"label"`   // «Заказ № 45 «Замена проводки»»
	Debit   string `json:"debit"`   // отформатировано, пусто — если 0
	Credit  string `json:"credit"`
	Balance string `json:"balance"`

	DebitKop   int64 `json:"debitKop"`
	CreditKop  int64 `json:"creditKop"`
	BalanceKop int64 `json:"balanceKop"`
}

// Data — всё, что нужно шаблону. Это же — содержимое снимка в базе:
// сериализуется в documents.snapshot и живёт в документе от черновика
// до аннулирования. Суммы отформатированы строками: шаблону незачем знать
// про копейки, а формат должен быть один и тот же в счёте, в акте и на экране.
type Data struct {
	// Версия снимка: при изменении структуры старые документы обязаны
	// читаться новыми версиями сервера.
	V int `json:"v"`

	Kind      string `json:"kind"` // invoice | act | estimate | waybill | upd | reconciliation
	Title     string `json:"title"`
	Number    int    `json:"number"`
	Year      int    `json:"year"`
	DocDate   string `json:"docDate"`  // ISO YYYY-MM-DD — дата документа
	IssuedAt  string `json:"issuedAt"` // «3 сентября 2026 г.» — та же дата словами
	OrderNo   int64  `json:"orderNo"`
	OrderName string `json:"orderName"`

	Supplier Party `json:"supplier"`
	Customer Party `json:"customer"`
	Bank     Bank  `json:"bank"`

	Lines      []Line `json:"lines"`
	Total      string `json:"total"`
	TotalKop   int64  `json:"totalKop"`
	TotalWords string `json:"totalWords"`
	ItemsCount int    `json:"itemsCount"`
	ItemsWord  string `json:"itemsWord"`

	// НДС «в том числе»: ставка в процентах, 0 — не работаем с НДС.
	VatRate     int    `json:"vatRate"`
	VatRateText string `json:"vatRateText"` // «20%» или «без НДС»
	VatTotal    string `json:"vatTotal"`
	VatTotalKop int64  `json:"vatTotalKop"`

	// Акт сверки: период и таблица операций вместо строк товара.
	PeriodFrom string      `json:"periodFrom"`
	PeriodTo   string      `json:"periodTo"`
	Ledger     []LedgerRow `json:"ledger"`
	Opening    string      `json:"opening"`
	OpeningKop int64       `json:"openingKop"`
	Closing    string      `json:"closing"`
	ClosingKop int64       `json:"closingKop"`

	// Смета/КП: до какой даты предложение действительно.
	ValidUntil string `json:"validUntil"`

	TaxNote    string `json:"taxNote"`
	FooterNote string `json:"footerNote"`

	SignerName  string `json:"signerName"`
	SignerTitle string `json:"signerTitle"`

	// Аннулированный документ печатается со штампом — его нельзя выдать
	// за действующий даже по ошибке.
	Annulled bool `json:"annulled"`

	// Live помечает документы легаси-ручки «счёт и акт по заказу»: их
	// содержимое пересобирается из живых данных заказа при каждом открытии,
	// как это работало до снимков. Документы нового потока не помечены —
	// их снимок заморожен навсегда, и легаси-ручка его не перезапишет.
	Live bool `json:"live,omitempty"`
}

// SnapshotVersion — версия, которую пишет нынешний сервер.
const SnapshotVersion = 1

// MarshalJSON не даёт табличным частям превратиться в null: nil-срез
// кодируется Go как null, а приложение ждёт массив даже пустой. Поэтому
// перед кодированием nil заменяется на пустой срез — и в свежих снимках,
// и при пересборке карточки из старых снимков с null внутри.
func (d Data) MarshalJSON() ([]byte, error) {
	if d.Lines == nil {
		d.Lines = []Line{}
	}
	if d.Ledger == nil {
		d.Ledger = []LedgerRow{}
	}
	type dataAlias Data
	return json.Marshal(dataAlias(d))
}

// VatKop выделяет НДС из суммы «в том числе»: ставка лежит внутри totalKop.
// Целочисленно, с округлением к ближайшему: половина копейки на строку —
// не та экономия, ради которой стоило бы плавать по базе.
func VatKop(totalKop int64, rate int) int64 {
	if rate <= 0 || totalKop <= 0 {
		return 0
	}
	r := int64(rate)
	return (totalKop*r + (100+r)/2) / (100 + r)
}

// LineTotalKop — сумма строки из количества и цены, в копейках.
// Округление как в составе заказа: (qty * price + 500) / 1000.
func LineTotalKop(qtyMilli, priceKop int64) int64 {
	q := qtyMilli
	if q < 0 {
		q = -q
	}
	t := (q*priceKop + 500) / 1000
	if qtyMilli < 0 {
		return -t
	}
	return t
}

// Recompute пересчитывает отформатированные поля из сырых: вызывается
// при правке черновика и при сборке документа. Сырые значения — правда,
// строки — их отображение, и собирать их в двух местах нельзя.
func (d *Data) Recompute() {
	var total, vatTotal int64
	for i := range d.Lines {
		line := &d.Lines[i]
		line.Number = i + 1
		line.TotalKop = LineTotalKop(line.QtyMilli, line.PriceKop)
		line.VatKop = VatKop(line.TotalKop, d.VatRate)
		line.Quantity = Quantity(line.QtyMilli)
		line.Price = Money(line.PriceKop)
		line.Total = Money(line.TotalKop)
		if d.VatRate > 0 {
			line.Vat = Money(line.VatKop)
		} else {
			line.Vat = ""
		}
		total += line.TotalKop
		vatTotal += line.VatKop
	}
	d.TotalKop = total
	d.Total = Money(total)
	d.TotalWords = MoneyWords(total)
	d.ItemsCount = len(d.Lines)
	d.ItemsWord = ItemsWord(len(d.Lines))
	// Дата документа печатается словами и всегда следует за ISO-датой:
	// расхождение двух представлений одной даты недопустимо.
	if d.DocDate != "" {
		d.IssuedAt = FormatDate(d.DocDate)
	}
	d.VatTotalKop = vatTotal
	if d.VatRate > 0 {
		d.VatRateText = fmt.Sprintf("%d%%", d.VatRate)
		d.VatTotal = Money(vatTotal)
	} else {
		d.VatRateText = "без НДС"
		d.VatTotal = ""
	}

	// Сверка: итоги по таблице операций. Итог документа — сальдо на конец,
	// а не сумма строк: строк табличной части у сверки нет.
	if len(d.Ledger) > 0 || d.PeriodTo != "" {
		var debit, credit int64
		for i := range d.Ledger {
			row := &d.Ledger[i]
			debit += row.DebitKop
			credit += row.CreditKop
			row.BalanceKop = d.OpeningKop + debit - credit
			row.Debit = formatNonZero(row.DebitKop)
			row.Credit = formatNonZero(row.CreditKop)
			row.Balance = Money(row.BalanceKop)
		}
		d.ClosingKop = d.OpeningKop + debit - credit
		d.Closing = Money(d.ClosingKop)
		d.TotalKop = d.ClosingKop
		d.Total = d.Closing
		d.TotalWords = MoneyWords(d.ClosingKop)
	}
}

func formatNonZero(kop int64) string {
	if kop == 0 {
		return ""
	}
	return Money(kop)
}

var monthNames = [...]string{
	"января", "февраля", "марта", "апреля", "мая", "июня",
	"июля", "августа", "сентября", "октября", "ноября", "декабря",
}

// FormatDate — «3 сентября 2026 г.». Русский формат, а не ISO: документ
// читает человек, и дата в нём пишется словами. Понимает и дату документа
// (YYYY-MM-DD), и момент выдачи (RFC3339).
func FormatDate(value string) string {
	for _, layout := range []string{time.RFC3339, "2006-01-02", "2006-01-02T15:04:05"} {
		if t, err := time.Parse(layout, value); err == nil {
			return fmt.Sprintf("%d %s %d г.", t.Day(), monthNames[t.Month()-1], t.Year())
		}
	}
	return value
}

// FormatShortDate — «03.09.2026» для таблицы акта сверки.
func FormatShortDate(iso string) string {
	if t, err := time.Parse("2006-01-02", iso); err == nil {
		return t.Format("02.01.2006")
	}
	return iso
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

// kindTemplates — какой шаблон у какого вида. Реестр видов (валидация,
// русские названия) живёт в store: HTTP-слой им пользуется для фильтров,
// а здесь нужна только связь «вид → файл».
var kindTemplates = map[string]string{
	"invoice":        "invoice.html",
	"act":            "act.html",
	"estimate":       "estimate.html",
	"waybill":        "waybill.html",
	"upd":            "upd.html",
	"reconciliation": "reconciliation.html",
}

// TemplateFor отвечает, есть ли печатная форма у вида документа.
func TemplateFor(kind string) (string, bool) {
	name, ok := kindTemplates[kind]
	return name, ok
}

// Render печатает документ в w. Шаблон выбирается по виду.
func Render(w io.Writer, data Data) error {
	tmpl, err := template.ParseFS(templatesFS, "templates/*.html")
	if err != nil {
		return fmt.Errorf("разбор шаблонов документа: %w", err)
	}
	name, ok := kindTemplates[data.Kind]
	if !ok {
		return fmt.Errorf("у вида %q нет печатной формы", data.Kind)
	}
	return tmpl.ExecuteTemplate(w, name, data)
}
