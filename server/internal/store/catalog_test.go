package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	st, err := Open(context.Background(), filepath.Join(t.TempDir(), "catalog.db"))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

// Остаток нигде не хранится, а считается из движений. Проверяем именно это:
// приход, ещё приход, списание — и сумма должна сходиться до тысячной.
func TestStockBalanceIsSumOfMoves(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	item, err := st.CreateCatalogItem(ctx, CatalogItem{
		Kind: KindMaterial, Name: "Кабель ВВГ 3х2.5", Unit: "м", PriceKop: 12_000, CostKop: 8_500,
	})
	if err != nil {
		t.Fatalf("создание материала: %v", err)
	}
	if item.StockMilli != 0 {
		t.Errorf("у новой позиции остаток %d, ожидался 0", item.StockMilli)
	}

	for _, qty := range []int64{100_000, 25_500, -30_250} {
		if _, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: qty}); err != nil {
			t.Fatalf("движение %d: %v", qty, err)
		}
	}

	got, err := st.CatalogItem(ctx, item.ID)
	if err != nil {
		t.Fatalf("чтение позиции: %v", err)
	}
	const want = 100_000 + 25_500 - 30_250
	if got.StockMilli != want {
		t.Errorf("остаток %d, ожидался %d", got.StockMilli, want)
	}
	if text := QuantityText(got.StockMilli); text != "95.25" {
		t.Errorf("остаток печатается как %q, ожидалось \"95.25\"", text)
	}
}

// Списать больше, чем есть, нельзя: отрицательный остаток означает,
// что чего-то не записали, и по нему нельзя считать себестоимость.
func TestStockRefusesToGoNegative(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	item, err := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Гофра", Unit: "м"})
	if err != nil {
		t.Fatalf("создание материала: %v", err)
	}
	if _, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: 5_000}); err != nil {
		t.Fatalf("приход: %v", err)
	}

	_, err = st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: -6_000})
	if !errors.Is(err, ErrNegativeStock) {
		t.Fatalf("списание сверх остатка прошло, ошибка: %v", err)
	}

	got, err := st.CatalogItem(ctx, item.ID)
	if err != nil {
		t.Fatalf("чтение позиции: %v", err)
	}
	if got.StockMilli != 5_000 {
		t.Errorf("после отказа остаток изменился: %d", got.StockMilli)
	}
}

// Приход, который уже списали, удалять нельзя — иначе остаток уйдёт в минус.
func TestDeleteStockMoveKeepsBalanceNonNegative(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	item, err := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Саморезы", Unit: "шт."})
	if err != nil {
		t.Fatalf("создание материала: %v", err)
	}
	income, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: 100_000})
	if err != nil {
		t.Fatalf("приход: %v", err)
	}
	if _, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: -100_000}); err != nil {
		t.Fatalf("списание: %v", err)
	}

	if err := st.DeleteStockMove(ctx, income.ID); !errors.Is(err, ErrNegativeStock) {
		t.Fatalf("списанный приход удалился, ошибка: %v", err)
	}
}

// Позицию с историей склада не удаляем: вместе с ней исчезли бы приходы
// и списания, по которым сходится остаток. Для этого есть отметка «архивная».
func TestDeleteCatalogItemKeepsStockHistory(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	withMoves, err := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Кабель", Unit: "м"})
	if err != nil {
		t.Fatalf("создание материала: %v", err)
	}
	if _, err := st.AddStockMove(ctx, withMoves.ID, StockMove{QtyMilli: 1_000}); err != nil {
		t.Fatalf("приход: %v", err)
	}
	if err := st.DeleteCatalogItem(ctx, withMoves.ID); !errors.Is(err, ErrStockHistory) {
		t.Fatalf("позиция с движениями удалилась, ошибка: %v", err)
	}

	// Архивная уходит из списка, но остаётся в базе.
	withMoves.Archived = true
	if _, err := st.UpdateCatalogItem(ctx, withMoves.ID, withMoves); err != nil {
		t.Fatalf("отметка архивной: %v", err)
	}
	active, err := st.CatalogItems(ctx, "", false)
	if err != nil {
		t.Fatalf("список: %v", err)
	}
	if len(active) != 0 {
		t.Errorf("архивная позиция осталась в списке: %d штук", len(active))
	}
	all, err := st.CatalogItems(ctx, "", true)
	if err != nil {
		t.Fatalf("список с архивом: %v", err)
	}
	if len(all) != 1 {
		t.Errorf("с архивом ожидалась 1 позиция, получено %d", len(all))
	}

	// А без движений — удаляется.
	clean, err := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindWork, Name: "Монтаж щита"})
	if err != nil {
		t.Fatalf("создание работы: %v", err)
	}
	if err := st.DeleteCatalogItem(ctx, clean.ID); err != nil {
		t.Errorf("позиция без движений не удалилась: %v", err)
	}
}

// У услуги склада нет: движения по ней записывать некуда, а остаток
// должен оставаться нулём, а не «ничего не осталось».
func TestServiceHasNoStock(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	service, err := st.CreateCatalogItem(ctx, CatalogItem{
		Kind: KindService, Name: "Выезд на объект", Unit: "выезд", PriceKop: 150_000,
	})
	if err != nil {
		t.Fatalf("создание услуги: %v", err)
	}
	if _, err := st.AddStockMove(ctx, service.ID, StockMove{QtyMilli: 1_000}); err == nil {
		t.Error("движение по услуге записалось, а не должно было")
	}
	if service.StockMilli != 0 {
		t.Errorf("у услуги остаток %d, ожидался 0", service.StockMilli)
	}
	if service.Unit != "выезд" {
		t.Errorf("единица измерения потерялась: %q", service.Unit)
	}
}

// Единицу измерения оставляем свободной строкой, но пустую подменяем самой
// частой: пустая единица в списке остатков читается как ошибка ввода.
func TestUnitDefaultsToPieces(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	item, err := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Клеммы", Unit: "  "})
	if err != nil {
		t.Fatalf("создание: %v", err)
	}
	if item.Unit != "шт." {
		t.Errorf("единица по умолчанию %q, ожидалось \"шт.\"", item.Unit)
	}
}

func TestQuantityText(t *testing.T) {
	cases := map[int64]string{
		0:        "0",
		5_000:    "5",
		2_500:    "2.5",
		125:      "0.125",
		95_250:   "95.25",
		-500:     "-0.5",
		-2_500:   "-2.5",
		1_000_00: "100",
	}
	for milli, want := range cases {
		if got := QuantityText(milli); got != want {
			t.Errorf("QuantityText(%d) = %q, ожидалось %q", milli, got, want)
		}
	}
}
