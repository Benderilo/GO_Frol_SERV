// Реквизиты ИП: подставляются во все документы.
import { api, A, h, card, btn, form, toast } from '../core.js';

export async function render(el) {
  const c = await api.get(`${A}/company`);
  const f = form([
    { type: 'section', label: 'Как называемся в документах' },
    { key: 'shortName', label: 'Краткое название', placeholder: 'ИП Фролов А. В.', required: true },
    { key: 'fullName', label: 'Полное название', placeholder: 'Индивидуальный предприниматель …' },
    { key: 'inn', label: 'ИНН', required: true },
    { key: 'ogrnip', label: 'ОГРНИП' },
    { key: 'kpp', label: 'КПП', hint: 'У ИП обычно пусто' },
    { key: 'vatRate', label: 'НДС', type: 'select', numeric: true,
      options: [[0, 'Без НДС (УСН)'], [5, '5 %'], [20, '20 %']] },
    { type: 'section', label: 'Контакты' },
    { key: 'address', label: 'Адрес', wide: true },
    { key: 'phone', label: 'Телефон', type: 'tel' },
    { key: 'email', label: 'Почта', type: 'email' },
    { key: 'site', label: 'Сайт' },
    { type: 'section', label: 'Банк — для счёта на оплату' },
    { key: 'bankName', label: 'Банк', wide: true },
    { key: 'bankBik', label: 'БИК' },
    { key: 'bankAccount', label: 'Расчётный счёт' },
    { key: 'bankCorrAccount', label: 'Корреспондентский счёт' },
    { type: 'section', label: 'Подпись и приписки' },
    { key: 'signerName', label: 'Кто подписывает', placeholder: 'Фролов А. В.' },
    { key: 'signerTitle', label: 'Должность или основание', placeholder: 'Индивидуальный предприниматель' },
    { key: 'taxNote', label: 'Приписка о налоге', type: 'textarea', placeholder: 'НДС не облагается в связи с применением УСН' },
    { key: 'footerNote', label: 'Приписка внизу документа', type: 'textarea' },
  ], c);
  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Реквизиты ИП')),
    card(null, h('div', null, f.el, h('div', { class: 'btn-row', style: 'margin-top:16px' }, btn('Сохранить', async () => {
      await api.put(`${A}/company`, { ...f.values(), updatedAt: c.updatedAt });
      toast('Реквизиты сохранены — новые документы возьмут их');
    }, 'primary')))));
}
