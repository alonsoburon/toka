# Toka — Project Rules

> Fuente de verdad: **`CLAUDE.md`**. Este archivo es solo un resumen para el agente.

## Stack
- **Cliente:** Android — Kotlin 2.3.21 + Compose, minSdk 33, WorkManager. Sin Retrofit, Room ni DataStore.
- **Auth:** Firebase Auth con Google (Credential Manager).
- **Datos:** Firestore con caché persistente offline; reglas en `firestore.rules`. Sin servidor propio.
- **Legado (a retirar tras migrar):** backend Go + SQLite (`main.go`, `internal/`, `db/`, `deploy/`),
  aún en producción en `https://toka.nuxapower.cl`. No se le añaden features.

## Quick commands
- `scripts/test-rules.sh` — prueba las reglas con un emulador propio (Auth 9199 / Firestore 8185).
- `scripts/dev.sh` — emuladores + emulador Android + instala "Toka DEV" (`--parar` los detiene).
- `scripts/publicar-reglas.sh` — publica reglas en `toka-hogar-037f` (solo si se pide).
- `cd android && ./gradlew compileDebugKotlin` / `assembleDebug`.

Nunca uses `pkill -f` ni toques los emuladores de Finanzas (9099 / 8085).

## Modelo de datos
```
users/{uid}{householdId}            invites/{code}{householdId}   (6 chars [A-Z0-9]; get, no list)
households/{hid}{name,inviteCode,members[<=10],createdBy,createdAt}
  people/{uid}{name,color,emoji}
  templates/{id}{name,description,recurrenceDays,preferredAssigneeId,reminderTimes,isActive,...}
  tasks/{id}{templateId,templateName,status,dueAt,assignedToId,completedById,completedAt,notes,generatedFrom,...}
```

## Reglas
- El aislamiento entre hogares lo hacen las reglas; validan miembros y asignados. Todo cambio de modelo
  actualiza `firestore.rules`, `scripts/test-rules.sh` y `CLAUDE.md`.
- Las escrituras no esperan la red (sin `await`); ids de persona `String`.
- Unirse a un hogar en dos pasos; listeners con `retryOnPermissionDenied`.

## Recurrence logic (`TaskRepository.resolve`)
- Al completar/saltar, mismo `WriteBatch`: actualiza la tarea y crea la siguiente con id `nextTaskId`.
- `dueAt = <momento de completar> + recurrenceDays`; asignada a `preferredAssigneeId`.
- Plantilla inactiva o `recurrenceDays` null → no se crea. Deshacer borra la siguiente si sigue pendiente.
- Borrar plantilla = `isActive=false` + borrar sus tareas pendientes (el historial queda).

## Builds
- Debug: `com.toka.app.dev`, usa emuladores, botones "Entrar como Ana/Beto (dev)".
- Release: `com.toka.app`, requiere `android/app/google-services.json` real (CI: secret `GOOGLE_SERVICES_JSON_BASE64`).
