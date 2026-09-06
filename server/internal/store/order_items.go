package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// ErrAlreadyWrittenOff — материал по этой строке уже списан со склада.
var ErrAlreadyWrittenOff = errors.New("строка уже списана со склада")

// OrderItem — строка состава заказа.
//
// Название, единица и цены скопированы из справочника в момент добавления,
// а не берутся по ссылке: позицию потом переименуют или она подорожает,
// а в выданном клиенту перечне должно остаться то, о чём договаривались.
// CatalogID остаётся, чтобы знать, откуда строка пришла, и списать материал.
type OrderItem struct {
	ID        int64  `json:"id"`
	OrderID   int64  `json:"orderId"`
	CatalogID *int64 `json:"catalogId"`
	Kind      string `json:"kind"`
	Name      string `json:"name"`
	Unit      string `json:"unit"`
	QtyMilli  int64  `json:"qtyMilli"`
	PriceKop  int64  `json:"priceKop"`
	CostKop   int64  `json:"costKop"`
	Sort      int64  `json:"sort"`

	// Движение склада, которым строка списана. Пусто — материал ещё на складе.
	StockMoveID *int64 `json:"stockMoveId"`

	CreatedAt string `json:"createdAt"`

	// Считаются при чтении: количество на цену и количество на закупку.
	TotalKop     int64 `json:"totalKop"`
	TotalCostKop int64 `json:"totalCostKop"`

	// Заполняется в выгрузке, чтобы лист состава читался без сверки по id.
	OrderTitle string `json:"orderTitle,omitempty"`
}

// WrittenOff отвечает, списан ли материал по этой строке.
func (i OrderItem) WrittenOff() bool { return i.StockMoveID != nil }

// lineTotal — количество на цену. Тысячные доли на копейки дают миллионные,
// поэтому делим на 1000 с округлением: 0.333 м по 100 ₽ — это 33.30 ₽,
// а не 33.29, набежавшее от отбрасывания.
func lineTotal(qtyMilli, priceKop int64) int64 {
	product := qtyMilli * priceKop
	if product < 0 {
		return -((-product + 500) / 1000)
	}
	return (product + 500) / 1000
}

const orderItemSelect = `
	SELECT id, order_id, catalog_id, kind, name, unit, qty_milli, price_kop, cost_kop,
	       stock_move_id, sort, created_at
	FROM order_items`

