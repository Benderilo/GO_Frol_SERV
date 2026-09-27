// Зарплаты: кому сколько аванс и зарплата за месяц, даты выплат и сами выплаты.
import {
  api, A, qs, h, mount, money, date, table, card, badge, btn, formDialog, confirmDialog, toast, loading,
  monthISO, todayISO, METHOD, link,
} from '../core.js';

const KIND = { advance: 'Аванс', salary: 'Зарплата' };

const stat = (label, value, hint) => h('div', { class: 'card stat' },
  h('div', { class: 'stat__label' }, label), h('div', { class: 'stat__value' }, value),
  hint ? h('div', { class: 'stat__hint' }, hint) : null);

// «2026-09» → «сентябрь 2026».
function monthName(m) {
  const d = new Date(Number(m.slice(0, 4)), Number(m.slice(5, 7)) - 1, 1);
  return d.toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' }).replace(' г.', '');
}

export async function render(el) {
  let month = monthISO();
  const box = h('div');
  el.append(box);

  async function load() {
    mount(box, loading());
    const p = await api.get(`${A}/payroll${qs({ month })}`);

    const monthIn = h('input', { class: 'input', type: 'month', value: month, style: 'width:auto',
      onchange: (e) => { month = e.target.value || monthISO(); load(); } });

    // Даты выплат этого месяца: правятся на месте, сброс — к дням по умолчанию.
    const advIn = h('input', { class: 'input', type: 'date', value: p.advanceDate, style: 'width:auto' });
    const salIn = h('input', { class: 'input', type: 'date', value: p.salaryDate, style: 'width:auto' });
    const datesCard = card(`Даты выплат за ${monthName(p.month)}`, h('div', null,
      h('div', { class: 'toolbar', style: 'flex-wrap:wrap;gap:12px' },
        h('label', { class: 'field' }, h('span', null, 'Аванс'), advIn),
        h('label', { class: 'field' }, h('span', null, 'Зарплата'), salIn),
        h('div', { class: 'btn-row', style: 'align-self:flex-end' },
          btn('Сохранить даты', async () => {
            await api.put(`${A}/payroll/dates/${p.month}`, { advanceDate: advIn.value, salaryDate: salIn.value });
            toast('Даты выплат сохранены');
            load();
          }, 'primary'),
          p.datesCustom ? btn('Вернуть по умолчанию', async () => {
            await api.put(`${A}/payroll/dates/${p.month}`, { advanceDate: '', salaryDate: '' });
            toast('Даты вернулись к обычным');
            load();
          }) : null)),
      h('div', { class: 'muted', style: 'margin-top:8px' },
        p.datesCustom ? 'Даты этого месяца перенесены вручную. ' : '',
        `Обычно: аванс ${p.settings.advanceDay}-го числа, зарплата ${p.settings.salaryDay}-го следующего месяца. `,
        h('a', { href: '#', onclick: (e) => { e.preventDefault(); settingsDialog(p.settings); } }, 'Изменить'))));

    const payments = p.rows.flatMap((r) => r.payments);

    mount(box,
      h('div', { class: 'page-head' }, h('h1', null, 'Зарплаты'), monthIn),
      h('div', { class: 'grid grid-4' },
        stat('Начислено', money(p.accruedKop), `${p.rows.length} чел.`),
        stat('Выплачено', money(p.paidKop), `${payments.length} выплат`),
        stat('Аванс к выдаче', money(p.advanceLeftKop), `до ${date(p.advanceDate)}`),
        stat('Зарплата к выдаче', money(p.salaryLeftKop), `до ${date(p.salaryDate)}`)),
      h('div', { style: 'margin-top:16px' }, datesCard),
      card('Кому сколько', table([
        { label: 'Сотрудник', value: (r) => [r.name, r.active ? null : [' ', badge('не работает')],
          r.position ? h('div', { class: 'muted' }, r.position) : null] },
        { label: 'Выходы', value: (r) => (r.salaryType === 'month' ? 'оклад' : `${r.days} (до 15-го: ${r.advanceDays})`), cls: 'nowrap' },
        { label: 'Начислено', value: (r) => money(r.accruedKop), cls: 'num' },
        { label: 'Аванс', value: (r) => part(r.advancePlanKop, r.advancePaidKop, r.advanceLeftKop), cls: 'num' },
        { label: 'Зарплата', value: (r) => part(r.salaryPlanKop, r.salaryPaidKop, r.salaryLeftKop), cls: 'num' },
        { label: 'Осталось', value: (r) => balance(r.balanceKop), cls: 'num' },
        { label: '', value: (r) => h('div', { class: 'btn-row', style: 'justify-content:flex-end' },
          btn('Аванс', () => paymentDialog(p, r, 'advance'), r.advanceLeftKop ? 'primary sm' : 'sm'),
          btn('Зарплата', () => paymentDialog(p, r, 'salary'), r.salaryLeftKop && !r.advanceLeftKop ? 'primary sm' : 'sm')) },
      ], p.rows, {
        empty: 'Сотрудников нет — добавьте их в разделе «Рабочие»',
        foot: [
          { text: 'Итого', span: 2 },
          { text: money(p.accruedKop), cls: 'num' },
          { text: `к выдаче ${money(p.advanceLeftKop)}`, cls: 'num' },
          { text: `к выдаче ${money(p.salaryLeftKop)}`, cls: 'num' },
          { text: money(p.advanceLeftKop + p.salaryLeftKop), cls: 'num' },
          { text: '' },
        ],
      }), link('/workers', 'Табель — в разделе «Рабочие»')),
      card('Выплаты за месяц', table([
        { label: 'Дата', value: (x) => date(x.paidOn), cls: 'nowrap' },
        { label: 'Сотрудник', value: (x) => x.workerName },
        { label: 'Вид', value: (x) => badge(KIND[x.kind] || x.kind, x.kind === 'advance' ? 'warn' : 'success') },
        { label: 'Откуда', value: (x) => METHOD[x.method] || x.method },
        { label: 'Комментарий', value: (x) => x.note },
        { label: 'Сумма', value: (x) => money(x.amountKop), cls: 'num' },
      ], payments.sort((a, b) => a.paidOn.localeCompare(b.paidOn)), {
        onRow: (x) => editPaymentDialog(x),
        empty: 'За этот месяц выплат ещё не было',
      })));
  }

  // Ячейка аванса или зарплаты: сколько положено, сколько выдано, сколько осталось.
  function part(plan, paid, left) {
    return h('div', null,
      h('div', null, money(plan)),
      paid ? h('div', { class: 'muted' }, `выдано ${money(paid)}`) : null,
      left ? h('div', { style: 'color:var(--warn, #b45309);font-weight:600' }, `осталось ${money(left)}`) : null);
  }

  function balance(kop) {
    if (kop < 0) return h('span', { style: 'color:var(--danger, #dc2626)' }, `переплата ${money(-kop)}`);
    if (kop === 0) return h('span', { class: 'muted' }, 'рассчитан');
    return h('b', null, money(kop));
  }

  function paymentFields() {
    return [
      { key: 'kind', label: 'Вид', type: 'select', options: Object.entries(KIND) },
      { key: 'amountKop', label: 'Сумма, ₽', type: 'money', required: true },
      { key: 'paidOn', label: 'Дата выплаты', type: 'date', required: true },
      { key: 'month', label: 'За месяц', type: 'month', required: true },
      { key: 'method', label: 'Откуда', type: 'select', options: Object.entries(METHOD) },
      { key: 'note', label: 'Комментарий', wide: true },
    ];
  }

  function paymentDialog(p, r, kind) {
    const left = kind === 'advance' ? r.advanceLeftKop : r.salaryLeftKop;
    formDialog({
      title: `${KIND[kind]}: ${r.name}`,
      fields: paymentFields(),
      values: { kind, amountKop: left || (kind === 'advance' ? r.advancePlanKop : r.salaryPlanKop),
        paidOn: todayISO(), month: p.month, method: 'cash' },
      saveLabel: 'Выплатить',
      extra: h('div', { class: 'muted', style: 'margin-top:10px' }, 'Выплата запишется расходом в кассе со статьёй «Зарплата».'),
      onSave: async (v) => {
        await api.post(`${A}/payroll/payments`, { ...v, workerId: r.workerId });
        toast(`${KIND[v.kind]} записан`);
        load();
      },
    });
  }

  function editPaymentDialog(x) {
    const close = formDialog({
      title: `${KIND[x.kind] || 'Выплата'}: ${x.workerName}`,
      fields: paymentFields(),
      values: x,
      extra: h('div', { class: 'muted', style: 'margin-top:10px' }, 'Строка в кассе поправится вместе с выплатой.'),
      left: btn('Удалить', async () => {
        if (!await confirmDialog({ title: 'Удалить выплату?', text: `${money(x.amountKop)} для «${x.workerName}» исчезнут и из кассы.` })) return;
        await api.del(`${A}/payroll/payments/${x.id}`);
        close();
        toast('Выплата удалена');
        load();
      }, 'danger'),
      onSave: async (v) => {
        await api.put(`${A}/payroll/payments/${x.id}`, v);
        toast('Выплата изменена');
        load();
      },
    });
  }

  function settingsDialog(s) {
    formDialog({
      title: 'Дни выплат по умолчанию',
      fields: [
        { key: 'advanceDay', label: 'Аванс, число месяца', type: 'number', required: true },
        { key: 'salaryDay', label: 'Зарплата, число следующего месяца', type: 'number', required: true },
      ],
      values: s,
      extra: h('div', { class: 'muted', style: 'margin-top:10px' },
        'Действует для всех месяцев, где даты не перенесены вручную. 31-е в коротком месяце — последний день.'),
      onSave: async (v) => {
        await api.put(`${A}/payroll/settings`, v);
        toast('Дни выплат сохранены');
        load();
      },
    });
  }

  await load();
}
