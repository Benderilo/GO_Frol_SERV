package store

import (
	"context"
	"database/sql"
	"errors"
)

const siteWorkColumns = `id, token, path, thumb_path, mime, size, width, height, caption, sort, created_at`

// AddSiteWork записывает снимок работы. Файл к этому моменту уже на диске;
// новый снимок встаёт в конец карусели.
func (s *Store) AddSiteWork(ctx context.Context, w SiteWork) (SiteWork, error) {
	var maxSort sql.NullInt64
	if err := s.db.QueryRowContext(ctx, `SELECT MAX(sort) FROM site_works`).Scan(&maxSort); err != nil {
		return SiteWork{}, err
	}
	w.Sort = int(maxSort.Int64) + 1

	res, err := s.db.ExecContext(ctx,
		`INSERT INTO site_works (token, path, thumb_path, mime, size, width, height, caption, sort, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		w.Token, w.Path, w.ThumbPath, w.Mime, w.Size, w.Width, w.Height, w.Caption, w.Sort, now())
	if err != nil {
		return SiteWork{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return SiteWork{}, err
	}
	return s.SiteWork(ctx, id)
}

func (s *Store) SiteWork(ctx context.Context, id int64) (SiteWork, error) {
	return scanSiteWork(s.db.QueryRowContext(ctx,
		`SELECT `+siteWorkColumns+` FROM site_works WHERE id = ?`, id))
}

// SiteWorkByToken нужен для отдачи файла: в адресе картинки стоит токен, не id.
func (s *Store) SiteWorkByToken(ctx context.Context, token string) (SiteWork, error) {
	return scanSiteWork(s.db.QueryRowContext(ctx,
		`SELECT `+siteWorkColumns+` FROM site_works WHERE token = ?`, token))
}

func (s *Store) SiteWorks(ctx context.Context) ([]SiteWork, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+siteWorkColumns+` FROM site_works ORDER BY sort, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]SiteWork, 0, 16)
	for rows.Next() {
		w, err := scanSiteWork(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, w)
	}
	return out, rows.Err()
}

func (s *Store) UpdateSiteWorkCaption(ctx context.Context, id int64, caption string) (SiteWork, error) {
	res, err := s.db.ExecContext(ctx, `UPDATE site_works SET caption = ? WHERE id = ?`, caption, id)
	if err != nil {
		return SiteWork{}, err
	}
	if err := affected(res); err != nil {
		return SiteWork{}, err
	}
	return s.SiteWork(ctx, id)
}

// MoveSiteWork сдвигает снимок на одну позицию влево (delta < 0) или вправо.
// Меняется местами с соседом; на краю карусели ничего не делает.
func (s *Store) MoveSiteWork(ctx context.Context, id int64, delta int) ([]SiteWork, error) {
	works, err := s.SiteWorks(ctx)
	if err != nil {
		return nil, err
	}
	from := -1
	for i, w := range works {
		if w.ID == id {
			from = i
			break
		}
	}
	if from < 0 {
		return nil, ErrNotFound
	}
	to := from + delta
	if to < 0 || to >= len(works) || delta == 0 {
		return works, nil
	}
	works[from], works[to] = works[to], works[from]

	// Перенумеровываем подряд: после удалений в sort бывают дыры и повторы.
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	for i, w := range works {
		if _, err := tx.ExecContext(ctx, `UPDATE site_works SET sort = ? WHERE id = ?`, i+1, w.ID); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return s.SiteWorks(ctx)
}

// DeleteSiteWork удаляет запись и возвращает её, чтобы вызывающий стёр файлы.
func (s *Store) DeleteSiteWork(ctx context.Context, id int64) (SiteWork, error) {
	w, err := s.SiteWork(ctx, id)
	if err != nil {
		return SiteWork{}, err
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM site_works WHERE id = ?`, id); err != nil {
		return SiteWork{}, err
	}
	return w, nil
}

func scanSiteWork(row interface{ Scan(...any) error }) (SiteWork, error) {
	var w SiteWork
	err := row.Scan(&w.ID, &w.Token, &w.Path, &w.ThumbPath, &w.Mime, &w.Size,
		&w.Width, &w.Height, &w.Caption, &w.Sort, &w.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return SiteWork{}, ErrNotFound
	}
	return w, err
}
