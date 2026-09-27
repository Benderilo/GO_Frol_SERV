// Оболочка панели: вход, меню, тема и переходы между разделами.
import {
  api, h, mount, clear, toastError, setUnauthorizedHandler, A, loading, errorBox,
} from './core.js';

// Разделы: путь → модуль. Модуль экспортирует render(el, params, ctx).
const SECTIONS = [
  { group: 'Работа' },
  { path: '', title: 'Сводка', mod: 'dashboard' },
  { path: 'requests', title: 'Заявки', mod: 'requests', count: 'requestsNew' },
  { path: 'orders', title: 'Заказы', mod: 'orders' },
  { path: 'clients', title: 'Клиенты', mod: 'clients' },
  { path: 'documents', title: 'Документы', mod: 'documents' },
  { path: 'tasks', title: 'Задачи', mod: 'tasks' },
  { group: 'Учёт' },
  { path: 'cash', title: 'Касса', mod: 'cash' },
  { path: 'catalog', title: 'Справочник и склад', mod: 'catalog' },
  { path: 'workers', title: 'Рабочие', mod: 'workers' },
  { path: 'payroll', title: 'Зарплаты', mod: 'payroll' },
  { path: 'reports', title: 'Отчёты', mod: 'reports' },
  { group: 'Настройки' },
  { path: 'company', title: 'Реквизиты ИП', mod: 'company' },
  { path: 'site', title: 'Сайт', mod: 'site' },
  { path: 'files', title: 'Файлы', mod: 'files' },
  { path: 'data', title: 'Данные и копии', mod: 'data' },
  { path: 'audit', title: 'Журнал действий', mod: 'audit' },
  { path: 'settings', title: 'Пароль и оформление', mod: 'settings' },
];

// ------------------------------ Тема ----------------------------------

const THEME_KEY = 'frolov-admin-theme';
function storedTheme() {
  try { return localStorage.getItem(THEME_KEY) || 'auto'; } catch { return 'auto'; }
}
export function applyTheme(mode = storedTheme()) {
  const dark = mode === 'dark' || (mode === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
}
export function setTheme(mode) {
  try { localStorage.setItem(THEME_KEY, mode); } catch { /* приватный режим */ }
  applyTheme(mode);
}
export const currentTheme = storedTheme;
applyTheme();
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => applyTheme());

// ------------------------------ Вход ----------------------------------

const root = document.getElementById('root');
let user = null;

function showLogin(message) {
  user = null;
  document.getElementById('modals').replaceChildren();
  const login = h('input', { class: 'input', name: 'username', autocomplete: 'username', required: true });
  const password = h('input', { class: 'input', type: 'password', name: 'password', autocomplete: 'current-password', required: true });
  const remember = h('input', { type: 'checkbox' });
  const err = h('div', { class: message ? 'alert error' : 'hidden' }, message || '');
  const submit = h('button', { class: 'btn primary', type: 'submit', style: 'height:38px' }, 'Войти');
  const formEl = h('form', { class: 'card__body' },
    h('div', { class: 'login__title' }, h('div', { class: 'brand__mark' }, 'Ф'), h('h1', null, 'Панель управления'),
      h('div', { class: 'muted', style: 'margin-top:4px' }, 'Логин и пароль — как в приложении')),
    err,
    h('label', { class: 'field' }, h('span', null, 'Логин'), login),
    h('label', { class: 'field' }, h('span', null, 'Пароль'), password),
    h('label', { class: 'check' }, remember, 'Запомнить на этом компьютере на 30 дней'),
    submit);
  formEl.addEventListener('submit', async (e) => {
    e.preventDefault();
    submit.disabled = true;
    err.className = 'hidden';
    try {
      const res = await api.post('/api/v1/auth/web-login', {
        login: login.value.trim(), password: password.value, remember: remember.checked,
      });
      user = res.user;
      password.value = '';
      start();
    } catch (ex) {
      err.textContent = ex.message;
      err.className = 'alert error';
    } finally {
      submit.disabled = false;
    }
  });
  mount(root, h('div', { class: 'login' }, h('div', { class: 'card login__box' }, formEl)));
  login.focus();
}

