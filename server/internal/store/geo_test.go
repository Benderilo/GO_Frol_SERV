package store

import (
	"context"
	"testing"
)

func TestParseLegacyGeo(t *testing.T) {
	lat, lon, ok := parseLegacyGeo("геопозиция 52.020000, 47.780000 — https://yandex.ru/maps/?pt=47.780000,52.020000&z=17")
	if !ok || lat != 52.02 || lon != 47.78 {
		t.Fatalf("старый формат: %v %v %v", lat, lon, ok)
	}
	for _, s := range []string{"ул. Ленина, 5", "геопозиция рядом с магазином", "геопозиция 0, 0", ""} {
		if _, _, ok := parseLegacyGeo(s); ok {
			t.Fatalf("не геопозиция: %q", s)
		}
	}
}

// Заявки и клиенты, где бот раньше записал точку текстом в адрес, после
// перезапуска получают координаты, а адрес освобождается; заказ по заявке
// берёт точку себе.
func TestBackfillGeo(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	legacy := "геопозиция 52.020000, 47.780000 — https://yandex.ru/maps/?pt=47.780000,52.020000&z=17"
	req, err := st.CreateRequest(ctx, Request{Name: "Иван", Phone: "+79001234567", Message: "Нет света", Address: legacy, Source: "max"})
	if err != nil {
		t.Fatal(err)
	}
	// Как было до переноса: у заказа нет ни адреса, ни точки.
	if _, err := st.db.ExecContext(ctx, `UPDATE orders SET address = '', lat = 0, lon = 0`); err != nil {
		t.Fatal(err)
	}
	if err := st.backfillGeo(ctx); err != nil {
		t.Fatal(err)
	}
	if err := st.backfillGeo(ctx); err != nil { // повторный запуск ничего не ломает
		t.Fatal(err)
	}

	r, _ := st.Request(ctx, req.ID)
	if r.Address != "" || r.Lat != 52.02 || r.Lon != 47.78 {
		t.Fatalf("заявка: %+v", r)
	}
	c, _ := st.ClientByPhone(ctx, "89001234567")
	if c.Address != "" || c.Lat != 52.02 || c.Lon != 47.78 {
		t.Fatalf("клиент: %+v", c)
	}
	o, _ := st.Order(ctx, req.OrderID)
	if o.Lat != 52.02 || o.Lon != 47.78 {
		t.Fatalf("заказ: %+v", o)
	}
}

// Точку из новой заявки клиент получает, только если своей у него нет:
// вторая заявка может быть с другого объекта — её точка остаётся в заказе.
func TestRequestGeoGoesToOrder(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	first, err := st.CreateRequest(ctx, Request{Name: "Иван", Phone: "+79001234567", Message: "Дом", Lat: 52.02, Lon: 47.78, Source: "max"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := st.CreateRequest(ctx, Request{Name: "Иван", Phone: "+79001234567", Message: "Дача", Lat: 52.5, Lon: 47.1, Source: "max"})
	if err != nil {
		t.Fatal(err)
	}
	c, _ := st.ClientByPhone(ctx, "+79001234567")
	if c.Lat != 52.02 || c.Lon != 47.78 {
		t.Fatalf("у клиента первая точка: %+v", c)
	}
	o1, _ := st.Order(ctx, first.OrderID)
	o2, _ := st.Order(ctx, second.OrderID)
	if o1.Lat != 52.02 || o2.Lat != 52.5 || o2.Lon != 47.1 {
		t.Fatalf("у заказов свои точки: %+v / %+v", o1, o2)
	}
}

// Вход в кабинет читает клиента тем же scanClient, что и CRM.
func TestPortalClientScans(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	c, err := st.CreateClient(ctx, Client{Name: "Иван", Phone: "+79001234567", Lat: 52.02, Lon: 47.78})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.db.ExecContext(ctx, `UPDATE clients SET portal_enabled = 1 WHERE id = ?`, c.ID); err != nil {
		t.Fatal(err)
	}
	got, err := st.ClientForPortal(ctx, c.ID)
	if err != nil || got.Lat != 52.02 {
		t.Fatalf("кабинет: %+v, %v", got, err)
	}
}
