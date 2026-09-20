# Toka

App de tareas domésticas compartidas: un *household* con varias *people*, plantillas
de tarea recurrentes (`task_templates`) que generan instancias (`task_instances`).

**Idioma:** responde en español. El código, los identificadores y los commits van en inglés.

## Stack

| Capa | Tecnología |
|------|-----------|
| Backend | Go 1.26, stdlib `net/http` (routing 1.22+), `database/sql` + `modernc.org/sqlite` (puro Go, sin CGO) |
| DB | SQLite: un solo archivo (`TOKA_DB`, por defecto `toka.db`). Sin daemon ni roles |
| Android | Kotlin + Compose (BOM 2024.12), Retrofit, Room (SQLite local), WorkManager, minSdk 33 |

## Comandos

```bash
make run         # copia .env si falta y sirve en :3000 (migra al arrancar)
make run-seed    # migra + carga db/seed.sql (idempotente)
make build       # compila ./toka
make db-reset    # borra toka.db y lo recrea con seed  ⚠️ destruye datos
make curl-setup  # imprime el cheat-sheet de curl de todos los endpoints
```

El servidor **lee `db/migrations/` y `db/seed.sql` desde el disco en runtime**
(`os.ReadDir`/`os.ReadFile`), así que hay que ejecutarlo desde la raíz del repo.

Android: `cd android && ./gradlew assembleDebug`. La URL del backend se configura en
la pantalla "Conectar al servidor"; `BASE_URL` en `android/app/build.gradle.kts` es
solo el valor por defecto.

## Estructura

```
main.go                     flags (-seed, -port, -migrate-only, -env), connect → migrate → seed → listen
internal/db/db.go           Connect, Migrate (tabla _migrations), Seed
internal/db/version.go      NextRowVersion (contador de sincronización)
internal/server/server.go   ÚNICA fuente de verdad del routing
internal/auth/auth.go       middleware Bearer, ctx keys, RequireAuth
internal/handler/           Server{DB *sql.DB} + un archivo por recurso
internal/model/models.go    structs con tags json
db/migrations/NNN_name.{up,down}.sql
android/app/src/main/java/com/toka/app/
    data/api/               TokaApi (Retrofit) + ApiModels (DTOs)
    data/repository/        un repo por dominio
    ui/<feature>/           Screen + ViewModel por feature
```

## Auth: dónde vive cada cosa

**Entrar a la base** ya no tiene capas: SQLite es un archivo y el proceso entra
directo. `TOKA_DB` elige la ruta (default `toka.db`).

**Aislamiento entre households** lo aplica **el código Go**: cada query filtra por
`household_id`. Antes lo garantizaba RLS en Postgres (migración 002, ya no existe);
al migrar a SQLite esa frontera volvió a ser disciplina del SQL. **Toda query
autenticada filtra por `household_id`** — olvidarla es una fuga entre familias.

`auth.Middleware` resuelve el Bearer token a una persona con una lectura por
`token_hash` (único global) y deja `Person` y `HouseholdID` en el contexto.
`auth.RequireAuth(w, r)` es lo que usan los handlers; devuelve `(person, hid, ok)`.

**Tokens** — `people.token_hash` guarda sha256; el token en claro solo existe en la
respuesta que se le entrega al cliente. Se generan con `crypto/rand`
(`auth.NewToken`, `auth.NewInviteCode`), nunca con `math/rand`.

## Sincronización offline

El cliente Android es local-first: la UI lee de SQLite (Room) y nunca espera a la red.

**Servidor** (migración 002, `internal/handler/sync.go`):

- `GET /sync?since=N` → todo lo que cambió después del cursor N, más el cursor nuevo.
- `POST /sync/mutations` → la cola de escrituras del cliente.

`row_version` sale de un contador global (`sync_counter`) que se incrementa con
`UPDATE ... RETURNING` desde `db.NextRowVersion`, **dentro de la misma transacción
que la escritura**. No es una secuencia: una secuencia reparte números antes del
COMMIT y dos transacciones podrían confirmar en orden inverso, haciendo que un
cliente se salte una fila para siempre. El contador lo garantiza, y SQLite además
serializa las escrituras de todos modos. **Toda escritura en `people`,
`task_templates` o `task_instances` debe asignar `row_version = NextRowVersion(...)`
y, si es un UPDATE, actualizar `updated_at` a mano** (SQLite no tiene el trigger
`set_updated_at()` de Postgres).

**Deduplicación, dos capas independientes:**

