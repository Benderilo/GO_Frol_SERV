package store

import (
	"context"
	"database/sql"
	"errors"
)

// AddPhoto записывает метаданные снимка. Файл к этому моменту уже на диске.
func (s *Store) AddPhoto(ctx context.Context, p OrderPhoto) (OrderPhoto, error) {
	// Новый снимок встаёт в конец списка.
	var maxSort sql.NullInt64
	if err := s.db.QueryRowContext(ctx,
		`SELECT MAX(sort) FROM order_photos WHERE order_id = ?`, p.OrderID).Scan(&maxSort); err != nil {
		return OrderPhoto{}, err
	}
	p.Sort = int(maxSort.Int64) + 1

	res, err := s.db.ExecContext(ctx,
		`INSERT INTO order_photos (order_id, token, path, thumb_path, mime, size, width, height, caption, sort, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		p.OrderID, p.Token, p.Path, p.ThumbPath, p.Mime, p.Size, p.Width, p.Height, p.Caption, p.Sort, now())
	if err != nil {
		return OrderPhoto{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return OrderPhoto{}, err
	}
	return s.Photo(ctx, id)
}

func (s *Store) Photo(ctx context.Context, id int64) (OrderPhoto, error) {
	return s.scanPhoto(s.db.QueryRowContext(ctx,
		`SELECT id, order_id, token, path, thumb_path, mime, size, width, height, caption, sort, created_at
		 FROM order_photos WHERE id = ?`, id))
}

// PhotoByToken нужен для отдачи файла: в адресе картинки стоит токен, не id.
func (s *Store) PhotoByToken(ctx context.Context, token string) (OrderPhoto, error) {
	return s.scanPhoto(s.db.QueryRowContext(ctx,
		`SELECT id, order_id, token, path, thumb_path, mime, size, width, height, caption, sort, created_at
		 FROM order_photos WHERE token = ?`, token))
}

func (s *Store) OrderPhotos(ctx context.Context, orderID int64) ([]OrderPhoto, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, order_id, token, path, thumb_path, mime, size, width, height, caption, sort, created_at
		 FROM order_photos WHERE order_id = ? ORDER BY sort, id`, orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]OrderPhoto, 0, 8)
	for rows.Next() {
		var p OrderPhoto
		if err := rows.Scan(&p.ID, &p.OrderID, &p.Token, &p.Path, &p.ThumbPath, &p.Mime,
			&p.Size, &p.Width, &p.Height, &p.Caption, &p.Sort, &p.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// PhotoCounts возвращает число снимков по каждому заказу из списка —
// одним запросом, чтобы не дёргать базу в цикле.
func (s *Store) PhotoCounts(ctx context.Context, orderIDs []int64) (map[int64]int, error) {
	counts := make(map[int64]int, len(orderIDs))
	if len(orderIDs) == 0 {
		return counts, nil
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT order_id, COUNT(*) FROM order_photos GROUP BY order_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	wanted := make(map[int64]bool, len(orderIDs))
	for _, id := range orderIDs {
		wanted[id] = true
	}
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		if wanted[id] {
			counts[id] = n
		}
	}
	return counts, rows.Err()
}

func (s *Store) UpdatePhotoCaption(ctx context.Context, id int64, caption string) (OrderPhoto, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE order_photos SET caption = ? WHERE id = ?`, caption, id)
	if err != nil {
		return OrderPhoto{}, err
	}
	if err := affected(res); err != nil {
		return OrderPhoto{}, err
	}
	return s.Photo(ctx, id)
}

// DeletePhoto удаляет запись и возвращает её, чтобы вызывающий стёр файлы.
func (s *Store) DeletePhoto(ctx context.Context, id int64) (OrderPhoto, error) {
	photo, err := s.Photo(ctx, id)
	if err != nil {
		return OrderPhoto{}, err
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM order_photos WHERE id = ?`, id); err != nil {
		return OrderPhoto{}, err
	}
	return photo, nil
}

// PhotoFilesForOrder нужен перед удалением заказа: файлы с диска
// каскад в БД не уберёт.
func (s *Store) PhotoFilesForOrder(ctx context.Context, orderID int64) ([]OrderPhoto, error) {
	return s.OrderPhotos(ctx, orderID)
}

func (s *Store) scanPhoto(row *sql.Row) (OrderPhoto, error) {
	var p OrderPhoto
	err := row.Scan(&p.ID, &p.OrderID, &p.Token, &p.Path, &p.ThumbPath, &p.Mime,
		&p.Size, &p.Width, &p.Height, &p.Caption, &p.Sort, &p.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return OrderPhoto{}, ErrNotFound
	}
	return p, err
}
