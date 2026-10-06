---
name: migration
description: (Solo backend Go/SQLite legado) Crear una migración SQL nueva en db/migrations siguiendo las convenciones de Toka sobre SQLite (columnas de metadata, FKs deferrable inline, par up/down, índices). Úsala cuando haya que cambiar el esquema — tabla nueva, columna nueva, índice, CHECK.
---

> **Solo backend legado.** Esta skill describe las migraciones del backend Go/SQLite (Go + SQLite), que sigue en producción hasta migrar y retirarlo. La arquitectura vigente es Firebase Auth + Firestore (ver `CLAUDE.md`); para cambios de datos ahí usa `firestore.rules`, `scripts/test-rules.sh` y `/android-feature`.

# Migración nueva (SQLite)

## 1. Numera

```bash
ls db/migrations/
```

Toma el número más alto y suma 1, con tres dígitos (`004`, `005`, …). El nombre va en
snake_case y describe el cambio: `004_add_task_priority`.

Crea **siempre el par**: `NNN_nombre.up.sql` y `NNN_nombre.down.sql`. El `.down.sql`
tiene que deshacer exactamente lo del `.up.sql`, en orden inverso.

**Nunca edites una migración que ya se aplicó** (está registrada en `_migrations`;
el server la salta y tu cambio no correría nunca). Añade una nueva.

## 2. Si creas una tabla

Copia esta plantilla completa — las cuatro columnas de metadata no son opcionales:

```sql
-- NNN_nombre.up.sql

CREATE TABLE cosas (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    -- ... campos propios ...
    created_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    updated_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    created_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED,
    updated_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_cosas_household ON cosas(household_id);
```

Y el down:

```sql
-- NNN_nombre.down.sql
DROP INDEX IF EXISTS idx_cosas_household;
DROP TABLE IF EXISTS cosas;
```

Puntos donde es fácil equivocarse:

- **No hay trigger de `updated_at`**: lo actualiza el handler en cada UPDATE.
  Tampoco existe `set_updated_at()`.
- Las FKs a `people` van **inline** en el `CREATE TABLE` (SQLite no soporta
  `ALTER TABLE ADD CONSTRAINT`) y son `DEFERRABLE INITIALLY DEFERRED`: se validan al
  COMMIT, lo que permite crear el household y su admin en la misma transacción.
- Fechas: `TIMESTAMP` (no `TEXT`, o el driver no las entrega como `time.Time`) con el
  default `strftime` de arriba. Booleanos: `BOOLEAN` (INTEGER 0/1). Enums: `TEXT` con
  `CHECK (col IN ('a','b'))`.
- Toda tabla con datos por household lleva `household_id ... ON DELETE CASCADE`.
- Si la tabla se sincroniza con la app (como `people`, `task_templates`,
  `task_instances`): añade `row_version INTEGER NOT NULL DEFAULT 0`, `client_id TEXT`,
  un índice `(household_id, row_version)` y el índice único parcial por
  `(household_id, client_id)`; mira `002_sync.up.sql`.
- Índices parciales cuando la query siempre filtra por un estado:
  `CREATE INDEX ... ON t(household_id, due_at) WHERE status = 'pending'`.

## 3. Si añades una columna

```sql
ALTER TABLE task_templates ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
```

Con `NOT NULL` **exige** `DEFAULT` no nulo, si no la migración falla contra datos
existentes. Una columna con `REFERENCES` también necesita `DEFAULT NULL` (o ser nullable).
Down (SQLite >= 3.35):

```sql
DROP INDEX IF EXISTS idx_...;                      -- primero los índices que usen la columna
ALTER TABLE task_templates DROP COLUMN priority;
```

`DROP COLUMN` falla si la columna está indexada, es PK/UNIQUE o la usa una FK/CHECK: borra
antes el índice. Cambiar el tipo o un CHECK de una columna existente exige recrear la
tabla (crear nueva, copiar, `DROP`, `RENAME`); evítalo si puedes.

## 4. Propaga

Un cambio de esquema casi nunca termina en el SQL:

1. `internal/model/models.go` — campo nuevo con su tag `json`, puntero si es nullable.
2. El `SELECT` **y** el `Scan` de cada handler que toque esa tabla — están acoplados por
   posición, si añades una columna al SELECT tienes que añadir el `&campo` al Scan.
3. `INSERT`/`UPDATE` correspondientes (los `PATCH` usan `COALESCE(?, columna)`), con
   `row_version = NextRowVersion(...)` y `updated_at` a mano en tablas sincronizadas.
4. `db/seed.sql` si el dato nuevo tiene sentido en los datos de prueba
   (manteniendo `ON CONFLICT DO NOTHING`).
5. Sincronización: si la columna viaja a la app, revisa `internal/handler/sync.go`
   (`Pull`/`applyOp`) y el DTO/Room en Android (`data/api/ApiModels.kt`, `data/local/`).
6. `CLAUDE.md` si cambian las convenciones o las rutas.

## 5. Aplica y verifica

```bash
make run          # migra al arrancar (desde la raíz del repo) e imprime "  ✓ NNN_nombre.up.sql"
```

Comprueba que quedó registrada y que la forma es la esperada:

```bash
sqlite3 toka.db "SELECT name, applied_at FROM _migrations ORDER BY name"
sqlite3 toka.db ".schema cosas"
```

Si la migración falló a medias, `_migrations` no se escribió: corrige el SQL y vuelve a
arrancar (si SQLite dejó cambios parciales, `make db-reset` — destruye datos). Para probar
sin tocar `toka.db`, `make smoke` corre todo contra una base temporal.
