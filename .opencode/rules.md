# Toka — Project Rules

> Fuente de verdad: **`CLAUDE.md`**. Este archivo es solo un resumen para el agente.

## Stack
- **Backend:** Go 1.26, stdlib `net/http`, `database/sql` + `modernc.org/sqlite` (sin CGO).
- **DB:** SQLite en un solo archivo (`TOKA_DB`, default `toka.db`). Sin daemon ni roles.
- **Frontend:** Android — Kotlin + Compose, Retrofit, Room, WorkManager (minSdk 33).

## Quick commands
- `make run-seed` — migra + siembra + sirve en `:3000` (crea `.env` si falta).
- `make run` — migra + sirve.
- `make build` — compila `./toka`.
- `make db-reset` — ⚠️ borra `toka.db` y lo recrea con seed (destruye datos).
- `make curl-setup` — cheat-sheet de endpoints.

El servidor lee `db/migrations/` y `db/seed.sql` del disco en runtime: ejecútalo desde la
raíz del repo. Flags: `-seed`, `-port`, `-migrate-only`, `-env <archivo>`.

## Database conventions
- Toda tabla lleva `created_at`, `updated_at`, `created_by`, `updated_by`
  (los dos últimos FK a `people(id)` DEFERRABLE INITIALLY DEFERRED).
- SQLite no tiene trigger `set_updated_at()`: **el handler actualiza `updated_at`** en cada UPDATE.
- Las tablas sincronizadas (`people`, `task_templates`, `task_instances`) llevan
  `row_version`, asignado con `db.NextRowVersion(tx)` dentro de la misma transacción.
- Migraciones `NNN_name.{up,down}.sql`, numeradas; **nunca editar una ya aplicada**.
- Seed idempotente (`ON CONFLICT DO NOTHING`).

## Auth model
- `people.token_hash` guarda sha256; el token en claro solo viaja en la respuesta que lo crea.
- Cada request: `Authorization: Bearer <token>`. El middleware resuelve token → persona → household.
- **Aislamiento por household lo aplica el código:** SQLite no tiene RLS, toda query
  autenticada filtra por `household_id`.
- Tokens e invite codes con `crypto/rand`, nunca `math/rand`.

## Go conventions
- stdlib `net/http` con routing 1.22+ (`r.PathValue("id")`).
- Handlers son métodos de `handler.Server{DB *sql.DB}`; un archivo por recurso.
- Toda escritura en transacción (`BeginTx` + `defer Rollback` + `Commit`).
- `created_by`/`updated_by` salen de `auth.RequireAuth`, nunca del body.
- PATCH usa `COALESCE(?, columna)` (online y en `applyOp`) para no borrar campos ausentes.
- SQL siempre parametrizado. Sin dependencias nuevas salvo que se pidan.

## Recurrence logic
- Al completar/saltar: si `recurrence_days IS NULL` no se genera nada (one-shot).
- Si no, se inserta una instancia `pending` con `due_at = <momento de completar> + recurrence_days`.
- No punitivo: atrasarse no acorta la ventana siguiente.
- `task.uncomplete` (deshacer) borra la instancia generada (enlazada por
  `generated_from_instance_id`) con su tombstone.

## API routes

Fuente de verdad: `internal/server/server.go`.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/healthz` | No | Sonda de disponibilidad |
| POST | `/households` | No | Create household + admin person |
| POST | `/households/join` | No | Join household via invite code |
| GET | `/households/{hid}/people` | Bearer | List household members |
| POST | `/households/{hid}/people` | Bearer | Add person (devuelve token) |
| PATCH | `/people/{id}` | Bearer | Update person name/color/emoji |
| DELETE | `/people/{id}` | Bearer | Delete person (limpia FKs + tombstone) |
| POST | `/households/{hid}/regenerate-invite` | Bearer | New invite code |
| POST | `/households/{hid}/leave` | Bearer | Delete own person (fails if last) |
| GET | `/templates` | Bearer | List active templates |
| POST | `/templates` | Bearer | Create template + first instance |
| PATCH | `/templates/{id}` | Bearer | Update template |
| DELETE | `/templates/{id}` | Bearer | Soft-delete (`is_active=false`) |
| GET | `/tasks` | Bearer | Pending tasks (overdue first) |
| GET | `/tasks/history?days=30` | Bearer | Done + skipped tasks |
| POST | `/tasks/{id}/complete` | Bearer | Mark done, spawn next if recurring |
| POST | `/tasks/{id}/skip` | Bearer | Mark skipped, spawn next if recurring |
| PATCH | `/tasks/{id}` | Bearer | Reassign / notes / due date |
| GET | `/sync?since=N` | Bearer | Sync delta since cursor |
| POST | `/sync/mutations` | Bearer | Offline write queue |

Auth-required routes return `401 {"error":"unauthorized"}` without a valid Bearer token.
