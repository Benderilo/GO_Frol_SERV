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
	status := defaultStatus(o.Status)
	closedAt := ""
	if status == "done" {
		closedAt = ts
	}
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO orders (id, client_id, title, description, status, price_kop, due_date, created_at, updated_at, closed_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		o.ID, o.ClientID, o.Title, o.Description, status, o.PriceKop, o.DueDate, ts, ts, closedAt)
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

// ---------- Загрузка остальных разделов ----------
//
// Правило одно на все: id из файла сохраняется. Есть такая строка — она
// обновляется, нет — вставляется с тем же id. Иначе восстановление из копии
// порождало бы дубли, а ссылки между листами (заказ → его состав, материал →
// его движения) указывали бы в пустоту.
//
// Ссылки на записи, которых в базе нет, обнуляются вместо ошибки: лучше
// принять строку без привязки к заказу, чем потерять её целиком.

// UpsertCatalogItem создаёт или обновляет позицию справочника.
func (s *Store) UpsertCatalogItem(ctx context.Context, item CatalogItem) (created bool, err error) {
	item.Kind = kindOrDefault(item.Kind)
	if item.ID == 0 {
		_, err := s.CreateCatalogItem(ctx, item)
		return true, err
	}

	if _, err := s.CatalogItem(ctx, item.ID); err == nil {
		_, err := s.UpdateCatalogItem(ctx, item.ID, item)
		return false, err
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	ts := now()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO catalog_items (id, kind, name, unit, price_kop, cost_kop, note, archived, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		item.ID, item.Kind, item.Name, unitOrDefault(item.Unit), item.PriceKop, item.CostKop,
		item.Note, boolToInt(item.Archived), ts, ts)
	return true, err
}

// UpsertStockMove восстанавливает движение склада как оно было записано.
// В обход AddStockMove: тот не даёт уйти в минус, а при загрузке история
// приезжает вперемешку, и промежуточный остаток может быть отрицательным.
func (s *Store) UpsertStockMove(ctx context.Context, m StockMove) (created bool, err error) {
	if m.ItemID == 0 {
		return false, ErrNotFound
	}
	if _, err := s.CatalogItem(ctx, m.ItemID); err != nil {
		return false, err
	}
	if m.OrderID != nil {
		if _, err := s.Order(ctx, *m.OrderID); err != nil {
			m.OrderID = nil
		}
	}
	createdAt := m.CreatedAt
	if createdAt == "" {
		createdAt = now()
	}

	if m.ID != 0 {
		res, err := s.db.ExecContext(ctx,
			`UPDATE stock_moves SET item_id = ?, order_id = ?, qty_milli = ?, cost_kop = ?, note = ?
			 WHERE id = ?`,
			m.ItemID, m.OrderID, m.QtyMilli, m.CostKop, m.Note, m.ID)
		if err != nil {
			return false, err
		}
		if n, err := res.RowsAffected(); err == nil && n > 0 {
			return false, nil
		}
		_, err = s.db.ExecContext(ctx,
			`INSERT INTO stock_moves (id, item_id, order_id, qty_milli, cost_kop, note, created_at)
			 VALUES (?, ?, ?, ?, ?, ?, ?)`,
			m.ID, m.ItemID, m.OrderID, m.QtyMilli, m.CostKop, m.Note, createdAt)
		return true, err
	}

	_, err = s.db.ExecContext(ctx,
		`INSERT INTO stock_moves (item_id, order_id, qty_milli, cost_kop, note, created_at)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		m.ItemID, m.OrderID, m.QtyMilli, m.CostKop, m.Note, createdAt)
	return true, err
}

// UpsertOrderItem восстанавливает строку состава заказа. Итог заказа после
// этого пересчитывается: цена заказа с составом — это сумма его строк.
func (s *Store) UpsertOrderItem(ctx context.Context, item OrderItem) (created bool, err error) {
	if item.OrderID == 0 {
		return false, ErrNotFound
	}
	if _, err := s.Order(ctx, item.OrderID); err != nil {
		return false, err
	}
	item.Kind = kindOrDefault(item.Kind)
	if item.CatalogID != nil {
		if _, err := s.CatalogItem(ctx, *item.CatalogID); err != nil {
			item.CatalogID = nil
		}
	}
	if item.StockMoveID != nil && !s.stockMoveExists(ctx, *item.StockMoveID) {
		item.StockMoveID = nil
	}
	createdAt := item.CreatedAt
	if createdAt == "" {
		createdAt = now()
	}

	if item.ID != 0 {
		res, err := s.db.ExecContext(ctx,
			`UPDATE order_items
			 SET order_id = ?, catalog_id = ?, kind = ?, name = ?, unit = ?, qty_milli = ?,
			     price_kop = ?, cost_kop = ?, stock_move_id = ?, sort = ?
			 WHERE id = ?`,
			item.OrderID, item.CatalogID, item.Kind, item.Name, unitOrDefault(item.Unit),
			item.QtyMilli, item.PriceKop, item.CostKop, item.StockMoveID, item.Sort, item.ID)
		if err != nil {
			return false, err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return false, err
		}
		if n > 0 {
			return false, s.recalcOrderTotal(ctx, item.OrderID)
		}
		if _, err := s.db.ExecContext(ctx,
			`INSERT INTO order_items
			   (id, order_id, catalog_id, kind, name, unit, qty_milli, price_kop, cost_kop, stock_move_id, sort, created_at)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			item.ID, item.OrderID, item.CatalogID, item.Kind, item.Name, unitOrDefault(item.Unit),
			item.QtyMilli, item.PriceKop, item.CostKop, item.StockMoveID, item.Sort, createdAt); err != nil {
			return false, err
		}
		return true, s.recalcOrderTotal(ctx, item.OrderID)
	}

	if _, err := s.AddOrderItem(ctx, item.OrderID, item); err != nil {
		return false, err
	}
	return true, nil
}

