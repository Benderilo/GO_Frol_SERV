package store

import (
	"context"
	"testing"
)

// Regression test: ClientOrders is read by the same scanOrder as ListOrders
// and Order. When the column lists diverged, the request failed with a Scan
// error and the API answered 500.
func TestClientOrdersScans(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	client, err := st.CreateClient(ctx, Client{Name: "Diman Lopatin"})
	if err != nil {
		t.Fatalf("client creation: %v", err)
	}
	if _, err := st.CreateOrder(ctx, Order{ClientID: &client.ID, Title: "Wiring", Status: "new"}); err != nil {
		t.Fatalf("order creation: %v", err)
	}

	orders, err := st.ClientOrders(ctx, client.ID)
	if err != nil {
		t.Fatalf("reading client orders: %v", err)
	}
	if len(orders) != 1 {
		t.Fatalf("orders %d, expected 1", len(orders))
	}
	if orders[0].ClientName != "Diman Lopatin" {
		t.Errorf("client name %q, expected \"Diman Lopatin\"", orders[0].ClientName)
	}
}

// Search must be case-insensitive. SQLite lower() only lowercases Latin, so
// a Cyrillic query like "Dim" (U+0414 U+0438 U+043C) against the name
// "Diman Lopatin" never matched through SQL LIKE — the filtering now
// happens in Go. Cyrillic strings are written as escapes so the file
// survives any editor encoding untouched.
func TestListClientsSearchCaseInsensitive(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	// "Diman Lopatin" in Cyrillic.
	const cyrName = "Диман Лопатин"
	const cyrQuery = "Дим" // "Dim"

	saved, err := st.CreateClient(ctx, Client{Name: cyrName, Phone: "+7 900 000-00-00"})
	if err != nil {
		t.Fatalf("client creation: %v", err)
	}
	if _, err := st.CreateClient(ctx, Client{Name: "Ivan Petrov"}); err != nil {
		t.Fatalf("second client: %v", err)
	}

	// Lowercase Cyrillic form of the query.
	cyrLower := "диман" // "diman"
	// "Lopatin" in lowercase Cyrillic letters.
	cyrSurname := "лопатин"
	for _, q := range []string{cyrQuery, cyrLower, cyrSurname} {
		got, err := st.ListClients(ctx, q, 100, 0)
		if err != nil {
			t.Fatalf("search %q: %v", q, err)
		}
		if len(got) != 1 || got[0].ID != saved.ID {
			t.Errorf("search %q: found %v, expected only the client %q", q, got, cyrName)
		}
	}

	// Latin search stays case-insensitive as well.
	got, err := st.ListClients(ctx, "IVAN", 100, 0)
	if err != nil {
		t.Fatalf("latin search: %v", err)
	}
	if len(got) != 1 || got[0].Name != "Ivan Petrov" {
		t.Errorf("latin search: found %v, expected only the client \"Ivan Petrov\"", got)
	}

	// The phone is searchable by digits, regardless of formatting.
	got, err = st.ListClients(ctx, "900000", 100, 0)
	if err != nil {
		t.Fatalf("phone search: %v", err)
	}
	if len(got) != 1 || got[0].ID != saved.ID {
		t.Errorf("phone search: found %v, expected only the client %q", got, cyrName)
	}

	// An empty query returns everyone; offset/limit apply to the
	// filtered list.
	got, err = st.ListClients(ctx, "", 1, 1)
	if err != nil {
		t.Fatalf("listing without search: %v", err)
	}
	if len(got) != 1 {
		t.Errorf("listing limit=1 offset=1: returned %d, expected 1", len(got))
	}
}