1. `mutation_id` (UUID del cliente) es la clave primaria de `sync_mutations`. Reenviar
   devuelve la respuesta guardada en vez de aplicar de nuevo.
2. `client_id` (UUID del cliente) tiene índice único por household en las tablas de
   datos. Aunque se perdiera el registro de mutaciones, una creación reenviada choca
   contra el índice en vez de duplicar la fila.

Cada mutación va en **su propia transacción**. `completed_at` lo manda el cliente, así
que estar tres días sin señal no corre la ventana de la siguiente tarea recurrente.

**Android** (`data/local/`, `data/sync/`):

- Room es la fuente de verdad de la UI; los repositorios exponen `Flow`.
- Una escritura aplica el cambio localmente y encola la mutación (tabla `outbox`).
- `SyncEngine` sube la cola y después baja los cambios, en ese orden.
- `SyncWorker` (WorkManager) corre al abrir la app, tras cada escritura y cada 15 min.
- Conflictos: gana el servidor. Un 409 descarta la mutación y el pull trae la verdad.
- Las creaciones offline viven con un id negativo provisional hasta que el servidor
  confirma el `client_id` y devuelve el id real.
- **Deshacer:** `undoTask` vuelve la tarea a `pending` localmente y encola
  `task.uncomplete`. En el servidor, esa operación revierte el estado y **borra la
  instancia que la recurrencia generó** (enlazada por `generated_from_instance_id`,
  migración 003) con su tombstone. Es lo que hace seguro el botón "Deshacer" del
  swipe.

## Recordatorios

Las horas de aviso viven en `task_templates.reminder_times` (`"HH:MM,HH:MM"`, hora
local del teléfono; el servidor no la mira). El cliente las configura al crear la tarea o
desde el detalle de una tarea existente. `ReminderWorker` (WorkManager, cada 15 min)
revisa las tareas pendientes que vencen **hoy** y notifica a cada hora ya pasada que
no se haya avisado ese día (dedupe en SharedPreferences). No hay push remoto ni
Firebase: son notificaciones locales. WorkManager tiene un mínimo de 15 min, así que
un aviso de las 09:00 puede salir hasta 09:14.

## Convenciones de base de datos

**Tipos SQLite:** como no hay tipos nativos, se usan:

- `INTEGER PRIMARY KEY AUTOINCREMENT` para los ids.
- `TIMESTAMP` para fechas: afinidad NUMERIC, pero guardan el texto ISO-8601 UTC de
  ancho fijo que escribe el driver (`_time_format=sqlite`). Declararlas `TEXT` haría
  que el driver no las entregue como `time.Time`. Ordenan lexicográficamente.
- `BOOLEAN` (INTEGER 0/1) y `TEXT` con `CHECK (... IN (...))` donde antes había enums.

**Toda tabla lleva estas cuatro columnas**, sin excepción:

```sql
created_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
updated_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
created_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED,
updated_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED
```

- Las FKs a `people(id)` van **inline** en el `CREATE TABLE` (SQLite no soporta
  `ALTER TABLE ADD CONSTRAINT`) y son `DEFERRABLE INITIALLY DEFERRED`: se validan al
  COMMIT, lo que permite crear el household y su admin en la misma transacción.
- `created_by`/`updated_by` los pone el handler desde la persona autenticada, nunca la DB.
- `updated_at` lo actualiza **el handler** en cada UPDATE (no hay trigger). Los INSERT
  usan el DEFAULT.
- `row_version` (tabla versionada) también lo pone el handler con `NextRowVersion`.
- Migraciones: numeradas, siempre con par `.up.sql` y `.down.sql`. **Nunca edites una
  migración ya aplicada** — añade una nueva. Se aplican en orden y se registran en
  `_migrations`.
- El seed es idempotente: todo `INSERT` lleva `ON CONFLICT DO NOTHING`.
- Índices parciales donde aplique (`idx_instances_due_at ... WHERE status = 'pending'`).

## Convenciones de Go

- Handlers son métodos sobre `handler.Server`; un archivo por recurso.
- Toda escritura va en transacción: `tx, err := s.DB.BeginTx(ctx, nil)` +
  `defer tx.Rollback()` + `tx.Commit()` explícito al final.
- Auth al principio de cada handler protegido:
  `person, hid, ok := auth.RequireAuth(w, r); if !ok { return }`.
- **Toda query filtra por `household_id`.** Es la única frontera de aislamiento entre
  households; olvidarla es una fuga de datos entre familias.
