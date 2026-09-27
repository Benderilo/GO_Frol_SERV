// Архив файлов: папки, загрузка с компьютера, скачивание, переименование.
import {
  api, A, qs, h, mount, dateTime, table, card, btn, formDialog, confirmDialog, toast, loading, pickFile, download,
} from '../core.js';

const size = (b) => (b > 1048576 ? `${(b / 1048576).toFixed(1)} МБ` : b > 1024 ? `${Math.round(b / 1024)} КБ` : `${b} Б`);

export async function render(el) {
  let folder = null; // null — корень
  const crumbs = h('div', { class: 'toolbar' });
  const box = h('div');
  el.append(h('div', { class: 'page-head' }, h('h1', null, 'Файлы'),
    btn('+ Папка', () => formDialog({
      title: 'Новая папка', fields: [{ key: 'name', label: 'Название', required: true, wide: true }],
      onSave: async (v) => { await api.post(`${A}/folders`, { name: v.name, parentId: folder }); load(); },
    })),
    btn('Загрузить файлы', async (b) => {
      const files = await pickFile({ multiple: true });
      let n = 0;
      for (const f of files) {
        b.textContent = `Загрузка ${++n} из ${files.length}…`;
        const fd = new FormData();
        fd.append('file', f);
        fd.append('name', f.name);
        if (folder) fd.append('folderId', folder);
        await api.upload(`${A}/files`, fd);
      }
      b.textContent = 'Загрузить файлы';
      if (files.length) { toast(`Загружено: ${files.length}`); load(); }
    }, 'primary')), crumbs, box);

  async function load() {
    mount(box, loading());
    const res = await api.get(`${A}/files${qs({ folder: folder ?? '' })}`);
    mount(crumbs,
      h('a', { href: '#', onclick: (e) => { e.preventDefault(); folder = null; load(); } }, 'Все файлы'),
      (res.path || []).map((p) => [' / ', h('a', { href: '#', onclick: (e) => { e.preventDefault(); folder = p.id; load(); } }, p.name)]));
    const rows = [
      ...(res.folders || []).map((f) => ({ folder: f })),
      ...(res.files || []).map((f) => ({ file: f })),
    ];
    mount(box, card(null, table([
      { label: 'Имя', value: (r) => (r.folder ? h('span', null, '📁 ', h('b', null, r.folder.name)) : r.file.name) },
      { label: 'Размер', value: (r) => (r.folder ? `${r.folder.folderCount} папок, ${r.folder.fileCount} файлов` : size(r.file.size)), cls: 'num' },
      { label: 'Изменён', value: (r) => dateTime((r.folder || r.file).updatedAt), cls: 'nowrap' },
      { label: '', cls: 'actions', value: (r) => h('div', { class: 'btn-row' },
        r.file ? btn('Скачать', () => download(`${A}/files/${r.file.id}/raw`, r.file.name), 'sm') : null,
        btn('Переименовать', () => formDialog({
          title: 'Переименовать', fields: [{ key: 'name', label: 'Название', required: true, wide: true }],
          values: { name: (r.folder || r.file).name },
          onSave: async (v) => {
            await api.put(r.folder ? `${A}/folders/${r.folder.id}` : `${A}/files/${r.file.id}`, { name: v.name });
            load();
          },
        }), 'sm'),
        btn('✕', async () => {
          const what = r.folder ? `папку «${r.folder.name}» со всем содержимым` : `файл «${r.file.name}»`;
          if (!await confirmDialog({ title: 'Удалить?', text: `Удалить ${what}?` })) return;
          await api.del(r.folder ? `${A}/folders/${r.folder.id}` : `${A}/files/${r.file.id}`);
          load();
        }, 'sm danger')) },
    ], rows, {
      onRow: (r) => { if (r.folder) { folder = r.folder.id; load(); } },
      empty: 'Папка пуста',
    })));
  }
  await load();
}
