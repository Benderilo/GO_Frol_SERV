// Пароль и оформление панели.
import { api, h, card, btn, form, toast } from '../core.js';
import { setTheme, currentTheme, logout } from '../app.js';

export async function render(el) {
  const me = await api.get('/api/v1/auth/me');

  const themeSel = h('select', { class: 'input', style: 'max-width:260px', onchange: () => setTheme(themeSel.value) },
    [['auto', 'Как в системе'], ['light', 'Светлая'], ['dark', 'Тёмная']].map(([v, l]) => h('option', { value: v, selected: currentTheme() === v }, l)));

  const pw = form([
    { key: 'currentPassword', label: 'Текущий пароль', type: 'password', required: true, autocomplete: 'current-password' },
    { key: 'newPassword', label: 'Новый пароль', type: 'password', required: true, autocomplete: 'new-password', hint: 'Не короче 6 символов' },
    { key: 'repeat', label: 'Новый пароль ещё раз', type: 'password', required: true, autocomplete: 'new-password' },
  ]);

  el.append(
    h('div', { class: 'page-head' }, h('h1', null, 'Пароль и оформление')),
    h('div', { class: 'grid grid-2' },
      card('Вход', h('div', null,
        h('dl', { class: 'props' }, h('dt', null, 'Логин'), h('dd', null, me.login)),
        h('p', { class: 'muted' }, 'Логин и пароль одни на приложение и панель. Смена пароля здесь или в приложении сразу закрывает все сессии панели — войти нужно будет новым паролем.'),
        pw.el,
        h('div', { class: 'btn-row', style: 'margin-top:14px' }, btn('Сменить пароль', async () => {
          const v = pw.values();
          if (v.newPassword !== v.repeat) throw new Error('Новые пароли не совпадают');
          await api.post('/api/v1/auth/password', { currentPassword: v.currentPassword, newPassword: v.newPassword });
          toast('Пароль изменён — войдите новым паролем');
          await logout();
        }, 'primary')))),
      card('Оформление', h('div', null,
        h('label', { class: 'field' }, h('span', null, 'Тема панели'), themeSel),
        h('p', { class: 'muted' }, 'Выбор запоминается в этом браузере.')))));
}
