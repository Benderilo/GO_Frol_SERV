// Рабочие: карточки, табель по дням и выплаты.
import {
  api, A, qs, h, mount, money, table, card, badge, btn, formDialog, form, confirmDialog, toast, go, loading,
  invalidate, monthISO, todayISO,
} from '../core.js';

const SALARY = [['day', 'За выход (в день)'], ['month', 'Оклад в месяц']];

export async function render(el, params, ctx) {
  if (params[0]) return renderWorker(el, Number(params[0]), ctx);
  const res = await api.get(`${A}/workers?all=1`);
  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Рабочие'), btn('+ Рабочий', () => workerDialog(null, (w) => go(`/workers/${w.id}`)), 'primary')),
    card(null, table([
      { label: 'Имя', value: (w) => [w.name, w.active ? null : [' ', badge('не работает')]] },
      { label: 'Должность', value: (w) => w.position },
      { label: 'Телефон', value: (w) => w.phone, cls: 'nowrap' },
      { label: 'Оплата', value: (w) => (w.salaryKop ? `${money(w.salaryKop)} · ${(SALARY.find(([v]) => v === w.salaryType) || [, w.salaryType])[1]}` : ''), cls: 'num' },
    ], res.items, { onRow: (w) => go(`/workers/${w.id}`), empty: 'Рабочих пока нет' })));
}

function workerDialog(w, onSaved) {
  formDialog({
    title: w ? 'Рабочий' : 'Новый рабочий',
    fields: [
      { key: 'name', label: 'Имя', required: true },
      { key: 'phone', label: 'Телефон', type: 'tel' },
      { key: 'position', label: 'Должность' },
      { key: 'salaryType', label: 'Как платим', type: 'select', options: SALARY },
      { key: 'salaryKop', label: 'Ставка, ₽', type: 'money' },
      { key: 'active', label: 'Работает', type: 'check' },
    ],
    values: w || { salaryType: 'day', active: true },
    onSave: async (v) => {
      const saved = w ? await api.put(`${A}/workers/${w.id}`, v) : await api.post(`${A}/workers`, v);
      invalidate('workers');
      toast('Сохранено');
      onSaved(saved);
    },
  });
}

async function renderWorker(el, id, ctx) {
  let month = monthISO();
  const box = h('div');
  el.append(h('a', { class: 'back', href: '#/workers' }, '← Рабочие'), box);

  async function load() {
    mount(box, loading());
    const lastDay = new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate();
    const [res, days] = await Promise.all([
      api.get(`${A}/workers/${id}${qs({ month })}`),
      api.get(`${A}/workdays${qs({ from: `${month}-01`, to: `${month}-${String(lastDay).padStart(2, '0')}`, workerId: id })}`),
    ]);
    const w = res.worker;
    const acc = res.accrual;
    ctx.setTitle(w.name);
    const worked = new Set(days.items.map((d) => d.workDate));

    const monthIn = h('input', { class: 'input', type: 'month', value: month, style: 'width:auto',
      onchange: (e) => { month = e.target.value || monthISO(); load(); } });
    const first = new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)) - 1, 1);
    const offset = (first.getDay() + 6) % 7; // понедельник — первый
    const today = todayISO();
    const cal = h('div', { class: 'calendar' },
      ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'].map((d) => h('div', { class: 'dow' }, d)),
      Array.from({ length: offset }, () => h('div')),
      Array.from({ length: lastDay }, (_, i) => {
        const iso = `${month}-${String(i + 1).padStart(2, '0')}`;
        const on = worked.has(iso);
        return h('button', { type: 'button', class: `${on ? 'on' : ''} ${iso === today ? 'today' : ''}`, title: on ? 'Выход — снять' : 'Отметить выход',
          onclick: async (e) => {
            e.target.disabled = true;
            try {
              if (on) await api.del(`${A}/workers/${id}/days/${iso}`);
              else await api.put(`${A}/workers/${id}/days/${iso}`, { note: '' });
              load();
            } catch (ex) { toast(ex.message, 'error'); e.target.disabled = false; }
          } }, i + 1);
      }));

    const f = form([
      { key: 'name', label: 'Имя', required: true },
      { key: 'phone', label: 'Телефон', type: 'tel' },
      { key: 'position', label: 'Должность' },
      { key: 'salaryType', label: 'Как платим', type: 'select', options: SALARY },
      { key: 'salaryKop', label: 'Ставка, ₽', type: 'money' },
      { key: 'active', label: 'Работает', type: 'check' },
    ], w);

    mount(box,
      h('div', { class: 'page-head' }, h('h1', null, w.name), w.active ? null : badge('не работает'),
        btn('Удалить', async () => {
          if (!await confirmDialog({ title: 'Удалить рабочего?', text: `«${w.name}» и его табель будут удалены. В заказах исполнитель станет пустым.` })) return;
          await api.del(`${A}/workers/${id}`);
          invalidate('workers');
          go('/workers');
        }, 'danger')),
      h('div', { class: 'split' },
        h('div', null,
          card('Табель', h('div', null, h('div', { class: 'toolbar' }, monthIn,
            h('span', { class: 'muted' }, `Выходов: ${acc.days}`)), cal)),
          card('Карточка', h('div', null, f.el, h('div', { class: 'btn-row', style: 'margin-top:14px' }, btn('Сохранить', async () => {
            await api.put(`${A}/workers/${id}`, f.values());
            invalidate('workers');
            toast('Сохранено');
            load();
          }, 'primary'))))),
        card(`Начислено за ${month}`, h('div', null,
          h('div', { class: 'stat__value' }, money(acc.amountKop)),
          h('div', { class: 'stat__hint' }, `${acc.days} выходов`),
          h('div', { class: 'btn-row', style: 'margin-top:14px' }, btn('Выплатить', () => formDialog({
            title: 'Выплата', fields: [{ key: 'amountKop', label: 'Сумма, ₽', type: 'money', required: true }],
            values: { amountKop: acc.amountKop },
            saveLabel: 'Выплатить',
            onSave: async (v) => {
              await api.post(`${A}/workers/${id}/payout`, { amountKop: v.amountKop, month });
              toast('Выплата записана расходом в кассе');
              load();
            },
          }), 'primary'))))));
  }
  await load();
}
