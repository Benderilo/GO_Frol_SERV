package store

import (
	"context"
	"testing"
)

func countOrders(t *testing.T, s *Store) int {
	t.Helper()
	orders, err := s.ListOrders(context.Background(), "", 500, 0)
	if err != nil {
		t.Fatal(err)
	}
	return len(orders)
}

// Кнопка «Заказ» по заявке открывает заказ, заведённый вместе с ней,
// а не создаёт второй — сколько её ни нажимай.
func TestOrderFromRequestReusesOrder(t *testing.T) {
	s := openTestStore(t)
	ctx := context.Background()

	req, err := s.CreateRequest(ctx, Request{Name: "Пётр", Phone: "+79001234567", Message: "Нет света"})
	if err != nil {
		t.Fatal(err)
	}
	if req.OrderID == 0 {
		t.Fatal("заявка без ссылки на свой заказ")
	}

	for i := range 2 {
		order, got, created, err := s.OrderFromRequest(ctx, req.ID)
		if err != nil {
			t.Fatal(err)
		}
		if created || order.ID != req.OrderID {
			t.Fatalf("нажатие %d: заказ %d, создан=%v, ждали %d", i+1, order.ID, created, req.OrderID)
		}
		if got.Status != "in_progress" {
			t.Fatalf("заявка не ушла в работу: %q", got.Status)
		}
	}
	if n := countOrders(t, s); n != 1 {
		t.Fatalf("заказов %d, ждали 1", n)
	}
}

// Заказ удалили — кнопка заводит новый и привязывает его; готовую
// заявку обратно в работу не возвращает.
func TestOrderFromRequestAfterOrderDeleted(t *testing.T) {
	s := openTestStore(t)
	ctx := context.Background()

	req, _ := s.CreateRequest(ctx, Request{Name: "Анна", Phone: "89171112233", Message: "Розетки"})
	if err := s.DeleteOrder(ctx, req.OrderID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.UpdateRequestStatus(ctx, req.ID, "done"); err != nil {
		t.Fatal(err)
	}

	order, got, created, err := s.OrderFromRequest(ctx, req.ID)
	if err != nil || !created || order.ID == 0 || got.OrderID != order.ID {
		t.Fatalf("новый заказ: %+v %+v %v %v", order, got, created, err)
	}
	if got.Status != "done" {
		t.Fatalf("статус обработанной заявки сменился на %q", got.Status)
	}
	if order.ClientID == nil {
		t.Fatal("заказ без клиента")
	}
	// Второе нажатие — тот же заказ.
	if again, _, created, _ := s.OrderFromRequest(ctx, req.ID); created || again.ID != order.ID {
		t.Fatalf("повторное нажатие: %d, создан=%v", again.ID, created)
	}
}

// Заявки, принятые до появления ссылки, находят свой заказ при запуске.
func TestBackfillRequestOrders(t *testing.T) {
	s := openTestStore(t)
	ctx := context.Background()

	req, _ := s.CreateRequest(ctx, Request{Name: "Олег", Phone: "89003334455", Message: "Щиток"})
	// Заказ, созданный кнопкой приложения, называется по тексту заявки —
	// его привязывать нельзя.
	if _, err := s.CreateOrder(ctx, Order{Title: "Щиток", Status: "new"}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE requests SET order_id = NULL`); err != nil {
		t.Fatal(err)
	}

	if err := s.backfillRequestOrders(ctx); err != nil {
		t.Fatal(err)
	}
	got, _ := s.Request(ctx, req.ID)
	if got.OrderID != req.OrderID {
		t.Fatalf("привязан заказ %d, ждали %d", got.OrderID, req.OrderID)
	}
}
