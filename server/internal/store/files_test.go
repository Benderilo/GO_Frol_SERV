package store

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

// Удаление папки должно вернуть файлы всего поддерева: каскад в БД уберёт
// строки, но файлы на диске остались бы навсегда, если сюда не попадут
// вложенные папки.
func TestDeleteFolderReturnsWholeSubtree(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	root, err := st.CreateFolder(ctx, nil, "Документы")
	if err != nil {
		t.Fatalf("CreateFolder: %v", err)
	}
	inner, err := st.CreateFolder(ctx, &root.ID, "Договоры")
	if err != nil {
		t.Fatalf("CreateFolder вложенной: %v", err)
	}

	for _, f := range []StoredFile{
		{FolderID: &root.ID, Token: "t1", Name: "смета.xlsx", Path: "files/t1.xlsx"},
		{FolderID: &inner.ID, Token: "t2", Name: "договор.pdf", Path: "files/t2.pdf"},
	} {
		if _, err := st.AddFile(ctx, f); err != nil {
			t.Fatalf("AddFile %s: %v", f.Name, err)
		}
	}

	removed, err := st.DeleteFolder(ctx, root.ID)
	if err != nil {
		t.Fatalf("DeleteFolder: %v", err)
	}
	if len(removed) != 2 {
		t.Fatalf("к удалению с диска отдано %d файлов, ожидалось 2", len(removed))
	}
	if _, err := st.Folder(ctx, inner.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("вложенная папка уцелела: %v", err)
	}
}

// Корень — это NULL, а не папка с нулевым id: список корня не должен
// прихватывать содержимое вложенных папок.
func TestRootListingIgnoresNestedItems(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	folder, err := st.CreateFolder(ctx, nil, "Фотографии")
	if err != nil {
		t.Fatalf("CreateFolder: %v", err)
	}
	if _, err := st.AddFile(ctx, StoredFile{Token: "r1", Name: "акт.pdf", Path: "files/r1.pdf"}); err != nil {
		t.Fatalf("AddFile в корне: %v", err)
	}
	if _, err := st.AddFile(ctx, StoredFile{
		FolderID: &folder.ID, Token: "r2", Name: "фасад.jpg", Path: "files/r2.jpg",
	}); err != nil {
		t.Fatalf("AddFile в папке: %v", err)
	}

	files, err := st.FilesIn(ctx, nil)
	if err != nil {
		t.Fatalf("FilesIn(корень): %v", err)
	}
	if len(files) != 1 || files[0].Name != "акт.pdf" {
		t.Fatalf("в корне %d файлов: %+v", len(files), files)
	}

	folders, err := st.Folders(ctx, nil)
	if err != nil {
		t.Fatalf("Folders(корень): %v", err)
	}
	if len(folders) != 1 || folders[0].FileCount != 1 {
		t.Fatalf("счётчик папки неверен: %+v", folders)
	}
}

// Имя уходит в заголовок скачивания и когда-нибудь — в путь при выгрузке
// архива; разделители пути в нём недопустимы.
func TestFolderNameRejectsPathSeparators(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	for _, name := range []string{"", "   ", "..", "счета/2026", `счета\2026`} {
		if _, err := st.CreateFolder(ctx, nil, name); !errors.Is(err, ErrBadName) {
			t.Fatalf("имя %q принято, ошибка: %v", name, err)
		}
	}
}

// Крошки строятся от корня к текущей папке — экран рисует их слева направо.
func TestFolderPathGoesFromRootDown(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	first, err := st.CreateFolder(ctx, nil, "Клиенты")
	if err != nil {
		t.Fatalf("CreateFolder: %v", err)
	}
	second, err := st.CreateFolder(ctx, &first.ID, "Иванов")
	if err != nil {
		t.Fatalf("CreateFolder: %v", err)
	}
	third, err := st.CreateFolder(ctx, &second.ID, "2026")
	if err != nil {
		t.Fatalf("CreateFolder: %v", err)
	}

	path, err := st.FolderPath(ctx, third.ID)
	if err != nil {
		t.Fatalf("FolderPath: %v", err)
	}
	want := []string{"Клиенты", "Иванов", "2026"}
	if len(path) != len(want) {
		t.Fatalf("в пути %d звеньев: %+v", len(path), path)
	}
	for i, name := range want {
		if path[i].Name != name {
			t.Fatalf("звено %d — %q, ожидалось %q", i, path[i].Name, name)
		}
	}
}

// Копия должна открываться как самостоятельная база с теми же данными:
// именно этим она отличается от простого копирования файла с WAL.
func TestBackupToMakesReadableCopy(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "src.db")

	st, err := Open(ctx, dbPath)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if _, err := st.CreateFolder(ctx, nil, "Сметы"); err != nil {
		t.Fatalf("CreateFolder: %v", err)
	}

	out := filepath.Join(dir, "copy.db")
	if err := BackupTo(ctx, dbPath, out); err != nil {
		t.Fatalf("BackupTo: %v", err)
	}
	// Копию снимаем на работающей базе — исходная должна остаться живой.
	if _, err := st.CreateFolder(ctx, nil, "Акты"); err != nil {
		t.Fatalf("запись после копии: %v", err)
	}
	st.Close()

	copied, err := Open(ctx, out)
	if err != nil {
		t.Fatalf("открытие копии: %v", err)
	}
	defer copied.Close()

	folders, err := copied.Folders(ctx, nil)
	if err != nil {
		t.Fatalf("Folders в копии: %v", err)
	}
	// В копии — то, что было на момент снимка, без более поздней записи.
	if len(folders) != 1 || folders[0].Name != "Сметы" {
		t.Fatalf("в копии %d папок: %+v", len(folders), folders)
	}
}

// Все прочие сущности пишут в журнал действий; файловый архив не должен
// быть исключением — иначе «кто удалил папку с документами» не выяснить.
func TestFileOperationsAreJournaled(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	folder, err := st.CreateFolder(ctx, nil, "Договоры")
	if err != nil {
		t.Fatalf("CreateFolder: %v", err)
	}
	if _, err := st.AddFile(ctx, StoredFile{
		FolderID: &folder.ID, Token: "j1", Name: "акт.pdf", Path: "files/j1.pdf",
	}); err != nil {
		t.Fatalf("AddFile: %v", err)
	}
	if _, err := st.DeleteFolder(ctx, folder.ID); err != nil {
		t.Fatalf("DeleteFolder: %v", err)
	}

	entries, err := st.ListAudit(ctx, 100)
	if err != nil {
		t.Fatalf("ListAudit: %v", err)
	}
	seen := map[string]string{}
	for _, e := range entries {
		seen[e.EntityType+"/"+e.Action] = e.Detail
	}
	for _, key := range []string{"folder/create", "file/create", "folder/delete"} {
		if _, ok := seen[key]; !ok {
			t.Fatalf("в журнале нет записи %s: %+v", key, seen)
		}
	}
	// В записи об удалении папки должно быть видно, сколько файлов ушло с ней.
	if !strings.Contains(seen["folder/delete"], "файлов внутри: 1") {
		t.Fatalf("удаление папки записано без счёта файлов: %q", seen["folder/delete"])
	}
}
