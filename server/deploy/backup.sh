#!/usr/bin/env bash
# Резервная копия CRM: база и загруженные файлы.
# Запускается на сервере таймером frolov-crm-backup.timer от пользователя
# frolov; окружение (в частности FROLOV_DB) приходит из /etc/frolov-crm.env.
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/frolov-crm}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/frolov-crm}"
KEEP_DAYS="${KEEP_DAYS:-14}"

stamp="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

# Базу снимаем средствами самой SQLite: при journal_mode=WAL копия файла
# на ходу отстаёт от истины на непрожатый -wal, а то и вовсе непригодна.
"$APP_DIR/frolov-crm" backup "$BACKUP_DIR/frolov-$stamp.db"
gzip -9 -f "$BACKUP_DIR/frolov-$stamp.db"

# Загрузки: снимки по заказам и файловый архив. Без них копия базы
# восстановит записи, но не сами файлы — в архиве останутся пустые ссылки.
if [[ -d "$APP_DIR/data/uploads" ]]; then
  tar -czf "$BACKUP_DIR/uploads-$stamp.tar.gz" -C "$APP_DIR/data" uploads
fi

# Чистим старое. Удаляем только свои файлы и только с верхнего уровня:
# каталог общий с системными копиями dpkg, задевать их нельзя.
find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name 'frolov-*.db.gz' -o -name 'uploads-*.tar.gz' \) \
  -mtime +"$KEEP_DAYS" -delete

kept="$(find "$BACKUP_DIR" -maxdepth 1 -name 'frolov-*.db.gz' | wc -l)"
echo "копия $stamp готова: база $(du -h "$BACKUP_DIR/frolov-$stamp.db.gz" | cut -f1)"
echo "хранится копий: $kept, занято всего $(du -sh "$BACKUP_DIR" | cut -f1)"
