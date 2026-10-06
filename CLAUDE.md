# Toka

App de tareas domésticas compartidas: un *household* con varias *people*, plantillas
de tarea recurrentes (`templates`) que generan instancias (`tasks`).

**Idioma:** responde en español. El código, los identificadores y los commits van en inglés.

## Arquitectura vigente

Android (Kotlin + Compose) habla **directo con Firebase**: Firebase Auth (Google) y
Firestore. No hay servidor propio en el flujo vigente. Misma arquitectura que la app
Finanzas (`~/code/personal_finance`).

| Capa | Tecnología |
|------|-----------|
| Cliente | Kotlin 2.3.21, AGP 8.13.2, Compose BOM 2026.03.01, minSdk 33 |
| Auth | Firebase Auth + Google vía Credential Manager 1.6.0 + googleid 1.2.1 |
| Datos | Firestore (BOM 34.19.0, auth + firestore), caché persistente offline; coroutines-play-services |
| Segundo plano | WorkManager 2.12.0 (recordatorios), widget RemoteViews |
| Reglas | `firestore.rules` — única frontera entre hogares |

Ya **no** hay Retrofit, Room, KSP, kotlinx-serialization ni DataStore.

## Backend Go + SQLite (legado, a retirar tras migrar)

`main.go`, `internal/`, `db/`, `Containerfile`, `deploy/`, `Makefile`, `.claude/scripts/smoke*.sh`
siguen en el repo y en producción (`https://toka.nuxapower.cl`, VPS OVH con Podman + Quadlet)
**hasta que se migren los datos y se retire**. No se le añaden features; solo se opera
(deploy, respaldo, rotación de credenciales: ver `README.md` y `deploy/`). Las skills
`/migration`, `/endpoint`, `/smoke` y el comando `/schema` describen ese backend y solo
aplican a él. Para correrlo: `make run` / `make run-seed` (desde la raíz; lee `db/` del disco).

## Comandos

```bash
scripts/test-rules.sh        # 50 comprobaciones de firestore.rules con emulador propio
scripts/dev.sh               # emuladores Firebase + emulador Android + instala "Toka DEV"
scripts/dev.sh --sin-compilar   # idem sin recompilar
scripts/dev.sh --parar       # detiene los emuladores de Toka (por PID)
scripts/publicar-reglas.sh   # publica firestore.rules en toka-hogar-037f
cd android && ./gradlew compileDebugKotlin   # verificación rápida de tipos
cd android && ./gradlew assembleDebug        # APK debug
```

**Emuladores:** los de Toka usan Auth `9199` / Firestore `8185` / UI `4100`. Finanzas usa
`9099` / `8085`. **Nunca compartas ni mates el emulador de Finanzas: nada de `pkill -f`**;
los scripts detienen por PID/grupo de proceso. Necesitan Java 21+ y `npx`.
`dev.sh` con teléfono real: `DISPOSITIVO=<serial> scripts/dev.sh` (usa `-Ptoka.emulador=<ip>`).

## Modelo de datos (Firestore)

```
users/{uid}                      { householdId }
invites/{code}                   { householdId }     código de 6 chars [A-Z0-9]; se consulta (get), nunca se lista
households/{hid}                 { name, inviteCode, members[<=10], createdBy, createdAt }
households/{hid}/people/{uid}    { name, color, emoji }                 id = uid del miembro
households/{hid}/templates/{id}  { name, description, recurrenceDays, preferredAssigneeId,
                                   reminderTimes, isActive, createdBy, createdAt, updatedAt }
households/{hid}/tasks/{id}      { templateId, templateName, status pending|done|skipped, dueAt (Timestamp),
                                   assignedToId, completedById, completedAt, notes, generatedFrom,
                                   createdBy, createdAt, updatedAt }
```

Todos los ids de persona son `String` (uid de Firebase), nunca `Int`/`Long`. Rutas en Kotlin:
`data/firebase/Firestore.kt` (`user`, `invite`, `household`, `people`, `templates`, `tasks`).
`reminderTimes` es `"HH:MM,HH:MM"` en hora local del teléfono.

## Reglas de seguridad: dónde vive el aislamiento

**Entre hogares lo aplican las reglas (`firestore.rules`), no código de servidor.** Un cliente
modificado puede intentar cualquier escritura; solo las reglas lo frenan. Lo que validan:

- `users/{uid}`: cada uno solo el suyo, únicamente `householdId`.
- `invites/{code}`: `get` para cualquier logueado, `list`/`update` prohibidos; crear exige ser miembro
  del hogar destino (tras el lote) y código válido; borrar, ser miembro.
- `households`: leer solo miembros. Crear con `members == [uid]`. Update en cuatro formas:
  unirse (solo agregarte, máx. 10), salir (solo quitarte), regenerar `inviteCode`, renombrar. Sin delete.
- `people/{pid}`: `pid == uid`, perfil acotado (nombre ≤40, color `#RRGGBB`, emoji ≤16).
- `templates`: `preferredAssigneeId` debe ser miembro del hogar; `recurrenceDays` 1..3650; sin delete
  (se da de baja con `isActive=false`).
- `tasks`: `assignedToId` debe ser miembro; `pending` no lleva `completedById/At`; resuelta debe
  llevar `completedById == uid` y `completedAt`; una resuelta solo vuelve a `pending` tocando los
  campos de resolución (`reabrirValido`); solo se borran las `pending`.