- Path params con `r.PathValue("id")`; parseo con `strconv.ParseInt`.
- Respuestas: `w.Header().Set("Content-Type", "application/json")` +
  `json.NewEncoder(w).Encode(v)`. Errores: `http.Error(w, `{"error":"..."}`, status)`.
- Structs de request anidados en el archivo del handler, no en `model`.
- `PATCH` usa `COALESCE(?, columna)` para que los campos ausentes no se borren.
- Placeholders `?` (SQLite); una fila ausente es `errors.Is(err, sql.ErrNoRows)`.
- Tokens e invite codes: `auth.NewToken` / `auth.NewInviteCode` (`crypto/rand`).
- SQL siempre parametrizado — nunca concatenar valores en la query.
- Sin dependencias nuevas salvo que se pidan explícitamente.

## Lógica de recurrencia

En `handler.createNextInstance` (`internal/handler/instances.go`), al completar o saltar:

- si `template.recurrence_days IS NULL` → no se genera nada (tarea one-shot);
- si no → se inserta una instancia `pending` con
  `due_at = <momento de completar> + recurrence_days` — **desde el completado, no desde el
  `due_at` original**. Es deliberadamente no punitivo: atrasarse no acorta la ventana siguiente.
- La nueva instancia se asigna a `template.preferred_assignee_id` (puede ser NULL).
- Se crea dentro de la misma transacción que el `complete`/`skip`.

## Rutas registradas

Fuente de verdad: `internal/server/server.go`.

| Método | Path | Auth | Handler |
|--------|------|------|---------|
| GET | `/healthz` | — | `Health` — sondeo de disponibilidad `{"status":"ok"}` |
| POST | `/households` | — | `CreateHousehold` — crea household + persona admin, devuelve token |
| POST | `/households/join` | — | `JoinHousehold` — por `invite_code` |
| GET | `/households/{hid}/people` | Bearer | `ListPeople` |
| POST | `/households/{hid}/regenerate-invite` | Bearer | `RegenerateInvite` |
| POST | `/households/{hid}/leave` | Bearer | `LeaveHousehold` — borra tu propia persona (falla si eres la última) |
| GET | `/templates` | Bearer | `ListTemplates` (solo `is_active`) |
| POST | `/templates` | Bearer | `CreateTemplate` + primera instancia |
| PATCH | `/templates/{id}` | Bearer | `UpdateTemplate` |
| DELETE | `/templates/{id}` | Bearer | `DeleteTemplate` (soft: `is_active=false`) |
| GET | `/tasks` | Bearer | `ListPendingTasks` (atrasadas primero) |
| GET | `/tasks/history?days=30` | Bearer | `ListTaskHistory` |
| POST | `/tasks/{id}/complete` | Bearer | `CompleteTask` |
| POST | `/tasks/{id}/skip` | Bearer | `SkipTask` |
| PATCH | `/tasks/{id}` | Bearer | `UpdateTask` (reasignar / notas) |
| POST | `/households/{hid}/people` | Bearer | `CreatePerson` |
| PATCH | `/people/{id}` | Bearer | `UpdatePerson` |
| DELETE | `/people/{id}` | Bearer | `DeletePerson` — limpia FKs + tombstone |
| GET | `/sync?since=N` | Bearer | `Pull` — delta desde el cursor |
| POST | `/sync/mutations` | Bearer | `Push` — cola de escrituras offline |

Sin Bearer válido: `401 {"error":"unauthorized"}`.

## Al terminar un cambio

1. `gofmt -w .` y `go build ./...` + `go vet ./...` (no hay tests unitarios).
2. Con el server arriba: `.claude/scripts/smoke.sh` y `.claude/scripts/smoke-sync.sh`.
3. Si tocaste rutas o el contrato JSON, actualiza **las dos** puntas:
   `server.go` ↔ `TokaApi.kt` + `ApiModels.kt`. Si además es una escritura, añade su
   operación al dispatch de `applyOp` en `internal/handler/sync.go`, o la app no podrá
   hacerla sin conexión.
4. Si tocaste el esquema, actualiza la tabla de rutas / convenciones de este archivo.

## Skills del proyecto

- `/migration` — crear una migración nueva con todas las convenciones
- `/endpoint` — añadir un endpoint de punta a punta (SQL → handler → ruta → Retrofit)
- `/smoke` — probar el flujo completo con curl contra el server local
- `/android-feature` — añadir pantalla Compose + ViewModel + repositorio
