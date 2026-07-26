#!/usr/bin/env bash
# Smoke test del flujo completo de la API de Toka.
# Uso: .claude/scripts/smoke.sh    (requiere el server corriendo: make run)
set -uo pipefail

BASE="${BASE:-http://localhost:3000}"
FAILED=0

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (esperado '$3', obtenido '$2')"; fi; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT

# req METHOD PATH [TOKEN] [BODY] -> body en stdout; el status queda en $(st)
# (req se llama dentro de $(...), o sea en un subshell: el status va por archivo,
#  no por variable, o se perdería al volver)
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

# ── 1. Onboarding ─────────────────────────────────────────────────────────────
step "1. Crear household + admin"
HOUSE=$(req POST /households "" '{"name":"Smoke House","admin_name":"Admin","admin_color":"#f472b6","admin_emoji":"🐱"}')
check "POST /households -> 201" "$(st)" "201"
ADMIN_TOKEN=$(jq -r '.token // empty' <<<"$HOUSE")
INVITE=$(jq -r '.invite_code // empty' <<<"$HOUSE")
HID=$(jq -r '.id // empty' <<<"$HOUSE")
[ -n "$ADMIN_TOKEN" ] && pass "devuelve token de admin" || fail "sin token de admin: $HOUSE"
[ -n "$INVITE" ] && pass "devuelve invite_code" || fail "sin invite_code: $HOUSE"

step "2. Unirse con el invite_code"
JOINED=$(req POST /households/join "" "{\"invite_code\":\"$INVITE\",\"name\":\"Invitada\",\"color\":\"#60a5fa\",\"emoji\":\"🐶\"}")
check "POST /households/join -> 201" "$(st)" "201"
GUEST_TOKEN=$(jq -r '.token // empty' <<<"$JOINED")
if [ -n "$GUEST_TOKEN" ] && [ "$GUEST_TOKEN" != "$ADMIN_TOKEN" ]; then
  pass "token distinto para la persona nueva"
else
  fail "token del invitado ausente o igual al del admin"
fi

step "3. Auth obligatoria"
req GET /tasks >/dev/null
check "GET /tasks sin token -> 401" "$(st)" "401"
req GET /tasks "token-basura-no-existe" >/dev/null
check "GET /tasks con token inválido -> 401" "$(st)" "401"
[ -n "$HID" ] && { req GET "/households/$HID/people" "$ADMIN_TOKEN" >/dev/null; check "GET people con token -> 200" "$(st)" "200"; }

# ── 2. Plantilla recurrente ───────────────────────────────────────────────────
step "4. Crear plantilla recurrente (7 días)"
TPL=$(req POST /templates "$ADMIN_TOKEN" '{"name":"Sacar la basura","description":"smoke","recurrence_days":7}')
check "POST /templates -> 201" "$(st)" "201"
TPL_ID=$(jq -r '.id // empty' <<<"$TPL")
[ -n "$TPL_ID" ] && pass "plantilla id=$TPL_ID" || fail "sin id de plantilla: $TPL"

step "5. La plantilla generó su primera instancia"
TASKS=$(req GET /tasks "$ADMIN_TOKEN")
check "GET /tasks -> 200" "$(st)" "200"
TASK_ID=$(jq -r --argjson t "${TPL_ID:-0}" 'map(select(.template_id == $t and .status == "pending")) | .[0].id // empty' <<<"$TASKS")
[ -n "$TASK_ID" ] && pass "instancia pending id=$TASK_ID" || fail "la plantilla no generó instancia pending"

