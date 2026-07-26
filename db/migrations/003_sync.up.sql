-- 003_sync.up.sql
--
-- Protocolo de sincronización para el cliente offline.
--
-- Tres piezas:
--
--   1. row_version — un contador global monótono. El cliente guarda hasta dónde
--      leyó y pide "todo lo que cambió después de N". Es el cursor.
--
--   2. sync_tombstones — las bajas también son un cambio. Sin esto, un cliente que
--      estuvo desconectado nunca se entera de lo que se borró.
--
--   3. sync_mutations — las escrituras que el cliente hizo offline llegan con un id
--      generado por él. El id es la clave primaria, así que reenviar la misma
--      mutación no la aplica dos veces: devuelve la respuesta que ya se guardó.
--
-- ── Por qué un contador y no una secuencia ───────────────────────────────────
--
-- Una secuencia (BIGSERIAL) reparte números antes del COMMIT. Dos transacciones
-- pueden tomar 5 y 6 y confirmar en orden inverso. Un cliente que sincroniza justo
-- en medio lee hasta 6, guarda 6 como cursor, y la fila 5 no aparece nunca más:
-- pérdida silenciosa de datos, del tipo que no se reproduce en desarrollo.
--
-- El UPDATE sobre una fila única toma un lock que se libera recién en el COMMIT, y
-- eso obliga a que el orden de confirmación sea el mismo que el de asignación. Si
-- una transacción tiene la versión 5, ninguna otra pudo tomar la 6 hasta que la
-- primera confirmó.
--
-- El precio es que todas las escrituras se serializan en esa fila. Para una app de
-- tareas domésticas es irrelevante. Si algún día deja de serlo, el reemplazo no es
-- volver a una secuencia sino leer el WAL (replicación lógica) o llevar el contador
-- por household.

CREATE TABLE sync_counter (
    only_row BOOLEAN PRIMARY KEY DEFAULT true CHECK (only_row),
    value    BIGINT NOT NULL DEFAULT 0
);
INSERT INTO sync_counter (only_row, value) VALUES (true, 0);

CREATE FUNCTION next_row_version() RETURNS BIGINT
    LANGUAGE sql
    AS $$ UPDATE sync_counter SET value = value + 1 RETURNING value $$;

-- ── Versionado de las tablas sincronizadas ───────────────────────────────────

ALTER TABLE people         ADD COLUMN row_version BIGINT NOT NULL DEFAULT next_row_version();
ALTER TABLE task_templates ADD COLUMN row_version BIGINT NOT NULL DEFAULT next_row_version();
ALTER TABLE task_instances ADD COLUMN row_version BIGINT NOT NULL DEFAULT next_row_version();

CREATE FUNCTION bump_row_version() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at  = now();
    NEW.row_version = next_row_version();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Reemplaza a set_updated_at() en las tablas que se sincronizan: hace lo mismo y
-- además mueve la versión. households no se sincroniza fila a fila, así que
-- conserva el trigger original.
DROP TRIGGER trg_people_updated_at ON people;
DROP TRIGGER trg_task_templates_updated_at ON task_templates;
DROP TRIGGER trg_task_instances_updated_at ON task_instances;

CREATE TRIGGER trg_people_row_version
    BEFORE UPDATE ON people
    FOR EACH ROW EXECUTE FUNCTION bump_row_version();
CREATE TRIGGER trg_task_templates_row_version
    BEFORE UPDATE ON task_templates
    FOR EACH ROW EXECUTE FUNCTION bump_row_version();
CREATE TRIGGER trg_task_instances_row_version
    BEFORE UPDATE ON task_instances
    FOR EACH ROW EXECUTE FUNCTION bump_row_version();

CREATE INDEX idx_people_sync    ON people(household_id, row_version);
CREATE INDEX idx_templates_sync ON task_templates(household_id, row_version);
CREATE INDEX idx_instances_sync ON task_instances(household_id, row_version);

-- ── Identificadores generados por el cliente ─────────────────────────────────
--
-- Una tarea creada sin conexión necesita un id antes de que exista el servidor que
-- se lo dé. El cliente genera un UUID, lo usa como clave local, y el servidor lo
-- guarda junto a su BIGSERIAL. Al sincronizar, el cliente reconcilia los dos.
--
-- El índice único es la segunda red de seguridad contra duplicados: aunque se
-- perdiera el registro de mutaciones, reenviar una creación choca contra él en vez
-- de crear una tarea repetida.

ALTER TABLE task_templates ADD COLUMN client_id UUID;
ALTER TABLE task_instances ADD COLUMN client_id UUID;
ALTER TABLE people         ADD COLUMN client_id UUID;

CREATE UNIQUE INDEX idx_templates_client_id ON task_templates(household_id, client_id)
    WHERE client_id IS NOT NULL;
CREATE UNIQUE INDEX idx_instances_client_id ON task_instances(household_id, client_id)
    WHERE client_id IS NOT NULL;
CREATE UNIQUE INDEX idx_people_client_id ON people(household_id, client_id)
    WHERE client_id IS NOT NULL;

-- ── Bajas ────────────────────────────────────────────────────────────────────
--
-- Hoy Toka no borra nada de verdad (las plantillas se desactivan con is_active y
-- viajan como una modificación más), así que esta tabla nace vacía. Está igual
-- porque un protocolo de sincronización que no sabe expresar una baja hay que
-- versionarlo el día que aparezca la primera.

CREATE TABLE sync_tombstones (
    id           BIGSERIAL PRIMARY KEY,
    household_id BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    entity       TEXT   NOT NULL CHECK (entity IN ('person', 'template', 'task')),
    entity_id    BIGINT NOT NULL,
    row_version  BIGINT NOT NULL DEFAULT next_row_version(),
    deleted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (household_id, entity, entity_id)
);

CREATE INDEX idx_tombstones_sync ON sync_tombstones(household_id, row_version);

-- ── Mutaciones ───────────────────────────────────────────────────────────────
--
-- La cola de escrituras del cliente. mutation_id lo genera el cliente (UUIDv7, que
-- ordena por tiempo) y es la clave primaria: reenviar es gratis y no duplica.
--
-- Se guarda la respuesta completa porque un reenvío tiene que devolver lo mismo que
-- la primera vez. Si la primera aplicación creó la tarea 42, el reintento tras un
-- timeout de red tiene que volver a decir 42 y no crear la 43.

CREATE TABLE sync_mutations (
    mutation_id  UUID PRIMARY KEY,
    household_id BIGINT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    person_id    BIGINT NOT NULL REFERENCES people(id),
    op           TEXT   NOT NULL,
    status       INT    NOT NULL,
    response     JSONB  NOT NULL,
    applied_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mutations_household ON sync_mutations(household_id, applied_at);

-- ── RLS para las tablas nuevas ───────────────────────────────────────────────

ALTER TABLE sync_tombstones ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_mutations  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tombstones_scope ON sync_tombstones
    FOR ALL USING (household_id = app_household_id())
            WITH CHECK (household_id = app_household_id());

CREATE POLICY mutations_scope ON sync_mutations
    FOR ALL USING (household_id = app_household_id())
            WITH CHECK (household_id = app_household_id());

-- sync_counter es global a propósito: es un solo contador para toda la instalación,
-- no tiene household. No lleva RLS, pero tampoco datos — solo un número.
GRANT SELECT, UPDATE ON sync_counter TO toka_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON sync_tombstones, sync_mutations TO toka_app;
GRANT USAGE, SELECT ON SEQUENCE sync_tombstones_id_seq TO toka_app;
