// Отчёты: выбор, параметры, таблицы и выгрузка в Excel.
import { api, A, qs, h, mount, card, btn, loading, download, todayISO, clientOptions } from '../core.js';

export async function render(el) {
  const list = (await api.get(`${A}/reports`)).items;
  const month = todayISO().slice(0, 7);
  const params = { from: `${month}-01`, to: todayISO(), clientId: '' };
  let current = list[0];
  const clients = await clientOptions(false);

  const sel = h('select', { class: 'input', onchange: () => { current = list.find((r) => r.id === sel.value); drawParams(); run(); } },
    list.map((r) => h('option', { value: r.id }, r.title)));
  const paramBox = h('div', { class: 'toolbar', style: 'margin:0' });
  const desc = h('p', { class: 'muted', style: 'margin:0 0 12px' });
  const out = h('div');

  function drawParams() {
    desc.textContent = current?.description || '';
    const parts = [];
    for (const p of current?.params || []) {
      if (p.kind === 'period') {
        parts.push(h('span', { class: 'muted' }, p.label),
          h('input', { class: 'input', type: 'date', value: params.from, onchange: (e) => { params.from = e.target.value; run(); } }), '—',
          h('input', { class: 'input', type: 'date', value: params.to, onchange: (e) => { params.to = e.target.value; run(); } }));
      }
      if (p.kind === 'client') {
        const s = h('select', { class: 'input', onchange: () => { params.clientId = s.value; run(); } },
          [['', '— выберите клиента —'], ...clients].map(([v, l]) => h('option', { value: v, selected: String(v) === String(params.clientId) }, l)));
        parts.push(h('span', { class: 'muted' }, p.label), s);
      }
    }
    mount(paramBox, parts);
  }

  const query = () => {
    const q = {};
    for (const p of current?.params || []) {
      if (p.kind === 'period') Object.assign(q, { from: params.from, to: params.to });
      if (p.kind === 'client') q.clientId = params.clientId;
    }
    return qs(q);
  };

  async function run() {
    if (!current) return;
    if ((current.params || []).some((p) => p.kind === 'client') && !params.clientId) {
      mount(out, h('div', { class: 'empty' }, 'Выберите клиента'));
      return;
    }
    mount(out, loading());
    try {
      const res = await api.get(`${A}/reports/${current.id}/run${query()}`);
      mount(out,
        res.subtitle ? h('p', { class: 'muted' }, res.subtitle) : null,
        (res.sections || []).map((s) => card(s.title, section(s))));
    } catch (e) {
      mount(out, h('div', { class: 'alert error' }, e.message));
    }
  }

  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Отчёты'),
      btn('Скачать в Excel', () => download(`${A}/reports/${current.id}/export${query()}`, `${current.id}.xlsx`))),
    h('div', { class: 'toolbar' }, sel, paramBox), desc, out);
  drawParams();
  await run();
}

function section(s) {
  if (!s.rows?.length) return h('div', { class: 'empty' }, s.note || 'Нет данных');
  const right = (c) => (c.kind && c.kind !== 'text' ? 'num' : null);
  return h('div', null,
    h('div', { class: 'table-wrap' }, h('table', { class: 'table' },
      h('thead', null, h('tr', null, s.columns.map((c) => h('th', { class: right(c) }, c.label)))),
      h('tbody', null, s.rows.map((row) => h('tr', null, row.map((cell, i) => h('td', { class: right(s.columns[i] || {}) }, cell.text))))),
      s.total?.length ? h('tfoot', null, h('tr', null, s.total.map((cell, i) => h('td', { class: right(s.columns[i] || {}) }, cell.text)))) : null)),
    s.note ? h('p', { class: 'muted', style: 'margin:8px 12px' }, s.note) : null);
}
