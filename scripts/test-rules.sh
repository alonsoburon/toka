#!/usr/bin/env bash
# Levanta SU PROPIO emulador de Firebase (puertos 9199/8185, distintos de los de Finanzas),
# prueba firestore.rules y lo apaga. Necesita Java 21+ (el JDK por defecto es 17) y npx.
#
# Importante: el emulador se detiene por PID/grupo de proceso, NUNCA con `pkill -f`: un patrón por
# nombre también mataría emuladores de otros proyectos que estén corriendo en esta máquina.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA21="${JAVA21_HOME:-/usr/lib/jvm/java-26-openjdk}"
LOG=/tmp/toka-firebase-test.log

# Si el puerto ya responde, es de otro proceso: no se comparte (correría contra reglas ajenas).
if curl -s -o /dev/null http://127.0.0.1:8185 || curl -s -o /dev/null http://127.0.0.1:9199; then
  echo "Los puertos 8185/9199 ya están en uso por otro proceso; ciérralo o cambia firebase.json." >&2
  exit 1
fi

echo "Levantando emuladores de Firebase…"
JAVA_HOME="$JAVA21" PATH="$JAVA21/bin:$PATH" setsid nohup \
  npx -y firebase-tools@15 emulators:start --only auth,firestore --project demo-toka >"$LOG" 2>&1 < /dev/null &
EMU_PID=$!
stop() { kill -INT -- "-$EMU_PID" 2>/dev/null || true; }
trap stop EXIT

for _ in $(seq 1 90); do
  curl -s -o /dev/null http://127.0.0.1:9199 && curl -s -o /dev/null http://127.0.0.1:8185 && break
  sleep 2
done
curl -s -o /dev/null http://127.0.0.1:8185 || { echo "No arrancaron; mira $LOG" >&2; exit 1; }

python3 scripts/test-rules.py
