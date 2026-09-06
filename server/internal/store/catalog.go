package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// Виды позиций справочника. Склад есть только у материала: услугу и работу
// не привозят на объект и не пересчитывают в конце месяца.
const (
	KindService  = "service"
	KindWork     = "work"
	KindMaterial = "material"
)

// ErrStockHistory — позицию с движениями удалять нельзя: вместе с ней исчезла
// бы история прихода и списания, по которой сходится остаток.
var ErrStockHistory = errors.New("по позиции есть движения")

// CatalogItem — позиция справочника: услуга, работа или материал.
//
// PriceKop — цена продажи, CostKop — цена закупки. Разница между ними и есть
// то, из чего потом считается заработок по заказу, поэтому закупку храним
// у самой позиции, а не вспоминаем по чекам из магазина.
type CatalogItem struct {
	ID       int64  `json:"id"`
	Kind     string `json:"kind"`
	Name     string `json:"name"`
	Unit     string `json:"unit"`
	PriceKop int64  `json:"priceKop"`
	CostKop  int64  `json:"costKop"`
	Note     string `json:"note"`
	Archived bool   `json:"archived"`

	CreatedAt string `json:"createdAt"`
	UpdatedAt string `json:"updatedAt"`

	// Остаток в тысячных долях единицы. Считается из движений,
	// у услуг и работ всегда ноль.
	StockMilli int64 `json:"stockMilli"`
}

// StockMove — одно движение материала. Количество положительное на приходе
// и отрицательное на списании: так остаток — просто сумма, без разбора знаков
// по видам операций.
type StockMove struct {
	ID       int64  `json:"id"`
	ItemID   int64  `json:"itemId"`
	OrderID  *int64 `json:"orderId"`
	QtyMilli int64  `json:"qtyMilli"`
	CostKop  int64  `json:"costKop"`
	Note     string `json:"note"`

	CreatedAt string `json:"createdAt"`

	// Заполняются при чтении, для читаемого списка.
	ItemName   string `json:"itemName,omitempty"`
	Unit       string `json:"unit,omitempty"`
	OrderTitle string `json:"orderTitle,omitempty"`
}

// ValidKind отвечает, знаком ли нам такой вид позиции.
func ValidKind(kind string) bool {
	switch kind {
	case KindService, KindWork, KindMaterial:
		return true
	}
	return false
}

const catalogSelect = `
	SELECT i.id, i.kind, i.name, i.unit, i.price_kop, i.cost_kop, i.note, i.archived,
	       i.created_at, i.updated_at,
	       COALESCE((SELECT SUM(m.qty_milli) FROM stock_moves m WHERE m.item_id = i.id), 0)
	FROM catalog_items i`

