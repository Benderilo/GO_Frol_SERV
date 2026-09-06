package store

import (
	"context"
	"errors"
	"testing"
)

func makeOrder(t *testing.T, st *Store, title string) Order {
	t.Helper()
	order, err := st.CreateOrder(context.Background(), Order{Title: title, Status: "new"})
	if err != nil {
		t.Fatalf("создание заказа: %v", err)
	}
	return order
}

// Итог заказа — сумма строк, а не отдельно вписанное число.
func TestOrderTotalIsSumOfItems(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	order := makeOrder(t, st, "Электрика в доме")

	// 2.5 м по 120 ₽ = 300 ₽, и один выезд за 1500 ₽.
	if _, err := st.AddOrderItem(ctx, order.ID, OrderItem{
		Name: "Кабель", Unit: "м", QtyMilli: 2_500, PriceKop: 12_000, CostKop: 8_000,
	}); err != nil {
		t.Fatalf("строка кабеля: %v", err)
	}
	if _, err := st.AddOrderItem(ctx, order.ID, OrderItem{
		Name: "Выезд", Unit: "выезд", QtyMilli: 1_000, PriceKop: 150_000,
	}); err != nil {
		t.Fatalf("строка выезда: %v", err)
	}

	got, err := st.Order(ctx, order.ID)
	if err != nil {
		t.Fatalf("чтение заказа: %v", err)
	}
	if got.PriceKop != 30_000+150_000 {
		t.Errorf("итог заказа %d копеек, ожидалось 180000", got.PriceKop)
	}
	if got.ItemsCount != 2 {
		t.Errorf("позиций %d, ожидалось 2", got.ItemsCount)
	}
	if got.CostKop != 20_000 {
		t.Errorf("себестоимость %d копеек, ожидалось 20000", got.CostKop)
	}
}

// Дробное количество не должно терять копейку: 0.333 м по 100 ₽ — это 33.30 ₽.
func TestLineTotalRoundsToKopeck(t *testing.T) {
	cases := []struct {
		qtyMilli, priceKop, want int64
	}{
		{1_000, 199_999, 199_999}, // одна штука — ровно цена
		{2_500, 12_000, 30_000},   // 2.5 × 120 ₽
		{333, 10_000, 3_330},      // 0.333 × 100 ₽
		{1, 100_000, 100},         // 0.001 × 1000 ₽
		{1_500, 199_999, 299_999}, // 1.5 × 1999.99 ₽ = 2999.985 → 2999.99
	}
	for _, c := range cases {
		if got := lineTotal(c.qtyMilli, c.priceKop); got != c.want {
			t.Errorf("lineTotal(%d, %d) = %d, ожидалось %d", c.qtyMilli, c.priceKop, got, c.want)
		}
	}
}

// Строка из справочника подставляет название, единицу и цены сама.
func TestOrderItemTakesDefaultsFromCatalog(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	order := makeOrder(t, st, "Монтаж")

	material, err := st.CreateCatalogItem(ctx, CatalogItem{
		Kind: KindMaterial, Name: "Кабель ВВГ", Unit: "м", PriceKop: 12_000, CostKop: 8_500,
	})
	if err != nil {
		t.Fatalf("создание материала: %v", err)
	}

	item, err := st.AddOrderItem(ctx, order.ID, OrderItem{CatalogID: &material.ID, QtyMilli: 3_000})
	if err != nil {
		t.Fatalf("строка из справочника: %v", err)
	}
	if item.Name != "Кабель ВВГ" || item.Unit != "м" {
		t.Errorf("название и единица не подставились: %+v", item)
	}
	if item.PriceKop != 12_000 || item.CostKop != 8_500 {
		t.Errorf("цены не подставились: %d / %d", item.PriceKop, item.CostKop)
	}
	if item.Kind != KindMaterial {
		t.Errorf("вид не подставился: %q", item.Kind)
	}
	if item.TotalKop != 36_000 {
		t.Errorf("итог строки %d, ожидалось 36000", item.TotalKop)
	}
}

