package store

import (
	"context"
	"errors"
)

// Результат загрузки: сколько записей создано и сколько обновлено.
type UpsertCount struct {
	Created int `json:"created"`
	Updated int `json:"updated"`
}

// UpsertClient создаёт или обновляет клиента.
// Если id задан, но такой записи нет, вставляем именно с этим id:
// так выгрузка и загрузка работают как настоящая резервная копия.
func (s *Store) UpsertClient(ctx context.Context, c Client) (created bool, err error) {
	if c.ID == 0 {
		_, err := s.CreateClient(ctx, c)
		return true, err
	}

	if _, err := s.Client(ctx, c.ID); err == nil {
		_, err := s.UpdateClient(ctx, c.ID, c)
		return false, err
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	ts := now()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO clients (id, name, phone, email, address, note, tag, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		c.ID, c.Name, c.Phone, c.Email, c.Address, c.Note, c.Tag, ts, ts)
	return true, err
}

func (s *Store) UpsertOrder(ctx context.Context, o Order) (created bool, err error) {
	// Ссылка на несуществующего клиента нарушила бы внешний ключ.
	if o.ClientID != nil {
		if _, err := s.Client(ctx, *o.ClientID); err != nil {
			o.ClientID = nil
		}
	}

	if o.ID == 0 {
		_, err := s.CreateOrder(ctx, o)
		return true, err
	}

	if _, err := s.Order(ctx, o.ID); err == nil {
		_, err := s.UpdateOrder(ctx, o.ID, o)
		return false, err
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	ts := now()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO orders (id, client_id, title, description, status, price, due_date, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		o.ID, o.ClientID, o.Title, o.Description, defaultStatus(o.Status), o.Price, o.DueDate, ts, ts)
	return true, err
}

func (s *Store) UpsertRequest(ctx context.Context, r Request) (created bool, err error) {
	if r.ID == 0 {
		_, err := s.CreateRequest(ctx, r)
		return true, err
	}

	if _, err := s.Request(ctx, r.ID); err == nil {
		_, err := s.UpdateRequestStatus(ctx, r.ID, r.Status)
		return false, err
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	ts := now()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO requests (id, name, phone, message, source, status, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		r.ID, r.Name, r.Phone, r.Message, defaultSource(r.Source), defaultStatus(r.Status), ts, ts)
	return true, err
}

func defaultSource(v string) string {
	if v == "" {
		return "site"
	}
	return v
}

// pageSize — списочные методы отдают максимум 500 записей за раз,
// поэтому выгрузка идёт страницами, а не одним огромным запросом.
const pageSize = 500

// AllForExport собирает всё, что уходит в выгрузку.
func (s *Store) AllForExport(ctx context.Context) ([]Client, []Order, []Request, error) {
	clients, err := collectPages(func(offset int) ([]Client, error) {
		return s.ListClients(ctx, "", pageSize, offset)
	})
	if err != nil {
		return nil, nil, nil, err
	}
	orders, err := collectPages(func(offset int) ([]Order, error) {
		return s.ListOrders(ctx, "", pageSize, offset)
	})
	if err != nil {
		return nil, nil, nil, err
	}
	requests, err := collectPages(func(offset int) ([]Request, error) {
		return s.ListRequests(ctx, "", pageSize, offset)
	})
	if err != nil {
		return nil, nil, nil, err
	}
	return clients, orders, requests, nil
}

func collectPages[T any](page func(offset int) ([]T, error)) ([]T, error) {
	all := make([]T, 0, pageSize)
	for offset := 0; ; offset += pageSize {
		batch, err := page(offset)
		if err != nil {
			return nil, err
		}
		all = append(all, batch...)
		if len(batch) < pageSize {
			return all, nil
		}
	}
}
