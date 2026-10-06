#!/usr/bin/env bash
# Desarrollo local sin Google ni Firebase reales:
#   1. emuladores de Firebase de Toka (Auth :9199, Firestore :8185, UI http://127.0.0.1:4100) con las mismas
#      firestore.rules; los datos persisten en .emulador/ entre sesiones,
#   2. emulador Android (AVD pixel_36, `emu`) o un teléfono (DISPOSITIVO=<serial>),
#   3. compila el debug una vez, lo instala ("Toka DEV", com.toka.app.dev) y lo abre.
#      En la app: "Entrar como Ana (dev)" / "Entrar como Beto (dev)".
# Uso: scripts/dev.sh [--sin-compilar]      Detener los emuladores de Firebase: scripts/dev.sh --parar
#
# Los emuladores se detienen por PID, nunca con `pkill -f`: en esta máquina corren también los de
# Finanzas (9099/8085) y un patrón por nombre los mataría.
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="$HOME/Android/Sdk/platform-tools/adb"
LOG=/tmp/toka-firebase-emuladores.log
PIDFILE=/tmp/toka-firebase-emuladores.pid
JAVA21="${JAVA21_HOME:-/usr/lib/jvm/java-26-openjdk}"

if [ "${1:-}" = "--parar" ]; then
  if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    kill -INT -- "-$(cat "$PIDFILE")" && rm -f "$PIDFILE" && echo "Emuladores de Firebase de Toka detenidos (datos en .emulador/)"
  else
    echo "No estaban corriendo (o no los levantó este script)"
  fi
  exit 0
fi

# 1. Emuladores de Firebase (necesitan Java 21+; el JDK por defecto es 17).
if ! curl -s -o /dev/null http://127.0.0.1:8185; then
  if curl -s -o /dev/null http://127.0.0.1:9199; then
    echo "El puerto 9199 está ocupado por otro proceso; ciérralo o cambia firebase.json." >&2
    exit 1
  fi
  echo "Levantando emuladores de Firebase de Toka…"
  mkdir -p .emulador
  JAVA_HOME="$JAVA21" PATH="$JAVA21/bin:$PATH" setsid nohup \
    npx -y firebase-tools@15 emulators:start --only auth,firestore \
      --project demo-toka --import .emulador --export-on-exit .emulador >"$LOG" 2>&1 < /dev/null &
  echo $! > "$PIDFILE"
  for _ in $(seq 1 90); do
    curl -s -o /dev/null http://127.0.0.1:9199 && curl -s -o /dev/null http://127.0.0.1:8185 && break
    sleep 2
  done
  curl -s -o /dev/null http://127.0.0.1:8185 || { echo "No arrancaron; mira $LOG" >&2; exit 1; }
fi
echo "Firebase local listo (UI: http://127.0.0.1:4100)"

# 2. Dispositivo: el emulador Android, o un teléfono con DISPOSITIVO=<serial> y la IP del PC en la LAN.
if [ -z "${DISPOSITIVO:-}" ]; then
  command -v emu >/dev/null 2>&1 && emu
  DISPOSITIVO=emulator-5554
  GRADLE_EXTRA=()
else
  IP_PC=$(ip -4 route get 1.1.1.1 | grep -oE 'src [0-9.]+' | cut -d' ' -f2)
  GRADLE_EXTRA=(-Ptoka.emulador="$IP_PC")
  echo "Teléfono $DISPOSITIVO → emuladores en $IP_PC"
fi

# 3. App debug (una sola compilación).
[ "${1:-}" = "--sin-compilar" ] || (cd android && ./gradlew :app:assembleDebug "${GRADLE_EXTRA[@]}" --console=plain -q)
APK=android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$DISPOSITIVO" install -r "$APK" | tail -1
"$ADB" -s "$DISPOSITIVO" shell am start -n com.toka.app.dev/com.toka.app.MainActivity >/dev/null
echo "Toka DEV abierta en $DISPOSITIVO"
