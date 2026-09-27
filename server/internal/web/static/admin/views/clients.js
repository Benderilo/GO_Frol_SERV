// Клиенты: список, карточка с реквизитами, заказами, документами и балансом.
import {
  api, A, qs, h, mount, money, date, table, card, badge, btn, formDialog, form, modal, confirmDialog,
  toast, go, invalidate, todayISO, loading,
  ORDER_STATUS, ORDER_STATUS_CLS, DOC_KIND, DOC_STATUS, DOC_STATUS_CLS,
} from '../core.js';
import { newOrderDialog } from './orders.js';

export const CLIENT_FIELDS = [
  { key: 'name', label: 'Имя или название', required: true },
  { key: 'phone', label: 'Телефон', type: 'tel' },
  { key: 'email', label: 'Почта', type: 'email' },
  { key: 'tag', label: 'Метка', placeholder: 'например, ТСЖ или постоянный' },
  { key: 'address', label: 'Адрес', wide: true },
  { key: 'note', label: 'Заметка', type: 'textarea' },
  { type: 'section', label: 'Реквизиты — для накладной, УПД и акта сверки' },
  { key: 'inn', label: 'ИНН' },
  { key: 'kpp', label: 'КПП' },
  { key: 'bankName', label: 'Банк' },
  { key: 'bankAccount', label: 'Расчётный счёт' },
];

export function clientDialog(existing, onSaved) {
  return formDialog({
    title: existing ? 'Клиент' : 'Новый клиент',
    fields: CLIENT_FIELDS,
    values: existing || {},
    onSave: async (v) => {
      const saved = existing
        ? await api.put(`${A}/clients/${existing.id}`, { ...existing, ...v })
        : await api.post(`${A}/clients`, v);
      invalidate('clients');
      toast('Клиент сохранён');
      onSaved?.(saved);
    },
  });
}

export async function render(el, params, ctx) {
  if (params[0]) return renderClient(el, Number(params[0]), ctx);

  let query = '';
  const box = h('div');
  const search = h('input', { class: 'input search', type: 'search', placeholder: 'Имя, телефон или почта' });
  let timer;
  search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { query = search.value.trim(); load(); }, 250); });

  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Клиенты'),
      btn('Найти дубли', () => duplicates(load)),
      btn('+ Клиент', () => clientDialog(null, (c) => go(`/clients/${c.id}`)), 'primary')),
    h('div', { class: 'toolbar' }, search), box);

  async function load() {
    mount(box, loading());
    const res = await api.get(`${A}/clients${qs({ q: query, limit: 500 })}`);
    mount(box, card(null, table([
      { label: 'Клиент', value: (c) => [c.name, c.tag ? [' ', badge(c.tag)] : null] },
      { label: 'Телефон', value: (c) => c.phone, cls: 'nowrap' },
      { label: 'Адрес', value: (c) => c.address },
      { label: 'Заказов', value: (c) => c.ordersCount ?? '', cls: 'num' },
      { label: 'Выручка', value: (c) => (c.revenueDoneKop ? money(c.revenueDoneKop) : ''), cls: 'num' },
      { label: 'Кабинет', value: (c) => (c.portalEnabled ? badge('открыт', 'success') : '') },
    ], res.items, { onRow: (c) => go(`/clients/${c.id}`), empty: query ? 'Никого не нашли' : 'Клиентов пока нет' })));
  }
  await load();
}

async function renderClient(el, id, ctx) {
  const [c, orders, docs, balance] = await Promise.all([
    api.get(`${A}/clients/${id}`),
    api.get(`${A}/clients/${id}/orders`),
    api.get(`${A}/documents${qs({ clientId: id, limit: 200 })}`),
    api.get(`${A}/clients/${id}/balance`),
  ]);
  ctx.setTitle(c.name);
  const reload = () => { el.replaceChildren(); renderClient(el, id, ctx); };

  const f = form(CLIENT_FIELDS, c);
  const save = btn('Сохранить', async () => {
    await api.put(`${A}/clients/${id}`, { ...c, ...f.values() });
    invalidate('clients');
    toast('Сохранено');
    reload();
  }, 'primary');

  const debt = balance.balanceKop;
  el.append(
    h('a', { class: 'back', href: '#/clients' }, '← Клиенты'),
    h('div', { class: 'page-head' }, h('h1', null, c.name),
      btn('+ Заказ', () => newOrderDialog({ clientId: id })),
      btn('Смета', () => createDoc('estimate', id)),
      btn('Акт сверки', () => reconciliation(id)),
      btn('Удалить', async () => {
        if (!await confirmDialog({ title: 'Удалить клиента?', text: `«${c.name}» будет удалён. Заказы останутся без клиента.` })) return;
        await api.del(`${A}/clients/${id}`);
        invalidate('clients');
        toast('Клиент удалён');
        go('/clients');
      }, 'danger')),
    h('div', { class: 'split' },
      h('div', null,
        card('Карточка', h('div', null, f.el, h('div', { class: 'btn-row', style: 'margin-top:14px' }, save))),
        card(`Заказы (${orders.count})`, table([
          { label: '№', value: (o) => o.id, cls: 'num' },
          { label: 'Заказ', value: (o) => o.title },
          { label: 'Статус', value: (o) => badge(ORDER_STATUS[o.status] || o.status, ORDER_STATUS_CLS[o.status]) },
          { label: 'Сумма', value: (o) => money(o.priceKop), cls: 'num' },
          { label: 'Оплачено', value: (o) => money(o.paidKop), cls: 'num' },
          { label: 'Создан', value: (o) => date(o.createdAt), cls: 'nowrap' },
        ], orders.items, { onRow: (o) => go(`/orders/${o.id}`), empty: 'Заказов нет' })),
        card(`Документы (${docs.count})`, table([
          { label: 'Документ', value: (d) => `${DOC_KIND[d.kind] || d.kind}${d.number ? ' № ' + d.number : ''}` },
          { label: 'Дата', value: (d) => date(d.docDate), cls: 'nowrap' },
          { label: 'Сумма', value: (d) => (d.totalKop ? money(d.totalKop) : ''), cls: 'num' },
          { label: 'Статус', value: (d) => badge(DOC_STATUS[d.status] || d.status, DOC_STATUS_CLS[d.status]) },
        ], docs.items, { onRow: (d) => go(`/documents/${d.id}`), empty: 'Документов нет' }))),
      h('div', null,
        card('Взаиморасчёты', h('div', null,
          h('div', { class: 'stat__label' }, debt > 0 ? 'Клиент должен' : debt < 0 ? 'Переплата клиента' : 'Долгов нет'),
          h('div', { class: 'stat__value' }, money(Math.abs(debt))),
          h('div', { class: 'stat__hint' }, 'по заказам и оплатам'))),
        card('Личный кабинет на сайте', portalBox(c, reload)))));
}

