/* Фролов Системы — поведение публичной страницы. */
(function () {
  'use strict';

  var root = document.documentElement;

  /* ----------------------------- Тема ---------------------------------- */
  var toggle = document.getElementById('theme-toggle');
  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = root.dataset.theme === 'dark' ? 'light' : 'dark';
      root.dataset.theme = next;
      localStorage.setItem('frolov-theme', next);
    });
  }
  // Если пользователь не выбирал тему вручную — следуем за системной.
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function (e) {
    if (!localStorage.getItem('frolov-theme')) {
      root.dataset.theme = e.matches ? 'dark' : 'light';
    }
  });

  /* --------------------------- Мобильное меню --------------------------- */
  var burger = document.getElementById('burger');
  var nav = document.getElementById('nav');
  if (burger && nav) {
    burger.addEventListener('click', function () {
      document.body.classList.toggle('nav-open');
    });
    nav.addEventListener('click', function (e) {
      if (e.target.tagName === 'A') document.body.classList.remove('nav-open');
    });
  }

  /* ------------------- Прогресс, «наверх», липкая шапка ----------------- */
  var progress = document.getElementById('scroll-progress');
  var header = document.getElementById('header');
  var toTop = document.getElementById('to-top');
  var ticking = false;

  function onScroll() {
    var scrolled = window.scrollY;
    var height = document.documentElement.scrollHeight - window.innerHeight;
    if (progress) progress.style.width = (height > 0 ? (scrolled / height) * 100 : 0) + '%';
    if (header) header.classList.toggle('is-stuck', scrolled > 8);
    if (toTop) toTop.classList.toggle('is-visible', scrolled > 600);
    ticking = false;
  }
  window.addEventListener('scroll', function () {
    if (!ticking) {
      ticking = true;
      window.requestAnimationFrame(onScroll);
    }
  }, { passive: true });
  onScroll();

  /* ---------------------- Появление блоков при скролле ------------------ */
  var revealables = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window) {
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry, i) {
        if (!entry.isIntersecting) return;
        // Небольшая лесенка, чтобы карточки появлялись не одновременно.
        entry.target.style.transitionDelay = (i * 70) + 'ms';
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px' });
    revealables.forEach(function (el) { observer.observe(el); });
  } else {
    revealables.forEach(function (el) { el.classList.add('is-visible'); });
  }

  /* ------------------- Подсветка карточки под курсором ------------------ */
  document.querySelectorAll('.card').forEach(function (card) {
    card.addEventListener('pointermove', function (e) {
      var rect = card.getBoundingClientRect();
      card.style.setProperty('--mx', (e.clientX - rect.left) + 'px');
      card.style.setProperty('--my', (e.clientY - rect.top) + 'px');
    });
  });

  /* ----------------------------- Форма заявки --------------------------- */
  var form = document.getElementById('request');
  var status = document.getElementById('form-status');
  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var button = form.querySelector('button[type="submit"]');
      var data = {
        name: form.elements.name.value.trim(),
        phone: form.elements.phone.value.trim(),
        message: form.elements.message.value.trim(),
        website: form.elements.website.value
      };

      var invalid = false;
      ['name', 'phone'].forEach(function (field) {
        var el = form.elements[field];
        var bad = !data[field];
        el.classList.toggle('is-invalid', bad);
        if (bad) invalid = true;
      });
      if (invalid) {
        setStatus('Заполните имя и телефон', 'is-error');
        return;
      }

      button.disabled = true;
      setStatus('Отправляем…', '');

      fetch('/api/v1/requests', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
      })
        .then(function (res) {
          if (!res.ok) throw new Error('HTTP ' + res.status);
          form.reset();
          setStatus('Заявка отправлена. Мы свяжемся с вами в ближайшее время.', 'is-ok');
        })
        .catch(function () {
          setStatus('Не удалось отправить. Позвоните нам или попробуйте позже.', 'is-error');
        })
        .finally(function () {
          button.disabled = false;
        });
    });
  }

  function setStatus(text, cls) {
    if (!status) return;
    status.textContent = text;
    status.className = 'form__status ' + cls;
  }

  /* -------------------------------- Мелочи ------------------------------ */
  var year = document.getElementById('year');
  if (year) year.textContent = new Date().getFullYear();
})();
