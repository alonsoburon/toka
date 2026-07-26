# Toka

App de tareas domésticas compartidas: un *household* con varias *people*, plantillas
de tarea recurrentes (`task_templates`) que generan instancias (`task_instances`).

**Idioma:** responde en español. El código, los identificadores y los commits van en inglés.

## Stack

| Capa | Tecnología |
|------|-----------|
| Backend | Go 1.26, stdlib `net/http` (routing 1.22+), `pgx/v5` — sin framework, sin ORM |
| DB | PostgreSQL nativo en `/var/lib/postgres/data` (el `docker-compose.yml` existe pero **no se usa**) |
| Android | Kotlin + Compose (BOM 2024.12), Retrofit, Room (SQLite local), WorkManager, minSdk 33 |

## Comandos

```bash
make db-up       # arranca PostgreSQL nativo (sudo pg_ctl) y espera pg_isready
make db-down     # detiene PostgreSQL
make run         # go run . — migra automáticamente al arrancar
make run-seed    # go run . -seed — migra + carga db/seed.sql (idempotente)
make build       # compila ./toka
make db-reset    # dropdb + createdb + migrate + seed  ⚠️ destruye datos
make curl-setup  # imprime el cheat-sheet de curl de todos los endpoints

make db-roles      # crea toka_app / toka_readonly (una vez por instalación)
make db-password   # TOKA_APP_PASSWORD=... make db-password
make db-check-rls  # verifica que el aislamiento lo aplica la base, no el WHERE
```

El servidor **lee `db/migrations/` y `db/seed.sql` desde el disco en runtime**
(`os.ReadDir`/`os.ReadFile`), así que hay que ejecutarlo desde la raíz del repo.

Android: `cd android && ./gradlew assembleDebug`. La URL del backend está hardcodeada en
`buildConfigField("String", "BASE_URL", ...)` en `android/app/build.gradle.kts` — es una IP
de LAN, hay que actualizarla al cambiar de red.

## Estructura

```
main.go                     flags (-seed, -port), connect → migrate → seed → listen
internal/db/db.go           Connect, Migrate (tabla _migrations), Seed
internal/server/server.go   ÚNICA fuente de verdad del routing
internal/auth/auth.go       middleware Bearer, ctx keys, RequireAuth
internal/handler/           Server{DB *pgxpool.Pool} + un archivo por recurso
internal/model/models.go    structs con tags json
db/migrations/NNN_name.{up,down}.sql
android/app/src/main/java/com/toka/app/
    data/api/               TokaApi (Retrofit) + ApiModels (DTOs)
    data/repository/        un repo por dominio
    ui/<feature>/           Screen + ViewModel por feature
```

## Auth: dónde vive cada cosa

La autenticación tiene dos capas y conviene no confundirlas.

**Entrar a la base** — roles de Postgres, en `db/roles.sql`:

| Rol | Para qué | Privilegios |
|-----|----------|-------------|
| `toka` | dueño de las tablas; migraciones y seed | todo, y **se salta RLS** |
| `toka_app` | el servidor en producción | DML, sin DDL, sin BYPASSRLS |
| `toka_readonly` | inspección y respaldos | SELECT, sujeto a RLS |

`DATABASE_URL` elige con cuál entra el servidor; `DATABASE_MIGRATION_URL`, con cuál
migra. Arrancar como `toka` (o cualquier superusuario) desactiva RLS de hecho, y el
servidor lo dice en el log de arranque en vez de dejarlo pasar en silencio.

Contra un host que no sea local, `sslmode=disable` hace que el servidor **se niegue a
arrancar**. En la nube corresponde `sslmode=verify-full` con `sslrootcert`.

**Aislamiento entre households** — Row-Level Security, migración 002. Ya no depende de
que cada handler recuerde el `WHERE household_id`:

```go
tx, err := db.BeginScoped(r.Context(), s.DB, hid)  // fija app.household_id
defer tx.Rollback(r.Context())
```

Los settings se fijan con `set_config(..., is_local => true)`: valen hasta el COMMIT y
no sobreviven a la devolución de la conexión al pool. Sin setting no se ve ninguna
fila — el default es negar. Dentro del household no hay más granularidad: cualquier
persona puede operar sobre cualquier otra, que es el modelo de confianza buscado.

Tres settings, tres momentos:

| Setting | Cuándo | Qué habilita |
|---------|--------|--------------|
| `app.household_id` | toda petición autenticada | ver y escribir ese household |
| `app.token_hash` | login | leer la única fila de `people` con ese token |
| `app.invite_code` | join | ver el único household con ese código |

**Tokens** — `people.token_hash` guarda sha256; el token en claro solo existe en la
respuesta que se le entrega al cliente. Se generan con `crypto/rand` (`auth.NewToken`,
`auth.NewInviteCode`), nunca con `math/rand`.

Cuidado al escribir SQL bajo RLS: `SELECT ... FOR UPDATE` cuenta como intención de
escribir, así que se evalúa contra la policy de UPDATE y no contra la de SELECT. Es
lo que rompía el join.

## Sincronización offline

El cliente Android es local-first: la UI lee de SQLite y nunca espera a la red.

**Servidor** (migración 003, `internal/handler/sync.go`):

- `GET /sync?since=N` → todo lo que cambió después del cursor N, más el cursor nuevo.
- `POST /sync/mutations` → la cola de escrituras del cliente.

`row_version` sale de un contador global (`sync_counter`) que se incrementa con un
`UPDATE ... RETURNING`. **No es una secuencia**, y la diferencia importa: una secuencia
reparte números antes del COMMIT, así que dos transacciones pueden confirmar en orden
inverso al de asignación y un cliente que sincroniza en medio se salta una fila para
siempre. El lock de la fila dura hasta el COMMIT y fuerza que ambos órdenes coincidan.
El precio es que las escrituras se serializan ahí; a escala doméstica es irrelevante.