function portalBox(c, reload) {
  const box = h('div');
  box.append(
    h('p', { class: 'muted', style: 'margin-top:0' }, c.portalEnabled
      ? `Доступ открыт${c.portalLastLogin ? ', последний вход ' + date(c.portalLastLogin) : ''}. Вход — по телефону и коду.`
      : 'Клиент видит в кабинете свои заказы и фотографии. Вход — по телефону и коду.'),
    h('div', { class: 'btn-row' },
      btn(c.portalEnabled ? 'Выдать новый код' : 'Открыть доступ', async () => {
        const res = await api.post(`${A}/clients/${c.id}/access`);
        modal({
          title: 'Код доступа',
          body: h('div', null, h('p', null, 'Передайте клиенту. Код показывается один раз — в базе хранится только его отпечаток.'),
            h('dl', { class: 'props' }, h('dt', null, 'Телефон'), h('dd', null, res.phone),
              h('dt', null, 'Код'), h('dd', { class: 'mono', style: 'font-size:20px' }, res.code))),
          actions: [{ label: 'Готово', kind: 'primary' }],
          onClose: reload,
        });
      }),
      c.portalEnabled ? btn('Закрыть доступ', async () => {
        await api.del(`${A}/clients/${c.id}/access`);
        toast('Доступ закрыт');
        reload();
      }, 'danger') : null));
  return box;
}

async function createDoc(kind, clientId, extra = {}) {
  const card = await api.post(`${A}/documents`, { kind, clientId, docDate: todayISO(), ...extra });
  toast(`${DOC_KIND[kind]}: черновик создан`);
  go(`/documents/${card.id}`);
}

function reconciliation(clientId) {
  const year = todayISO().slice(0, 4);
  formDialog({
    title: 'Акт сверки',
    fields: [
      { key: 'periodFrom', label: 'С', type: 'date', required: true },
      { key: 'periodTo', label: 'По', type: 'date', required: true },
    ],
    values: { periodFrom: `${year}-01-01`, periodTo: todayISO() },
    saveLabel: 'Создать',
    onSave: (v) => createDoc('reconciliation', clientId, v),
  });
}

async function duplicates(onDone) {
  const res = await api.get(`${A}/clients/duplicates`);
  if (!res.items.length) {
    toast('Дублей по телефону нет');
    return;
  }
  const body = h('div', null, h('p', { class: 'muted', style: 'margin-top:0' },
    'Клиенты с одним номером. Слияние переносит заказы, документы и оплаты к выбранной карточке, остальные удаляются.'));
  const close = modal({ title: 'Дубли клиентов', wide: true, body, actions: [{ label: 'Закрыть' }] });
  for (const d of res.items) {
    const radios = d.clientIds.map((cid, i) => h('label', { class: 'check', style: 'margin-right:16px' },
      h('input', { type: 'radio', name: 'keep-' + d.phone, value: cid, checked: i === 0 }), `${d.clientNames[i]} (№ ${cid})`));
    body.append(h('div', { class: 'card', style: 'padding:12px;margin-top:10px' },
      h('div', { style: 'font-weight:600;margin-bottom:8px' }, d.phone),
      h('div', null, radios),
      h('div', { class: 'btn-row', style: 'margin-top:10px' }, btn('Слить в выбранного', async () => {
        const keep = Number(radios.map((r) => r.querySelector('input')).find((i) => i.checked).value);
        await api.post(`${A}/clients/merge`, { keepId: keep, mergeIds: d.clientIds.filter((x) => x !== keep) });
        invalidate('clients');
        toast('Клиенты объединены');
        close(); onDone();
      }, 'primary'))));
  }
}
