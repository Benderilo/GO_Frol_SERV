// Заявки с сайта и из MAX.
import {
  api, A, qs, h, mount, dateTime, table, card, badge, btn, modal, confirmDialog, toast, go,
  REQUEST_STATUS, REQUEST_STATUS_CLS,
} from '../core.js';

export async function render(el, _params, ctx) {
  let status = 'new';
  const box = h('div');
  const filter = h('select', { class: 'input', onchange: () => { status = filter.value; load(); } },
    [['', 'Все'], ...Object.entries(REQUEST_STATUS)].map(([v, l]) => h('option', { value: v, selected: v === status }, l)));
  el.append(h('div', { class: 'page-head' }, h('h1', null, 'Заявки')),
    h('div', { class: 'toolbar' }, filter), box);

  async function load() {
    const res = await api.get(`${A}/requests${qs({ status, limit: 500 })}`);
    mount(box, card(null, table([
      { label: '№', value: (r) => r.id, cls: 'num' },
      { label: 'Поступила', value: (r) => dateTime(r.createdAt), cls: 'nowrap' },
      { label: 'Имя', value: (r) => r.name },
      { label: 'Телефон', value: (r) => r.phone, cls: 'nowrap' },
      { label: 'Обращение', value: (r) => [r.message, r.address ? h('div', { class: 'muted' }, 'Адрес: ' + r.address) : null] },
      { label: 'Откуда', value: (r) => (r.source === 'max' ? 'MAX' : 'сайт') },
      { label: 'Статус', value: (r) => badge(REQUEST_STATUS[r.status] || r.status, REQUEST_STATUS_CLS[r.status]) },
    ], res.items, { onRow: open, empty: 'Заявок нет' })));
  }

  function open(r) {
    const setStatus = (s) => btn(REQUEST_STATUS[s], async () => {
      await api.patch(`${A}/requests/${r.id}`, { status: s });
      toast('Статус изменён');
      close(); load(); ctx.refreshCounts();
    }, r.status === s ? 'primary' : '');
    const close = modal({
      title: `Заявка № ${r.id}`,
      body: h('div', null,
        h('dl', { class: 'props' },
          h('dt', null, 'Поступила'), h('dd', null, dateTime(r.createdAt), ' · ', r.source === 'max' ? 'из MAX' : 'с сайта'),
          h('dt', null, 'Имя'), h('dd', null, r.name || '—'),
          h('dt', null, 'Телефон'), h('dd', null, r.phone ? h('a', { href: 'tel:' + r.phone }, r.phone) : '—'),
          r.address ? [h('dt', null, 'Адрес'), h('dd', null, r.address)] : null,
          h('dt', null, 'Обращение'), h('dd', { style: 'white-space:pre-wrap' }, r.message || '—'),
          h('dt', null, 'Заказ'), h('dd', null, r.orderId ? h('a', { href: `#/orders/${r.orderId}`, onclick: () => close() }, `№ ${r.orderId}`) : 'нет')),
        h('h3', { style: 'margin:16px 0 8px' }, 'Статус'),
        h('div', { class: 'btn-row' }, Object.keys(REQUEST_STATUS).map(setStatus))),
      left: btn('Удалить', async () => {
        if (!await confirmDialog({ title: 'Удалить заявку?', text: 'Заявка исчезнет из списка. Созданный по ней заказ останется.' })) return;
        await api.del(`${A}/requests/${r.id}`);
        toast('Заявка удалена');
        close(); load(); ctx.refreshCounts();
      }, 'danger'),
      actions: [
        { label: 'Закрыть' },
        { label: r.orderId ? 'К заказу, взять в работу' : 'Создать заказ', kind: 'primary', onClick: async (c) => {
          const res = await api.post(`${A}/requests/${r.id}/order`);
          toast(res.created ? `Создан заказ № ${res.order.id}` : `Заявка в работе, заказ № ${res.order.id}`);
          c(); ctx.refreshCounts(); go(`/orders/${res.order.id}`);
        } },
      ],
    });
  }

  await load();
}
