---
description: Verificación completa antes de dar un cambio por terminado — reglas, compilación Android e invariantes de Firestore
allowed-tools: Bash, Read, Grep, Glob, Agent
---

Verifica el estado actual del proyecto (arquitectura Firebase + Firestore), en este orden, y no
te detengas en el primer fallo (reporta todo junto al final):

1. `scripts/test-rules.sh` — levanta su propio emulador (9199/8185), prueba `firestore.rules` y lo
   apaga. Si los puertos están ocupados, repórtalo; **no mates procesos** (`pkill -f` está prohibido:
   el emulador de Finanzas usa 9099/8085).
2. `cd android && ./gradlew compileDebugKotlin`.
3. Lanza el subagente `toka-reviewer` sobre los archivos modificados (`git diff --name-only`) para
   revisar los invariantes: reglas que validan miembros/asignados, escrituras sin `await` de red,
   ids `String`, recurrencia en un solo `WriteBatch`.
4. Coherencia del modelo: campos de `Firestore.kt`/`Models.kt` contra `firestore.rules`
   (`hasOnly([...])` de plantillas y tareas) y contra la sección "Modelo de datos" de `CLAUDE.md`.
5. Solo si hay cambios en el backend legado (`main.go`, `internal/`, `db/`): `gofmt -l .`,
   `go build ./...`, `go vet ./...`, `go test ./...` y `make smoke`.

Reporta al final una tabla de paso/fallo por punto, con los fallos primero y el output real del
comando que falló. No arregles nada sin que se pida.
