// Документы: журнал, печатная форма, печать, PDF, проведение и отправка в MAX.
import {
  api, A, qs, h, mount, money, date, dateTime, qty, table, card, badge, btn, formDialog, confirmDialog,
  toast, go, loading, download, todayISO, clientOptions, parseMoney, parseQty, kopToInput,
  DOC_KIND, DOC_STATUS, DOC_STATUS_CLS,
} from '../core.js';

const ORDER_KINDS = ['invoice', 'act', 'waybill', 'upd'];

export async function render(el, params, ctx) {
  if (params[0]) return renderDoc(el, Number(params[0]), ctx);

  const filter = { kind: '', status: '', q: '', from: '', to: '' };
  const box = h('div');
  const sel = (key, opts) => {
    const s = h('select', { class: 'input', onchange: () => { filter[key] = s.value; load(); } },
      opts.map(([v, l]) => h('option', { value: v }, l)));
    return s;
  };
  const dateIn = (key, title) => {
    const i = h('input', { class: 'input', type: 'date', title, onchange: () => { filter[key] = i.value; load(); } });
    return i;
  };
  const search = h('input', { class: 'input search', type: 'search', placeholder: 'Клиент, название или номер' });
  let t;
  search.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { filter.q = search.value.trim(); load(); }, 250); });

  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Документы'), btn('+ Документ', () => newDocDialog(), 'primary')),
    h('div', { class: 'toolbar' },
      sel('kind', [['', 'Все виды'], ...Object.entries(DOC_KIND)]),
      sel('status', [['', 'Все статусы'], ...Object.entries(DOC_STATUS)]),
      search, h('span', { class: 'muted' }, 'с'), dateIn('from', 'Дата с'), h('span', { class: 'muted' }, 'по'), dateIn('to', 'Дата по')),
    box);

  async function load() {
    mount(box, loading());
    const res = await api.get(`${A}/documents${qs({ ...filter, limit: 500 })}`);
    const total = res.items.filter((d) => d.status === 'issued').reduce((s, d) => s + d.totalKop, 0);
    mount(box, card(null, table([
      { label: 'Документ', value: (d) => `${DOC_KIND[d.kind] || d.kind}${d.number ? ' № ' + d.number : ''}` },
      { label: 'Дата', value: (d) => date(d.docDate), cls: 'nowrap' },
      { label: 'Клиент', value: (d) => d.clientName },
      { label: 'Заказ', value: (d) => d.orderTitle },
      { label: 'Сумма', value: (d) => (d.totalKop ? money(d.totalKop) : ''), cls: 'num' },
      { label: 'Статус', value: (d) => badge(DOC_STATUS[d.status] || d.status, DOC_STATUS_CLS[d.status]) },
    ], res.items, {
      onRow: (d) => go(`/documents/${d.id}`),
      empty: 'Документов нет',
      foot: [{ text: `Документов: ${res.items.length}`, span: 4 }, { text: money(total), cls: 'num' }, { text: 'по проведённым' }],
    })));
  }
  await load();
}

// Новый документ: по заказу (счёт, акт, накладная, УПД), по клиенту
// (смета, акт сверки) или пустая смета.
async function newDocDialog() {
  const [clients, orders] = await Promise.all([
    clientOptions(),
    api.get(`${A}/orders?limit=500`).then((r) => r.items),
  ]);
  const year = todayISO().slice(0, 4);
  formDialog({
    title: 'Новый документ',
    fields: [
      { key: 'kind', label: 'Вид', type: 'select', options: Object.entries(DOC_KIND) },
      { key: 'docDate', label: 'Дата', type: 'date' },
      { key: 'orderId', label: 'Заказ — для счёта, акта, накладной, УПД', type: 'select', numeric: true, wide: true,
        options: [['', '— без заказа —'], ...orders.map((o) => [o.id, `№ ${o.id} · ${o.title}${o.clientName ? ' · ' + o.clientName : ''}`])] },
      { key: 'clientId', label: 'Клиент — для сметы и акта сверки', type: 'select', numeric: true, wide: true, options: clients },
      { key: 'periodFrom', label: 'Сверка с', type: 'date' },
      { key: 'periodTo', label: 'Сверка по', type: 'date' },
    ],
    values: { kind: 'invoice', docDate: todayISO(), periodFrom: `${year}-01-01`, periodTo: todayISO() },
    saveLabel: 'Создать',
    onSave: async (v) => {
      if (ORDER_KINDS.includes(v.kind) && !v.orderId) throw new Error('Для этого документа выберите заказ');
      if (v.kind === 'reconciliation' && !v.clientId) throw new Error('Для акта сверки выберите клиента');
      const body = { kind: v.kind, docDate: v.docDate };
      if (v.orderId) body.orderId = v.orderId;
      if (v.clientId) body.clientId = v.clientId;
      if (v.kind === 'reconciliation') Object.assign(body, { periodFrom: v.periodFrom, periodTo: v.periodTo });
      const c = await api.post(`${A}/documents`, body);
      toast('Черновик создан');
      go(`/documents/${c.id}`);
    },
  });
}

