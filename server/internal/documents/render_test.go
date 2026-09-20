package documents

import (
	"strings"
	"testing"
)

// Все виды обязаны печататься: новый шаблон, забытый в реестре, обнаружится
// здесь первым, а не на телефоне у клиента.
func TestRenderAllKinds(t *testing.T) {
	for _, kind := range []string{"invoice", "act", "estimate", "waybill", "upd", "reconciliation"} {
		data := Data{
			V:      SnapshotVersion,
			Kind:   kind,
			Title:  DocTitleFor(kind),
			Number: 3,
			Year:   2026,
			Supplier: Party{Name: "ИП Фролов А. В.", INN: "643900000000"},
			Customer: Party{
				Name: "ООО «Ромашка»", INN: "645800000000", KPP: "645801001",
				BankName: "Сбербанк", BankAccount: "40702810900000000123",
			},
		}
		switch kind {
		case "invoice", "act", "estimate", "waybill", "upd":
			data.Lines = []Line{{
				Name: "Замена проводки", Unit: "усл.", QtyMilli: 2000, PriceKop: 250_000,
			}}
		case "reconciliation":
			data.PeriodFrom = "01.01.2026"
			data.PeriodTo = "30.09.2026"
			data.OpeningKop = 100_000
			data.Ledger = []LedgerRow{{
				Date: "12.05.2026", Label: "Заказ № 45 «Щит»", DebitKop: 500_000,
			}, {
				Date: "20.05.2026", Label: "Оплата заказа № 45", CreditKop: 400_000,
			}}
		}
		data.Recompute()

		var sb strings.Builder
		if err := Render(&sb, data); err != nil {
			t.Fatalf("Render(%s): %v", kind, err)
		}
		page := sb.String()
		if !strings.Contains(page, data.Title) {
			t.Errorf("%s: на странице нет заголовка %q", kind, data.Title)
		}
		if kind == "reconciliation" {
			if !strings.Contains(page, "Сальдо на начало периода") ||
				!strings.Contains(page, "Сальдо на конец периода") {
				t.Errorf("сверка: нет строк сальдо")
			}
			if data.ClosingKop != 200_000 {
				t.Errorf("сверка: сальдо на конец = 1000 + 5000 − 4000, получено %d", data.ClosingKop)
			}
		}
	}
}

// Аннулированный документ печатается со штампом — иначе его можно выдать
// за действующий.
func TestRenderAnnulledStamp(t *testing.T) {
	data := Data{Kind: "invoice", Title: "Счёт на оплату", Number: 5, V: SnapshotVersion,
		Supplier: Party{Name: "ИП"}, Lines: []Line{{Name: "Работы", QtyMilli: 1000, PriceKop: 100}}}
	data.Recompute()

	var live, annulled strings.Builder
	if err := Render(&live, data); err != nil {
		t.Fatalf("Render: %v", err)
	}
	data.Annulled = true
	if err := Render(&annulled, data); err != nil {
		t.Fatalf("Render: %v", err)
	}
	if strings.Contains(live.String(), "АННУЛИРОВАН") {
		t.Error("штамп появился у действующего документа")
	}
	if !strings.Contains(annulled.String(), "АННУЛИРОВАН") {
		t.Error("у аннулированного нет штампа")
	}
}

// НДС «в том числе» считается целыми числами и в сумме не теряет копеек.
func TestVatAndRecompute(t *testing.T) {
	if got := VatKop(120_000, 20); got != 20_000 {
		t.Fatalf("НДС 20%% из 1200 ₽ = 200 ₽, получено %d", got)
	}
	if got := VatKop(100_000, 20); got != 16_667 {
		t.Fatalf("НДС 20%% из 1000 ₽ = 166,67 ₽, получено %d", got)
	}
	if got := VatKop(500_000, 0); got != 0 {
		t.Fatalf("без ставки НДС быть не должно: %d", got)
	}

	data := Data{VatRate: 20, Lines: []Line{
		{Name: "Работы", QtyMilli: 1000, PriceKop: 120_000},
		{Name: "Материалы", QtyMilli: 2500, PriceKop: 40_000},
	}}
	data.Recompute()

	if data.Lines[0].TotalKop != 120_000 || data.Lines[1].TotalKop != 100_000 {
		t.Fatalf("суммы строк: %d, %d", data.Lines[0].TotalKop, data.Lines[1].TotalKop)
	}
	if data.TotalKop != 220_000 {
		t.Fatalf("итог: %d", data.TotalKop)
	}
	if data.VatTotalKop != 20_000+16_667 {
		t.Fatalf("НДС итого: %d", data.VatTotalKop)
	}
	if data.VatRateText != "20%" || data.VatTotal != "366,67" {
		t.Fatalf("подписи НДС: %q %q", data.VatRateText, data.VatTotal)
	}
	if data.ItemsCount != 2 || data.TotalWords == "" || data.IssuedAt != "" {
		t.Fatalf("пересчёт не заполнил сопутствующие поля: %+v", data)
	}

	// Дата документа печатается словами и следует за ISO-датой.
	data.DocDate = "2026-09-03"
	data.Recompute()
	if data.IssuedAt != "3 сентября 2026 г." {
		t.Fatalf("дата словами: %q", data.IssuedAt)
	}
}

// DocTitleFor — заголовок для теста; вынесен, чтобы не дублировать списки.
func DocTitleFor(kind string) string {
	switch kind {
	case "invoice":
		return "Счёт на оплату"
	case "act":
		return "Акт выполненных работ"
	case "estimate":
		return "Смета"
	case "waybill":
		return "Товарная накладная"
	case "upd":
		return "Универсальный передаточный документ"
	case "reconciliation":
		return "Акт сверки взаимных расчётов"
	}
	return kind
}
