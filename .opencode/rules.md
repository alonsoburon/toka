# Toka — Project Rules

## Stack
- **Backend:** Go (stdlib `net/http` + `pgx/v5`)
- **Database:** PostgreSQL 18 (native, `/var/lib/postgres/data`)
- **Frontend:** TBD (Android planned)

## Quick commands
- `make db-up` — start PostgreSQL native service
- `make db-down` — stop PostgreSQL
- `make run` — run backend (auto-migrates on start)
- `make run-seed` — run + seed database (seed is idempotent with ON CONFLICT)
- `make build` — compile binary
- `make db-reset` — drop + recreate + migrate + seed
- `make curl-setup` — print curl cheat-sheet for all endpoints

## Database conventions

### Metadata columns — EVERY table MUST have these:
```sql
created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
created_by   BIGINT NOT NULL REFERENCES people(id)
updated_by   BIGINT NOT NULL REFERENCES people(id)
```

### Triggers
Every table has a `BEFORE UPDATE` trigger that sets `updated_at = now()`:
```sql
CREATE FUNCTION set_updated_at() RETURNS TRIGGER ...
-- Applied to: households, people, task_templates, task_instances
```

### Migrations
- Numbered files in `db/migrations/` as `NNN_name.{up,down}.sql`
- Read from disk at runtime (`os.ReadDir`/`os.ReadFile`)
- Run automatically on server start, tracked in `_migrations` table
- Seed is idempotent (all INSERTs use `ON CONFLICT DO NOTHING`)

### Auth model
- `people.token` is a random string (bearer token)
- Every request: `Authorization: Bearer <token>`
- Auth middleware resolves token → person → household
- `created_by` and `updated_by` set from authenticated person on write operations

## Go conventions
- stdlib `net/http` with Go 1.22+ routing patterns (`PathValue`)
- Handlers are methods on `type Server struct { db *pgxpool.Pool }`
- All write operations use transactions
- `people.token` generated with `lib.ReqStr(32)` or similar random string util
- Request/response types are simple structs with `json` tags
- No ORM, plain SQL via `pgx`

## Recurrence logic
- On task completion: if `template.recurrence_days > 0`, INSERT next instance
- Next `due_at = completed_at + recurrence_days` (NOT from original due_at)
- Non-punitive: delay in completing doesn't shorten the next window
- One-shot tasks (`recurrence_days IS NULL`) do not regenerate

## API Routes

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/households` | No | Create household + admin person |
| POST | `/households/join` | No | Join household via invite code |
| GET | `/households/{hid}/people` | Bearer | List household members |
| POST | `/households/{hid}/people` | Bearer | Add person to household |
| PATCH | `/people/{id}` | Bearer | Update person name/color/emoji |
| GET | `/templates` | Bearer | List active task templates |
| POST | `/templates` | Bearer | Create template + first instance |
| PATCH | `/templates/{id}` | Bearer | Update template |
| DELETE | `/templates/{id}` | Bearer | Soft-delete (is_active=false) |
| GET | `/tasks` | Bearer | Pending tasks (overdue first) |
| GET | `/tasks/history?days=30` | Bearer | Done + skipped tasks |
| POST | `/tasks/{id}/complete` | Bearer | Mark done, spawn next if recurring |
| POST | `/tasks/{id}/skip` | Bearer | Mark skipped, spawn next if recurring |
| PATCH | `/tasks/{id}` | Bearer | Reassign or add notes |

Auth-required routes return 401 if no/invalid Bearer token.
