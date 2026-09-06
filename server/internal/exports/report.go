package exports

import (
	"bytes"
	"fmt"

	"github.com/Benderilo/GO_Frol_SERV/internal/store"
	"github.com/xuri/excelize/v2"
)

// Листы отчёта за период. Названия направлений и способов оплаты общие
// с выгрузкой базы — они лежат в xlsx.go, чтобы в двух книгах одно и то же
// не называлось по-разному.
const (
	SheetSummary    = "Свод"
	SheetOperations = "Операции"
	SheetMaterials  = "Материалы"
)

// BuildReport собирает книгу с отчётом за период: свод, операции кассы
// и расход материалов. Суммы пишутся рублями — книгу читает человек.
func BuildReport(report store.PeriodReport, ops []store.CashOp) ([]byte, error) {
	f := excelize.NewFile()
	defer f.Close()

	header, money, err := reportStyles(f)
	if err != nil {
		return nil, err
	}

	if err := writeSummary(f, report, header, money); err != nil {
		return nil, err
	}
	if err := writeCashSheet(f, ops, header, money); err != nil {
		return nil, err
	}
	if err := writeMaterialsSheet(f, report.Materials, header, money); err != nil {
		return nil, err
	}

	// Лист по умолчанию создаётся пустым — убираем, чтобы книга открывалась
	// сразу на своде, а не на «Sheet1».
	if idx, err := f.GetSheetIndex(SheetSummary); err == nil {
		f.SetActiveSheet(idx)
	}
	f.DeleteSheet("Sheet1")

	var buf bytes.Buffer
	if err := f.Write(&buf); err != nil {
		return nil, fmt.Errorf("запись книги отчёта: %w", err)
	}
	return buf.Bytes(), nil
}

func reportStyles(f *excelize.File) (header, money int, err error) {
	header, err = f.NewStyle(&excelize.Style{
		Font:      &excelize.Font{Bold: true},
		Fill:      excelize.Fill{Type: "pattern", Pattern: 1, Color: []string{"#EFEFEF"}},
		Alignment: &excelize.Alignment{Vertical: "center", WrapText: true},
	})
	if err != nil {
		return 0, 0, err
	}
	money, err = f.NewStyle(&excelize.Style{NumFmt: 4}) // # ##0,00
	return header, money, err
}

func writeSummary(f *excelize.File, r store.PeriodReport, header, money int) error {
	if _, err := f.NewSheet(SheetSummary); err != nil {
		return err
	}
	period := "за всё время"
	if r.From != "" || r.To != "" {
		period = fmt.Sprintf("с %s по %s", orDash(r.From), orDash(r.To))
	}

	rows := []struct {
		label string
		value any
		isSum bool
	}{
		{"Отчёт " + period, nil, false},
		{"", nil, false},
		{"КАССА", nil, false},
		{"Остаток на начало", rubles(r.OpeningKop), true},
		{"Поступило", rubles(r.IncomeKop), true},
		{"Израсходовано", rubles(r.ExpenseKop), true},
		{"Остаток на конец", rubles(r.ClosingKop), true},
		{"", nil, false},
		{"ЗАКАЗЫ", nil, false},
		{"Закрыто заказов", r.OrdersClosed, false},
		{"Выручка по закрытым", rubles(r.RevenueKop), true},
		{"Закупка по закрытым", rubles(r.CostKop), true},
		{"Заработок", rubles(r.ProfitKop()), true},
		{"", nil, false},
		{"ДОЛГИ КЛИЕНТОВ (на сейчас)", nil, false},
		{"Заказов с долгом", r.DebtOrders, false},
		{"Сумма долга", rubles(r.DebtKop), true},
	}

	row := 1
	for _, item := range rows {
		if item.label == "" {
			row++
			continue
		}
		cell, _ := excelize.CoordinatesToCellName(1, row)
		if err := f.SetCellValue(SheetSummary, cell, item.label); err != nil {
			return err
		}
		if item.value == nil {
			if err := f.SetCellStyle(SheetSummary, cell, cell, header); err != nil {
				return err
			}
		} else {
			valueCell, _ := excelize.CoordinatesToCellName(2, row)
			if err := f.SetCellValue(SheetSummary, valueCell, item.value); err != nil {
				return err
			}
			if item.isSum {
				if err := f.SetCellStyle(SheetSummary, valueCell, valueCell, money); err != nil {
					return err
				}
			}
		}
		row++
	}

	// Разбивка по статьям — под сводом, чтобы всё было на одном листе.
	if len(r.ByCategory) > 0 {
		row++
		if err := writeReportHeader(f, SheetSummary, row, header,
			[]string{"Статья", "Направление", "Сумма, ₽", "Операций"}); err != nil {
			return err
		}
		row++
		for _, c := range r.ByCategory {
			values := []any{c.Category, directionNames[c.Direction], rubles(c.AmountKop), c.Count}
			if err := writeRow(f, SheetSummary, row, values); err != nil {
				return err
			}
			cell, _ := excelize.CoordinatesToCellName(3, row)
			if err := f.SetCellStyle(SheetSummary, cell, cell, money); err != nil {
				return err
			}
			row++
		}
	}

	return setWidths(f, SheetSummary, []float64{34, 16, 14, 12})
}

