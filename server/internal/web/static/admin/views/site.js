// Содержимое публичного сайта: тексты, услуги, преимущества, контакты, цвета.
import { api, h, mount, card, btn, form, toast, confirmDialog } from '../core.js';

const ICONS = [['bolt', 'Молния'], ['wrench', 'Ключ'], ['home', 'Дом'], ['building', 'Здание'], ['shield', 'Щит'], ['doc', 'Документ']];

export async function render(el) {
  const s = await api.get('/api/v1/site');
  const main = form([
    { type: 'section', label: 'Шапка' },
    { key: 'siteName', label: 'Название', required: true },
    { key: 'tagline', label: 'Подзаголовок' },
    { key: 'heroBadge', label: 'Плашка над заголовком' },
    { key: 'heroTitle', label: 'Главный заголовок', wide: true },
    { key: 'heroSubtitle', label: 'Текст под заголовком', type: 'textarea' },
    { key: 'heroPrimary', label: 'Кнопка 1' },
    { key: 'heroSecondary', label: 'Кнопка 2' },
    { type: 'section', label: 'Бегущая строка' },
    { key: 'tickerEnabled', label: 'Показывать', type: 'check', wide: true },
    { key: 'tickerText', label: 'Текст', type: 'textarea' },
    { key: 'tickerSpeed', label: 'Один проход, секунд', type: 'number' },
    { type: 'section', label: 'О компании' },
    { key: 'aboutTitle', label: 'Заголовок' },
    { key: 'aboutText', label: 'Текст', type: 'textarea', rows: 4 },
    { type: 'section', label: 'Контакты' },
    { key: 'phone', label: 'Телефон', type: 'tel' },
    { key: 'email', label: 'Почта', type: 'email' },
    { key: 'address', label: 'Адрес' },
    { key: 'city', label: 'Город' },
    { key: 'telegram', label: 'Telegram (ссылка)' },
    { key: 'max', label: 'MAX (ссылка на бота)' },
    { key: 'whatsapp', label: 'WhatsApp (ссылка)' },
    { key: 'workHours', label: 'Часы работы' },
    { key: 'workHoursSchema', label: 'Часы для поисковиков', placeholder: 'Mo-Su 08:00-21:00' },
    { type: 'section', label: 'Оформление' },
    { key: 'accent', label: 'Основной цвет', type: 'color' },
    { key: 'accentAlt', label: 'Второй цвет', type: 'color' },
    { key: 'defaultMode', label: 'Тема по умолчанию', type: 'select', options: [['auto', 'Как в системе'], ['light', 'Светлая'], ['dark', 'Тёмная']] },
    { key: 'footerNote', label: 'Подпись в подвале', type: 'textarea' },
  ], {
    siteName: s.siteName, tagline: s.tagline,
    heroBadge: s.hero.badge, heroTitle: s.hero.title, heroSubtitle: s.hero.subtitle,
    heroPrimary: s.hero.primaryCta, heroSecondary: s.hero.secondaryCta,
    tickerEnabled: s.ticker.enabled, tickerText: s.ticker.text, tickerSpeed: s.ticker.speedSec,
    aboutTitle: s.about.title, aboutText: s.about.text,
    ...s.contacts, ...s.appearance, footerNote: s.footerNote,
  });

  const services = repeater(s.services || [], [
    { key: 'title', label: 'Услуга' }, { key: 'price', label: 'Цена', width: '160px' },
    { key: 'icon', label: 'Значок', options: ICONS, width: '130px' }, { key: 'description', label: 'Описание', wide: true },
  ], { title: '', price: '', icon: 'bolt', description: '' });
  const advantages = repeater(s.advantages || [], [
    { key: 'value', label: 'Число', width: '140px' }, { key: 'label', label: 'Подпись' },
  ], { value: '', label: '' });

  const save = btn('Сохранить сайт', async () => {
    const v = main.values();
    const body = {
      siteName: v.siteName, tagline: v.tagline,
      ticker: { enabled: v.tickerEnabled, text: v.tickerText, speedSec: v.tickerSpeed || 28 },
      hero: { badge: v.heroBadge, title: v.heroTitle, subtitle: v.heroSubtitle, primaryCta: v.heroPrimary, secondaryCta: v.heroSecondary },
      about: { title: v.aboutTitle, text: v.aboutText },
      services: services.values(),
      advantages: advantages.values(),
      contacts: {
        phone: v.phone, email: v.email, address: v.address, city: v.city, telegram: v.telegram,
        whatsapp: v.whatsapp, max: v.max, workHours: v.workHours, workHoursSchema: v.workHoursSchema,
      },
      appearance: { accent: v.accent, accentAlt: v.accentAlt, defaultMode: v.defaultMode },
      footerNote: v.footerNote,
    };
    await api.put('/api/v1/admin/site', body);
    toast('Сайт обновлён — изменения уже видны посетителям');
  }, 'primary');

  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Сайт'),
      h('a', { class: 'btn', href: '/', target: '_blank', rel: 'noopener' }, 'Открыть сайт'),
      btn('Вернуть заводское', async () => {
        if (!await confirmDialog({ title: 'Вернуть заводское наполнение?', text: 'Все тексты, услуги и контакты сайта заменятся исходными.', ok: 'Вернуть' })) return;
        await api.post('/api/v1/admin/site/reset');
        toast('Наполнение сброшено');
        el.replaceChildren();
        render(el);
      }, 'danger'),
      save),
    card('Тексты, контакты, оформление', main.el),
    card('Услуги и цены', services.el),
    card('Цифры (преимущества)', advantages.el),
    h('div', { class: 'btn-row', style: 'margin-top:16px' }, btn('Сохранить сайт', () => save.click(), 'primary')));
}

// Редактор списка однотипных строк: услуги, преимущества.
function repeater(items, cols, blank) {
  const body = h('div');
  const rows = [];
  const add = (item) => {
    const inputs = {};
    const row = h('div', { class: 'card', style: 'padding:12px;margin-bottom:8px' });
    const grid = h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;align-items:end' });
    for (const c of cols) {
      const input = c.options
        ? h('select', { class: 'input' }, c.options.map(([v, l]) => h('option', { value: v, selected: item[c.key] === v }, l)))
        : h('input', { class: 'input', value: item[c.key] || '' });
      inputs[c.key] = input;
      grid.append(h('label', { class: 'field', style: c.wide ? 'flex:1 1 100%' : `flex:${c.width ? '0 0 ' + c.width : '1 1 200px'}` },
        h('span', null, c.label), input));
    }
    const entry = { row, read: () => Object.fromEntries(Object.entries(inputs).map(([k, i]) => [k, i.value.trim()])) };
    row.append(grid, h('div', { class: 'btn-row', style: 'margin-top:8px' },
      btn('↑', () => { const i = rows.indexOf(entry); if (i > 0) { rows.splice(i, 1); rows.splice(i - 1, 0, entry); redraw(); } }, 'sm'),
      btn('↓', () => { const i = rows.indexOf(entry); if (i < rows.length - 1) { rows.splice(i, 1); rows.splice(i + 1, 0, entry); redraw(); } }, 'sm'),
      btn('Удалить', () => { rows.splice(rows.indexOf(entry), 1); redraw(); }, 'sm danger')));
    rows.push(entry);
  };
  const redraw = () => mount(body, rows.map((r) => r.row));
  items.forEach(add);
  redraw();
  return {
    el: h('div', null, body, btn('+ Добавить', () => { add({ ...blank }); redraw(); })),
    values: () => rows.map((r) => r.read()).filter((v) => Object.values(v).some(Boolean)),
  };
}
