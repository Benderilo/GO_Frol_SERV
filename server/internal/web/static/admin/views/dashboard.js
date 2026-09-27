// Сводка: деньги, заказы, заявки и задачи на сегодня.
import { api, A, h, money, date, table, card, link, badge, REQUEST_STATUS, REQUEST_STATUS_CLS, go } from '../core.js';

const stat = (label, value, hint) => h('div', { class: 'card stat' },
  h('div', { class: 'stat__label' }, label), h('div', { class: 'stat__value' }, value),
  hint ? h('div', { class: 'stat__hint' }, hint) : null);

export async function render(el) {
  const [st, accounts, reqs, tasks] = await Promise.all([
    api.get(`${A}/stats`),
    api.get(`${A}/accounts`),
    api.get(`${A}/requests?status=new&limit=10`),
    api.get(`${A}/tasks?filter=today`),
  ]);

  el.append(
    h('div', { class: 'grid grid-4' },
      stat('Выручка по завершённым', money(st.revenueTotalKop), `в работе на ${money(st.revenueActiveKop)}`),
      stat('Долги клиентов', money(st.debtOutstandingKop), st.debtOverdueKop ? `просрочено ${money(st.debtOverdueKop)}` : 'просрочки нет'),
      stat('Деньги на счетах', money(accounts.balanceKop),
        accounts.items.map((a) => `${({ cash: 'нал.', card: 'карта', account: 'р/с' })[a.method] || a.method} ${money(a.balanceKop)}`).join(' · ')),
      stat('Заказы', `${st.ordersActive} в работе`, `всего ${st.orders}, завершено ${st.ordersDone}, клиентов ${st.clients}`)),
    h('div', { class: 'grid grid-2', style: 'margin-top:16px' },
      card(`Новые заявки (${st.requestsNew})`, table([
        { label: 'Когда', value: (r) => date(r.createdAt), cls: 'nowrap' },
        { label: 'Имя', value: (r) => r.name },
        { label: 'Телефон', value: (r) => r.phone, cls: 'nowrap' },
        { label: 'Откуда', value: (r) => (r.source === 'max' ? 'MAX' : 'сайт') },
        { label: '', value: (r) => badge(REQUEST_STATUS[r.status] || r.status, REQUEST_STATUS_CLS[r.status]) },
      ], reqs.items, { onRow: () => go('/requests'), empty: 'Новых заявок нет' }), link('/requests', 'Все заявки')),
      card('Задачи на сегодня и просроченные', table([
        { label: 'Срок', value: (t) => date(t.dueDate), cls: 'nowrap' },
        { label: 'Задача', value: (t) => t.title },
        { label: 'Клиент', value: (t) => t.clientName },
        { label: '', value: (t) => (t.priority === 'high' ? badge('срочно', 'danger') : null) },
      ], tasks.items, { onRow: () => go('/tasks'), empty: 'На сегодня задач нет' }), link('/tasks', 'Все задачи'))));
}