Se prueban con `scripts/test-rules.sh`. **Cada cambio en reglas, modelo o escrituras del
cliente se acompaña de su comprobación ahí**, y las reglas se publican con
`scripts/publicar-reglas.sh` (pedirlo, no hacerlo por iniciativa).

## Sesión y onboarding

`SessionRepository` (Auth + `users/{uid}`) emite `Session.{Loading, SignedOut, NoHousehold,
InHousehold, Failed}`; `MainActivity` elige `LoginScreen` / `HouseholdSetupScreen` / la app.
`SessionCache` (SharedPreferences) entrega `uid`/`householdId` de forma síncrona a workers y widget.

**Unirse a un hogar es en dos pasos** (primero `members` + perfil en `people`, luego `users/{uid}`)
para evitar una carrera de permisos. Los listeners que pueden abrirse justo tras crear/unirse
usan `retryOnPermissionDenied` (un listener con `PERMISSION_DENIED` muere para siempre).

## Escrituras, offline y recurrencia

Firestore guarda las escrituras en su caché persistente y las sube solo. **Las escrituras no
esperan la red**: nunca hagas `await()` de una escritura en la UI o el ViewModel (con el avión
activado nunca resolvería). El indicador ⟳ N cuenta documentos con `metadata.hasPendingWrites()`.

La recurrencia vive en el cliente, `TaskRepository.resolve`:

- al completar/saltar, **el mismo `WriteBatch`** actualiza la tarea y crea la siguiente;
- id de la siguiente: `nextTaskId(taskId)` (hash SHA-256 truncado, determinista), así dos teléfonos
  offline que completan lo mismo escriben el mismo documento y no se duplica;
- `dueAt = <momento de completar> + recurrenceDays` — desde el completado, no desde el vencimiento
  original (no punitivo);
- se asigna a `preferredAssigneeId` (puede ser null);
- si la plantilla está inactiva o `recurrenceDays` es null (una sola vez), no se crea nada;
- **deshacer** vuelve la tarea a `pending` y borra la siguiente si sigue pendiente;
- **borrar plantilla** = `isActive=false` + borrar sus tareas pendientes (el historial se conserva).

## Recordatorios y widget

`ReminderWorker` (WorkManager, cada 15 min) avisa de tareas pendientes que vencen **hoy** a cada
hora de `reminderTimes` ya pasada, con dedupe diario en SharedPreferences; un aviso de las 09:00
puede salir hasta 09:14. El widget "Mis tareas de hoy" (`TodayWidgetProvider`, RemoteViews) y los
workers leen de la **caché de Firestore** vía `SessionCache`. Son notificaciones locales: no hay FCM.

## Builds, entornos y Firebase real

- **Debug:** `applicationId` `com.toka.app.dev` ("Toka DEV"). Usa los emuladores (`EMULATOR_HOST`
  `10.0.2.2`; `-Ptoka.emulador=<ip>` para teléfono en LAN) y muestra "Entrar como Ana/Beto (dev)".
- **Release:** `com.toka.app`; necesita `android/app/google-services.json` real (gitignoreado).
  En CI sale del secret `GOOGLE_SERVICES_JSON_BASE64` (`.github/workflows/release.yml`, falla si falta),
  además de los secrets del keystore.
- **Proyecto Firebase:** `toka-hogar-037f` (cuenta alonso@tucunar.com, organización Tucunar), Firestore en
  `southamerica-west1`, plan Spark. Checklist de consola pendiente en `README.md`.

## Estructura (Android)

```
android/app/src/main/java/com/toka/app/
  data/firebase/     Firestore.kt (rutas, asFlow, retryOnPermissionDenied, mapeo, nextTaskId), FirebaseSetup.kt
  data/repository/   AuthRepository, HouseholdRepository, SessionRepository, TaskRepository
  data/di/           AppContainer (inyección manual)
  data/              SessionCache, Dates, Results; model/Models.kt (DTOs)
  ui/<feature>/      Screen + ViewModel por feature; ui/navigation, ui/components, ui/theme
  notifications/     ReminderWorker, TaskActionWorker, Notifications
  widget/            TodayWidgetProvider
firestore.rules  firebase.json  scripts/{dev,test-rules,publicar-reglas}.sh
```

## Al terminar un cambio

1. `scripts/test-rules.sh` si tocaste reglas, modelo de datos o escrituras.
2. `cd android && ./gradlew compileDebugKotlin` (o `assembleDebug`).
3. Si tocaste el modelo, actualiza **las tres** puntas: `firestore.rules` ↔ `Firestore.kt`/`Models.kt`
   ↔ este archivo (y `scripts/test-rules.sh`).
4. Si tocaste el backend legado: `gofmt -l .`, `go build ./...`, `go vet ./...`, `go test ./...`, `make smoke`.

## Convenciones

- Commits en inglés; **no hagas push, tags ni despliegues sin que se pidan**.
- Sin dependencias nuevas salvo que se pidan explícitamente.
- Estado de UI con `StateFlow` desde ViewModels; colores y tipografía de `ui/theme/`; strings en `res/values/strings.xml`.

## Skills del proyecto

- `/android-feature` — pantalla Compose + ViewModel + repositorio sobre Firestore
- `/check`, `/up` — verificación y arranque del flujo Firestore (reglas, emuladores, compilación)
- `/migration`, `/endpoint`, `/smoke`, `/schema` — **solo backend legado** Go/SQLite
