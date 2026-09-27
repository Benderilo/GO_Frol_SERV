// Ядро панели: запросы к API, сборка DOM, форматирование, окна и формы.
// Данные из базы вставляются в страницу только как текст (textContent),
// никогда как HTML — чужой текст в имени клиента не станет кодом.

// ------------------------------ API -----------------------------------

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }

// Заголовок панели: сервер принимает сессию только вместе с ним,
// а чужой сайт поставить его не может.
const WEB = { 'X-Frolov-Web': '1' };

async function send(method, path, body, raw) {
  const opts = { method, headers: { ...WEB }, credentials: 'same-origin' };
  if (body instanceof FormData) {
    opts.body = body;
  } else if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, opts);
  } catch {
    throw new ApiError(0, 'Нет связи с сервером');
  }
  if (res.status === 401) {
    onUnauthorized();
    throw new ApiError(401, 'Сессия закончилась — войдите заново');
  }
  if (!res.ok) {
    let msg = `Ошибка ${res.status}`;
    try { const j = await res.json(); if (j.message) msg = j.message; } catch { /* не JSON */ }
    throw new ApiError(res.status, msg);
  }
  if (raw) return res;
  if (res.status === 204) return null;
  const type = res.headers.get('Content-Type') || '';
  return type.includes('application/json') ? res.json() : res.text();
}

export const api = {
  get: (p) => send('GET', p),
  post: (p, b) => send('POST', p, b ?? {}),
  put: (p, b) => send('PUT', p, b),
  patch: (p, b) => send('PATCH', p, b),
  del: (p) => send('DELETE', p),
  upload: (p, form) => send('POST', p, form),
  raw: (p) => send('GET', p, undefined, true),
};

export const A = '/api/v1/admin';

export function qs(params) {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') s.set(k, v);
  }
  const str = s.toString();
  return str ? `?${str}` : '';
}

// Скачать файл с сервера под именем из ответа (или запасным).
export async function download(path, fallbackName) {
  const res = await api.raw(path);
  const blob = await res.blob();
  let name = fallbackName;
  const cd = res.headers.get('Content-Disposition') || '';
  const star = cd.match(/filename\*=UTF-8''([^;]+)/i);
  const plain = cd.match(/filename="([^"]+)"/i);
  if (star) name = decodeURIComponent(star[1]);
  else if (plain) name = plain[1];
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

// ------------------------------ DOM -----------------------------------

// h('div', {class: 'x', onclick: fn}, 'текст', child, [дети]) — всё текстом.
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'class') el.className = v;
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked') el.checked = !!v;
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, String(v));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c === undefined || c === null || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else if (c instanceof Node) el.append(c);
    else el.append(document.createTextNode(String(c)));
  }
}

export function clear(el) { while (el.firstChild) el.firstChild.remove(); return el; }
export function mount(el, ...children) { clear(el); append(el, children); return el; }

// -------------------------- Форматирование ----------------------------

const rubFmt = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const money = (kop) => rubFmt.format((kop || 0) / 100) + ' ₽';
export const moneyPlain = (kop) => rubFmt.format((kop || 0) / 100);

// «1 234,50» → 123450 копеек. Пусто — 0.
export function parseMoney(s) {
  const clean = String(s ?? '').replace(/\s/g, '').replace(',', '.').replace(/[^\d.-]/g, '');
  if (!clean) return 0;
  const n = Number(clean);
  return Number.isFinite(n) ? Math.round(n * 100) : NaN;
}
export const kopToInput = (kop) => (kop ? (kop / 100).toFixed(2).replace('.', ',').replace(/,00$/, '') : '');

export const qty = (milli) => {
  const v = (milli || 0) / 1000;
  return Number.isInteger(v) ? String(v) : v.toLocaleString('ru-RU', { maximumFractionDigits: 3 });
};
export function parseQty(s) {
  const n = Number(String(s ?? '').replace(/\s/g, '').replace(',', '.'));
  return Number.isFinite(n) ? Math.round(n * 1000) : NaN;
}

export function date(iso) {
  if (!iso) return '';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString('ru-RU');
}
export function dateTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso
    : d.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
export const todayISO = () => {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
};
export const monthISO = () => todayISO().slice(0, 7);

export const ORDER_STATUS = { new: 'Новый', in_progress: 'В работе', done: 'Завершён', canceled: 'Отменён' };
export const ORDER_STATUS_CLS = { new: 'accent', in_progress: 'warn', done: 'success', canceled: '' };
export const REQUEST_STATUS = { new: 'Новая', in_progress: 'В работе', done: 'Обработана', spam: 'Спам' };
export const REQUEST_STATUS_CLS = { new: 'accent', in_progress: 'warn', done: 'success', spam: '' };
export const DOC_KIND = {
  invoice: 'Счёт на оплату', act: 'Акт выполненных работ', estimate: 'Смета',
  waybill: 'Накладная', upd: 'УПД', reconciliation: 'Акт сверки',
};
export const DOC_STATUS = { draft: 'Черновик', issued: 'Проведён', annulled: 'Аннулирован' };
export const DOC_STATUS_CLS = { draft: '', issued: 'success', annulled: 'danger' };
export const METHOD = { cash: 'Наличные', card: 'Карта', account: 'Расчётный счёт' };
export const ITEM_KIND = { service: 'Услуга', work: 'Работа', material: 'Материал' };
export const PRIORITY = { low: 'Низкий', normal: 'Обычный', high: 'Высокий' };