// Списание по заказу уменьшает склад ровно на количество из строк,
// а повторное нажатие ничего не задваивает.
func TestWriteOffOrderMovesStockOnce(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	order := makeOrder(t, st, "Монтаж щита")

	material, err := st.CreateCatalogItem(ctx, CatalogItem{
		Kind: KindMaterial, Name: "Кабель", Unit: "м", PriceKop: 12_000, CostKop: 8_000,
	})
	if err != nil {
		t.Fatalf("создание материала: %v", err)
	}
	if _, err := st.AddStockMove(ctx, material.ID, StockMove{QtyMilli: 100_000}); err != nil {
		t.Fatalf("приход: %v", err)
	}
	if _, err := st.AddOrderItem(ctx, order.ID, OrderItem{CatalogID: &material.ID, QtyMilli: 30_000}); err != nil {
		t.Fatalf("строка: %v", err)
	}
	// Услуга в том же заказе списываться не должна: склада у неё нет.
	if _, err := st.AddOrderItem(ctx, order.ID, OrderItem{
		Kind: KindService, Name: "Выезд", QtyMilli: 1_000, PriceKop: 150_000,
	}); err != nil {
		t.Fatalf("строка услуги: %v", err)
	}

	written, err := st.WriteOffOrder(ctx, order.ID)
	if err != nil {
		t.Fatalf("списание: %v", err)
	}
	if written != 1 {
		t.Errorf("списано строк %d, ожидалась 1 — услуга не в счёт", written)
	}

	got, err := st.CatalogItem(ctx, material.ID)
	if err != nil {
		t.Fatalf("чтение материала: %v", err)
	}
	if got.StockMilli != 70_000 {
		t.Errorf("остаток %d, ожидалось 70000", got.StockMilli)
	}

	// Повторное списание не должно тронуть склад.
	again, err := st.WriteOffOrder(ctx, order.ID)
	if err != nil {
		t.Fatalf("повторное списание: %v", err)
	}
	if again != 0 {
		t.Errorf("повторно списалось %d строк, ожидалось 0", again)
	}
	got, _ = st.CatalogItem(ctx, material.ID)
	if got.StockMilli != 70_000 {
		t.Errorf("остаток после повтора %d, ожидалось 70000", got.StockMilli)
	}
}

// Удалили строку — материал вернулся на склад: расхода-то не было.
func TestDeleteWrittenOffItemReturnsStock(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	order := makeOrder(t, st, "Монтаж")

	material, _ := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Гофра", Unit: "м"})
	if _, err := st.AddStockMove(ctx, material.ID, StockMove{QtyMilli: 50_000}); err != nil {
		t.Fatalf("приход: %v", err)
	}
	item, err := st.AddOrderItem(ctx, order.ID, OrderItem{CatalogID: &material.ID, QtyMilli: 20_000})
	if err != nil {
		t.Fatalf("строка: %v", err)
	}
	if _, err := st.WriteOffOrder(ctx, order.ID); err != nil {
		t.Fatalf("списание: %v", err)
	}

	if err := st.DeleteOrderItem(ctx, item.ID); err != nil {
		t.Fatalf("удаление строки: %v", err)
	}
	got, _ := st.CatalogItem(ctx, material.ID)
	if got.StockMilli != 50_000 {
		t.Errorf("после удаления строки остаток %d, ожидалось 50000", got.StockMilli)
	}
}

// Правка количества у списанной строки пересписывает материал,
// а не оставляет склад с прежним расходом.
func TestUpdateWrittenOffItemRewritesStock(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	order := makeOrder(t, st, "Монтаж")

	material, _ := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Кабель", Unit: "м"})
	if _, err := st.AddStockMove(ctx, material.ID, StockMove{QtyMilli: 100_000}); err != nil {
		t.Fatalf("приход: %v", err)
	}
	item, _ := st.AddOrderItem(ctx, order.ID, OrderItem{CatalogID: &material.ID, QtyMilli: 30_000})
	if _, err := st.WriteOffOrder(ctx, order.ID); err != nil {
		t.Fatalf("списание: %v", err)
	}

	item.QtyMilli = 40_000
	updated, err := st.UpdateOrderItem(ctx, item.ID, item)
	if err != nil {
		t.Fatalf("правка строки: %v", err)
	}
	if !updated.WrittenOff() {
		t.Error("строка перестала быть списанной после правки")
	}
	got, _ := st.CatalogItem(ctx, material.ID)
	if got.StockMilli != 60_000 {
		t.Errorf("остаток %d, ожидалось 60000", got.StockMilli)
	}
}

