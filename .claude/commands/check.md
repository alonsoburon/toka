---
description: Verificación completa antes de dar un cambio por terminado — build, vet, revisión de invariantes y smoke test
allowed-tools: Bash, Read, Grep, Glob, Agent
---

Verifica el estado actual del proyecto, en este orden, y no te detengas en el primer fallo
(reporta todo junto al final):

1. `gofmt -l .` — debe salir vacío.
2. `go build ./...`
3. `go vet ./...`
4. `go test ./...`.
5. Contrasta los handlers exportados de `internal/handler/` contra las rutas registradas en
   `internal/server/server.go`. Lista cualquier handler sin ruta y cualquier ruta que la app
   Android llame y el backend no exponga (`android/.../data/api/TokaApi.kt`).
6. Lanza el subagente `toka-reviewer` sobre los archivos Go modificados para revisar los
   invariantes del proyecto.
7. `make smoke` — levanta un server propio con una base temporal (no toca `toka.db`) y
   corre `smoke.sh` y `smoke-sync.sh`. No necesita que haya nada arriba.
8. Si hay algo en `android/` modificado: `cd android && ./gradlew compileDebugKotlin`.

Reporta al final una tabla de paso/fallo por punto, con los fallos primero y el output real
del comando que falló. No arregles nada sin que se te pida.
