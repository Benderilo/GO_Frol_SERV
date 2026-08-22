/* Фролов Системы — личный кабинет клиента. */
(function () {
  'use strict';

  var root = document.documentElement;

  var loginView = document.getElementById('login-view');
  var cabinetView = document.getElementById('cabinet-view');
  var loading = document.getElementById('cabinet-loading');
  var status = document.getElementById('login-status');
  var logoutBtn = document.getElementById('logout');

  var statusLabels = {
    new: 'Принят',
    in_progress: 'В работе',
    done: 'Завершён',
    canceled: 'Отменён'
  };

  /* ----------------------------- Тема ---------------------------------- */
  var toggle = document.getElementById('theme-toggle');
  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = root.dataset.theme === 'dark' ? 'light' : 'dark';
      root.dataset.theme = next;
      localStorage.setItem('frolov-theme', next);
    });
  }

  var year = document.getElementById('year');
  if (year) year.textContent = new Date().getFullYear();

  /* ------------------------------ Вход --------------------------------- */
  document.getElementById('login-submit').addEventListener('click', submitLogin);
  document.getElementById('code').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') submitLogin();
  });

  function submitLogin() {
    var phone = document.getElementById('phone').value.trim();
    var code = document.getElementById('code').value.trim();
    if (!phone || !code) {
      setStatus('Введите телефон и код', 'is-error');
      return;
    }
    setStatus('Проверяем…', '');

    fetch('/api/v1/portal/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phone: phone, code: code })
    })
      .then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok) throw new Error(body.message || 'Не удалось войти');
          return body;
        });
      })
      .then(function () {
        setStatus('', '');
        load();
      })
      .catch(function (e) {
        setStatus(e.message, 'is-error');
      });
  }

  logoutBtn.addEventListener('click', function () {
    fetch('/api/v1/portal/logout', { method: 'POST' }).then(function () {
      location.reload();
    });
  });

  function setStatus(text, cls) {
    status.textContent = text;
    status.className = 'form__status ' + cls;
  }

  /* --------------------------- Данные кабинета -------------------------- */
  function load() {
    loading.hidden = false;
    loginView.hidden = true;
    cabinetView.hidden = true;

    fetch('/api/v1/portal/me')
      .then(function (res) {
        if (res.status === 401) return null;      // просто ещё не вошли
        if (!res.ok) throw new Error('Не удалось загрузить данные');
        return res.json();
      })
      .then(function (data) {
        loading.hidden = true;
        if (!data) {
          loginView.hidden = false;
          logoutBtn.hidden = true;
          return;
        }
        render(data);
        cabinetView.hidden = false;
        logoutBtn.hidden = false;
      })
      .catch(function (e) {
        loading.hidden = true;
        loginView.hidden = false;
        setStatus(e.message, 'is-error');
      });
  }

  function render(data) {
    var client = data.client || {};
    var orders = data.orders || [];

    document.getElementById('client-name').textContent = client.name || 'Клиент';
    var meta = [client.phone, client.address].filter(Boolean).join(' • ');
    document.getElementById('client-meta').textContent = meta;

    renderStats(orders);
    renderOrders(orders);
  }

  function renderStats(orders) {
    var active = orders.filter(function (o) {
      return o.status === 'new' || o.status === 'in_progress';
    }).length;
    var done = orders.filter(function (o) { return o.status === 'done'; }).length;
    var total = orders.reduce(function (sum, o) {
      return o.status === 'done' ? sum + (o.price || 0) : sum;
    }, 0);

    var tiles = [
      { value: orders.length, label: 'всего заказов' },
      { value: active, label: 'в работе' },
      { value: done, label: 'завершено' },
      { value: formatMoney(total), label: 'на сумму' }
    ];

    document.getElementById('cabinet-stats').innerHTML = tiles.map(function (t) {
      return '<div class="stat"><strong>' + escapeHtml(String(t.value)) + '</strong>' +
        '<span>' + escapeHtml(t.label) + '</span></div>';
    }).join('');
  }

  function renderOrders(orders) {
    var box = document.getElementById('orders');
    if (!orders.length) {
      box.innerHTML = '<p class="cabinet-empty">Заказов пока нет. ' +
        'Как только менеджер оформит заказ, он появится здесь.</p>';
      return;
    }

    box.innerHTML = orders.map(function (order) {
      var photos = (order.photos || []).map(function (p) {
        return '<button class="order-photo" type="button" data-full="' + escapeHtml(p.url) +
          '" data-caption="' + escapeHtml(p.caption || '') + '">' +
          '<img src="' + escapeHtml(p.thumbUrl) + '" alt="' + escapeHtml(p.caption || 'Фото по заказу') +
          '" loading="lazy"></button>';
      }).join('');

      return '<article class="order-card">' +
        '<div class="order-card__head">' +
          '<h4>' + escapeHtml(order.title || 'Заказ') + '</h4>' +
          '<span class="order-status order-status--' + escapeHtml(order.status) + '">' +
            escapeHtml(statusLabels[order.status] || order.status) + '</span>' +
        '</div>' +
        (order.description ? '<p>' + escapeHtml(order.description) + '</p>' : '') +
        (photos ? '<div class="order-photos">' + photos + '</div>' : '') +
        '<div class="order-card__foot">' +
          '<span class="order-price">' + escapeHtml(formatMoney(order.price || 0)) + '</span>' +
          (order.dueDate ? '<span class="order-due">Срок: ' + escapeHtml(order.dueDate) + '</span>' : '') +
        '</div>' +
      '</article>';
    }).join('');
  }

  /* --------------------------- Просмотр фото ---------------------------- */
  var lightbox = document.getElementById('lightbox');
  var lightboxImg = document.getElementById('lightbox-img');
  var lightboxCaption = document.getElementById('lightbox-caption');

  document.getElementById('orders').addEventListener('click', function (e) {
    var btn = e.target.closest('.order-photo');
    if (!btn) return;
    lightboxImg.src = btn.dataset.full;
    lightboxCaption.textContent = btn.dataset.caption || '';
    lightbox.hidden = false;
  });

  function closeLightbox() {
    lightbox.hidden = true;
    lightboxImg.src = '';
  }
  document.getElementById('lightbox-close').addEventListener('click', closeLightbox);
  lightbox.addEventListener('click', function (e) {
    if (e.target === lightbox) closeLightbox();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && !lightbox.hidden) closeLightbox();
  });

  /* -------------------------------- Утилиты ----------------------------- */
  // Данные приходят с сервера, но экранируем всё равно: описание и подпись
  // к фото вводит человек, и любой < сломал бы разметку.
  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function formatMoney(value) {
    var rounded = Math.round(value);
    return rounded.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + ' ₽';
  }

  load();
})();
