// Заказы: список, карточка, состав, оплаты, фотографии и документы.
import {
  api, A, qs, h, mount, money, date, qty, table, card, badge, btn, form, formDialog, modal, confirmDialog,
  toast, go, loading, pickFile, todayISO, parseQty,
  loadCatalog, clientOptions, workerOptions, geoLine, hasGeo,
  ORDER_STATUS, ORDER_STATUS_CLS, DOC_KIND, DOC_STATUS, DOC_STATUS_CLS, METHOD, ITEM_KIND,
} from '../core.js';

const statusOptions = Object.entries(ORDER_STATUS);

// Новый заказ — из списка заказов, карточки клиента или заявки.
export async function newOrderDialog(preset = {}) {
  const [clients, workers] = await Promise.all([clientOptions(), workerOptions()]);
  formDialog({
    title: 'Новый заказ',
    fields: [
      { key: 'title', label: 'Что сделать', required: true, wide: true },
      { key: 'clientId', label: 'Клиент', type: 'select', options: clients, numeric: true },
      { key: 'workerId', label: 'Исполнитель', type: 'select', options: workers, numeric: true },
      { key: 'priceKop', label: 'Стоимость, ₽', type: 'money', hint: 'Если будет состав — посчитается по нему' },
      { key: 'dueDate', label: 'Срок', type: 'date' },
      { key: 'address', label: 'Адрес выезда', wide: true, hint: 'Пусто — по адресу из карточки клиента' },
      { key: 'description', label: 'Описание', type: 'textarea' },
    ],
    values: { status: 'new', ...preset },
    saveLabel: 'Создать',
    onSave: async (v) => {
      const o = await api.post(`${A}/orders`, { ...v, status: 'new' });
      toast(`Заказ № ${o.id} создан`);
      go(`/orders/${o.id}`);
    },
  });
}

export async function render(el, params, ctx) {
  if (params[0]) return renderOrder(el, Number(params[0]), ctx);

  let status = '';
  let query = '';
  let rows = [];
  const box = h('div');
  const statusSel = h('select', { class: 'input', onchange: () => { status = statusSel.value; load(); } },
    [['', 'Все статусы'], ...statusOptions].map(([v, l]) => h('option', { value: v }, l)));
  const search = h('input', { class: 'input search', type: 'search', placeholder: 'Поиск по названию или клиенту' });
  search.addEventListener('input', () => { query = search.value.trim().toLowerCase(); draw(); });

  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Заказы'), btn('+ Заказ', () => newOrderDialog(), 'primary')),
    h('div', { class: 'toolbar' }, statusSel, search), box);

  async function load() {
    mount(box, loading());
    rows = (await api.get(`${A}/orders${qs({ status, limit: 500 })}`)).items;
    draw();
  }
  function draw() {
    const list = query ? rows.filter((o) => `${o.title} ${o.clientName} ${o.id}`.toLowerCase().includes(query)) : rows;
    const sum = (k) => list.reduce((s, o) => s + (o[k] || 0), 0);
    mount(box, card(null, table([
      { label: '№', value: (o) => o.id, cls: 'num' },
      { label: 'Заказ', value: (o) => [o.title, o.workerName ? h('div', { class: 'muted' }, o.workerName) : null] },
      { label: 'Клиент', value: (o) => [o.clientName, o.address || hasGeo(o) ? h('div', { class: 'muted' }, hasGeo(o) ? '📍 ' : '', o.address) : null] },
      { label: 'Статус', value: (o) => badge(ORDER_STATUS[o.status] || o.status, ORDER_STATUS_CLS[o.status]) },
      { label: 'Сумма', value: (o) => money(o.priceKop), cls: 'num' },
      { label: 'Оплачено', value: (o) => paidBadge(o), cls: 'num' },
      { label: 'Срок', value: (o) => date(o.dueDate), cls: 'nowrap' },
      { label: 'Изменён', value: (o) => date(o.updatedAt), cls: 'nowrap' },
    ], list, {
      onRow: (o) => go(`/orders/${o.id}`),
      empty: 'Заказов нет',
      foot: [{ text: `Итого: ${list.length}`, span: 4 }, { text: money(sum('priceKop')), cls: 'num' },
        { text: money(sum('paidKop')), cls: 'num' }, { text: '', span: 2 }],
    })));
  }
  await load();
}

function paidBadge(o) {
  if (!o.priceKop) return money(o.paidKop);
  if (o.paidKop >= o.priceKop) return badge(money(o.paidKop), 'success');
  if (o.paidKop > 0) return badge(money(o.paidKop), 'warn');
  return money(0);
}