func writeCashSheet(f *excelize.File, ops []store.CashOp, header, money int) error {
	if _, err := f.NewSheet(SheetOperations); err != nil {
		return err
	}
	if err := writeReportHeader(f, SheetOperations, 1, header, []string{
		"Дата", "Направление", "Сумма, ₽", "Способ", "Статья", "Заказ", "Клиент", "Заметка",
	}); err != nil {
		return err
	}
	for i, op := range ops {
		row := i + 2
		values := []any{
			op.HappenedAt, directionNames[op.Direction], rubles(op.AmountKop),
			methodNames[op.Method], op.Category, op.OrderTitle, op.ClientName, op.Note,
		}
		if err := writeRow(f, SheetOperations, row, values); err != nil {
			return err
		}
		cell, _ := excelize.CoordinatesToCellName(3, row)
		if err := f.SetCellStyle(SheetOperations, cell, cell, money); err != nil {
			return err
		}
	}
	return setWidths(f, SheetOperations, []float64{22, 14, 14, 12, 22, 28, 24, 30})
}

func writeMaterialsSheet(f *excelize.File, used []store.MaterialUsage, header, money int) error {
	if _, err := f.NewSheet(SheetMaterials); err != nil {
		return err
	}
	if err := writeReportHeader(f, SheetMaterials, 1, header, []string{
		"Материал", "Ед.", "Израсходовано", "Сумма, ₽", "Остаток",
	}); err != nil {
		return err
	}
	for i, u := range used {
		row := i + 2
		values := []any{u.Name, u.Unit, quantity(u.QtyMilli), rubles(u.CostKop), quantity(u.StockLeft)}
		if err := writeRow(f, SheetMaterials, row, values); err != nil {
			return err
		}
		cell, _ := excelize.CoordinatesToCellName(4, row)
		if err := f.SetCellStyle(SheetMaterials, cell, cell, money); err != nil {
			return err
		}
	}
	return setWidths(f, SheetMaterials, []float64{34, 8, 16, 14, 12})
}

func writeReportHeader(f *excelize.File, sheet string, row, style int, titles []string) error {
	if err := writeRow(f, sheet, row, toAny(titles)); err != nil {
		return err
	}
	first, _ := excelize.CoordinatesToCellName(1, row)
	last, _ := excelize.CoordinatesToCellName(len(titles), row)
	return f.SetCellStyle(sheet, first, last, style)
}

func toAny(values []string) []any {
	out := make([]any, len(values))
	for i, v := range values {
		out[i] = v
	}
	return out
}

// quantity — тысячные доли в число для ячейки: 2500 → 2.5.
func quantity(milli int64) float64 { return float64(milli) / 1000 }

func orDash(v string) string {
	if v == "" {
		return "—"
	}
	return v
}
