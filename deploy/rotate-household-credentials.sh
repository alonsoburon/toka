#!/usr/bin/env bash
# Rota el token de una persona y el invite code de su hogar, directamente en el SQLite.
# Pensado para el caso en que el seed (token/invite públicos en git) llegó a producción.
#
#   TOKA_DB             ruta en el HOST del toka.db (obligatoria)
#   $1 = PERSON_ID      persona cuyo token se rota (en el seed: 1)
#   $2 = HOUSEHOLD_ID   hogar cuyo invite code se rota (en el seed: 1)
#
# Hace, en orden:
#   1. respaldo verificado (backup-toka.sh + restore-check.sh); si falla, no sigue
#   2. auditoría: personas del hogar y actividad reciente, para ver si alguien entró
#   3. rotación en una sola transacción
#   4. imprime el token NUEVO una única vez (guárdalo; en la base solo queda su sha256)
#
# Los demás integrantes no se ven afectados (cada uno tiene su propio token). El teléfono
# que usaba el token viejo recibirá un 401 y volverá a la pantalla de entrada: ahí se
# entra con "Token" y el nuevo.
set -euo pipefail

DB="${TOKA_DB:?define TOKA_DB con la ruta del toka.db en el host}"
PERSON="${1:?uso: rotate-household-credentials.sh PERSON_ID HOUSEHOLD_ID}"
HOUSE="${2:?uso: rotate-household-credentials.sh PERSON_ID HOUSEHOLD_ID}"
HERE="$(cd "$(dirname "$0")" && pwd)"

[[ "$PERSON" =~ ^[0-9]+$ && "$HOUSE" =~ ^[0-9]+$ ]] || { echo "los ids deben ser numéricos" >&2; exit 1; }
[ -f "$DB" ] || { echo "no existe $DB" >&2; exit 1; }

q() { sqlite3 -batch -header -column "$DB" ".timeout 5000" "$1"; }

owner="$(sqlite3 "$DB" "SELECT household_id FROM people WHERE id = $PERSON;")"
[ "$owner" = "$HOUSE" ] || { echo "la persona $PERSON no pertenece al hogar $HOUSE (pertenece a '${owner:-nadie}')" >&2; exit 1; }

echo "== 1/4 Respaldo verificado =="
TOKA_DB="$DB" "$HERE/backup-toka.sh"
TOKA_DB="$DB" "$HERE/restore-check.sh"

echo
echo "== 2/4 Auditoría del hogar $HOUSE (¿entró alguien que no debía?) =="
q "SELECT id, name, created_at FROM people WHERE household_id = $HOUSE ORDER BY id;"
echo
echo "-- mutaciones por persona y día (últimos 30 días)"
q "SELECT person_id, substr(applied_at,1,10) AS dia, COUNT(*) AS n
   FROM sync_mutations WHERE household_id = $HOUSE AND applied_at > datetime('now','-30 day')
   GROUP BY 1,2 ORDER BY 2 DESC, 1;"
echo
echo "Revisa la lista: una persona que no reconozcas es un acceso indebido (bórrala con la app"
echo "o con DELETE /people/{id} después de rotar)."
read -r -p "¿Rotar ahora? [escribe ROTAR] " ans
[ "$ans" = "ROTAR" ] || { echo "cancelado, no se cambió nada"; exit 1; }

echo
echo "== 3/4 Rotación =="
# Mismo formato que auth.NewToken (32 bytes, base64 URL sin padding) y auth.NewInviteCode
# (10 caracteres del alfabeto sin I, O, 0, 1).
token="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
hash="$(printf '%s' "$token" | sha256sum | cut -d' ' -f1)"
invite="$(LC_ALL=C tr -dc 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789' < /dev/urandom | head -c 10 || true)"
[ "${#invite}" -eq 10 ] || { echo "no se pudo generar el invite" >&2; exit 1; }

# Todo por stdin: con argumentos, sqlite3 ejecuta solo los argumentos e ignora stdin.
sqlite3 -batch "$DB" <<SQL
.timeout 5000
BEGIN IMMEDIATE;
UPDATE people     SET token_hash  = '$hash',   updated_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now') WHERE id = $PERSON;
UPDATE households SET invite_code = '$invite', updated_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now') WHERE id = $HOUSE;
COMMIT;
SQL

# No se imprime nada como "listo" sin comprobar que la base realmente cambió.
got_hash="$(sqlite3 "$DB" "SELECT token_hash FROM people WHERE id = $PERSON;")"
got_invite="$(sqlite3 "$DB" "SELECT invite_code FROM households WHERE id = $HOUSE;")"
if [ "$got_hash" != "$hash" ] || [ "$got_invite" != "$invite" ]; then
  echo "ERROR: la rotación no quedó aplicada en la base. No uses el token de abajo." >&2
  echo "El respaldo previo sigue en ${TOKA_BACKUP_DIR:-~/backups/toka}." >&2
  exit 1
fi

echo
echo "== 4/4 Listo. Guarda esto ahora (no se vuelve a mostrar) =="
echo "  token nuevo de la persona $PERSON : $token"
echo "  invite code nuevo del hogar $HOUSE : $invite"
echo
echo "Comprueba que el token viejo ya no entra:"
echo "  curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer buvea-alonso-token-2026' https://toka.nuxapower.cl/me   # esperado: 401"
echo "y que el nuevo sí:"
echo "  curl -s -H \"Authorization: Bearer <token nuevo>\" https://toka.nuxapower.cl/me"
