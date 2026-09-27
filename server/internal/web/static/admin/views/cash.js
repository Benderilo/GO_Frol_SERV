// Касса: приходы, расходы, переводы между счетами, остатки.
import {
  api, A, qs, h, mount, money, dateTime, table, card, badge, btn, formDialog, confirmDialog, toast, loading,
  METHOD, todayISO,
} from '../core.js';

export async function render(el) {
  const month = todayISO().slice(0, 7);
  const filter = { from: `${month}-01`, to: todayISO(), direction: '' };
  const accBox = h('div', { class: 'grid grid-4', style: 'margin-bottom:16px' });
  const box = h('div');
  const dateIn = (key) => h('input', { class: 'input', type: 'date', value: filter[key], onchange: (e) => { filter[key] = e.target.value; load(); } });
  const dir = h('select', { class: 'input', onchange: () => { filter.direction = dir.value; load(); } },
    [['', 'Приход и расход'], ['in', 'Только приход'], ['out', 'Только расход']].map(([v, l]) => h('option', { value: v }, l)));

  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Касса'),
      btn('Перевод между счетами', () => transferDialog(load)),
      btn('+ Расход', () => opDialog('out', load)),
      btn('+ Приход', () => opDialog('in', load), 'primary')),
    accBox,
    h('div', { class: 'toolbar' }, h('span', { class: 'muted' }, 'Период'), dateIn('from'), '—', dateIn('to'), dir),
    box);

  async function load() {
    mount(box, loading());
    // Дату «по» сервер сам включает целиком, до конца суток.
    const [res, acc] = await Promise.all([
      api.get(`${A}/cash${qs({ ...filter, limit: 1000 })}`),
      api.get(`${A}/accounts`),
    ]);
    mount(accBox,
      ...acc.items.map((a) => h('div', { class: 'card stat' }, h('div', { class: 'stat__label' }, METHOD[a.method] || a.method),
        h('div', { class: 'stat__value' }, money(a.balanceKop)))),
      h('div', { class: 'card stat' }, h('div', { class: 'stat__label' }, 'Всего'), h('div', { class: 'stat__value' }, money(acc.balanceKop))));
    mount(box, card(null, table([
      { label: 'Когда', value: (o) => dateTime(o.happenedAt), cls: 'nowrap' },
      { label: 'Сумма', value: (o) => h('span', { style: `color:var(--${o.direction === 'in' ? 'success' : 'danger'});font-weight:600` },
        (o.direction === 'in' ? '+' : '−') + money(o.amountKop)), cls: 'num' },
      { label: 'Счёт', value: (o) => METHOD[o.method] || o.method },
      { label: 'Статья', value: (o) => [o.category, o.pairId ? [' ', badge('перевод')] : null] },
      { label: 'Заказ / клиент', value: (o) => [o.orderId ? h('a', { href: `#/orders/${o.orderId}` }, o.orderTitle || `№ ${o.orderId}`) : null,
        o.clientName ? h('div', { class: 'muted' }, o.clientName) : null] },
      { label: 'Комментарий', value: (o) => o.note },
      { label: '', cls: 'actions', value: (o) => btn('✕', async () => {
        if (!await confirmDialog({ title: 'Удалить операцию?', text: o.pairId ? 'Это перевод — удалятся обе его половины.' : `${money(o.amountKop)} уйдёт из кассы.` })) return;
        await api.del(`${A}/cash/${o.id}`);
        toast('Операция удалена');
        load();
      }, 'sm danger') },
    ], res.items, {
      empty: 'За период операций нет',
      foot: [{ text: 'Приход', cls: '' }, { text: money(res.incomeKop), cls: 'num' }, { text: 'Расход' },
        { text: money(res.expenseKop), cls: 'num' }, { text: `Сальдо ${money(res.incomeKop - res.expenseKop)}`, span: 3 }],
    })));
  }
  await load();
}

function opDialog(direction, onSaved) {
  formDialog({
    title: direction === 'in' ? 'Приход' : 'Расход',
    fields: [
      { key: 'amountKop', label: 'Сумма, ₽', type: 'money', required: true },
      { key: 'method', label: 'Счёт', type: 'select', options: Object.entries(METHOD) },
      { key: 'category', label: 'Статья', placeholder: direction === 'in' ? 'Оплата, аванс…' : 'Материалы, бензин, аренда…' },
      { key: 'happenedAt', label: 'Когда', type: 'date' },
      { key: 'note', label: 'Комментарий', wide: true },
    ],
    values: { method: 'cash', happenedAt: todayISO() },
    onSave: async (v) => {
      if (v.amountKop <= 0) throw new Error('Сумма должна быть больше нуля');
      // Сегодня — текущий момент (пусто), другой день — его середина.
      const happenedAt = !v.happenedAt || v.happenedAt === todayISO() ? '' : `${v.happenedAt}T12:00:00Z`;
      await api.post(`${A}/cash`, { ...v, direction, happenedAt });
      toast('Записано');
      onSaved();
    },
  });
}

function transferDialog(onSaved) {
  const methods = Object.entries(METHOD);
  formDialog({
    title: 'Перевод между счетами',
    fields: [
      { key: 'from', label: 'Откуда', type: 'select', options: methods },
      { key: 'to', label: 'Куда', type: 'select', options: methods },
      { key: 'amountKop', label: 'Сумма, ₽', type: 'money', required: true },
      { key: 'note', label: 'Комментарий' },
    ],
    values: { from: 'cash', to: 'account' },
    onSave: async (v) => {
      if (v.from === v.to) throw new Error('Счета должны различаться');
      await api.post(`${A}/cash/transfer`, v);
      toast('Перевод записан');
      onSaved();
    },
  });
}
