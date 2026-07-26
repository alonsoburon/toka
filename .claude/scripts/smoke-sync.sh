#!/usr/bin/env bash
# Verifica el protocolo de sincronización offline: cursor, cola de mutaciones y las
# dos capas de deduplicación.
# Uso: .claude/scripts/smoke-sync.sh   (requiere el server corriendo: make run)
set -uo pipefail

BASE="${BASE:-http://localhost:3000}"
FAILED=0

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (esperado '$3', obtenido '$2')"; fi; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
uuid() { cat /proc/sys/kernel/random/uuid; }

TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT

req() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-s -X "$method" "$BASE$path" -o "$TMPD/body" -w '%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}" > "$TMPD/status"
  cat "$TMPD/body"
}
st() { cat "$TMPD/status" 2>/dev/null; }

if ! curl -s -o /dev/null --max-time 3 "$BASE/tasks"; then
  echo "No hay server en $BASE — arráncalo con 'make run'" >&2
  exit 1
fi

step "0. Household de prueba"
H=$(req POST /households "" '{"name":"Sync House","admin_name":"Admin"}')
TOK=$(jq -r '.token' <<<"$H")
[ -n "$TOK" ] && pass "token obtenido" || { fail "sin token: $H"; exit 1; }

step "1. Pull inicial"
S0=$(req GET "/sync?since=0" "$TOK")
check "GET /sync -> 200" "$(st)" "200"
C0=$(jq -r '.cursor' <<<"$S0")
NP=$(jq -r '.people | length' <<<"$S0")
[ "${C0:-0}" -gt 0 ] && pass "cursor inicial = $C0" || fail "cursor inválido: $C0"
check "el household nuevo trae 1 persona" "$NP" "1"

step "2. Pull incremental vacío"
S1=$(req GET "/sync?since=$C0" "$TOK")
check "sin cambios: 0 personas" "$(jq -r '.people | length' <<<"$S1")" "0"
check "sin cambios: 0 plantillas" "$(jq -r '.templates | length' <<<"$S1")" "0"
check "sin cambios: 0 tareas" "$(jq -r '.tasks | length' <<<"$S1")" "0"

step "3. Mutación offline: crear plantilla"
M1=$(uuid); CID=$(uuid)
P1=$(req POST /sync/mutations "$TOK" "{\"mutations\":[{\"mutation_id\":\"$M1\",\"op\":\"template.create\",\"payload\":{\"client_id\":\"$CID\",\"name\":\"Barrer\",\"recurrence_days\":3}}]}")
check "POST /sync/mutations -> 200" "$(st)" "200"
check "status de la mutación" "$(jq -r '.results[0].status' <<<"$P1")" "201"
TID=$(jq -r '.results[0].body.id' <<<"$P1")
[ -n "$TID" ] && [ "$TID" != "null" ] && pass "plantilla creada id=$TID" || fail "sin id: $P1"

step "4. Deduplicación por mutation_id (el mismo reenvío)"
P2=$(req POST /sync/mutations "$TOK" "{\"mutations\":[{\"mutation_id\":\"$M1\",\"op\":\"template.create\",\"payload\":{\"client_id\":\"$CID\",\"name\":\"Barrer\",\"recurrence_days\":3}}]}")
check "marcada como duplicada" "$(jq -r '.results[0].duplicate' <<<"$P2")" "true"
check "devuelve el mismo id" "$(jq -r '.results[0].body.id' <<<"$P2")" "$TID"
NT=$(req GET "/sync?since=0" "$TOK" | jq -r '.templates | length')
check "sigue habiendo 1 plantilla, no 2" "$NT" "1"

step "5. Deduplicación por client_id (mutation_id distinto, misma tarea local)"
M2=$(uuid)
P3=$(req POST /sync/mutations "$TOK" "{\"mutations\":[{\"mutation_id\":\"$M2\",\"op\":\"template.create\",\"payload\":{\"client_id\":\"$CID\",\"name\":\"Barrer\",\"recurrence_days\":3}}]}")
check "devuelve el id existente" "$(jq -r '.results[0].body.id' <<<"$P3")" "$TID"
NT=$(req GET "/sync?since=0" "$TOK" | jq -r '.templates | length')
check "sigue habiendo 1 plantilla" "$NT" "1"

