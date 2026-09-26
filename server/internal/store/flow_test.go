package store

import (
	"context"
	"testing"
)

// Исполнитель заказа: назначили — видно в заказе и в списке; удалили
// рабочего — заказ остаётся, просто без исполнителя.
func TestOrderWorkerLink(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	w, err := st.CreateWorker(ctx, WorkerUpsert{Name: "Пётр", SalaryType: "day", SalaryKop: 300_000, Active: true})
	if err != nil {
		t.Fatalf("рабочий: %v", err)
	}
	o, err := st.CreateOrder(ctx, Order{Title: "Щит", PriceKop: 500_000, WorkerID: &w.ID})
	if err != nil {
		t.Fatalf("заказ: %v", err)
	}
	if o.WorkerID == nil || *o.WorkerID != w.ID || o.WorkerName != "Пётр" {
		t.Fatalf("исполнитель не записан: %+v", o)
	}

	list, err := st.ListOrders(ctx, "", 10, 0)
	if err != nil || len(list) != 1 || list[0].WorkerName != "Пётр" {
		t.Fatalf("в списке нет исполнителя: %v %+v", err, list)
	}

	o.WorkerID = nil
	o, err = st.UpdateOrder(ctx, o.ID, o)
	if err != nil || o.WorkerID != nil {
		t.Fatalf("снять исполнителя не вышло: %v %+v", err, o)
	}

	o.WorkerID = &w.ID
	if _, err := st.UpdateOrder(ctx, o.ID, o); err != nil {
		t.Fatalf("вернуть исполнителя: %v", err)
	}
	if err := st.DeleteWorker(ctx, w.ID); err != nil {
		t.Fatalf("удаление рабочего: %v", err)
	}
	got, err := st.Order(ctx, o.ID)
	if err != nil || got.WorkerID != nil || got.WorkerName != "" {
		t.Fatalf("после удаления рабочего заказ должен остаться без исполнителя: %v %+v", err, got)
	}
}

// Закупка из кассы: приход с оплатой рождает расход «Материалы» на ту же
// сумму, а удаление прихода убирает и расход.
func TestStockReceiptPaidFromCash(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	item, err := st.CreateCatalogItem(ctx, CatalogItem{Kind: KindMaterial, Name: "Автомат 16А", Unit: "шт"})
	if err != nil {
		t.Fatalf("материал: %v", err)
	}
	move, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: 10_000, CostKop: 250_000, PayMethod: MethodCard})
	if err != nil {
		t.Fatalf("приход: %v", err)
	}
	if move.CashOpID == nil {
		t.Fatal("у оплаченного прихода нет расхода в кассе")
	}
	op, err := st.CashOp(ctx, *move.CashOpID)
	if err != nil {
		t.Fatalf("расход: %v", err)
	}
	if op.Direction != DirectionOut || op.AmountKop != 250_000 || op.Method != MethodCard || op.Category != "Материалы" {
		t.Errorf("расход записан неверно: %+v", op)
	}

	// Без способа оплаты — только склад, касса не трогается.
	plain, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: 1_000, CostKop: 25_000})
	if err != nil || plain.CashOpID != nil {
		t.Fatalf("приход без оплаты не должен трогать кассу: %v %+v", err, plain)
	}

	if err := st.DeleteStockMove(ctx, move.ID); err != nil {
		t.Fatalf("удаление прихода: %v", err)
	}
	if _, err := st.CashOp(ctx, op.ID); err == nil {
		t.Error("после удаления прихода расход остался в кассе")
	}

	if _, err := st.AddStockMove(ctx, item.ID, StockMove{QtyMilli: 1_000, CostKop: 100, PayMethod: "barter"}); err == nil {
		t.Error("неизвестный способ оплаты должен отклоняться")
	}
}
