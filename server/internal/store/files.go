package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// maxFolderDepth ограничивает подъём по родителям. Внешние ключи петлю
// создать не мешают (папку можно перенести в собственную подпапку), а вечный
// цикл в построении пути положил бы обработчик.
const maxFolderDepth = 64

var ErrBadName = errors.New("недопустимое имя")

// ---------- Папки ----------

// Folders возвращает подпапки указанной папки; parent = nil — корень.
// Счётчики считаются подзапросами: папок в архиве немного, а лишний обход
// в цикле по каждой строке дал бы N+1 запрос.
func (s *Store) Folders(ctx context.Context, parent *int64) ([]Folder, error) {
	rows, err := s.db.QueryContext(ctx, folderSelect+` WHERE f.parent_id IS ? ORDER BY f.name COLLATE NOCASE`, parent)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Folder, 0, 8)
	for rows.Next() {
		f, err := scanFolder(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

const folderSelect = `SELECT f.id, f.parent_id, f.name, f.created_at, f.updated_at,
       (SELECT COUNT(*) FROM folders c WHERE c.parent_id = f.id),
       (SELECT COUNT(*) FROM stored_files sf WHERE sf.folder_id = f.id)
 FROM folders f`

func (s *Store) Folder(ctx context.Context, id int64) (Folder, error) {
	rows, err := s.db.QueryContext(ctx, folderSelect+` WHERE f.id = ?`, id)
	if err != nil {
		return Folder{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return Folder{}, ErrNotFound
	}
	return scanFolder(rows)
}

// FolderPath — цепочка от корня до папки, для «хлебных крошек».
func (s *Store) FolderPath(ctx context.Context, id int64) ([]Folder, error) {
	chain := make([]Folder, 0, 8)
	for current := id; current != 0 && len(chain) < maxFolderDepth; {
		f, err := s.Folder(ctx, current)
		if err != nil {
			return nil, err
		}
		chain = append(chain, f)
		if f.ParentID == nil {
			break
		}
		current = *f.ParentID
	}
	// Собирали снизу вверх — разворачиваем, чтобы корень оказался первым.
	for i, j := 0, len(chain)-1; i < j; i, j = i+1, j-1 {
		chain[i], chain[j] = chain[j], chain[i]
	}
	return chain, nil
}

func (s *Store) CreateFolder(ctx context.Context, parent *int64, name string) (Folder, error) {
	clean, err := cleanName(name)
	if err != nil {
		return Folder{}, err
	}
	if parent != nil {
		if _, err := s.Folder(ctx, *parent); err != nil {
			return Folder{}, err
		}
	}
	ts := now()
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO folders (parent_id, name, created_at, updated_at) VALUES (?, ?, ?, ?)`,
		parent, clean, ts, ts)
	if err != nil {
		return Folder{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Folder{}, err
	}
	s.AddAudit(ctx, "folder", id, "create", clean)
	return s.Folder(ctx, id)
}

func (s *Store) RenameFolder(ctx context.Context, id int64, name string) (Folder, error) {
	clean, err := cleanName(name)
	if err != nil {
		return Folder{}, err
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE folders SET name = ?, updated_at = ? WHERE id = ?`, clean, now(), id)
	if err != nil {
		return Folder{}, err
	}
	if err := affected(res); err != nil {
		return Folder{}, err
	}
	s.AddAudit(ctx, "folder", id, "update", clean)
	return s.Folder(ctx, id)
}

// DeleteFolder стирает папку со всем содержимым и возвращает файлы поддерева,
// чтобы вызывающий убрал их с диска: каскад в БД до файлов на диске не достаёт.
func (s *Store) DeleteFolder(ctx context.Context, id int64) ([]StoredFile, error) {
	folder, err := s.Folder(ctx, id)
	if err != nil {
		return nil, err
	}
	files, err := s.filesInSubtree(ctx, id)
	if err != nil {
		return nil, err
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM folders WHERE id = ?`, id); err != nil {
		return nil, err
	}
	s.AddAudit(ctx, "folder", id, "delete", folderAuditText(folder, len(files)))
	return files, nil
}

// filesInSubtree обходит дерево вширь: рекурсивный запрос читался бы хуже,
// а глубина архива невелика.
func (s *Store) filesInSubtree(ctx context.Context, root int64) ([]StoredFile, error) {
	out := make([]StoredFile, 0, 16)
	queue := []int64{root}
	seen := map[int64]bool{root: true}

	for len(queue) > 0 {
		id := queue[0]
		queue = queue[1:]

		files, err := s.FilesIn(ctx, &id)
		if err != nil {
			return nil, err
		}
		out = append(out, files...)

		children, err := s.Folders(ctx, &id)
		if err != nil {
			return nil, err
		}
		for _, c := range children {
			if !seen[c.ID] {
				seen[c.ID] = true
				queue = append(queue, c.ID)
			}
		}
	}
	return out, nil
}

// ---------- Файлы ----------

const fileSelect = `SELECT id, folder_id, token, name, path, mime, size, created_at, updated_at FROM stored_files`

// FilesIn возвращает файлы папки; folder = nil — файлы корня.
func (s *Store) FilesIn(ctx context.Context, folder *int64) ([]StoredFile, error) {
	rows, err := s.db.QueryContext(ctx, fileSelect+` WHERE folder_id IS ? ORDER BY name COLLATE NOCASE`, folder)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]StoredFile, 0, 16)
	for rows.Next() {
		f, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

func (s *Store) StoredFile(ctx context.Context, id int64) (StoredFile, error) {
	rows, err := s.db.QueryContext(ctx, fileSelect+` WHERE id = ?`, id)
	if err != nil {
		return StoredFile{}, err
	}
	defer rows.Close()
	if !rows.Next() {
		return StoredFile{}, ErrNotFound
	}
	return scanFile(rows)
}

// AddFile записывает описание файла; содержимое к этому моменту уже на диске.
func (s *Store) AddFile(ctx context.Context, f StoredFile) (StoredFile, error) {
	clean, err := cleanName(f.Name)
	if err != nil {
		return StoredFile{}, err
	}
	if f.FolderID != nil {
		if _, err := s.Folder(ctx, *f.FolderID); err != nil {
			return StoredFile{}, err
		}
	}
	ts := now()
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO stored_files (folder_id, token, name, path, mime, size, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		f.FolderID, f.Token, clean, f.Path, f.Mime, f.Size, ts, ts)
	if err != nil {
		return StoredFile{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return StoredFile{}, err
	}
	s.AddAudit(ctx, "file", id, "create", clean)
	return s.StoredFile(ctx, id)
}

func (s *Store) RenameFile(ctx context.Context, id int64, name string) (StoredFile, error) {
	clean, err := cleanName(name)
	if err != nil {
		return StoredFile{}, err
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE stored_files SET name = ?, updated_at = ? WHERE id = ?`, clean, now(), id)
	if err != nil {
		return StoredFile{}, err
	}
	if err := affected(res); err != nil {
		return StoredFile{}, err
	}
	s.AddAudit(ctx, "file", id, "update", clean)
	return s.StoredFile(ctx, id)
}

// DeleteFile удаляет запись и возвращает её — вызывающий стирает файл с диска.
func (s *Store) DeleteFile(ctx context.Context, id int64) (StoredFile, error) {
	f, err := s.StoredFile(ctx, id)
	if err != nil {
		return StoredFile{}, err
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM stored_files WHERE id = ?`, id); err != nil {
		return StoredFile{}, err
	}
	s.AddAudit(ctx, "file", id, "delete", f.Name)
	return f, nil
}

// ---------- Общее ----------

func scanFolder(rows *sql.Rows) (Folder, error) {
	var f Folder
	var parent sql.NullInt64
	if err := rows.Scan(&f.ID, &parent, &f.Name, &f.CreatedAt, &f.UpdatedAt,
		&f.FolderCount, &f.FileCount); err != nil {
		return Folder{}, err
	}
	if parent.Valid {
		id := parent.Int64
		f.ParentID = &id
	}
	return f, nil
}

func scanFile(rows *sql.Rows) (StoredFile, error) {
	var f StoredFile
	var folder sql.NullInt64
	if err := rows.Scan(&f.ID, &folder, &f.Token, &f.Name, &f.Path, &f.Mime,
		&f.Size, &f.CreatedAt, &f.UpdatedAt); err != nil {
		return StoredFile{}, err
	}
	if folder.Valid {
		id := folder.Int64
		f.FolderID = &id
	}
	return f, nil
}

// cleanName приводит имя папки или файла к пригодному виду. Разделители пути
// запрещены: имя показывается как есть, но однажды его захотят подставить
// в путь при выгрузке архива, и «../» там будет некстати.
func cleanName(name string) (string, error) {
	clean := strings.TrimSpace(name)
	if clean == "" || clean == "." || clean == ".." {
		return "", ErrBadName
	}
	if strings.ContainsAny(clean, `/\`) || strings.ContainsRune(clean, 0) {
		return "", ErrBadName
	}
	if len([]rune(clean)) > 120 {
		return "", ErrBadName
	}
	return clean, nil
}

// folderAuditText поясняет в журнале, что именно унесло удаление папки:
// одна строка «удалена папка» без счёта выглядит безобидно, а вместе
// с ней могли уйти десятки файлов.
func folderAuditText(f Folder, files int) string {
	if files == 0 {
		return f.Name
	}
	return fmt.Sprintf("%s (файлов внутри: %d)", f.Name, files)
}
