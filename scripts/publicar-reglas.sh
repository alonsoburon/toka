#!/usr/bin/env bash
# Publica firestore.rules en el proyecto Firebase de Toka (sin firebase CLI, vía API de Rules).
# Variables: FIREBASE_PROJECT (default toka-hogar-037f), GCLOUD_ACCOUNT (default alonso@tucunar.com).
set -euo pipefail
P="${FIREBASE_PROJECT:-toka-hogar-037f}"
CUENTA="${GCLOUD_ACCOUNT:-alonso@tucunar.com}"
cd "$(dirname "$0")/.."
T=$(gcloud auth print-access-token --account="$CUENTA")
API="https://firebaserules.googleapis.com/v1/projects/${P}"
BODY=$(python3 -c 'import json;print(json.dumps({"source":{"files":[{"name":"firestore.rules","content":open("firestore.rules").read()}]}}))')
RULESET=$(curl -sf -X POST -H "Authorization: Bearer $T" -H "x-goog-user-project: ${P}" -H "Content-Type: application/json" \
  "${API}/rulesets" -d "$BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin)["name"])')
# La primera vez la release no existe: se crea; después se actualiza.
REL="projects/${P}/releases/cloud.firestore"
if ! curl -sf -X PATCH -H "Authorization: Bearer $T" -H "x-goog-user-project: ${P}" -H "Content-Type: application/json" \
  "${API}/releases/cloud.firestore" -d "{\"release\":{\"name\":\"${REL}\",\"rulesetName\":\"${RULESET}\"}}" >/dev/null; then
  curl -sf -X POST -H "Authorization: Bearer $T" -H "x-goog-user-project: ${P}" -H "Content-Type: application/json" \
    "${API}/releases" -d "{\"name\":\"${REL}\",\"rulesetName\":\"${RULESET}\"}" >/dev/null
fi
echo "Reglas publicadas en ${P}: ${RULESET}"
