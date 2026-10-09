#!/usr/bin/env bash
# Выкладывает свежую сборку приложения на сервер для автообновления.
#
#   bash deploy/publish-apk.sh 91.184.246.64 ["Что нового: строка для полоски"]
#
# Берёт APK из app/release (туда кладёт «Generate Signed APK» Android Studio)
# или из app/build/outputs/apk/release (./gradlew :app:assembleRelease) —
# какой новее. Версию читает из output-metadata.json рядом с APK, а не из
# build.gradle.kts: так она точно совпадает с тем, что внутри файла.
# Приложение на телефонах увидит обновление, только если versionCode вырос.
set -euo pipefail

HOST="${1:-}"
NOTES="${2:-}"
SSH_USER="${SSH_USER:-root}"
REMOTE_APP_DIR="/opt/frolov-crm/data/app"

if [[ -z "$HOST" ]]; then
  echo "Использование: $0 <адрес-сервера> [\"что нового\"]" >&2
  exit 1
fi

cd "$(dirname "$0")/../.."

APK=""
for candidate in app/release/app-release.apk app/build/outputs/apk/release/app-release.apk; do
  if [[ -s "$candidate" && ( -z "$APK" || "$candidate" -nt "$APK" ) ]]; then
    APK="$candidate"
  fi
done
if [[ -z "$APK" ]]; then
  echo "Не найден app-release.apk — соберите release-сборку." >&2
  exit 1
fi
META="$(dirname "$APK")/output-metadata.json"
if [[ ! -s "$META" ]]; then
  echo "Рядом с $APK нет output-metadata.json — не понять, какая это версия." >&2
  exit 1
fi

VERSION_CODE="$(grep -o '"versionCode": *[0-9]*' "$META" | head -1 | grep -o '[0-9]*$')"
VERSION_NAME="$(grep -o '"versionName": *"[^"]*"' "$META" | head -1 | sed 's/.*: *"\(.*\)"/\1/')"
if [[ -z "$VERSION_CODE" ]]; then
  echo "В $META не нашёлся versionCode." >&2
  exit 1
fi

SIZE="$(wc -c < "$APK" | tr -d ' ')"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Кавычки и обратные слеши в заметке ломали бы JSON.
NOTES_JSON="$(printf '%s' "$NOTES" | sed 's/\\/\\\\/g; s/"/\\"/g')"

STAGE=".deploy-apk"
rm -rf "$STAGE"
mkdir -p "$STAGE"
trap 'rm -rf "$STAGE"' EXIT
cp "$APK" "$STAGE/frolov-crm.apk"
cat > "$STAGE/latest.json" <<JSON
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "notes": "${NOTES_JSON}",
  "size": ${SIZE},
  "sha256": "${SHA}",
  "publishedAt": "${NOW}"
}
JSON

echo "==> Выкладываю ${APK}: версия ${VERSION_NAME} (${VERSION_CODE}), $(du -h "$APK" | cut -f1)"
# Сначала APK, потом latest.json: иначе телефон мог бы увидеть новую
# версию раньше, чем докачан её файл.
tar czf - -C "$STAGE" . | ssh "${SSH_USER}@${HOST}" "
  set -e
  mkdir -p ${REMOTE_APP_DIR}
  tmp=\$(mktemp -d)
  tar xzf - -C \$tmp
  mv \$tmp/frolov-crm.apk ${REMOTE_APP_DIR}/frolov-crm.apk
  mv \$tmp/latest.json ${REMOTE_APP_DIR}/latest.json
  rm -rf \$tmp
  chown -R frolov:frolov ${REMOTE_APP_DIR}
"
echo "Готово: телефоны предложат обновиться при следующем открытии приложения."
