#!/usr/bin/env bash
# Перенос данных CRM со старого сервера на новый.
#
#   ./deploy/migrate.sh 195.19.195.169 91.184.246.64
#
# Переезжает весь каталог данных: база SQLite вместе с журналом WAL и
# фотографии заказов. Новый сервер к этому моменту уже должен быть
# развёрнут (deploy.sh), иначе восстанавливать будет некуда.
#
# Пароль спрашивают по одному разу на сервер: со старым и новым работаем
# одним соединением каждый. Чтобы не спрашивали вовсе — ssh-copy-id.
set -euo pipefail

OLD="${1:-}"
NEW="${2:-}"
SSH_USER="${SSH_USER:-root}"
APP_DIR="/opt/frolov-crm"
STAMP="$(date +%Y%m%d-%H%M%S)"
DUMP="${TMPDIR:-/tmp}/frolov-data-${STAMP}.tgz"

if [[ -z "$OLD" || -z "$NEW" ]]; then
  echo "Использование: $0 <старый-адрес> <новый-адрес>" >&2
  exit 1
fi

echo "==> 1/2 Забираем данные со старого сервера ${OLD}"
# Одно соединение: останавливаем службу и сразу отдаём архив в stdout.
# Сообщения уходят в stderr, чтобы не попасть в архив.
ssh "${SSH_USER}@${OLD}" "
  set -e
  systemctl stop frolov-crm >/dev/null 2>&1 || true
  if [ ! -d ${APP_DIR}/data ]; then
    echo 'На старом сервере нет ${APP_DIR}/data — переносить нечего' >&2
    exit 1
  fi
  tar czf - -C ${APP_DIR} data
" > "$DUMP"

SIZE="$(du -h "$DUMP" | cut -f1)"
if [[ ! -s "$DUMP" ]]; then
  echo "Архив пуст — перенос прерван, старый сервер остановлен." >&2
  exit 1
fi
echo "    получено ${SIZE} → ${DUMP}"

echo "==> 2/2 Разворачиваем на новом сервере ${NEW}"
ssh "${SSH_USER}@${NEW}" "
  set -e
  systemctl stop frolov-crm >/dev/null 2>&1 || true

  # Прежние данные не удаляем, а отодвигаем: будет куда вернуться.
  if [ -d ${APP_DIR}/data ]; then
    mv ${APP_DIR}/data ${APP_DIR}/data.before-${STAMP}
    echo 'Прежний каталог сохранён как data.before-${STAMP}'
  fi

  tar xzf - -C ${APP_DIR}

  # Сертификаты не переносим: у нового сервера своё имя и свои ключи.
  mkdir -p ${APP_DIR}/data/certs
  chown -R frolov:frolov ${APP_DIR}
  chmod 700 ${APP_DIR}/data/certs

  systemctl start frolov-crm
  sleep 3
  systemctl --no-pager --lines=15 status frolov-crm || true
" < "$DUMP"

echo
echo "==> Перенос завершён. Локальная копия осталась здесь:"
echo "    $DUMP"
echo "    Старый сервер остановлен. Убедитесь, что новый работает,"
echo "    и только потом удаляйте старый."
echo
echo "    Пароль администратора переехал вместе с базой — тот же, что был."
