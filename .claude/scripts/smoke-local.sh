#!/usr/bin/env bash
# Corre las smoke tests contra un server con base temporal, para no ensuciar toka.db
# (el hogar real "Buvea"). Uso: make smoke
set -u
cd "$(dirname "$0")/../.." || exit 1

PORT="${PORT:-3099}"
TMPDB="$(mktemp -u /tmp/toka-smoke-XXXXXX.db)"
BIN="/tmp/toka-smoke-bin"

go build -o "$BIN" . || exit 1

TOKA_DB="$TMPDB" "$BIN" -seed -port "$PORT" -env "" >/tmp/toka-smoke.log 2>&1 &
SRV=$!
trap 'kill "$SRV" 2>/dev/null; wait "$SRV" 2>/dev/null; rm -f "$TMPDB" "$TMPDB-shm" "$TMPDB-wal" "$BIN"' EXIT

for _ in $(seq 1 40); do
  [ "$(curl -s -m1 -o /dev/null -w '%{http_code}' "http://localhost:$PORT/healthz")" = "200" ] && break
  sleep 0.5
done

BASE="http://localhost:$PORT" bash .claude/scripts/smoke.sh; s1=$?
BASE="http://localhost:$PORT" bash .claude/scripts/smoke-sync.sh; s2=$?
exit $((s1 || s2))