export const badge = (text, cls = '') => h('span', { class: `badge ${cls}` }, text);

// ------------------------------ Уведомления ---------------------------

export function toast(text, kind = '') {
  const el = h('div', { class: `toast ${kind}` }, text);
  document.getElementById('toasts').append(el);
  setTimeout(() => el.remove(), kind === 'error' ? 6000 : 3000);
}
export const toastError = (e) => toast(e?.message || String(e), 'error');

// Выполнить действие с блокировкой кнопки и сообщением об ошибке.
export async function busy(btn, fn) {
  const was = btn?.disabled;
  if (btn) btn.disabled = true;
  try {
    return await fn();
  } catch (e) {
    toastError(e);
    return undefined;
  } finally {
    if (btn) btn.disabled = was;
  }
}

// ------------------------------ Окна ----------------------------------

// modal({title, body, actions:[{label, kind, onClick(close)}], wide}) → close()
export function modal({ title, body, actions = [], wide = false, left, onClose }) {
  const host = document.getElementById('modals');
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
    if (onClose) onClose();
  };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  const foot = h('div', { class: 'modal__foot' },
    left ? h('div', { class: 'left' }, left) : null,
    actions.map((a) => {
      const b = h('button', { class: `btn ${a.kind || ''}`, type: 'button' }, a.label);
      b.addEventListener('click', () => a.onClick ? busy(b, () => a.onClick(close)) : close());
      return b;
    }));
  const backdrop = h('div', { class: 'modal-backdrop' },
    h('div', { class: `modal ${wide ? 'wide' : ''}`, role: 'dialog', 'aria-modal': 'true' },
      h('div', { class: 'modal__head' }, h('h2', null, title),
        h('button', { class: 'btn ghost sm', type: 'button', onclick: () => close(), 'aria-label': 'Закрыть' }, '✕')),
      h('div', { class: 'modal__body' }, body),
      actions.length || left ? foot : null));
  backdrop.addEventListener('mousedown', (e) => { if (e.target === backdrop) close(); });
  document.addEventListener('keydown', onKey);
  host.append(backdrop);
  const first = backdrop.querySelector('input, select, textarea');
  if (first) setTimeout(() => first.focus(), 30);
  return close;
}

export function confirmDialog({ title, text, ok = 'Удалить', danger = true }) {
  return new Promise((resolve) => {
    let answer = false;
    modal({
      title,
      body: h('p', { style: 'margin:0' }, text),
      onClose: () => resolve(answer),
      actions: [
        { label: 'Отмена' },
        { label: ok, kind: danger ? 'danger solid' : 'primary', onClick: (c) => { answer = true; c(); } },
      ],
    });
  });
}

// ------------------------------ Формы ---------------------------------

// fields: [{key, label, type, options, wide, hint, required, placeholder, readonly}]
// type: text | textarea | number | money | qty | date | month | select | check | password | email | tel | color
export function form(fields, values = {}) {
  const inputs = {};
  const grid = h('div', { class: 'form-grid' });
  for (const f of fields) {
    if (f.type === 'section') {
      grid.append(h('h3', { class: 'wide', style: 'margin-top:6px' }, f.label));
      continue;
    }
    const v = values[f.key];
    let input;
    switch (f.type) {
      case 'textarea':
        input = h('textarea', { class: 'input', rows: f.rows || 3, placeholder: f.placeholder }, v ?? '');
        break;
      case 'select':
        input = h('select', { class: 'input' },
          (f.options || []).map(([val, label]) => h('option', { value: val, selected: String(v ?? '') === String(val) }, label)));
        break;
      case 'check':
        input = h('input', { type: 'checkbox', checked: !!v });
        break;
      case 'money':
        input = h('input', { class: 'input num', inputmode: 'decimal', value: kopToInput(v), placeholder: '0,00' });
        break;
      case 'qty':
        input = h('input', { class: 'input num', inputmode: 'decimal', value: v ? qty(v) : '', placeholder: '1' });
        break;
      default:
        input = h('input', {
          class: 'input', type: f.type || 'text', value: v ?? '', placeholder: f.placeholder,
          autocomplete: f.autocomplete || 'off',
        });
    }
    if (f.readonly) input.readOnly = true;
    inputs[f.key] = { input, f };
    const label = f.type === 'check'
      ? h('label', { class: `check ${f.wide ? 'wide' : ''}` }, input, f.label)
      : h('label', { class: `field ${f.wide || f.type === 'textarea' ? 'wide' : ''}` },
        h('span', null, f.label + (f.required ? ' *' : '')), input, f.hint ? h('span', { class: 'hint' }, f.hint) : null);
    grid.append(label);
  }
  return {
    el: grid,
    inputs,
    // Значения в виде, в каком их ждёт API. Ошибка — исключение с текстом.
    values() {
      const out = {};
      for (const [key, { input, f }] of Object.entries(inputs)) {
        let val;
        switch (f.type) {
          case 'check': val = input.checked; break;
          case 'number': val = input.value === '' ? 0 : Number(input.value); break;
          case 'money':
            val = parseMoney(input.value);
            if (Number.isNaN(val)) throw new Error(`«${f.label}»: не похоже на сумму`);
            break;
          case 'qty':
            val = input.value.trim() === '' ? 1000 : parseQty(input.value);
            if (Number.isNaN(val)) throw new Error(`«${f.label}»: не похоже на количество`);
            break;
          case 'select':
            val = input.value;
            if (f.numeric) val = val === '' ? null : Number(val);
            break;
          default: val = input.value.trim();
        }
        if (f.required && (val === '' || val === null)) throw new Error(`Заполните «${f.label}»`);
        out[key] = val;
      }
      return out;
    },
  };
}

