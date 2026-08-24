#!/usr/bin/env bash
# Сборка и развёртывание frolov-crm на сервер.
#
#   DOMAIN=v3002851.hosted-by-vdsina.ru ./deploy.sh 91.184.246.64
#
# Сервер получает сертификат Let's Encrypt сам и работает только по HTTPS.
# На 80-м порту не отдаётся ничего, кроме проверки владения доменом, —
# всё остальное перенаправляется на HTTPS. Закрывать 80-й нельзя:
# без него удостоверяющий центр не сможет продлить сертификат.
# Домен обязан заранее указывать на этот сервер: удостоверяющий центр
# проверяет владение, обращаясь по имени на 80-й порт.
set -euo pipefail

HOST="${1:-}"
SSH_USER="${SSH_USER:-root}"
APP_DIR="/opt/frolov-crm"
ENV_FILE="/etc/frolov-crm.env"
DOMAIN="${DOMAIN:-}"
HTTP_PORT="${HTTP_PORT:-80}"
ACME_EMAIL="${ACME_EMAIL:-}"

if [[ -z "$HOST" ]]; then
  echo "Использование: DOMAIN=имя.домена $0 <адрес-сервера>" >&2
  exit 1
fi

# Без домена сервис поднялся бы по незащищённому HTTP, а этого мы больше
# не допускаем. Для локальной отладки запускайте сервер напрямую.
if [[ -z "$DOMAIN" && "${ALLOW_PLAIN_HTTP:-}" != "yes" ]]; then
  cat >&2 <<'MSG'
Не задан DOMAIN — развёртывание остановлено.
Сервер работает только по HTTPS, а сертификат выдаётся на доменное имя.

  DOMAIN=v3002851.hosted-by-vdsina.ru ./deploy/deploy.sh 91.184.246.64

Если незащищённый запуск нужен осознанно, добавьте ALLOW_PLAIN_HTTP=yes.
MSG
  exit 1
fi

cd "$(dirname "$0")/.."

echo "==> Проверка кода перед отправкой"
go vet ./...
go test ./... >/dev/null

echo "==> Сборка linux/amd64"
VERSION="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /tmp/frolov-crm ./cmd/server

echo "==> Копирование на ${SSH_USER}@${HOST}"
scp /tmp/frolov-crm "${SSH_USER}@${HOST}:/tmp/frolov-crm.new"
scp deploy/frolov-crm.service "${SSH_USER}@${HOST}:/tmp/frolov-crm.service"

echo "==> Установка на сервере"
ssh "${SSH_USER}@${HOST}" \
  APP_DIR="$APP_DIR" ENV_FILE="$ENV_FILE" HTTP_PORT="$HTTP_PORT" \
  DOMAIN="$DOMAIN" ACME_EMAIL="$ACME_EMAIL" 'bash -s' <<'REMOTE'
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
FROLOV_TOKEN_TTL=12h
ENV
  chmod 600 "$ENV_FILE"
  echo "!!! Создан ${ENV_FILE}. Логин: admin, пароль: ${ADMIN_PASSWORD}"
  echo "!!! Запишите пароль — он больше нигде не выводится."
fi

# Домен дописываем (или обновляем) отдельно: он может появиться позже.
sed -i '/^FROLOV_DOMAIN=/d;/^FROLOV_ACME_EMAIL=/d' "$ENV_FILE"
if [[ -n "$DOMAIN" ]]; then
  echo "FROLOV_DOMAIN=${DOMAIN}" >> "$ENV_FILE"
  [[ -n "$ACME_EMAIL" ]] && echo "FROLOV_ACME_EMAIL=${ACME_EMAIL}" >> "$ENV_FILE"
  echo "==> HTTPS включён для ${DOMAIN}"
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
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  [[ -n "$DOMAIN" ]] && ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
fi

sleep 3
systemctl --no-pager --lines=20 status frolov-crm || true
REMOTE

echo
if [[ -n "$DOMAIN" ]]; then
  echo "==> Готово. Первый запрос выпустит сертификат, это занимает несколько секунд:"
  echo "    https://${DOMAIN}/"
  echo "    curl https://${DOMAIN}/api/v1/health"
else
  echo "==> Готово. Проверка:"
  echo "    http://${HOST}:${HTTP_PORT}/"
fi
