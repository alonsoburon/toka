---
name: migration
description: Crear una migración SQL nueva en db/migrations siguiendo las convenciones de Toka (columnas de metadata, trigger updated_at, FKs deferrable, par up/down, índices). Úsala cuando haya que cambiar el esquema — tabla nueva, columna nueva, índice, enum.
---

# Migración nueva

## 1. Numera

```bash
ls db/migrations/
```

Toma el número más alto y suma 1, con tres dígitos: `002`, `003`, … El nombre va en
snake_case y describe el cambio: `002_add_task_priority`.

Crea **siempre el par**: `NNN_nombre.up.sql` y `NNN_nombre.down.sql`. El `.down.sql`
tiene que deshacer exactamente lo del `.up.sql`, en orden inverso.

**Nunca edites una migración que ya se aplicó** (está registrada en `_migrations`;
el server la salta y tu cambio no correría nunca). Añade una nueva.

## 2. Si creas una tabla

Copia esta plantilla completa — las cuatro columnas de metadata no son opcionales:

```sql
-- NNN_nombre.up.sql

CREATE TABLE cosas (
    id           BIGSERIAL PRIMARY KEY,
    household_id BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    -- ... campos propios ...
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT NOT NULL,
    updated_by   BIGINT NOT NULL
);

CREATE TRIGGER trg_cosas_updated_at
    BEFORE UPDATE ON cosas
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE cosas ADD CONSTRAINT fk_cosas_created_by
    FOREIGN KEY (created_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE cosas ADD CONSTRAINT fk_cosas_updated_by
    FOREIGN KEY (updated_by) REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_cosas_household ON cosas(household_id);
```

Y el down:

```sql
-- NNN_nombre.down.sql
DROP TABLE IF EXISTS cosas;   -- el trigger y los índices caen con la tabla
```

Puntos donde es fácil equivocarse:

- `set_updated_at()` ya existe (migración 001) — no la vuelvas a crear.
- Las FKs a `people` van **fuera** del `CREATE TABLE` y **deferrable**; el patrón del
  repo lo necesita porque hay ciclos de referencia en el bootstrap del household.
- Toda tabla con datos por household lleva `household_id ... ON DELETE CASCADE`.
- Índices parciales cuando la query siempre filtra por un estado:
  `CREATE INDEX ... ON t(household_id, due_at) WHERE status = 'pending'`.

## 3. Si añades una columna

```sql
ALTER TABLE task_templates ADD COLUMN priority INT NOT NULL DEFAULT 0;
```

Con `NOT NULL` **exige** `DEFAULT`, si no la migración falla contra datos existentes.
Down: `ALTER TABLE task_templates DROP COLUMN priority;`

Si la columna es un enum nuevo: `CREATE TYPE x AS ENUM (...)` en el up y
`DROP TYPE x` en el down, después del `DROP COLUMN`.

## 4. Propaga

Un cambio de esquema casi nunca termina en el SQL:

1. `internal/model/models.go` — campo nuevo con su tag `json`, puntero si es nullable.
2. El `SELECT` **y** el `Scan` de cada handler que toque esa tabla — están acoplados por
   posición, si añades una columna al SELECT tienes que añadir el `&campo` al Scan.
3. `INSERT`/`UPDATE` correspondientes (los `PATCH` usan `COALESCE($n, columna)`).
4. `db/seed.sql` si el dato nuevo tiene sentido en los datos de prueba
   (manteniendo `ON CONFLICT DO NOTHING`).
5. Android: `data/api/ApiModels.kt` para el DTO equivalente.
6. `CLAUDE.md` si cambian las convenciones o las rutas.

## 5. Aplica y verifica

```bash
make db-up
make run          # migra al arrancar e imprime "  ✓ NNN_nombre.up.sql"
```

Comprueba que quedó registrada y que la forma es la esperada:

```bash
sudo -u postgres psql -d toka -c "SELECT name, applied_at FROM _migrations ORDER BY name"
sudo -u postgres psql -d toka -c "\d cosas"
```

Si la migración falló a medias, el `_migrations` no se escribió: corrige el SQL y vuelve
a arrancar. Para partir de cero, `make db-reset` (destruye datos, pide confirmación).