**Deduplicación, dos capas independientes:**

1. `mutation_id` (UUID del cliente) es la clave primaria de `sync_mutations`. Reenviar
   devuelve la respuesta guardada en vez de aplicar de nuevo. Es lo que hace seguro
   reintentar tras un timeout, donde el cliente no puede saber si la petición llegó.
2. `client_id` (UUID del cliente) tiene índice único por household en las tablas de
   datos. Aunque se perdiera el registro de mutaciones, una creación reenviada choca
   contra el índice en vez de duplicar la fila.

Cada mutación va en **su propia transacción**: una inválida no bloquea la cola.
`completed_at` lo manda el cliente, así que estar tres días sin señal no corre la
ventana de la siguiente tarea recurrente.

**Android** (`data/local/`, `data/sync/`):

- Room es la fuente de verdad de la UI; los repositorios exponen `Flow`.
- Una escritura aplica el cambio localmente y encola la mutación (tabla `outbox`).
- `SyncEngine` sube la cola y después baja los cambios, en ese orden.
- `SyncWorker` (WorkManager) corre al abrir la app, tras cada escritura y cada 15 min.
- Conflictos: gana el servidor. Un 409 descarta la mutación y el pull trae la verdad.
- Las creaciones offline viven con un id negativo provisional hasta que el servidor
  confirma el `client_id` y devuelve el id real.

## Convenciones de base de datos

**Toda tabla lleva estas cuatro columnas**, sin excepción:

```sql
created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
created_by   BIGINT NOT NULL,
updated_by   BIGINT NOT NULL
```

Las FKs a `people(id)` se añaden con `ALTER TABLE ... DEFERRABLE INITIALLY DEFERRED`
después de crear las tablas — es obligatorio porque la primera `people` row se
referencia a sí misma y `households` se crea antes de que exista cualquier persona.

- `updated_at` lo mantiene el trigger `set_updated_at()`; cada tabla nueva necesita su
  `CREATE TRIGGER trg_<tabla>_updated_at BEFORE UPDATE ... EXECUTE FUNCTION set_updated_at()`.
- `created_by`/`updated_by` los pone el handler desde la persona autenticada, nunca la DB.
- Migraciones: numeradas, siempre con par `.up.sql` y `.down.sql`. **Nunca edites una
  migración ya aplicada** — añade una nueva. Se aplican en orden alfabético y se registran
  en `_migrations`.
- El seed es idempotente: todo `INSERT` lleva `ON CONFLICT DO NOTHING`.
- Índices parciales donde aplique (ver `idx_instances_due_at ... WHERE status = 'pending'`).

## Convenciones de Go

- Handlers son métodos sobre `handler.Server`; un archivo por recurso.
- Toda escritura va en transacción: `tx, err := s.DB.Begin(ctx)` + `defer tx.Rollback(ctx)`
  + `tx.Commit(ctx)` explícito al final.
- Auth al principio de cada handler protegido:
  `person, hid, ok := auth.RequireAuth(w, r); if !ok { return }`.
- **Toda query filtra por `household_id`.** Es la única frontera de aislamiento entre
  households; olvidarla es una fuga de datos entre familias.
- Path params con `r.PathValue("id")`; parseo con `strconv.ParseInt`.
- Respuestas: `w.Header().Set("Content-Type", "application/json")` +
  `json.NewEncoder(w).Encode(v)`. Errores: `http.Error(w, `{"error":"..."}`, status)`.
- Structs de request anidados en el archivo del handler, no en `model`.
- `PATCH` usa `COALESCE($n, columna)` para que los campos ausentes no se borren.
- Tokens e invite codes: `randomString(32)` / `randomString(8)` de `handler/households.go`.
- SQL siempre parametrizado (`$1`, `$2`) — nunca concatenar valores en la query.
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
| POST | `/households` | — | `CreateHousehold` — crea household + persona admin, devuelve token |
| POST | `/households/join` | — | `JoinHousehold` — por `invite_code` |
| GET | `/households/{hid}/people` | Bearer | `ListPeople` |
| POST | `/households/{hid}/regenerate-invite` | Bearer | `RegenerateInvite` |
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
| GET | `/sync?since=N` | Bearer | `Pull` — delta desde el cursor |
| POST | `/sync/mutations` | Bearer | `Push` — cola de escrituras offline |

Sin Bearer válido: `401 {"error":"unauthorized"}`.

## Al terminar un cambio

1. `gofmt` corre automáticamente vía hook al editar `.go`.
2. `go build ./...` y `go vet ./...` — el proyecto no tiene tests unitarios; los
   smoke tests de `.claude/scripts/` cumplen ese papel.
3. `make db-check-rls` si tocaste policies, roles o migraciones.
4. `.claude/scripts/smoke.sh` y `.claude/scripts/smoke-sync.sh` con el server arriba.
5. Si tocaste rutas o el contrato JSON, actualiza **las dos** puntas:
   `server.go` ↔ `TokaApi.kt` + `ApiModels.kt`. Si además es una escritura, añade su
   operación al dispatch de `applyOp` en `internal/handler/sync.go`, o la app no podrá
   hacerla sin conexión.
6. Si tocaste el esquema, actualiza la tabla de rutas / convenciones de este archivo.

## Skills del proyecto

- `/migration` — crear una migración nueva con todas las convenciones
- `/endpoint` — añadir un endpoint de punta a punta (SQL → handler → ruta → Retrofit)
- `/smoke` — probar el flujo completo con curl contra el server local
- `/android-feature` — añadir pantalla Compose + ViewModel + repositorio
