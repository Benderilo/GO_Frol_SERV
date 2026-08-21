#!/usr/bin/env bash
# Сборка и развёртывание frolov-crm на сервер.
#
#   ./deploy.sh 195.19.195.169
#
# Скрипт: собирает бинарник под linux/amd64, копирует его на сервер по SSH,
# создаёт пользователя, systemd-юнит и правила ufw. Пароль SSH спросит сам ssh
# (или используйте ключ: ssh-copy-id root@195.19.195.169).
set -euo pipefail

HOST="${1:-}"
SSH_USER="${SSH_USER:-root}"
APP_DIR="/opt/frolov-crm"
ENV_FILE="/etc/frolov-crm.env"
HTTP_PORT="${HTTP_PORT:-80}"

if [[ -z "$HOST" ]]; then
  echo "Использование: $0 <адрес-сервера>" >&2
  exit 1
fi

cd "$(dirname "$0")/.."

echo "==> Сборка linux/amd64"
VERSION="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /tmp/frolov-crm ./cmd/server

echo "==> Копирование на ${SSH_USER}@${HOST}"
scp /tmp/frolov-crm "${SSH_USER}@${HOST}:/tmp/frolov-crm.new"
scp deploy/frolov-crm.service "${SSH_USER}@${HOST}:/tmp/frolov-crm.service"

echo "==> Установка на сервере"
ssh "${SSH_USER}@${HOST}" APP_DIR="$APP_DIR" ENV_FILE="$ENV_FILE" HTTP_PORT="$HTTP_PORT" 'bash -s' <<'REMOTE'
set -euo pipefail

id -u frolov >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin frolov
mkdir -p "$APP_DIR/data"

# Файл окружения создаём один раз, чтобы не перетереть секрет и пароль.
if [[ ! -f "$ENV_FILE" ]]; then
  JWT_SECRET="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  ADMIN_PASSWORD="$(head -c 12 /dev/urandom | base64 | tr -d '/+=\n' | cut -c1-14)"
  cat > "$ENV_FILE" <<ENV
FROLOV_ENV=prod
FROLOV_ADDR=:${HTTP_PORT}
FROLOV_DB=${APP_DIR}/data/frolov.db
FROLOV_JWT_SECRET=${JWT_SECRET}
FROLOV_ADMIN_LOGIN=admin
FROLOV_ADMIN_PASSWORD=${ADMIN_PASSWORD}
FROLOV_CORS_ORIGINS=*
ENV
  chmod 600 "$ENV_FILE"
  echo "!!! Создан ${ENV_FILE}. Логин: admin, пароль: ${ADMIN_PASSWORD}"
  echo "!!! Запишите пароль — он больше нигде не выводится."
fi

systemctl stop frolov-crm 2>/dev/null || true
install -m 0755 -o frolov -g frolov /tmp/frolov-crm.new "$APP_DIR/frolov-crm"
rm -f /tmp/frolov-crm.new
chown -R frolov:frolov "$APP_DIR"

install -m 0644 /tmp/frolov-crm.service /etc/systemd/system/frolov-crm.service
rm -f /tmp/frolov-crm.service
systemctl daemon-reload
systemctl enable --now frolov-crm

# Файрвол: SSH обязательно первым, иначе можно потерять доступ.
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp     >/dev/null
  ufw allow "${HTTP_PORT}/tcp" >/dev/null
  ufw --force enable   >/dev/null
fi

sleep 2
systemctl --no-pager --lines=15 status frolov-crm || true
REMOTE

echo
echo "==> Готово. Проверка:"
echo "    http://${HOST}:${HTTP_PORT}/"
echo "    curl http://${HOST}:${HTTP_PORT}/api/v1/health"
