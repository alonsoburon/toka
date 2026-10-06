# AGENTS.md — Toka

App Android (Kotlin + Compose) de tareas domésticas compartidas que usa **Firebase Auth
(Google) + Firestore directamente**, sin servidor propio. El contexto profundo (modelo de datos,
reglas, sesión, recurrencia) vive en **`CLAUDE.md`**: léelo antes de tocar nada.

El backend Go + SQLite (`main.go`, `internal/`, `db/`, `deploy/`, `Makefile`) es **legado**: sigue
en producción (`https://toka.nuxapower.cl`) hasta migrar los datos y retirarlo. No se le añaden
features. Las skills `/migration`, `/endpoint`, `/smoke` y `/schema` aplican solo a él.

## Comandos

```bash
scripts/test-rules.sh                        # prueba firestore.rules (emulador propio)
scripts/dev.sh                               # emuladores Firebase + emulador Android + "Toka DEV"
scripts/dev.sh --parar                       # detiene los emuladores de Toka
scripts/publicar-reglas.sh                   # publica reglas en toka-hogar-e194 (solo si se pide)
cd android && ./gradlew compileDebugKotlin   # verificación rápida
cd android && ./gradlew assembleDebug
```

Emuladores de Toka: Auth 9199 / Firestore 8185 / UI 4100. Finanzas usa 9099 / 8085: **nunca los
compartas ni los mates (nada de `pkill -f`)**. La build debug es `com.toka.app.dev` y usa los
emuladores; la release es `com.toka.app` y necesita `android/app/google-services.json` real.

## Verificación

1. `scripts/test-rules.sh` si tocaste reglas, modelo o escrituras.
2. `cd android && ./gradlew compileDebugKotlin`.
3. Si tocaste el backend legado: `gofmt -l .`, `go build ./...`, `go vet ./...`, `go test ./...`, `make smoke`.

`.claude/commands/check.md` tiene el orden completo. Los releases salen de tags `vX.Y.Z` (ver README);
no hay flujo de PR documentado, no inventes convenciones de ramas.

## Reglas que un agente suele romper

- **El aislamiento entre hogares lo hacen `firestore.rules`, no el cliente.** Toda colección nueva o
  campo nuevo necesita regla y prueba en `scripts/test-rules.sh`. Las reglas validan que miembros y
  asignados (`assignedToId`, `preferredAssigneeId`) pertenezcan al hogar.
- **Las escrituras no esperan la red:** sin `await()` de escrituras de Firestore en UI/ViewModel
  (offline nunca resolvería). Firestore las encola en su caché; ⟳ N cuenta `hasPendingWrites`.
- **Ids de persona son `String`** (uid de Firebase), nunca `Int`/`Long`.
- **Recurrencia en `TaskRepository.resolve`:** tarea + siguiente en el **mismo `WriteBatch`**, id
  determinista `nextTaskId`, `dueAt = momento de completar + recurrenceDays`, asignada a
  `preferredAssigneeId`; plantilla inactiva o de una sola vez no genera. Deshacer borra la siguiente
  si sigue pendiente.
- **Unirse a un hogar en dos pasos** (members + perfil, luego `users/{uid}`) y listeners con
  `retryOnPermissionDenied`.
- **Modelo en tres puntas:** `firestore.rules` ↔ `Firestore.kt`/`Models.kt` ↔ `CLAUDE.md`.
- Sin dependencias nuevas salvo que se pidan. Commits en inglés; sin push, tags ni despliegues sin pedirlos.