// CatalogItems отдаёт справочник. Пустой kind — все виды. Архивные позиции
// по умолчанию скрыты: их держат ради истории, а не для выбора в заказе.
func (s *Store) CatalogItems(ctx context.Context, kind string, withArchived bool) ([]CatalogItem, error) {
	query := catalogSelect
	var where []string
	var args []any
	if kind != "" {
		where = append(where, "i.kind = ?")
		args = append(args, kind)
	}
	if !withArchived {
		where = append(where, "i.archived = 0")
	}
	if len(where) > 0 {
		query += " WHERE " + strings.Join(where, " AND ")
	}
	// Материалы первыми: за ними следят, а услуги просто выбирают из списка.
	query += ` ORDER BY CASE i.kind WHEN 'material' THEN 0 WHEN 'work' THEN 1 ELSE 2 END, i.name`

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]CatalogItem, 0, 32)
	for rows.Next() {
		item, err := scanCatalogItem(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (s *Store) CatalogItem(ctx context.Context, id int64) (CatalogItem, error) {
	rows, err := s.db.QueryContext(ctx, catalogSelect+" WHERE i.id = ?", id)
	if err != nil {
		return CatalogItem{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return CatalogItem{}, err
		}
		return CatalogItem{}, ErrNotFound
	}
	return scanCatalogItem(rows)
}

func (s *Store) CreateCatalogItem(ctx context.Context, item CatalogItem) (CatalogItem, error) {
	ts := now()
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO catalog_items (kind, name, unit, price_kop, cost_kop, note, archived, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		item.Kind, item.Name, unitOrDefault(item.Unit), item.PriceKop, item.CostKop,
		item.Note, boolToInt(item.Archived), ts, ts)
	if err != nil {
		return CatalogItem{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return CatalogItem{}, err
	}
	s.AddAudit(ctx, "catalog", id, "create", item.Name)
	return s.CatalogItem(ctx, id)
}

func (s *Store) UpdateCatalogItem(ctx context.Context, id int64, item CatalogItem) (CatalogItem, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE catalog_items
		 SET kind = ?, name = ?, unit = ?, price_kop = ?, cost_kop = ?, note = ?, archived = ?, updated_at = ?
		 WHERE id = ?`,
		item.Kind, item.Name, unitOrDefault(item.Unit), item.PriceKop, item.CostKop,
		item.Note, boolToInt(item.Archived), now(), id)
	if err != nil {
		return CatalogItem{}, err
	}
	if err := affected(res); err != nil {
		return CatalogItem{}, err
	}
	s.AddAudit(ctx, "catalog", id, "update", item.Name)
	return s.CatalogItem(ctx, id)
}

// DeleteCatalogItem удаляет позицию, но только пока по ней нет движений.
// Если движения есть, позицию убирают из списка отметкой «архивная»:
// удалять её вместе с историей склада нельзя.
func (s *Store) DeleteCatalogItem(ctx context.Context, id int64) error {
	var moves int
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM stock_moves WHERE item_id = ?`, id).Scan(&moves); err != nil {
		return err
	}
	if moves > 0 {
		return ErrStockHistory
	}

	item, err := s.CatalogItem(ctx, id)
	if err != nil {
		return err
	}
	res, err := s.db.ExecContext(ctx, `DELETE FROM catalog_items WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "catalog", id, "delete", item.Name)
	return nil
}

const stockSelect = `
	SELECT m.id, m.item_id, m.order_id, m.qty_milli, m.cost_kop, m.note, m.created_at,
	       i.name, i.unit, COALESCE(o.title, '')
	FROM stock_moves m
	JOIN catalog_items i ON i.id = m.item_id
	LEFT JOIN orders o ON o.id = m.order_id`

// StockMoves отдаёт историю по материалу, свежее сверху. Нулевой itemID —
// движения по всем материалам сразу: так видно последние приходы и списания.
func (s *Store) StockMoves(ctx context.Context, itemID int64, limit int) ([]StockMove, error) {
	query := stockSelect
	var args []any
	if itemID > 0 {
		query += " WHERE m.item_id = ?"
		args = append(args, itemID)
	}
	query += " ORDER BY m.id DESC LIMIT ?"
	args = append(args, limitOrDefault(limit, 200))

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	moves := make([]StockMove, 0, 32)
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
		moves = append(moves, m)
	}
	return moves, rows.Err()
}

// AddStockMove записывает приход или списание. Уходить в минус не даём:
// отрицательный остаток означает, что чего-то не записали, и лучше узнать
// об этом сразу, чем считать по нему себестоимость.
func (s *Store) AddStockMove(ctx context.Context, itemID int64, move StockMove) (StockMove, error) {
	item, err := s.CatalogItem(ctx, itemID)
	if err != nil {
		return StockMove{}, err
	}
	if item.Kind != KindMaterial {
		return StockMove{}, fmt.Errorf("%w: склад есть только у материалов", ErrNotFound)
	}
	if item.StockMilli+move.QtyMilli < 0 {
		return StockMove{}, ErrNegativeStock
	}

	res, err := s.db.ExecContext(ctx,
		`INSERT INTO stock_moves (item_id, order_id, qty_milli, cost_kop, note, created_at)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		itemID, move.OrderID, move.QtyMilli, move.CostKop, move.Note, now())
	if err != nil {
		return StockMove{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return StockMove{}, err
	}
	s.AddAudit(ctx, "stock", id, "create", stockText(item, move.QtyMilli))

	moves, err := s.StockMoves(ctx, itemID, 1)
	if err != nil || len(moves) == 0 {
		return StockMove{}, err
	}
	return moves[0], nil
}

// DeleteStockMove убирает ошибочную запись. Проверяем, что без неё остаток
// не станет отрицательным: удалённый приход мог уже быть списан.
func (s *Store) DeleteStockMove(ctx context.Context, id int64) error {
	var itemID, qty int64
	err := s.db.QueryRowContext(ctx,
		`SELECT item_id, qty_milli FROM stock_moves WHERE id = ?`, id).Scan(&itemID, &qty)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}

	item, err := s.CatalogItem(ctx, itemID)
	if err != nil {
		return err
	}
	if item.StockMilli-qty < 0 {
		return ErrNegativeStock
	}

	res, err := s.db.ExecContext(ctx, `DELETE FROM stock_moves WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if err := affected(res); err != nil {
		return err
	}
	s.AddAudit(ctx, "stock", id, "delete", stockText(item, -qty))
	return nil
}

// ErrNegativeStock — остаток ушёл бы в минус.
var ErrNegativeStock = errors.New("остаток стал бы отрицательным")

func scanCatalogItem(rows *sql.Rows) (CatalogItem, error) {
	var item CatalogItem
	var archived int
	if err := rows.Scan(&item.ID, &item.Kind, &item.Name, &item.Unit, &item.PriceKop,
		&item.CostKop, &item.Note, &archived, &item.CreatedAt, &item.UpdatedAt,
		&item.StockMilli); err != nil {
		return CatalogItem{}, err
	}
	item.Archived = archived != 0
	// Остаток есть только у материала: у услуги движений не бывает,
	// и ноль там значил бы «нечем работать», а не «ничего не осталось».
	if item.Kind != KindMaterial {
		item.StockMilli = 0
	}
	return item, nil
}

// unitOrDefault: единицу оставляем свободной строкой — фиксированный список
// всё равно перерастут, — но пустую заменяем на самую частую.
func unitOrDefault(unit string) string {
	if strings.TrimSpace(unit) == "" {
		return "шт."
	}
	return strings.TrimSpace(unit)
}

func boolToInt(v bool) int {
	if v {
		return 1
	}
	return 0
}

func limitOrDefault(limit, fallback int) int {
	if limit <= 0 || limit > 1000 {
		return fallback
	}
	return limit
}

// stockText — строка для журнала действий: «+5 шт. Кабель ВВГ».
func stockText(item CatalogItem, qtyMilli int64) string {
	sign := "+"
	if qtyMilli < 0 {
		sign = "-"
		qtyMilli = -qtyMilli
	}
	return fmt.Sprintf("%s%s %s %s", sign, QuantityText(qtyMilli), item.Unit, item.Name)
}

// QuantityText печатает тысячные доли так, как человек их пишет: 5, 2.5,
// 0.125 — без хвостовых нулей, которые в списке остатков только мешают.
func QuantityText(milli int64) string {
	sign := ""
	if milli < 0 {
		// Знак ставим сами: у -0.5 целая часть равна нулю,
		// и минус потерялся бы вместе с ней.
		sign, milli = "-", -milli
	}
	whole, frac := milli/1000, milli%1000
	if frac == 0 {
		return fmt.Sprintf("%s%d", sign, whole)
	}
	return sign + strings.TrimRight(fmt.Sprintf("%d.%03d", whole, frac), "0")
}