async function renderDoc(el, id, ctx) {
  const d = await api.get(`${A}/documents/${id}`);
  const title = `${DOC_KIND[d.kind] || d.kindTitle}${d.number ? ' № ' + d.number : ''}`;
  ctx.setTitle(title);
  const reload = () => { el.replaceChildren(); renderDoc(el, id, ctx); };

  const frame = h('iframe', { class: 'doc-frame', title: 'Печатная форма' });
  api.get(`${A}/documents/${id}/print`).then((html) => { frame.srcdoc = html; })
    .catch((e) => { frame.srcdoc = ''; toast(e.message, 'error'); });

  const actions = [];
  actions.push(btn('Печать', () => {
    if (!frame.contentWindow) return;
    frame.contentWindow.focus();
    frame.contentWindow.print();
  }, 'primary'));
  actions.push(btn('Скачать PDF', () => download(`${A}/documents/${id}/pdf`, `${title}.pdf`)));
  if (d.status === 'draft') {
    actions.push(btn('Провести', async () => {
      await api.post(`${A}/documents/${id}/issue`);
      toast('Документ проведён: номер закреплён');
      reload();
    }, 'primary'));
    actions.push(btn('Изменить черновик', () => editDraft(d, reload)));
  }
  if (d.status !== 'annulled') {
    actions.push(btn('Отправить в MAX', async () => {
      if (!await confirmDialog({
        title: 'Отправить клиенту в MAX?',
        text: `${title} уйдёт PDF-файлом${d.clientName ? ' клиенту «' + d.clientName + '»' : ''} с кнопками «Согласен», «Не устраивает», «Есть вопрос».`,
        ok: 'Отправить', danger: false,
      })) return;
      const res = await api.post(`${A}/documents/${id}/send-max`);
      toast(`Отправлено в MAX${res.clientName ? ' — ' + res.clientName : ''}`);
      reload();
    }));
  }
  if (d.kind === 'estimate' && !d.orderId && d.status !== 'annulled') {
    actions.push(btn('Смету в заказ', async () => {
      const res = await api.post(`${A}/documents/${id}/order`);
      toast(`Создан заказ № ${res.order.id}`);
      go(`/orders/${res.order.id}`);
    }));
  }
  if (d.status === 'issued') {
    actions.push(btn('Аннулировать', async () => {
      if (!await confirmDialog({
        title: 'Аннулировать документ?',
        text: `Номер ${d.number} больше не используется, на печатной форме появится штамп. Отменить нельзя.`,
        ok: 'Аннулировать',
      })) return;
      await api.post(`${A}/documents/${id}/annul`);
      toast('Документ аннулирован');
      reload();
    }, 'danger'));
  }
  if (d.status === 'draft') {
    actions.push(btn('Удалить', async () => {
      if (!await confirmDialog({ title: 'Удалить черновик?', text: 'Черновик исчезнет из журнала.' })) return;
      await api.del(`${A}/documents/${id}`);
      toast('Черновик удалён');
      go('/documents');
    }, 'danger'));
  }

  const c = d.content || {};
  el.append(
    h('a', { class: 'back', href: '#/documents' }, '← Документы'),
    h('div', { class: 'page-head' }, h('h1', null, title), badge(DOC_STATUS[d.status] || d.status, DOC_STATUS_CLS[d.status])),
    h('div', { class: 'btn-row', style: 'margin-bottom:16px' }, actions),
    h('div', { class: 'split' },
      card(null, frame),
      h('div', null,
        card('Сведения', h('dl', { class: 'props' },
          h('dt', null, 'Дата'), h('dd', null, date(d.docDate)),
          d.periodFrom ? [h('dt', null, 'Период'), h('dd', null, `${date(d.periodFrom)} – ${date(d.periodTo)}`)] : null,
          h('dt', null, 'Сумма'), h('dd', null, money(d.totalKop)),
          h('dt', null, 'Клиент'), h('dd', null, d.clientId ? h('a', { href: `#/clients/${d.clientId}` }, d.clientName || '—') : (d.clientName || '—')),
          h('dt', null, 'Заказ'), h('dd', null, d.orderId ? h('a', { href: `#/orders/${d.orderId}` }, d.orderTitle || `№ ${d.orderId}`) : '—'),
          d.issuedAt ? [h('dt', null, 'Проведён'), h('dd', null, dateTime(d.issuedAt))] : null,
          h('dt', null, 'Строк'), h('dd', null, (c.lines || []).length))),
        card('Клиент в MAX', maxBox(d)),
        d.status === 'draft' && ORDER_KINDS.includes(d.kind)
          ? h('div', { class: 'alert info', style: 'margin-top:16px' },
            'Черновик по заказу собирается из заказа: цены и состав правятся в заказе. При проведении содержимое замораживается.')
          : null)));
}