# ── 3. Recurrencia ────────────────────────────────────────────────────────────
step "6. Completar → se genera la siguiente instancia"
if [ -n "$TASK_ID" ]; then
  DONE=$(req POST "/tasks/$TASK_ID/complete" "$ADMIN_TOKEN" '{"notes":"smoke"}')
  check "POST /tasks/$TASK_ID/complete -> 200" "$(st)" "200"

  AFTER=$(req GET /tasks "$ADMIN_TOKEN")
  NEXT=$(jq -r --argjson t "$TPL_ID" --argjson old "$TASK_ID" \
    'map(select(.template_id == $t and .status == "pending" and .id != $old)) | .[0] // empty' <<<"$AFTER")
  if [ -n "$NEXT" ]; then
    pass "nueva instancia pending generada"
    DUE=$(jq -r '.due_at' <<<"$NEXT")
    DUE_EPOCH=$(date -d "$DUE" +%s 2>/dev/null || echo 0)
    EXPECTED=$(( $(date +%s) + 7*86400 ))
    DELTA=$(( DUE_EPOCH > EXPECTED ? DUE_EPOCH - EXPECTED : EXPECTED - DUE_EPOCH ))
    if [ "$DUE_EPOCH" -ne 0 ] && [ "$DELTA" -lt 3600 ]; then
      pass "due_at ≈ ahora + 7d (delta ${DELTA}s, se cuenta desde el completado)"
    else
      fail "due_at fuera de rango: $DUE (delta ${DELTA}s)"
    fi
  else
    fail "completar no generó la siguiente instancia"
  fi
fi

step "7. One-shot no regenera"
TPL1=$(req POST /templates "$ADMIN_TOKEN" '{"name":"Pintar la sala","description":"smoke one-shot"}')
TPL1_ID=$(jq -r '.id // empty' <<<"$TPL1")
if [ -n "$TPL1_ID" ]; then
  T1=$(req GET /tasks "$ADMIN_TOKEN")
  T1_ID=$(jq -r --argjson t "$TPL1_ID" 'map(select(.template_id == $t)) | .[0].id // empty' <<<"$T1")
  if [ -n "$T1_ID" ]; then
    req POST "/tasks/$T1_ID/complete" "$ADMIN_TOKEN" '{}' >/dev/null
    AFTER1=$(req GET /tasks "$ADMIN_TOKEN")
    LEFT=$(jq -r --argjson t "$TPL1_ID" 'map(select(.template_id == $t and .status == "pending")) | length' <<<"$AFTER1")
    check "one-shot: 0 instancias pending tras completar" "$LEFT" "0"
  else
    fail "la plantilla one-shot no generó instancia"
  fi
fi

step "8. Historial"
HIST=$(req GET "/tasks/history?days=30" "$ADMIN_TOKEN")
check "GET /tasks/history -> 200" "$(st)" "200"
NDONE=$(jq -r 'map(select(.status == "done")) | length' <<<"$HIST" 2>/dev/null || echo 0)
[ "${NDONE:-0}" -ge 1 ] && pass "historial incluye $NDONE completada(s)" || fail "historial sin tareas completadas"

# ── 4. Aislamiento entre households ───────────────────────────────────────────
step "9. Aislamiento entre households"
OTHER=$(req POST /households "" '{"name":"Otra Casa","admin_name":"Otro","admin_color":"#a3e635","admin_emoji":"🦊"}')
OTHER_TOKEN=$(jq -r '.token // empty' <<<"$OTHER")
if [ -n "$OTHER_TOKEN" ]; then
  OTHER_TASKS=$(req GET /tasks "$OTHER_TOKEN")
  LEAK=$(jq -r --argjson t "${TPL_ID:-0}" 'map(select(.template_id == $t)) | length' <<<"$OTHER_TASKS" 2>/dev/null || echo "?")
  check "la casa B no ve las tareas de la casa A" "$LEAK" "0"
  OTHER_TPLS=$(req GET /templates "$OTHER_TOKEN")
  LEAK2=$(jq -r --argjson t "${TPL_ID:-0}" 'map(select(.id == $t)) | length' <<<"$OTHER_TPLS" 2>/dev/null || echo "?")
  check "la casa B no ve las plantillas de la casa A" "$LEAK2" "0"
fi

printf '\n'
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32mTodo verde\033[0m\n'
else
  printf '\033[31mHay fallos\033[0m\n'
fi
exit "$FAILED"
