package reports

import (
	"bytes"
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/xuri/excelize/v2"
)

// Workbook собирает результат отчёта в книгу Excel: по листу на секцию.
// Деньги — числами с двумя знаками (NumFmt 4), количества — числами:
// книга потом сама участвует в сводных таблицах и формулах.
func Workbook(res Result) ([]byte, error) {
	f := excelize.NewFile()
	defer f.Close()

	header, err := f.NewStyle(&excelize.Style{
		Font: &excelize.Font{Bold: true},
		Fill: excelize.Fill{Type: "pattern", Color: []string{"EEEEEE"}, Pattern: 1},
		Border: []excelize.Border{
			{Type: "bottom", Color: "999999", Style: 1},
		},
	})
	if err != nil {
		return nil, err
	}
	moneyStyle, err := f.NewStyle(&excelize.Style{CustomNumFmt: strPtr("# ##0,00")})
	if err != nil {
		return nil, err
	}
	totalStyle, err := f.NewStyle(&excelize.Style{Font: &excelize.Font{Bold: true}})
	if err != nil {
		return nil, err
	}

	first := true
	for i, section := range res.Sections {
		name := sheetName(section.Title, i)
		if first {
			if err := f.SetSheetName("Sheet1", name); err != nil {
				return nil, err
			}
			first = false
		} else {
			if _, err := f.NewSheet(name); err != nil {
				return nil, err
			}
		}

		row := 1
		if err := f.SetCellValue(name, cell(1, row), section.Title); err != nil {
			return nil, err
		}
		if err := f.SetCellStyle(name, cell(1, row), cell(1, row), totalStyle); err != nil {
			return nil, err
		}
		row++

		for c, col := range section.Columns {
			if err := f.SetCellValue(name, cell(c+1, row), col.Label); err != nil {
				return nil, err
			}
		}
		if err := f.SetCellStyle(name, cell(1, row), cell(len(section.Columns), row), header); err != nil {
			return nil, err
		}
		row++

		for _, dataRow := range section.Rows {
			for c, value := range dataRow {
				if err := writeCell(f, name, c+1, row, value, moneyStyle); err != nil {
					return nil, err
				}
			}
			row++
		}

		if len(section.Total) > 0 {
			for c, value := range section.Total {
				if c >= len(section.Columns) {
					break
				}
				if err := writeCell(f, name, c+1, row, value, moneyStyle); err != nil {
					return nil, err
				}
			}
			if err := f.SetCellStyle(name, cell(1, row), cell(len(section.Columns), row), totalStyle); err != nil {
				return nil, err
			}
			row++
		}

		if section.Note != "" {
			if err := f.SetCellValue(name, cell(1, row), section.Note); err != nil {
				return nil, err
			}
		}
	}

	// Ширина колонок — по заголовку и самому длинному значению, с потолком.
	for i, section := range res.Sections {
		name := sheetName(section.Title, i)
		for c, col := range section.Columns {
			width := utf8.RuneCountInString(col.Label)
			for _, dataRow := range section.Rows {
				if c < len(dataRow) {
					if l := utf8.RuneCountInString(dataRow[c].Text); l > width {
						width = l
					}
				}
			}
			if width < 10 {
				width = 10
			}
			if width > 48 {
				width = 48
			}
			colLetter, _ := excelize.ColumnNumberToName(c + 1)
			if err := f.SetColWidth(name, colLetter, colLetter, float64(width)+2); err != nil {
				return nil, err
			}
		}
	}

	var buf bytes.Buffer
	if err := f.Write(&buf); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writeCell(f *excelize.File, sheet string, col, row int, value Cell, moneyStyle int) error {
	ref := cell(col, row)
	switch value.Kind {
	case KindMoney:
		if err := f.SetCellValue(sheet, ref, float64(value.ValueKop)/100); err != nil {
			return err
		}
		return f.SetCellStyle(sheet, ref, ref, moneyStyle)
	case KindQty:
		return f.SetCellValue(sheet, ref, float64(value.ValueMilli)/1000)
	case KindCount:
		return f.SetCellValue(sheet, ref, value.ValueCount)
	default:
		return f.SetCellValue(sheet, ref, value.Text)
	}
}

// cell превращает (колонка, строка) в адрес A1-стиля.
func cell(col, row int) string {
	name, _ := excelize.CoordinatesToCellName(col, row)
	return name
}

// sheetName делает имя листа из заголовка секции: без запрещённых символов,
// не длиннее 31 знака, с номером — чтобы одноимённые секции не столкнулись.
func sheetName(title string, index int) string {
	replacer := strings.NewReplacer("\\", " ", "/", " ", "?", " ", "*", " ", "[", " ", "]", " ", ":", " ")
	name := replacer.Replace(strings.TrimSpace(title))
	runes := []rune(name)
	if len(runes) > 28 {
		runes = runes[:28]
	}
	return fmt.Sprintf("%s %d", strings.TrimSpace(string(runes)), index+1)
}

func strPtr(v string) *string { return &v }
