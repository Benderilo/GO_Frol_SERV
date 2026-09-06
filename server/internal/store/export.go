package store

import (
	"context"
	"database/sql"
)

// Backup — вся база одним куском: то, что уходит в книгу Excel и приезжает
// из неё обратно. Раньше выгружались только клиенты, заказы, заявки и платежи,
// и после «восстановления из резервной копии» оставалось руками заводить склад,
// кассу, состав заказов и задачи. Здесь собрано всё, что человек вводит
// в приложении, — по разделу на лист.
//
// Чего тут нет: фотографии заказов (файлы на диске, в таблицу не ложатся),
// журнал действий и номера выданных документов (их порождает сам сервер),
// наполнение сайта (у него свой редактор и своя выгрузка).
type Backup struct {
	Clients    []Client
	Orders     []Order
	OrderItems []OrderItem
	Requests   []Request
	Payments   []Payment
	Cash       []CashOp
	Catalog    []CatalogItem
	StockMoves []StockMove
	Tasks      []Task
	Company    Company
}

// AllForExport собирает всё, что уходит в выгрузку.
func (s *Store) AllForExport(ctx context.Context) (Backup, error) {
	var b Backup
	var err error

	if b.Clients, err = collectPages(func(offset int) ([]Client, error) {
		return s.ListClients(ctx, "", pageSize, offset)
	}); err != nil {
		return Backup{}, err
	}
	if b.Orders, err = collectPages(func(offset int) ([]Order, error) {
		return s.ListOrders(ctx, "", pageSize, offset)
	}); err != nil {
		return Backup{}, err
	}
	if b.Requests, err = collectPages(func(offset int) ([]Request, error) {
		return s.ListRequests(ctx, "", pageSize, offset)
	}); err != nil {
		return Backup{}, err
	}
	if b.Payments, err = s.PaymentsForExport(ctx); err != nil {
		return Backup{}, err
	}
	if b.OrderItems, err = s.OrderItemsForExport(ctx); err != nil {
		return Backup{}, err
	}
	if b.Cash, err = s.CashOpsForExport(ctx); err != nil {
		return Backup{}, err
	}
	// Архивные позиции тоже в выгрузке: по ним есть движения склада,
	// и без них история прихода-списания повисла бы в воздухе.
	if b.Catalog, err = s.CatalogItems(ctx, "", true); err != nil {
		return Backup{}, err
	}
	if b.StockMoves, err = s.StockMovesForExport(ctx); err != nil {
		return Backup{}, err
	}
	if b.Tasks, err = s.TasksForExport(ctx); err != nil {
		return Backup{}, err
	}
	if b.Company, err = s.Company(ctx); err != nil {
		return Backup{}, err
	}
	return b, nil
}

// OrderItemsForExport — состав всех заказов сразу. Название заказа
// подтягивается, чтобы лист читался без сверки с другим листом по id.
func (s *Store) OrderItemsForExport(ctx context.Context) ([]OrderItem, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT i.id, i.order_id, i.catalog_id, i.kind, i.name, i.unit, i.qty_milli,
		       i.price_kop, i.cost_kop, i.stock_move_id, i.sort, i.created_at,
		       COALESCE(o.title, '')
		FROM order_items i
		LEFT JOIN orders o ON o.id = i.order_id
		ORDER BY i.order_id, i.sort, i.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]OrderItem, 0, 64)
	for rows.Next() {
		var item OrderItem
		var catalogID, moveID sql.NullInt64
		if err := rows.Scan(&item.ID, &item.OrderID, &catalogID, &item.Kind, &item.Name,
			&item.Unit, &item.QtyMilli, &item.PriceKop, &item.CostKop, &moveID,
			&item.Sort, &item.CreatedAt, &item.OrderTitle); err != nil {
			return nil, err
		}
		if catalogID.Valid {
			id := catalogID.Int64
			item.CatalogID = &id
		}
		if moveID.Valid {
			id := moveID.Int64
			item.StockMoveID = &id
		}
		item.TotalKop = lineTotal(item.QtyMilli, item.PriceKop)
		item.TotalCostKop = lineTotal(item.QtyMilli, item.CostKop)
		out = append(out, item)
	}
	return out, rows.Err()
}

// TasksForExport — все задачи в порядке появления. Порядок здесь значим:
// родитель почти всегда заведён раньше подзадачи, поэтому по id ссылка
// смотрит вверх по листу, и восстановление одним проходом сохраняет дерево.
// Экранный порядок (сначала несделанные, по сроку) для этого не годится.
func (s *Store) TasksForExport(ctx context.Context) ([]Task, error) {
	rows, err := s.db.QueryContext(ctx, taskSelect+" ORDER BY t.id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Task, 0, 32)
	for rows.Next() {
		task, err := scanTask(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, task)
	}
	return out, rows.Err()
}

// CashOpsForExport — вся касса без ограничения по количеству и периоду:
// у CashOps лимит рассчитан на экран списка, а в резервную копию должно
// попасть всё до последней операции.
func (s *Store) CashOpsForExport(ctx context.Context) ([]CashOp, error) {
	rows, err := s.db.QueryContext(ctx, cashSelect+" ORDER BY p.id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]CashOp, 0, 64)
	for rows.Next() {
		op, err := scanCashOp(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, op)
	}
	return out, rows.Err()
}

// StockMovesForExport — вся история склада, старые движения первыми:
// так по листу видно, как набирался остаток.
func (s *Store) StockMovesForExport(ctx context.Context) ([]StockMove, error) {
	rows, err := s.db.QueryContext(ctx, stockSelect+" ORDER BY m.id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]StockMove, 0, 64)
	for rows.Next() {
		var m StockMove
		var orderID sql.NullInt64
		if err := rows.Scan(&m.ID, &m.ItemID, &orderID, &m.QtyMilli, &m.CostKop, &m.Note,
			&m.CreatedAt, &m.ItemName, &m.Unit, &m.OrderTitle); err != nil {
			return nil, err
		}
		if orderID.Valid {
			id := orderID.Int64
			m.OrderID = &id
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
