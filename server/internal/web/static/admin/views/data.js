// Данные: всё в Excel на компьютер, загрузка из Excel, копия базы.
import { api, A, h, mount, card, btn, confirmDialog, toast, pickFile, download, todayISO } from '../core.js';

export async function render(el) {
  const result = h('div');
  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Данные и копии')),
    h('div', { class: 'grid grid-3' },
      card('Выгрузить в Excel', h('div', null,
        h('p', { class: 'muted', style: 'margin-top:0' }, 'Клиенты, заказы с составом, оплаты, касса, склад, заявки, задачи и реквизиты — лист на раздел. Файл открывается в Excel и LibreOffice.'),
        btn('Скачать книгу Excel', () => download(`${A}/export.xlsx`, `frolov-${todayISO()}.xlsx`), 'primary'))),
      card('Загрузить из Excel', h('div', null,
        h('p', { class: 'muted', style: 'margin-top:0' }, 'Книга в том же формате, что выгрузка. Строки с номером обновляют существующие записи, без номера — добавляются. Ничего не удаляется.'),
        btn('Выбрать файл…', async () => {
          const [file] = await pickFile({ accept: '.xlsx' });
          if (!file) return;
          if (!await confirmDialog({
            title: 'Загрузить данные?',
            text: `«${file.name}»: записи с номерами будут перезаписаны данными из файла. Перед загрузкой лучше скачать копию базы.`,
            ok: 'Загрузить', danger: false,
          })) return;
          const fd = new FormData();
          fd.append('file', file);
          const res = await api.upload(`${A}/import`, fd);
          showSummary(result, res);
          toast('Загрузка завершена');
        }))),
      card('Копия базы', h('div', null,
        h('p', { class: 'muted', style: 'margin-top:0' }, 'Полный снимок базы данных SQLite на эту минуту. Сервер и сам делает копии каждую ночь и хранит 14 дней.'),
        btn('Скачать копию базы', () => download(`${A}/backup`, `frolov-${todayISO()}.db`), 'primary')))),
    result);
}

function showSummary(box, s) {
  const names = {
    clients: 'Клиенты', catalog: 'Справочник', orders: 'Заказы', orderItems: 'Состав заказов', stockMoves: 'Движения склада',
    requests: 'Заявки', payments: 'Оплаты', cash: 'Касса', tasks: 'Задачи',
  };
  mount(box, card('Итог загрузки', h('div', null,
    h('table', { class: 'table' },
      h('thead', null, h('tr', null, h('th', null, 'Раздел'), h('th', { class: 'num' }, 'Добавлено'), h('th', { class: 'num' }, 'Обновлено'))),
      h('tbody', null, Object.entries(names).map(([k, l]) => h('tr', null, h('td', null, l),
        h('td', { class: 'num' }, s[k]?.created ?? 0), h('td', { class: 'num' }, s[k]?.updated ?? 0))))),
    s.company ? h('p', null, 'Реквизиты ИП обновлены.') : null,
    s.warnings?.length ? h('div', { class: 'alert warn', style: 'margin-top:12px' },
      h('b', null, 'Замечания:'), h('ul', { style: 'margin:6px 0 0;padding-left:18px' }, s.warnings.map((w) => h('li', null, w)))) : null)));
}