async function renderOrder(el, id, ctx) {
  const [o, items, payments, photos, docs, clients, workers] = await Promise.all([
    api.get(`${A}/orders/${id}`),
    api.get(`${A}/orders/${id}/items`),
    api.get(`${A}/orders/${id}/payments`),
    api.get(`${A}/orders/${id}/photos`),
    api.get(`${A}/documents${qs({ orderId: id, limit: 100 })}`),
    clientOptions(),
    workerOptions(),
  ]);
  ctx.setTitle(`Заказ № ${o.id}`);
  const reload = () => { el.replaceChildren(); renderOrder(el, id, ctx); };
  const hasItems = items.items.length > 0;

  const f = form([
    { key: 'title', label: 'Что сделать', required: true, wide: true },
    { key: 'clientId', label: 'Клиент', type: 'select', options: clients, numeric: true },
    { key: 'workerId', label: 'Исполнитель', type: 'select', options: workers, numeric: true },
    { key: 'status', label: 'Статус', type: 'select', options: statusOptions },
    { key: 'dueDate', label: 'Срок', type: 'date' },
    hasItems
      ? { key: 'priceKop', label: 'Стоимость — итог по составу', type: 'money', readonly: true }
      : { key: 'priceKop', label: 'Стоимость, ₽', type: 'money' },
    { key: 'address', label: 'Адрес выезда', wide: true, hint: 'Пусто — по адресу из карточки клиента' },
    { key: 'description', label: 'Описание', type: 'textarea', rows: 4 },
  ], o);
  const save = btn('Сохранить', async () => {
    const v = f.values();
    await api.put(`${A}/orders/${id}`, { ...v, clientId: v.clientId || null, workerId: v.workerId || null });
    toast('Заказ сохранён');
    reload();
  }, 'primary');

  const debt = o.priceKop - o.paidKop;
  el.append(
    h('a', { class: 'back', href: '#/orders' }, '← Заказы'),
    h('div', { class: 'page-head' },
      h('h1', null, `№ ${o.id} · ${o.title}`),
      badge(ORDER_STATUS[o.status] || o.status, ORDER_STATUS_CLS[o.status]),
      btn('Удалить', async () => {
        if (!await confirmDialog({ title: 'Удалить заказ?', text: 'Заказ удалится вместе с составом и фотографиями. Оплаты останутся в кассе без заказа.' })) return;
        await api.del(`${A}/orders/${id}`);
        toast('Заказ удалён');
        go('/orders');
      }, 'danger')),
    h('div', { class: 'split' },
      h('div', null,
        card('Заказ', h('div', null, f.el,
          geoLine(o, async () => {
            if (!await confirmDialog({ title: 'Убрать точку?', text: 'Маршрут будет строиться по адресу текстом.', ok: 'Убрать' })) return;
            const v = f.values();
            await api.put(`${A}/orders/${id}`, { ...v, clientId: v.clientId || null, workerId: v.workerId || null, lat: 0, lon: 0 });
            toast('Точка убрана');
            reload();
          }),
          h('div', { class: 'btn-row', style: 'margin-top:14px' }, save,
          o.clientId ? h('a', { class: 'btn', href: `#/clients/${o.clientId}` }, 'Карточка клиента') : null))),
        itemsCard(o, items.items, reload),
        photosCard(o, photos.items, reload)),
      h('div', null,
        card('Деньги', h('dl', { class: 'props' },
          h('dt', null, 'Стоимость'), h('dd', null, money(o.priceKop)),
          h('dt', null, 'Оплачено'), h('dd', null, money(o.paidKop)),
          h('dt', null, debt > 0 ? 'Долг' : 'Остаток'), h('dd', { style: debt > 0 ? 'color:var(--danger);font-weight:600' : null }, money(Math.max(debt, 0))),
          o.costKop ? [h('dt', null, 'Себестоимость'), h('dd', null, money(o.costKop))] : null,
          o.costKop ? [h('dt', null, 'Прибыль'), h('dd', null, money(o.priceKop - o.costKop))] : null)),
        paymentsCard(o, payments.items, reload),
        documentsCard(o, docs.items))));
}

// ------------------------------ Состав --------------------------------

