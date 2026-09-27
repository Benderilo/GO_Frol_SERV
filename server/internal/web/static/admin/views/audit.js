// Журнал действий: кто что менял — для разбора «куда делось».
import { api, A, h, dateTime, table, card } from '../core.js';

const ENTITY = {
  client: 'Клиент', order: 'Заказ', request: 'Заявка', document: 'Документ', payment: 'Оплата', cash: 'Касса',
  catalog: 'Справочник', stock: 'Склад', task: 'Задача', worker: 'Рабочий', file: 'Файл', folder: 'Папка', photo: 'Фото',
};
const ACTION = {
  create: 'создан', update: 'изменён', delete: 'удалён', status: 'статус', issue: 'проведён', annul: 'аннулирован',
  send_max: 'отправлен в MAX', max_reply: 'ответ клиента', order: 'заказ по заявке', merge: 'объединение',
};
const LINK = { client: 'clients', order: 'orders', document: 'documents', worker: 'workers' };

export async function render(el) {
  const res = await api.get(`${A}/audit?limit=500`);
  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Журнал действий'), h('span', { class: 'muted' }, 'последние 500 записей')),
    card(null, table([
      { label: 'Когда', value: (e) => dateTime(e.createdAt), cls: 'nowrap' },
      { label: 'Что', value: (e) => (LINK[e.entityType]
        ? h('a', { href: `#/${LINK[e.entityType]}/${e.entityId}` }, `${ENTITY[e.entityType] || e.entityType} № ${e.entityId}`)
        : `${ENTITY[e.entityType] || e.entityType} № ${e.entityId}`) },
      { label: 'Действие', value: (e) => ACTION[e.action] || e.action },
      { label: 'Подробности', value: (e) => e.detail },
    ], res.items, { empty: 'Журнал пуст' })));
}