func (s *Store) OrderItems(ctx context.Context, orderID int64) ([]OrderItem, error) {
	rows, err := s.db.QueryContext(ctx,
		orderItemSelect+" WHERE order_id = ? ORDER BY sort, id", orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]OrderItem, 0, 8)
	for rows.Next() {
		item, err := scanOrderItem(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (s *Store) OrderItem(ctx context.Context, id int64) (OrderItem, error) {
	rows, err := s.db.QueryContext(ctx, orderItemSelect+" WHERE id = ?", id)
	if err != nil {
		return OrderItem{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return OrderItem{}, err
		}
		return OrderItem{}, ErrNotFound
	}
	return scanOrderItem(rows)
}

// AddOrderItem добавляет строку. Если указан catalog_id, недостающие поля
// берём из справочника: приложению незачем пересылать то, что сервер знает.
func (s *Store) AddOrderItem(ctx context.Context, orderID int64, item OrderItem) (OrderItem, error) {
	if _, err := s.Order(ctx, orderID); err != nil {
		return OrderItem{}, err
	}
	item = s.fillFromCatalog(ctx, item)
	if strings.TrimSpace(item.Name) == "" {
		return OrderItem{}, fmt.Errorf("%w: строка без наименования", ErrNotFound)
	}

	var nextSort int64
	if err := s.db.QueryRowContext(ctx,
		`SELECT COALESCE(MAX(sort), 0) + 1 FROM order_items WHERE order_id = ?`,
		orderID).Scan(&nextSort); err != nil {
		return OrderItem{}, err
	}

	res, err := s.db.ExecContext(ctx,
		`INSERT INTO order_items
		   (order_id, catalog_id, kind, name, unit, qty_milli, price_kop, cost_kop, sort, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		orderID, item.CatalogID, kindOrDefault(item.Kind), strings.TrimSpace(item.Name),
		unitOrDefault(item.Unit), qtyOrOne(item.QtyMilli), item.PriceKop, item.CostKop,
		nextSort, now())
	if err != nil {
		return OrderItem{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return OrderItem{}, err
	}
	if err := s.recalcOrderTotal(ctx, orderID); err != nil {
		return OrderItem{}, err
	}
	s.AddAudit(ctx, "order_item", id, "create", item.Name)
	return s.OrderItem(ctx, id)
}

// UpdateOrderItem правит строку. Списанную со склада строку сначала
// возвращаем на склад, потом списываем заново: иначе остаток разошёлся бы
// с тем количеством, которое стоит в строке.
func (s *Store) UpdateOrderItem(ctx context.Context, id int64, item OrderItem) (OrderItem, error) {
	current, err := s.OrderItem(ctx, id)
	if err != nil {
		return OrderItem{}, err
	}

	rewriteOff := current.WrittenOff()
	if rewriteOff {
		if err := s.DeleteStockMove(ctx, *current.StockMoveID); err != nil {
			return OrderItem{}, err
		}
		if _, err := s.db.ExecContext(ctx,
			`UPDATE order_items SET stock_move_id = NULL WHERE id = ?`, id); err != nil {
			return OrderItem{}, err
		}
	}

	res, err := s.db.ExecContext(ctx,
		`UPDATE order_items
		   SET kind = ?, name = ?, unit = ?, qty_milli = ?, price_kop = ?, cost_kop = ?
		 WHERE id = ?`,
		kindOrDefault(item.Kind), strings.TrimSpace(item.Name), unitOrDefault(item.Unit),
		qtyOrOne(item.QtyMilli), item.PriceKop, item.CostKop, id)
	if err != nil {
		return OrderItem{}, err
	}
	if err := affected(res); err != nil {
		return OrderItem{}, err
	}
	if err := s.recalcOrderTotal(ctx, current.OrderID); err != nil {
		return OrderItem{}, err
	}
	if rewriteOff {
		if _, err := s.writeOffItem(ctx, id); err != nil {
			return OrderItem{}, err
		}
	}
	s.AddAudit(ctx, "order_item", id, "update", item.Name)
	return s.OrderItem(ctx, id)
}

// DeleteOrderItem убирает строку и возвращает списанный по ней материал
// на склад: строки нет — значит, и расхода не было.
func (s *Store) DeleteOrderItem(ctx context.Context, id int64) error {
	item, err := s.OrderItem(ctx, id)
	if err != nil {
		return err
	}
	if item.WrittenOff() {
		if err := s.DeleteStockMove(ctx, *item.StockMoveID); err != nil {
			return err
		}
	}
	res, err := s.db.ExecContext(ctx, `DELETE FROM order_items WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	if err := s.recalcOrderTotal(ctx, item.OrderID); err != nil {
		return err
	}
	s.AddAudit(ctx, "order_item", id, "delete", item.Name)
	return nil
}

// WriteOffOrder списывает со склада все материалы заказа, которые ещё не
// списаны. Отдаёт, сколько строк списалось: ноль означает, что списывать
// было нечего, и это не ошибка.
func (s *Store) WriteOffOrder(ctx context.Context, orderID int64) (int, error) {
	items, err := s.OrderItems(ctx, orderID)
	if err != nil {
		return 0, err
	}
	written := 0
	for _, item := range items {
		if item.Kind != KindMaterial || item.CatalogID == nil || item.WrittenOff() {
			continue
		}
		if _, err := s.writeOffItem(ctx, item.ID); err != nil {
			// Остатка не хватило — говорим об этом сразу и не списываем
			// остальное наполовину: половина списания хуже, чем ничего.
			return written, err
		}
		written++
	}
	return written, nil
}

// writeOffItem списывает материал по одной строке и запоминает движение.
func (s *Store) writeOffItem(ctx context.Context, itemID int64) (OrderItem, error) {
	item, err := s.OrderItem(ctx, itemID)
	if err != nil {
		return OrderItem{}, err
	}
	if item.Kind != KindMaterial || item.CatalogID == nil {
		return item, nil
	}
	if item.WrittenOff() {
		return OrderItem{}, ErrAlreadyWrittenOff
	}

	move, err := s.AddStockMove(ctx, *item.CatalogID, StockMove{
		OrderID:  &item.OrderID,
		QtyMilli: -item.QtyMilli,
		CostKop:  item.TotalCostKop,
		Note:     "списано по заказу",
	})
	if err != nil {
		return OrderItem{}, err
	}
	if _, err := s.db.ExecContext(ctx,
		`UPDATE order_items SET stock_move_id = ? WHERE id = ?`, move.ID, itemID); err != nil {
		return OrderItem{}, err
	}
	return s.OrderItem(ctx, itemID)
}

// recalcOrderTotal держит orders.price_kop равным сумме позиций.
// У заказа без позиций цену не трогаем: там она вписана руками, и обнулить
// её пересчётом значило бы потерять сумму старых заказов.
func (s *Store) recalcOrderTotal(ctx context.Context, orderID int64) error {
	items, err := s.OrderItems(ctx, orderID)
	if err != nil {
		return err
	}
	if len(items) == 0 {
		return nil
	}
	var total int64
	for _, item := range items {
		total += item.TotalKop
	}
	_, err = s.db.ExecContext(ctx,
		`UPDATE orders SET price_kop = ?, updated_at = ? WHERE id = ?`, total, now(), orderID)
	return err
}

// fillFromCatalog дописывает поля из справочника там, где приложение их
// не прислало: выбрал позицию — цена и единица подставились сами.
func (s *Store) fillFromCatalog(ctx context.Context, item OrderItem) OrderItem {
	if item.CatalogID == nil {
		return item
	}
	source, err := s.CatalogItem(ctx, *item.CatalogID)
	if err != nil {
		// Позиции больше нет — строка останется тем, что прислали.
		item.CatalogID = nil
		return item
	}
	if strings.TrimSpace(item.Name) == "" {
		item.Name = source.Name
	}
	if strings.TrimSpace(item.Unit) == "" {
		item.Unit = source.Unit
	}
	if item.Kind == "" {
		item.Kind = source.Kind
	}
	if item.PriceKop == 0 {
		item.PriceKop = source.PriceKop
	}
	if item.CostKop == 0 {
		item.CostKop = source.CostKop
	}
	return item
}

func scanOrderItem(rows *sql.Rows) (OrderItem, error) {
	var item OrderItem
	var catalogID, moveID sql.NullInt64
	if err := rows.Scan(&item.ID, &item.OrderID, &catalogID, &item.Kind, &item.Name,
		&item.Unit, &item.QtyMilli, &item.PriceKop, &item.CostKop, &moveID,
		&item.Sort, &item.CreatedAt); err != nil {
		return OrderItem{}, err
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
	return item, nil
}

func kindOrDefault(kind string) string {
	if ValidKind(kind) {
		return kind
	}
	return KindService
}

// qtyOrOne: строка без количества — это одна единица, а не ноль.
// Ноль означал бы бесплатную строку, чего никто не имел в виду.
func qtyOrOne(milli int64) int64 {
	if milli == 0 {
		return 1000
	}
	return milli
}
