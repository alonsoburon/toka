#!/usr/bin/env bash
# Sube una copia de toka.db a R2 (tucunar-backups/toka/<fecha>/) con las mismas claves que
# pg-backup.sh, y borra lo que tenga más de 14 días. Lo invoca backup-toka.sh con la ruta
# del .gz como $1 (TOKA_BACKUP_UPLOAD).
set -euo pipefail

file="${1:?uso: upload-r2.sh ARCHIVO.gz}"
set -a; . "${R2_ENV:-$HOME/.config/tucunar/r2-backup.env}"; set +a

day="$(date -u +%F)"
dest="R2:tucunar-backups/toka/$day"

rclone copyto "$file" "$dest/$(basename "$file")" --s3-no-check-bucket
[ -f "$file.sha256" ] && rclone copyto "$file.sha256" "$dest/$(basename "$file").sha256" --s3-no-check-bucket

rclone delete "R2:tucunar-backups/toka/" --min-age 14d --s3-no-check-bucket
rclone rmdirs "R2:tucunar-backups/toka/" --leave-root --s3-no-check-bucket || true
echo "subido a $dest"
