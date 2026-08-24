#!/usr/bin/env bash
# Перенос данных CRM со старого сервера на новый.
#
#   ./migrate.sh 195.19.195.169 91.184.246.64
#
# Переезжает всё содержимое каталога данных: база SQLite вместе с журналом
# WAL и каталог с фотографиями. Новый сервер к этому моменту уже должен быть
# развёрнут (deploy.sh), иначе восстанавливать будет некуда.
#
# Пароль по SSH спросят несколько раз — по разу на каждое подключение.
# Чтобы спрашивали один раз, заранее разложите ключи:
#   ssh-copy-id root@<адрес>
set -euo pipefail

OLD="${1:-}"
NEW="${2:-}"
SSH_USER="${SSH_USER:-root}"
APP_DIR="/opt/frolov-crm"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOCAL_DUMP="${TMPDIR:-/tmp}/frolov-data-${STAMP}.tgz"

if [[ -z "$OLD" || -z "$NEW" ]]; then
  echo "Использование: $0 <старый-адрес> <новый-адрес>" >&2
  exit 1
fi

echo "==> 1/5 Останавливаем службу на старом сервере, чтобы база не менялась при копировании"
ssh "${SSH_USER}@${OLD}" 'systemctl stop frolov-crm || true'

echo "==> 2/5 Упаковываем данные на старом сервере"
ssh "${SSH_USER}@${OLD}" APP_DIR="$APP_DIR" 'bash -s' <<'REMOTE'
set -euo pipefail
if [[ ! -d "$APP_DIR/data" ]]; then
  echo "На старом сервере нет каталога $APP_DIR/data — переносить нечего" >&2
  exit 1
fi
tar czf /tmp/frolov-data.tgz -C "$APP_DIR" data
ls -la /tmp/frolov-data.tgz
REMOTE

echo "==> 3/5 Скачиваем к себе"
scp "${SSH_USER}@${OLD}:/tmp/frolov-data.tgz" "$LOCAL_DUMP"
ssh "${SSH_USER}@${OLD}" 'rm -f /tmp/frolov-data.tgz'

echo "==> 4/5 Отправляем на новый сервер"
scp "$LOCAL_DUMP" "${SSH_USER}@${NEW}:/tmp/frolov-data.tgz"

echo "==> 5/5 Разворачиваем на новом сервере"
ssh "${SSH_USER}@${NEW}" APP_DIR="$APP_DIR" STAMP="$STAMP" 'bash -s' <<'REMOTE'
set -euo pipefail

systemctl stop frolov-crm 2>/dev/null || true

# Прежние данные не удаляем, а отодвигаем: если что-то пойдёт не так,
# будет куда вернуться.
if [[ -d "$APP_DIR/data" ]]; then
  mv "$APP_DIR/data" "$APP_DIR/data.before-${STAMP}"
  echo "Прежний каталог сохранён как data.before-${STAMP}"
fi

tar xzf /tmp/frolov-data.tgz -C "$APP_DIR"
rm -f /tmp/frolov-data.tgz

# Сертификаты со старого сервера не переносятся: там их не было,
# а новый выпишет свои. Каталог создаём заранее.
mkdir -p "$APP_DIR/data/certs"
chown -R frolov:frolov "$APP_DIR"
chmod 700 "$APP_DIR/data/certs"

systemctl start frolov-crm
sleep 3
systemctl --no-pager --lines=15 status frolov-crm || true
REMOTE

echo
echo "==> Перенос завершён. Локальная копия осталась здесь: $LOCAL_DUMP"
echo "    Старый сервер остановлен. Убедитесь, что новый работает,"
echo "    и только потом удаляйте старый."
