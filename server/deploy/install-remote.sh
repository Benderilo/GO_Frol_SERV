#!/usr/bin/env bash
# Устанавливает frolov-crm. Запускается НА СЕРВЕРЕ скриптом deploy.sh,
# который заранее кладёт рядом бинарник и systemd-юнит.
#
# Ожидает переменные: APP_DIR, ENV_FILE, HTTP_PORT, DOMAIN, ACME_EMAIL.
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/frolov-crm}"
ENV_FILE="${ENV_FILE:-/etc/frolov-crm.env}"
HTTP_PORT="${HTTP_PORT:-80}"
DOMAIN="${DOMAIN:-}"
ACME_EMAIL="${ACME_EMAIL:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"

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
  echo
  echo "!!! Создан ${ENV_FILE}"
  echo "!!! Логин: admin   Пароль: ${ADMIN_PASSWORD}"
  echo "!!! Запишите пароль — он больше нигде не выводится."
  echo "!!! Если сейчас перенесёте базу со старого сервера, войти нужно будет"
  echo "!!! старым паролем: он хранится в базе, а не в этом файле."
  echo
fi

# Домен дописываем отдельно: он может появиться или смениться позже.
sed -i '/^FROLOV_DOMAIN=/d;/^FROLOV_ACME_EMAIL=/d' "$ENV_FILE"
if [[ -n "$DOMAIN" ]]; then
  echo "FROLOV_DOMAIN=${DOMAIN}" >> "$ENV_FILE"
  [[ -n "$ACME_EMAIL" ]] && echo "FROLOV_ACME_EMAIL=${ACME_EMAIL}" >> "$ENV_FILE"
  echo "==> HTTPS включён для ${DOMAIN}"
fi

systemctl stop frolov-crm 2>/dev/null || true
install -m 0755 -o frolov -g frolov "$HERE/frolov-crm" "$APP_DIR/frolov-crm"
install -m 0644 "$HERE/frolov-crm.service" /etc/systemd/system/frolov-crm.service
chown -R frolov:frolov "$APP_DIR"

systemctl daemon-reload
systemctl enable --now frolov-crm

# Файрвол: SSH обязательно первым, иначе можно потерять доступ к серверу.
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  [[ -n "$DOMAIN" ]] && ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
  echo "==> Файрвол: открыты 22, 80${DOMAIN:+ и 443}"
fi

rm -rf "$HERE"

sleep 3
systemctl --no-pager --lines=20 status frolov-crm || true
