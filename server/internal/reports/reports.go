// Package reports — ядро отчётов: реестр доступных отчётов и единая модель
// результата. Отчёт описывается один раз (параметры + сборка), а показывается
// везде одинаково: приложение рисует секции таблицами, Excel пишет теми же
// секциями. Новый отчёт появляется в приложении без правки экранов.
package reports

import (
	"context"

	"github.com/Benderilo/GO_Frol_SERV/internal/documents"
	"github.com/Benderilo/GO_Frol_SERV/internal/store"
)

// Параметры, которые отчёт умеет спрашивать у пользователя.
const (
	ParamPeriod = "period" // период: от и до
	ParamClient = "client" // один клиент (например, для сверки)
)

// Param — один параметр отчёта: какого рода вопрос задать при прогоне.
type Param struct {
	Kind  string `json:"kind"`
	Label string `json:"label"`
}

// Params — значения, которыми прогнали отчёт.
type Params struct {
	From, To string
	ClientID int64
	// Имя клиента — для заголовка; заполняет HTTP-слой.
	ClientName string
}

// Виды ячеек: по ним приложение и Excel решают, как рисовать значение.
const (
	KindText  = "text"
	KindMoney = "money" // деньги: в ячейке копейки, текст отформатирован
	KindQty   = "qty"   // количество: тысячные доли единицы
	KindCount = "count" // целое число
)

// Cell — значение в таблице. Text готов к показу, сырые значения нужны
// Excel-экспорту, чтобы деньги остались числами, а не строками.
type Cell struct {
	Text       string `json:"text"`
	Kind       string `json:"kind"`
	ValueKop   int64  `json:"valueKop,omitempty"`
	ValueMilli int64  `json:"valueMilli,omitempty"`
	ValueCount int64  `json:"valueCount,omitempty"`
}

// Column — колонка секции.
type Column struct {
	Key   string `json:"key"`
	Label string `json:"label"`
	Kind  string `json:"kind"`
}

// Section — таблица: колонки, строки и строка итогов.
type Section struct {
	Title   string   `json:"title"`
	Columns []Column `json:"columns"`
	Rows    [][]Cell `json:"rows"`
	Total   []Cell   `json:"total,omitempty"`
	Note    string   `json:"note,omitempty"`
}

// Result — отчёт целиком: заголовок и секции.
type Result struct {
	Title    string    `json:"title"`
	Subtitle string    `json:"subtitle,omitempty"`
	Sections []Section `json:"sections"`
}

// Definition — отчёт в реестре: что спрашивает и как собирается.
type Definition struct {
	ID          string
	Title       string
	Description string
	Params      []Param
	Build       func(ctx context.Context, st *store.Store, p Params) (Result, error)
}

// All — реестр всех отчётов. Порядок — порядок в списке приложения.
func All() []*Definition {
	out := make([]*Definition, len(definitionList))
	for i := range definitionList {
		out[i] = &definitionList[i]
	}
	return out
}

// ByID находит отчёт по идентификатору.
func ByID(id string) *Definition {
	for i := range definitionList {
		if definitionList[i].ID == id {
			return &definitionList[i]
		}
	}
	return nil
}

// ---------- Сборщики ячеек ----------

func text(v string) Cell { return Cell{Text: v, Kind: KindText} }

func money(kop int64) Cell {
	return Cell{Text: documents.Money(kop), Kind: KindMoney, ValueKop: kop}
}

func qty(milli int64) Cell {
	return Cell{Text: documents.Quantity(milli), Kind: KindQty, ValueMilli: milli}
}

func count(n int64) Cell {
	return Cell{Text: documents.Quantity(n), Kind: KindCount, ValueCount: n}
}

// description для подзаголовка прогона: период и клиент, если задан.
func (p Params) subtitle() string {
	parts := []string{}
	if p.From != "" || p.To != "" {
		from, to := p.From, p.To
		if from == "" {
			from = "начала"
		}
		if to == "" {
			to = "сегодня"
		}
		parts = append(parts, "с "+from+" по "+to)
	}
	if p.ClientName != "" {
		parts = append(parts, p.ClientName)
	}
	out := ""
	for i, part := range parts {
		if i > 0 {
			out += " · "
		}
		out += part
	}
	return out
}