function itemsCard(o, items, reload) {
  const total = items.reduce((s, i) => s + i.totalKop, 0);
  const unwritten = items.some((i) => i.kind === 'material' && !i.stockMoveId);
  return card(`Состав (${items.length})`, table([
    { label: 'Наименование', value: (i) => [i.name, h('div', { class: 'muted' }, ITEM_KIND[i.kind] || i.kind,
      i.kind === 'material' ? (i.stockMoveId ? ' · списан' : ' · не списан') : '')] },
    { label: 'Кол-во', value: (i) => `${qty(i.qtyMilli)} ${i.unit}`, cls: 'num' },
    { label: 'Цена', value: (i) => money(i.priceKop), cls: 'num' },
    { label: 'Сумма', value: (i) => money(i.totalKop), cls: 'num' },
    { label: '', cls: 'actions', value: (i) => h('div', { class: 'btn-row' },
      btn('Изменить', () => itemDialog(o, i, reload), 'sm'),
      btn('✕', async () => {
        if (!await confirmDialog({ title: 'Убрать строку?', text: `«${i.name}» уйдёт из состава заказа.` })) return;
        await api.del(`${A}/order-items/${i.id}`);
        reload();
      }, 'sm danger')) },
  ], items, {
    empty: 'Состава нет — стоимость задана вручную',
    foot: items.length ? [{ text: 'Итого', span: 3 }, { text: money(total), cls: 'num' }, { text: '' }] : null,
  }), h('div', { class: 'btn-row' },
    unwritten ? btn('Списать материалы со склада', async () => {
      const res = await api.post(`${A}/orders/${o.id}/writeoff`);
      toast(`Списано строк: ${res.written}`);
      reload();
    }) : null,
    btn('+ Из справочника', () => catalogPick(o, reload)),
    btn('+ Строка', () => itemDialog(o, null, reload), 'primary')));
}

function itemDialog(o, item, reload) {
  formDialog({
    title: item ? 'Строка состава' : 'Новая строка',
    fields: [
      { key: 'name', label: 'Наименование', required: true, wide: true },
      { key: 'kind', label: 'Вид', type: 'select', options: Object.entries(ITEM_KIND) },
      { key: 'unit', label: 'Единица', placeholder: 'шт.' },
      { key: 'qtyMilli', label: 'Количество', type: 'qty' },
      { key: 'priceKop', label: 'Цена, ₽', type: 'money' },
      { key: 'costKop', label: 'Закупка за единицу, ₽', type: 'money', hint: 'Для расчёта прибыли' },
    ],
    values: item || { kind: 'service', unit: 'шт.', qtyMilli: 1000 },
    onSave: async (v) => {
      const body = { ...v, unit: v.unit || 'шт.', catalogId: item?.catalogId ?? null };
      if (item) await api.put(`${A}/order-items/${item.id}`, body);
      else await api.post(`${A}/orders/${o.id}/items`, body);
      reload();
    },
  });
}

async function catalogPick(o, reload) {
  const all = (await loadCatalog()).filter((c) => !c.archived);
  const qtyIn = h('input', { class: 'input num', value: '1', style: 'width:90px' });
  const search = h('input', { class: 'input', type: 'search', placeholder: 'Поиск' });
  const list = h('div');
  const draw = () => {
    const q = search.value.trim().toLowerCase();
    mount(list, table([
      { label: 'Позиция', value: (c) => [c.name, h('div', { class: 'muted' }, ITEM_KIND[c.kind])] },
      { label: 'Цена', value: (c) => `${money(c.priceKop)} / ${c.unit}`, cls: 'num' },
      { label: 'Остаток', value: (c) => (c.kind === 'material' ? `${qty(c.stockMilli)} ${c.unit}` : ''), cls: 'num' },
      { label: '', cls: 'actions', value: (c) => btn('Добавить', async () => {
        const q2 = parseQty(qtyIn.value);
        if (!q2 || Number.isNaN(q2)) throw new Error('Укажите количество');
        await api.post(`${A}/orders/${o.id}/items`, { catalogId: c.id, qtyMilli: q2 });
        toast(`Добавлено: ${c.name}`);
        changed = true;
      }, 'sm primary') },
    ], all.filter((c) => !q || c.name.toLowerCase().includes(q)), { empty: 'В справочнике ничего нет' }));
  };
  let changed = false;
  search.addEventListener('input', draw);
  draw();
  modal({
    title: 'Добавить из справочника', wide: true,
    body: h('div', null, h('div', { class: 'toolbar' }, search, h('span', { class: 'muted' }, 'Количество:'), qtyIn), list),
    actions: [{ label: 'Готово', kind: 'primary' }],
    onClose: () => { if (changed) reload(); },
  });
}

// ------------------------------ Оплаты --------------------------------