// Списать больше, чем есть, нельзя — и заказ при этом не остаётся
// списанным наполовину.
func TestWriteOffRefusesWhenStockIsShort(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	order := makeOrder(t, st, "Большой монтаж")

	material, _ := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Кабель", Unit: "м"})
	if _, err := st.AddStockMove(ctx, material.ID, StockMove{QtyMilli: 10_000}); err != nil {
		t.Fatalf("приход: %v", err)
	}
	if _, err := st.AddOrderItem(ctx, order.ID, OrderItem{CatalogID: &material.ID, QtyMilli: 50_000}); err != nil {
		t.Fatalf("строка: %v", err)
	}

	if _, err := st.WriteOffOrder(ctx, order.ID); !errors.Is(err, ErrNegativeStock) {
		t.Fatalf("списание сверх остатка прошло, ошибка: %v", err)
	}
	got, _ := st.CatalogItem(ctx, material.ID)
	if got.StockMilli != 10_000 {
		t.Errorf("после отказа остаток изменился: %d", got.StockMilli)
	}
}

// У заказа без состава цена вписана руками — пересчёт не должен её обнулить.
func TestOrderWithoutItemsKeepsManualPrice(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	order, err := st.CreateOrder(ctx, Order{Title: "Старый заказ", Status: "done", PriceKop: 500_000})
	if err != nil {
		t.Fatalf("создание: %v", err)
	}
	if err := st.recalcOrderTotal(ctx, order.ID); err != nil {
		t.Fatalf("пересчёт: %v", err)
	}
	got, _ := st.Order(ctx, order.ID)
	if got.PriceKop != 500_000 {
		t.Errorf("цена заказа без состава стала %d, ожидалось 500000", got.PriceKop)
	}
}

// Номер документа закрепляется за заказом навсегда: перевыпуск того же счёта
// не должен порождать новый номер, иначе у клиента на руках и в учёте
// окажутся разные счета на одну работу.
func TestDocumentNumberIsStable(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	first := makeOrder(t, st, "Первый")
	second := makeOrder(t, st, "Второй")

	invoice1, err := st.IssueDocument(ctx, first.ID, DocInvoice)
	if err != nil {
		t.Fatalf("выдача счёта: %v", err)
	}
	if invoice1.Number != 1 {
		t.Errorf("первый счёт получил номер %d, ожидался 1", invoice1.Number)
	}

	again, err := st.IssueDocument(ctx, first.ID, DocInvoice)
	if err != nil {
		t.Fatalf("повторная выдача: %v", err)
	}
	if again.Number != invoice1.Number || again.IssuedAt != invoice1.IssuedAt {
		t.Errorf("перевыпуск сменил номер или дату: было %+v, стало %+v", invoice1, again)
	}

	invoice2, err := st.IssueDocument(ctx, second.ID, DocInvoice)
	if err != nil {
		t.Fatalf("счёт по второму заказу: %v", err)
	}
	if invoice2.Number != 2 {
		t.Errorf("второй счёт получил номер %d, ожидался 2", invoice2.Number)
	}

	// Акты нумеруются своей чередой, а не продолжают счета.
	act, err := st.IssueDocument(ctx, first.ID, DocAct)
	if err != nil {
		t.Fatalf("выдача акта: %v", err)
	}
	if act.Number != 1 {
		t.Errorf("первый акт получил номер %d, ожидался 1", act.Number)
	}

	if _, err := st.IssueDocument(ctx, first.ID, "накладная"); err == nil {
		t.Error("неизвестный вид документа выдался, а не должен был")
	}
}