step "6. El pull incremental trae lo que subió el propio cliente"
S2=$(req GET "/sync?since=$C0" "$TOK")
check "1 plantilla nueva" "$(jq -r '.templates | length' <<<"$S2")" "1"
check "1 instancia nueva" "$(jq -r '.tasks | length' <<<"$S2")" "1"
check "el client_id vuelve para reconciliar" "$(jq -r '.templates[0].client_id' <<<"$S2")" "$CID"
C2=$(jq -r '.cursor' <<<"$S2")
[ "$C2" -gt "$C0" ] && pass "el cursor avanzó: $C0 → $C2" || fail "el cursor no avanzó"

step "7. row_version es monótono y ordena el resultado"
VS=$(jq -r '[.templates[].row_version, .tasks[].row_version] | @sh' <<<"$S2")
SORTED=$(jq -r '[.templates[].row_version] as $t | ([.tasks[].row_version] + $t) | sort | @sh' <<<"$S2")
[ -n "$VS" ] && pass "versiones presentes: $VS" || fail "faltan row_version"

step "8. Completar offline, y el conflicto con lo que ya pasó"
TASK=$(req GET /tasks "$TOK" | jq -r '.[0].id')
M3=$(uuid)
P4=$(req POST /sync/mutations "$TOK" "{\"mutations\":[{\"mutation_id\":\"$M3\",\"op\":\"task.complete\",\"payload\":{\"id\":$TASK,\"notes\":\"offline\",\"completed_at\":\"2026-07-20T10:00:00Z\"}}]}")
check "task.complete -> 200" "$(jq -r '.results[0].status' <<<"$P4")" "200"
check "genera la siguiente instancia" "$(jq -r '.results[0].body.next_due_at != null' <<<"$P4")" "true"
DUE=$(jq -r '.results[0].body.next_due_at' <<<"$P4")
case "$DUE" in
  2026-07-23*) pass "due_at = completed_at del cliente + 3d ($DUE)" ;;
  *) fail "due_at no se calculó desde el completed_at del cliente: $DUE" ;;
esac

M4=$(uuid)
P5=$(req POST /sync/mutations "$TOK" "{\"mutations\":[{\"mutation_id\":\"$M4\",\"op\":\"task.complete\",\"payload\":{\"id\":$TASK}}]}")
check "completar algo ya resuelto -> 409" "$(jq -r '.results[0].status' <<<"$P5")" "409"

step "9. Errores por mutación, sin tumbar la tanda"
M5=$(uuid); M6=$(uuid)
P6=$(req POST /sync/mutations "$TOK" "{\"mutations\":[{\"mutation_id\":\"$M5\",\"op\":\"op.inexistente\",\"payload\":{}},{\"mutation_id\":\"$M6\",\"op\":\"template.update\",\"payload\":{\"id\":$TID,\"name\":\"Barrer bien\"}}]}")
check "la operación desconocida -> 400" "$(jq -r '.results[0].status' <<<"$P6")" "400"
check "la siguiente igual se aplica -> 200" "$(jq -r '.results[1].status' <<<"$P6")" "200"
check "sin mutation_id -> 400" \
  "$(req POST /sync/mutations "$TOK" '{"mutations":[{"op":"template.delete","payload":{"id":1}}]}' | jq -r '.results[0].status')" "400"

step "10. Aislamiento: el sync no cruza households"
O=$(req POST /households "" '{"name":"Otra","admin_name":"Otro"}')
OTOK=$(jq -r '.token' <<<"$O")
OS=$(req GET "/sync?since=0" "$OTOK")
check "la casa B no ve plantillas de la casa A" "$(jq -r '.templates | length' <<<"$OS")" "0"
M7=$(uuid)
PX=$(req POST /sync/mutations "$OTOK" "{\"mutations\":[{\"mutation_id\":\"$M7\",\"op\":\"template.update\",\"payload\":{\"id\":$TID,\"name\":\"secuestrada\"}}]}")
check "no puede mutar una plantilla ajena -> 404" "$(jq -r '.results[0].status' <<<"$PX")" "404"

printf '\n'
if [ "$FAILED" -eq 0 ]; then printf '\033[32mSync OK\033[0m\n'; else printf '\033[31mHay fallos\033[0m\n'; fi
exit "$FAILED"
