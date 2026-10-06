#!/usr/bin/env bash
# Prueba de restauración: toma la copia más reciente (o la que pases como $1), la
# descomprime en un archivo temporal, verifica su hash e integridad y compara los
# conteos de tablas contra el toka.db vivo (si TOKA_DB está definida). Un respaldo
# que nunca se restauró no cuenta como respaldo.
set -euo pipefail

OUT_DIR="${TOKA_BACKUP_DIR:-$HOME/backups/toka}"
GZ="${1:-$(ls -1t "$OUT_DIR"/toka-*.db.gz 2>/dev/null | head -1)}"
[ -n "$GZ" ] && [ -f "$GZ" ] || { echo "no hay copias en $OUT_DIR" >&2; exit 1; }

if [ -f "$GZ.sha256" ]; then
  ( cd "$(dirname "$GZ")" && sha256sum -c "$(basename "$GZ").sha256" )
fi

TMP="$(mktemp -u /tmp/toka-restore-XXXXXX.db)"
trap 'rm -f "$TMP" "$TMP-wal" "$TMP-shm"' EXIT
gunzip -c "$GZ" > "$TMP"

[ "$(sqlite3 "$TMP" 'PRAGMA integrity_check;')" = "ok" ] || { echo "integrity_check falló" >&2; exit 1; }

counts() {
  for t in households people task_templates task_instances sync_mutations sync_tombstones; do
    printf '%s=%s ' "$t" "$(sqlite3 "$1" "SELECT COUNT(*) FROM $t;")"
  done
  echo
}

echo "restaurada ($GZ):"; counts "$TMP"
if [ -n "${TOKA_DB:-}" ] && [ -f "$TOKA_DB" ]; then
  echo "viva ($TOKA_DB):"; counts "$TOKA_DB"
  echo "(la viva puede ir por delante de la copia; lo importante es que la restaurada abre y tiene datos)"
fi
echo "OK: la copia se restaura y pasa integrity_check"
