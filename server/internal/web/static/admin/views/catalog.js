// Справочник услуг, работ и материалов; склад материалов.
import {
  api, A, qs, h, mount, money, qty, dateTime, table, card, badge, btn, formDialog, confirmDialog, toast, loading,
  invalidate, ITEM_KIND, METHOD,
} from '../core.js';

export async function render(el) {
  let kind = '';
  let archived = false;
  const box = h('div');
  const tabs = h('div', { class: 'tabs' });
  const drawTabs = () => mount(tabs, [['', 'Все'], ['service', 'Услуги'], ['work', 'Работы'], ['material', 'Материалы'], ['moves', 'Движения склада']]
    .map(([v, l]) => h('button', { type: 'button', class: v === kind ? 'active' : null, onclick: () => { kind = v; drawTabs(); load(); } }, l)));
  drawTabs();
  const arch = h('label', { class: 'check' }, h('input', { type: 'checkbox', onchange: (e) => { archived = e.target.checked; load(); } }), 'Показывать архивные');
  el.append(h('div', { class: 'page-head' }, h('h1', null, 'Справочник и склад'), arch,
    btn('+ Позиция', () => itemDialog(null, load), 'primary')), tabs, box);

  async function load() {
    mount(box, loading());
    if (kind === 'moves') return drawMoves(box, load);
    const res = await api.get(`${A}/catalog${qs({ kind, archived: archived ? 1 : '' })}`);
    mount(box, card(null, table([
      { label: 'Наименование', value: (c) => [c.name, c.archived ? [' ', badge('архив')] : null, c.note ? h('div', { class: 'muted' }, c.note) : null] },
      { label: 'Вид', value: (c) => ITEM_KIND[c.kind] || c.kind },
      { label: 'Цена', value: (c) => `${money(c.priceKop)} / ${c.unit}`, cls: 'num' },
      { label: 'Закупка', value: (c) => (c.costKop ? money(c.costKop) : ''), cls: 'num' },
      { label: 'Остаток', value: (c) => (c.kind === 'material' ? h('span', { style: c.stockMilli <= 0 ? 'color:var(--danger)' : null }, `${qty(c.stockMilli)} ${c.unit}`) : ''), cls: 'num' },
      { label: '', cls: 'actions', value: (c) => h('div', { class: 'btn-row' },
        c.kind === 'material' ? btn('Приход / списание', () => moveDialog(c, load), 'sm') : null,
        btn('Изменить', () => itemDialog(c, load), 'sm'),
        btn('✕', async () => {
          if (!await confirmDialog({ title: 'Удалить позицию?', text: `«${c.name}». Если по ней были движения склада — позиция уйдёт в архив.` })) return;
          await api.del(`${A}/catalog/${c.id}`);
          invalidate('catalog');
          load();
        }, 'sm danger')) },
    ], res.items, { empty: 'Справочник пуст' })));
  }
  await load();
}

function itemDialog(c, onSaved) {
  formDialog({
    title: c ? 'Позиция справочника' : 'Новая позиция',
    fields: [
      { key: 'name', label: 'Наименование', required: true, wide: true },
      { key: 'kind', label: 'Вид', type: 'select', options: Object.entries(ITEM_KIND) },
      { key: 'unit', label: 'Единица', placeholder: 'шт.' },
      { key: 'priceKop', label: 'Цена для клиента, ₽', type: 'money' },
      { key: 'costKop', label: 'Закупка, ₽', type: 'money' },
      { key: 'note', label: 'Заметка', wide: true },
      { key: 'archived', label: 'В архиве — не предлагать в заказах', type: 'check', wide: true },
    ],
    values: c || { kind: 'service', unit: 'шт.' },
    onSave: async (v) => {
      const body = { ...v, unit: v.unit || 'шт.' };
      if (c) await api.put(`${A}/catalog/${c.id}`, body);
      else await api.post(`${A}/catalog`, body);
      invalidate('catalog');
      toast('Сохранено');
      onSaved();
    },
  });
}

function moveDialog(c, onSaved) {
  formDialog({
    title: `Склад: ${c.name}`,
    fields: [
      { key: 'dir', label: 'Движение', type: 'select', options: [['in', 'Приход'], ['out', 'Списание']] },
      { key: 'qtyMilli', label: `Количество, ${c.unit}`, type: 'qty', required: true },
      { key: 'costKop', label: 'Сумма закупки, ₽', type: 'money', hint: 'Для прихода' },
      { key: 'payMethod', label: 'Оплата закупки из кассы', type: 'select',
        options: [['', 'Не записывать расход'], ...Object.entries(METHOD)], hint: 'Появится расход «Материалы»' },
      { key: 'note', label: 'Комментарий', wide: true },
    ],
    values: { dir: 'in', costKop: 0 },
    onSave: async (v) => {
      const q = v.dir === 'out' ? -Math.abs(v.qtyMilli) : Math.abs(v.qtyMilli);
      await api.post(`${A}/catalog/${c.id}/stock`, {
        qtyMilli: q, costKop: v.dir === 'in' ? v.costKop : 0, payMethod: v.dir === 'in' ? v.payMethod : '', note: v.note,
      });
      invalidate('catalog');
      toast('Записано');
      onSaved();
    },
  });
}

async function drawMoves(box, reload) {
  const res = await api.get(`${A}/stock?limit=500`);
  mount(box, card(null, table([
    { label: 'Когда', value: (m) => dateTime(m.createdAt), cls: 'nowrap' },
    { label: 'Материал', value: (m) => m.itemName },
    { label: 'Количество', value: (m) => h('span', { style: `color:var(--${m.qtyMilli > 0 ? 'success' : 'danger'})` },
      `${m.qtyMilli > 0 ? '+' : ''}${qty(m.qtyMilli)} ${m.unit || ''}`), cls: 'num' },
    { label: 'Сумма', value: (m) => (m.costKop ? money(m.costKop) : ''), cls: 'num' },
    { label: 'Заказ', value: (m) => (m.orderId ? h('a', { href: `#/orders/${m.orderId}` }, m.orderTitle || `№ ${m.orderId}`) : '') },
    { label: 'Комментарий', value: (m) => m.note },
    { label: '', cls: 'actions', value: (m) => btn('✕', async () => {
      if (!await confirmDialog({ title: 'Удалить движение?', text: 'Остаток пересчитается. Связанный расход кассы тоже удалится.' })) return;
      await api.del(`${A}/stock/${m.id}`);
      invalidate('catalog');
      reload();
    }, 'sm danger') },
  ], res.items, { empty: 'Движений нет' })));
}
