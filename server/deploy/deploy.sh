#!/usr/bin/env bash
# Сборка и развёртывание frolov-crm на сервер.
#
#   DOMAIN=v3002851.hosted-by-vdsina.ru ./deploy/deploy.sh 91.184.246.64
#
# Всё уезжает одним SSH-соединением, поэтому пароль спрашивают один раз.
# Чтобы не спрашивали вовсе, разложите ключ:  ssh-copy-id root@<адрес>
#
# Сервер получает сертификат Let's Encrypt сам и работает только по HTTPS.
# На 80-м порту не отдаётся ничего, кроме проверки владения доменом, —
# всё остальное перенаправляется на HTTPS. Закрывать 80-й нельзя:
# без него удостоверяющий центр не сможет продлить сертификат.
# Домен обязан заранее указывать на этот сервер.
set -euo pipefail

HOST="${1:-}"
SSH_USER="${SSH_USER:-root}"
APP_DIR="/opt/frolov-crm"
ENV_FILE="/etc/frolov-crm.env"
DOMAIN="${DOMAIN:-}"
HTTP_PORT="${HTTP_PORT:-80}"
ACME_EMAIL="${ACME_EMAIL:-}"
REMOTE_DIR="/tmp/frolov-deploy"

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

# Скрипт можно звать откуда угодно: сами встаём в корень серверного модуля.
cd "$(dirname "$0")/.."

echo "==> Проверка кода перед отправкой"
go vet ./...
go test ./... >/dev/null

echo "==> Сборка linux/amd64"
VERSION="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o "$STAGE/frolov-crm" ./cmd/server
cp deploy/frolov-crm.service deploy/install-remote.sh "$STAGE/"
echo "    собран ${VERSION}, $(du -h "$STAGE/frolov-crm" | cut -f1)"

echo "==> Отправка и установка на ${SSH_USER}@${HOST} (пароль спросят один раз)"
# Файлы и установку отправляем одним соединением: архив идёт в stdin,
# а сам сценарий установки лежит внутри архива.
tar czf - -C "$STAGE" . | ssh "${SSH_USER}@${HOST}" "
  set -e
  rm -rf ${REMOTE_DIR}
  mkdir -p ${REMOTE_DIR}
  tar xzf - -C ${REMOTE_DIR}
  APP_DIR='${APP_DIR}' ENV_FILE='${ENV_FILE}' HTTP_PORT='${HTTP_PORT}' \
  DOMAIN='${DOMAIN}' ACME_EMAIL='${ACME_EMAIL}' \
  bash ${REMOTE_DIR}/install-remote.sh
"

echo
if [[ -n "$DOMAIN" ]]; then
  echo "==> Готово. Первый запрос выпустит сертификат, это занимает несколько секунд:"
  echo "    curl https://${DOMAIN}/api/v1/health"
  echo "    https://${DOMAIN}/"
else
  echo "==> Готово (без TLS). Проверка:  http://${HOST}:${HTTP_PORT}/"
fi
