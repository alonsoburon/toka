---
description: Levanta los emuladores de Firebase de Toka y la app debug para probar
allowed-tools: Bash
---

Arranca el entorno de desarrollo de Toka (Firebase local, sin servidor propio ni Google real).

1. Comprueba si los emuladores ya están arriba: `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8185`
   (Firestore) y `:9199` (Auth). Son los de Toka; los de Finanzas (9099/8085) son otro proyecto y
   **no se tocan**.
2. Corre `scripts/dev.sh` (emuladores con `firestore.rules`, datos persistentes en `.emulador/`,
   emulador Android y app "Toka DEV" `com.toka.app.dev`). Añade `--sin-compilar` si el APK ya está
   al día. Para un teléfono real: `DISPOSITIVO=<serial> scripts/dev.sh`. Necesita Java 21+ y `npx`.
3. Reporta en dos líneas: estado de los emuladores (UI en http://127.0.0.1:4100) y dónde quedó
   instalada la app. En la app: "Entrar como Ana (dev)" / "Entrar como Beto (dev)".

Para detenerlos: `scripts/dev.sh --parar` (por PID, nunca `pkill -f`). No borres `.emulador/`
(sus datos) salvo que se pida. El backend Go legado se levanta aparte con `make run`.