function paymentsCard(o, payments, reload) {
  return card(`Оплаты (${payments.length})`, table([
    { label: 'Дата', value: (p) => date(p.createdAt), cls: 'nowrap' },
    { label: 'Сумма', value: (p) => money(p.amountKop), cls: 'num' },
    { label: 'Куда', value: (p) => METHOD[p.method || 'cash'] },
    { label: '', cls: 'actions', value: (p) => btn('✕', async () => {
      if (!await confirmDialog({ title: 'Удалить оплату?', text: `${money(p.amountKop)} уйдёт из кассы и из оплат заказа.` })) return;
      await api.del(`${A}/payments/${p.id}`);
      reload();
    }, 'sm danger') },
  ], payments, { empty: 'Оплат нет' }), btn('+ Оплата', () => formDialog({
    title: 'Оплата по заказу',
    fields: [
      { key: 'amountKop', label: 'Сумма, ₽', type: 'money', required: true },
      { key: 'method', label: 'Куда пришли деньги', type: 'select', options: Object.entries(METHOD) },
      { key: 'note', label: 'Комментарий', wide: true },
    ],
    values: { amountKop: Math.max(o.priceKop - o.paidKop, 0), method: 'cash' },
    onSave: async (v) => {
      if (v.amountKop <= 0) throw new Error('Сумма должна быть больше нуля');
      await api.post(`${A}/orders/${o.id}/payments`, v);
      toast('Оплата записана');
      reload();
    },
  }), 'primary'));
}

// ------------------------------ Фото ----------------------------------

function photosCard(o, photos, reload) {
  const grid = h('div', { class: 'photos' }, photos.map((p) => h('div', { class: 'photo' },
    h('img', { src: p.thumbUrl || p.url, alt: p.caption || 'фото', loading: 'lazy', onclick: () => viewPhoto(p) }),
    h('div', { class: 'photo__bar' }, h('span', { title: p.caption }, p.caption || '—'),
      btn('✎', () => formDialog({
        title: 'Подпись к фото', fields: [{ key: 'caption', label: 'Подпись', wide: true }], values: p,
        onSave: async (v) => { await api.patch(`${A}/photos/${p.id}`, v); reload(); },
      }), 'sm ghost'),
      btn('✕', async () => {
        if (!await confirmDialog({ title: 'Удалить фото?', text: 'Фотография удалится с сервера.' })) return;
        await api.del(`${A}/photos/${p.id}`);
        reload();
      }, 'sm ghost')))));
  const upload = btn('+ Загрузить фото', async (b) => {
    const files = await pickFile({ accept: 'image/*', multiple: true });
    if (!files.length) return;
    let n = 0;
    for (const file of files) {
      const fd = new FormData();
      fd.append('photo', file);
      b.textContent = `Загрузка ${++n} из ${files.length}…`;
      await api.upload(`${A}/orders/${o.id}/photos`, fd);
    }
    toast(`Загружено фото: ${files.length}`);
    reload();
  }, 'primary');
  return card(`Фотографии (${photos.length})`, photos.length ? grid : h('div', { class: 'empty' }, 'Фотографий нет'), upload);
}

function viewPhoto(p) {
  modal({
    title: p.caption || 'Фото', wide: true,
    body: h('img', { src: p.url, alt: p.caption || '', style: 'width:100%;height:auto;border-radius:6px' }),
    left: h('a', { class: 'btn', href: p.url, download: `photo-${p.id}` }, 'Скачать'),
    actions: [{ label: 'Закрыть' }],
  });
}

// ------------------------------ Документы -----------------------------

function documentsCard(o, docs) {
  const kinds = ['invoice', 'act', 'waybill', 'upd', 'estimate'];
  // Открываем проведённый документ вида, иначе черновик, иначе заводим.
  const openKind = async (kind) => {
    const same = docs.filter((d) => d.kind === kind);
    const pick = same.find((d) => d.status === 'issued') || same.find((d) => d.status === 'draft');
    if (pick) return go(`/documents/${pick.id}`);
    const card2 = await api.post(`${A}/documents`, { kind, orderId: o.id, docDate: todayISO() });
    toast(`${DOC_KIND[kind]}: черновик создан`);
    go(`/documents/${card2.id}`);
  };
  return card('Документы', h('div', null,
    h('div', { class: 'btn-row', style: 'margin-bottom:10px' }, kinds.map((k) => btn(DOC_KIND[k], () => openKind(k), 'sm'))),
    table([
      { label: 'Документ', value: (d) => `${DOC_KIND[d.kind]}${d.number ? ' № ' + d.number : ''}` },
      { label: 'Сумма', value: (d) => (d.totalKop ? money(d.totalKop) : ''), cls: 'num' },
      { label: '', value: (d) => badge(DOC_STATUS[d.status], DOC_STATUS_CLS[d.status]) },
    ], docs, { onRow: (d) => go(`/documents/${d.id}`), empty: 'Документов по заказу нет' })));
}