setUnauthorizedHandler(() => {
  if (user) showLogin('Сессия закончилась или пароль сменили — войдите заново.');
});

export async function logout() {
  try { await api.post('/api/v1/auth/web-logout'); } catch { /* всё равно выходим */ }
  showLogin();
}

// ------------------------------ Каркас --------------------------------

let content, titleEl, navEl, appEl;
const counts = {};

function buildShell() {
  navEl = h('nav', { class: 'nav' });
  titleEl = h('div', { class: 'topbar__title' });
  content = h('div', { class: 'content' });
  appEl = h('div', { class: 'app' },
    h('aside', { class: 'sidebar' },
      h('div', { class: 'brand' }, h('div', { class: 'brand__mark' }, 'Ф'),
        h('div', null, h('div', { class: 'brand__name' }, 'Фролов Системы'), h('div', { class: 'brand__sub' }, 'Панель управления'))),
      navEl,
      h('div', { class: 'sidebar__foot' },
        h('div', { class: 'muted' }, user?.displayName || user?.login || ''),
        h('a', { href: '#', onclick: (e) => { e.preventDefault(); logout(); } }, 'Выйти'))),
    h('div', { class: 'main' },
      h('header', { class: 'topbar' },
        h('button', { class: 'btn ghost menu-toggle', type: 'button', onclick: () => appEl.classList.toggle('menu-open') }, '☰'),
        titleEl, h('div', { class: 'topbar__spacer' }),
        h('button', { class: 'btn ghost sm', type: 'button', title: 'Светлая / тёмная тема', onclick: () => {
          setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
        } }, '◐ Тема')),
      content));
  mount(root, appEl);
}

function renderNav(active) {
  mount(navEl, SECTIONS.map((s) => {
    if (s.group) return h('div', { class: 'nav__group' }, s.group);
    const n = s.count ? counts[s.count] : 0;
    return h('a', { href: '#/' + s.path, class: s.path === active ? 'active' : null },
      h('span', null, s.title), n ? h('span', { class: 'count' }, n) : null);
  }));
}

export async function refreshCounts() {
  try {
    const st = await api.get(`${A}/stats`);
    counts.requestsNew = st.requestsNew;
    renderNav(currentSection);
  } catch { /* счётчик не главное */ }
}

// ------------------------------ Маршруты ------------------------------

let currentSection = '';
let renderSeq = 0;

export function setTitle(t) {
  titleEl.textContent = t;
  document.title = `${t} — панель управления`;
}

async function route() {
  if (!user) return;
  appEl.classList.remove('menu-open');
  const path = location.hash.replace(/^#\/?/, '');
  const [section, ...rest] = path.split('/');
  const def = SECTIONS.find((s) => s.path === section && s.mod) || SECTIONS[1];
  currentSection = def.path;
  renderNav(currentSection);
  setTitle(def.title);
  const seq = ++renderSeq;
  mount(content, loading());
  try {
    const mod = await import(`./views/${def.mod}.js`);
    if (seq !== renderSeq) return;
    clear(content);
    await mod.render(content, rest.filter(Boolean), { setTitle, refreshCounts });
  } catch (e) {
    if (seq === renderSeq && e?.status !== 401) {
      mount(content, errorBox(e));
      if (!(e?.status)) console.error(e);
    }
  }
  window.scrollTo(0, 0);
}

function start() {
  buildShell();
  window.onhashchange = route;
  route();
  refreshCounts();
}

// Уже вошли? Сессию держит cookie, её не видно скрипту — спрашиваем сервер.
(async () => {
  try {
    user = await api.get('/api/v1/auth/me');
    start();
  } catch (e) {
    if (e.status !== 401) toastError(e);
    showLogin();
  }
})();