func (s *Store) stockMoveExists(ctx context.Context, id int64) bool {
	var one int
	err := s.db.QueryRowContext(ctx, `SELECT 1 FROM stock_moves WHERE id = ?`, id).Scan(&one)
	return err == nil
}

// UpsertTask создаёт или обновляет задачу.
func (s *Store) UpsertTask(ctx context.Context, t Task) (created bool, err error) {
	if t.ClientID != nil {
		if _, err := s.Client(ctx, *t.ClientID); err != nil {
			t.ClientID = nil
		}
	}
	if t.OrderID != nil {
		if _, err := s.Order(ctx, *t.OrderID); err != nil {
			t.OrderID = nil
		}
	}
	// Родитель может ещё не приехать из файла — тогда задача встаёт верхним
	// уровнем: потерять её хуже, чем потерять вложенность.
	if t.ParentID != nil {
		if _, err := s.Task(ctx, *t.ParentID); err != nil {
			t.ParentID = nil
		}
	}

	if t.ID == 0 {
		_, err := s.CreateTask(ctx, t)
		return true, err
	}

	if _, err := s.Task(ctx, t.ID); err == nil {
		_, err := s.UpdateTask(ctx, t.ID, t)
		return false, err
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	ts := now()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO tasks (id, client_id, order_id, parent_id, title, note, due_date, done, priority, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		t.ID, t.ClientID, t.OrderID, t.ParentID, t.Title, t.Note, t.DueDate,
		boolInt(t.Done), defaultPriority(t.Priority), ts, ts)
	return true, err
}

// UpsertCashOp создаёт или обновляет операцию кассы.
func (s *Store) UpsertCashOp(ctx context.Context, op CashOp) (created bool, err error) {
	if !ValidDirection(op.Direction) {
		op.Direction = DirectionIn
	}
	if !ValidMethod(op.Method) {
		op.Method = MethodCash
	}
	if op.OrderID != nil {
		if _, err := s.Order(ctx, *op.OrderID); err != nil {
			op.OrderID = nil
		}
	}
	if op.ClientID != nil {
		if _, err := s.Client(ctx, *op.ClientID); err != nil {
			op.ClientID = nil
		}
	}
	if op.HappenedAt == "" {
		op.HappenedAt = now()
	}

	if op.ID == 0 {
		_, err := s.AddCashOp(ctx, op)
		return true, err
	}

	if _, err := s.CashOp(ctx, op.ID); err == nil {
		_, err := s.db.ExecContext(ctx,
			`UPDATE cash_ops
			 SET direction = ?, amount_kop = ?, method = ?, category = ?, order_id = ?,
			     client_id = ?, note = ?, happened_at = ?
			 WHERE id = ?`,
			op.Direction, op.AmountKop, op.Method, op.Category, op.OrderID,
			op.ClientID, op.Note, op.HappenedAt, op.ID)
		return false, err
	} else if !errors.Is(err, ErrNotFound) {
		return false, err
	}

	_, err = s.db.ExecContext(ctx,
		`INSERT INTO cash_ops (id, direction, amount_kop, method, category, order_id, client_id, note, happened_at, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		op.ID, op.Direction, op.AmountKop, op.Method, op.Category, op.OrderID,
		op.ClientID, op.Note, op.HappenedAt, now())
	return true, err
}
