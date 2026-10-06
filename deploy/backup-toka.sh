#!/usr/bin/env bash
# Copia consistente de toka.db sin parar el servicio.
#
#   TOKA_DB            ruta en el HOST del archivo SQLite (obligatoria)
#   TOKA_BACKUP_DIR    dónde dejar las copias (default ~/backups/toka)
#   TOKA_BACKUP_KEEP   cuántas copias locales conservar (default 14)
#   TOKA_BACKUP_UPLOAD ejecutable opcional que recibe la ruta del .gz (p. ej. un script
#                      que sube a R2 con las mismas claves que pg-backup.sh)
#
# `sqlite3 .backup` usa la API de backup online: es seguro con el servidor escribiendo
# en WAL, no hace falta pararlo ni copiar -wal/-shm a mano. Requiere el paquete sqlite3.
set -euo pipefail

DB="${TOKA_DB:?define TOKA_DB con la ruta del toka.db en el host}"
OUT_DIR="${TOKA_BACKUP_DIR:-$HOME/backups/toka}"
KEEP="${TOKA_BACKUP_KEEP:-14}"

[ -f "$DB" ] || { echo "no existe $DB" >&2; exit 1; }
mkdir -p "$OUT_DIR"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
snap="$OUT_DIR/toka-$stamp.db"
trap 'rm -f "$snap"' EXIT

sqlite3 "$DB" ".timeout 5000" ".backup '$snap'"

check="$(sqlite3 "$snap" 'PRAGMA integrity_check;')"
if [ "$check" != "ok" ]; then
  echo "integrity_check falló: $check" >&2
  exit 1
fi

gzip -9 -c "$snap" > "$snap.gz"
( cd "$OUT_DIR" && sha256sum "toka-$stamp.db.gz" > "toka-$stamp.db.gz.sha256" )
echo "copia lista: $snap.gz ($(stat -c %s "$snap.gz") bytes)"

if [ -n "${TOKA_BACKUP_UPLOAD:-}" ]; then
  "$TOKA_BACKUP_UPLOAD" "$snap.gz"
  echo "subida con $TOKA_BACKUP_UPLOAD"
fi

# Retención local: se quedan las KEEP más recientes.
ls -1t "$OUT_DIR"/toka-*.db.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r old; do
  rm -f "$old" "$old.sha256"
done
