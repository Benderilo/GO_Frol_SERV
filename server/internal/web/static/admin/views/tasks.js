// Задачи и напоминания.
import {
  api, A, qs, h, mount, date, table, card, badge, btn, formDialog, confirmDialog, toast, loading,
  clientOptions, PRIORITY, todayISO,
} from '../core.js';

const FILTERS = [['open', 'Открытые'], ['today', 'На сегодня'], ['overdue', 'Просроченные'], ['done', 'Выполненные'], ['', 'Все']];

export async function render(el) {
  let filter = 'open';
  const box = h('div');
  const tabs = h('div', { class: 'tabs' });
  const drawTabs = () => mount(tabs, FILTERS.map(([v, l]) => h('button', {
    type: 'button', class: v === filter ? 'active' : null, onclick: () => { filter = v; drawTabs(); load(); },
  }, l)));
  drawTabs();
  el.append(h('div', { class: 'page-head' }, h('h1', null, 'Задачи'), btn('+ Задача', () => taskDialog(null, load), 'primary')), tabs, box);

  async function load() {
    mount(box, loading());
    const res = await api.get(`${A}/tasks${qs({ filter: filter === 'open' ? 'open' : filter })}`);
    const today = todayISO();
    mount(box, card(null, table([
      { label: '', value: (t) => h('input', { type: 'checkbox', checked: t.done, title: 'Выполнена', onchange: async (e) => {
        try {
          await api.patch(`${A}/tasks/${t.id}`, { done: e.target.checked });
          load();
        } catch (ex) { toast(ex.message, 'error'); e.target.checked = !e.target.checked; }
      } }), width: '32px' },
      { label: 'Задача', value: (t) => [h('div', { style: t.done ? 'text-decoration:line-through;color:var(--muted)' : null }, t.title),
        t.note ? h('div', { class: 'muted', style: 'white-space:pre-wrap' }, t.note) : null] },
      { label: 'Клиент', value: (t) => (t.clientId ? h('a', { href: `#/clients/${t.clientId}` }, t.clientName) : '') },
      { label: 'Заказ', value: (t) => (t.orderId ? h('a', { href: `#/orders/${t.orderId}` }, `№ ${t.orderId}`) : '') },
      { label: 'Срок', value: (t) => (t.dueDate ? h('span', { style: !t.done && t.dueDate < today ? 'color:var(--danger);font-weight:600' : null }, date(t.dueDate)) : ''), cls: 'nowrap' },
      { label: 'Важность', value: (t) => (t.priority === 'high' ? badge('высокая', 'danger') : t.priority === 'low' ? badge('низкая') : '') },
      { label: '', cls: 'actions', value: (t) => h('div', { class: 'btn-row' },
        btn('Изменить', () => taskDialog(t, load), 'sm'),
        btn('✕', async () => {
          if (!await confirmDialog({ title: 'Удалить задачу?', text: `«${t.title}»` })) return;
          await api.del(`${A}/tasks/${t.id}`);
          load();
        }, 'sm danger')) },
    ], res.items, { empty: 'Задач нет' })));
  }
  await load();
}

async function taskDialog(t, onSaved) {
  const [clients, orders] = await Promise.all([clientOptions(), api.get(`${A}/orders?limit=500`).then((r) => r.items)]);
  formDialog({
    title: t ? 'Задача' : 'Новая задача',
    fields: [
      { key: 'title', label: 'Что сделать', required: true, wide: true },
      { key: 'dueDate', label: 'Срок', type: 'date' },
      { key: 'priority', label: 'Важность', type: 'select', options: Object.entries(PRIORITY) },
      { key: 'clientId', label: 'Клиент', type: 'select', options: clients, numeric: true },
      { key: 'orderId', label: 'Заказ', type: 'select', numeric: true,
        options: [['', '— без заказа —'], ...orders.map((o) => [o.id, `№ ${o.id} · ${o.title}`])] },
      { key: 'note', label: 'Заметка', type: 'textarea' },
    ],
    values: t || { priority: 'normal' },
    onSave: async (v) => {
      const body = { ...v, clientId: v.clientId || null, orderId: v.orderId || null, parentId: t?.parentId ?? null, done: t?.done ?? false };
      if (t) await api.put(`${A}/tasks/${t.id}`, body);
      else await api.post(`${A}/tasks`, body);
      toast('Задача сохранена');
      onSaved();
    },
  });
}