function maxBox(d) {
  const s = d.maxSend;
  if (!s) return h('div', { class: 'muted' }, 'Не отправлялся');
  const map = {
    accepted: ['Клиент согласен', 'success'],
    declined: ['Клиента не устраивает', 'danger'],
    question: ['У клиента вопрос', 'accent'],
  };
  const [label, cls] = map[s.reply] || ['Ждём ответа', 'warn'];
  return h('div', null,
    h('div', null, badge(label, cls)),
    h('div', { class: 'muted', style: 'margin-top:6px' }, `Отправлен ${dateTime(s.sentAt)}`,
      s.remindedAt ? `, напомнили ${dateTime(s.remindedAt)}` : '',
      s.repliedAt ? `, ответ ${dateTime(s.repliedAt)}` : ''),
    s.note ? h('p', { style: 'white-space:pre-wrap' }, `«${s.note}»`) : null);
}

// Правка черновика: дата, название и срок у всех; период — у сверки;
// строки — у сметы (у документов по заказу строки берутся из заказа).
function editDraft(d, reload) {
  const c = d.content || {};
  const fields = [
    { key: 'docDate', label: 'Дата документа', type: 'date' },
    { key: 'title', label: 'Название', placeholder: DOC_KIND[d.kind] },
  ];
  if (d.kind === 'estimate') fields.push({ key: 'validUntil', label: 'Действует до', type: 'date' });
  if (d.kind === 'reconciliation') {
    fields.push({ key: 'periodFrom', label: 'Сверка с', type: 'date' }, { key: 'periodTo', label: 'Сверка по', type: 'date' });
  }
  const lines = d.kind === 'estimate' ? linesEditor(c.lines || []) : null;
  formDialog({
    title: 'Черновик', wide: !!lines, fields,
    values: { docDate: d.docDate, title: c.title, validUntil: c.validUntil, periodFrom: d.periodFrom, periodTo: d.periodTo },
    extra: lines ? h('div', { style: 'margin-top:16px' }, h('h3', { style: 'margin-bottom:8px' }, 'Строки сметы'), lines.el) : null,
    onSave: async (v) => {
      const body = { ...v };
      for (const k of Object.keys(body)) if (body[k] === '') delete body[k];
      if (lines) body.lines = lines.values();
      await api.patch(`${A}/documents/${d.id}`, body);
      toast('Черновик сохранён');
      reload();
    },
  });
}

function linesEditor(initial) {
  const tbody = h('tbody');
  const addRow = (l = {}) => {
    const name = h('input', { class: 'input', value: l.name || '' });
    const unit = h('input', { class: 'input', value: l.unit || 'шт.', style: 'width:70px' });
    const q = h('input', { class: 'input num', value: l.qtyMilli ? qty(l.qtyMilli) : '1', style: 'width:80px' });
    const price = h('input', { class: 'input num', value: kopToInput(l.priceKop), style: 'width:110px' });
    const tr = h('tr', null, h('td', null, name), h('td', null, q), h('td', null, unit), h('td', null, price),
      h('td', { class: 'actions' }, btn('✕', () => tr.remove(), 'sm ghost')));
    tr._read = () => ({ name: name.value.trim(), unit: unit.value.trim() || 'шт.', qtyMilli: parseQty(q.value), priceKop: parseMoney(price.value) });
    tbody.append(tr);
  };
  initial.forEach(addRow);
  if (!initial.length) addRow();
  return {
    el: h('div', null,
      h('div', { class: 'table-wrap' }, h('table', { class: 'table' },
        h('thead', null, h('tr', null, h('th', null, 'Наименование'), h('th', null, 'Кол-во'), h('th', null, 'Ед.'), h('th', null, 'Цена, ₽'), h('th'))),
        tbody)),
      h('div', { style: 'margin-top:8px' }, btn('+ Строка', () => addRow(), 'sm'))),
    values() {
      return [...tbody.children].map((tr) => tr._read()).filter((l) => l.name).map((l) => {
        if (Number.isNaN(l.qtyMilli) || Number.isNaN(l.priceKop)) throw new Error(`Строка «${l.name}»: проверьте количество и цену`);
        return l;
      });
    },
  };
}