// Окно с формой: onSave(values) → если бросает, окно остаётся открытым.
export function formDialog({ title, fields, values, onSave, saveLabel = 'Сохранить', wide, extra, left }) {
  const f = form(fields, values);
  return modal({
    title, wide, left,
    body: h('div', null, f.el, extra || null),
    actions: [
      { label: 'Отмена' },
      { label: saveLabel, kind: 'primary', onClick: async (close) => { await onSave(f.values()); close(); } },
    ],
  });
}

// ------------------------------ Таблицы -------------------------------

// columns: [{label, value(row) → текст|узел, cls, width}], onRow(row)
export function table(columns, rows, { onRow, empty = 'Пока пусто', foot } = {}) {
  if (!rows.length) return h('div', { class: 'empty' }, empty);
  return h('div', { class: 'table-wrap' },
    h('table', { class: 'table' },
      h('thead', null, h('tr', null, columns.map((c) => h('th', { class: c.cls, style: c.width ? `width:${c.width}` : null }, c.label)))),
      h('tbody', null, rows.map((r) => h('tr', { class: onRow ? 'clickable' : null, onclick: onRow ? (e) => {
        if (e.target.closest('button, a, input, select, label')) return;
        onRow(r);
      } : null }, columns.map((c) => h('td', { class: c.cls }, c.value(r)))))),
      foot ? h('tfoot', null, h('tr', null, foot.map((c) => h('td', { class: c.cls, colspan: c.span }, c.text)))) : null));
}

export function card(title, body, actions) {
  return h('section', { class: 'card' },
    title || actions ? h('div', { class: 'card__head' }, h('h2', null, title || ''), actions || null) : null,
    h('div', { class: `card__body ${body && body.classList && body.classList.contains('table-wrap') ? 'flush' : ''}` }, body));
}

export const loading = () => h('div', { class: 'loading' }, h('span', { class: 'spinner' }), ' Загрузка…');
export const errorBox = (e) => h('div', { class: 'alert error' }, e?.message || String(e));

// Кнопка.
export function btn(label, onClick, kind = '') {
  const b = h('button', { class: `btn ${kind}`, type: 'button' }, label);
  if (onClick) b.addEventListener('click', () => busy(b, () => onClick(b)));
  return b;
}

// Выбор файла с компьютера.
export function pickFile({ accept, multiple } = {}) {
  return new Promise((resolve) => {
    const input = h('input', { type: 'file', accept, multiple, style: 'display:none' });
    input.addEventListener('change', () => { resolve([...input.files]); input.remove(); });
    document.body.append(input);
    input.click();
  });
}

// ------------------------------ Навигация -----------------------------

export function go(path) { location.hash = '#' + path; }
export function link(path, text) { return h('a', { href: '#' + path }, text); }

// Списки-справочники с коротким кешем: клиенты и рабочие нужны многим формам.
const cache = new Map();
export async function cached(key, loader, ttl = 30000) {
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < ttl) return hit.value;
  const value = await loader();
  cache.set(key, { at: Date.now(), value });
  return value;
}
export function invalidate(prefix) {
  for (const k of cache.keys()) if (k.startsWith(prefix)) cache.delete(k);
}
export const loadClients = () => cached('clients', async () => (await api.get(`${A}/clients?limit=500`)).items);
export const loadWorkers = () => cached('workers', async () => (await api.get(`${A}/workers?all=1`)).items);
export const loadCatalog = () => cached('catalog', async () => (await api.get(`${A}/catalog`)).items);

export async function clientOptions(withNone = true) {
  const list = await loadClients();
  const opts = list.map((c) => [c.id, c.phone ? `${c.name} · ${c.phone}` : c.name]);
  return withNone ? [['', '— без клиента —'], ...opts] : opts;
}
export async function workerOptions() {
  const list = await loadWorkers();
  return [['', '— не назначен —'], ...list.map((w) => [w.id, w.name + (w.active ? '' : ' (не работает)')])];
}
