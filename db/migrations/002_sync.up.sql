-- 002_sync.up.sql
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
-- ── Por qué un contador y no un AUTOINCREMENT ────────────────────────────────
--
-- Un contador asignado por la aplicación bajo un lock que dura hasta el COMMIT
-- obliga a que el orden de las versiones sea el orden real de confirmación. SQLite
-- serializa las escrituras de todos modos (un solo escritor), pero el contador lo
-- deja explícito y portable. El precio es que todas las escrituras se serializan
-- en esa fila; a escala doméstica es irrelevante.

CREATE TABLE sync_counter (
    only_row INTEGER PRIMARY KEY DEFAULT 1 CHECK (only_row = 1),
    value    INTEGER NOT NULL DEFAULT 0
);
INSERT INTO sync_counter (only_row, value) VALUES (1, 0);

-- ── Versionado de las tablas sincronizadas ───────────────────────────────────

ALTER TABLE people         ADD COLUMN row_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task_templates ADD COLUMN row_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task_instances ADD COLUMN row_version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_people_sync    ON people(household_id, row_version);
CREATE INDEX idx_templates_sync ON task_templates(household_id, row_version);
CREATE INDEX idx_instances_sync ON task_instances(household_id, row_version);

-- ── Identificadores generados por el cliente ─────────────────────────────────
--
-- Una tarea creada sin conexión necesita un id antes de que exista el servidor que
-- se lo dé. El cliente genera un UUID, lo usa como clave local, y el servidor lo
-- guarda junto a su id. Al sincronizar, el cliente reconcilia los dos.
--
-- El índice único es la segunda red de seguridad contra duplicados: aunque se
-- perdiera el registro de mutaciones, reenviar una creación choca contra él en vez
-- de crear una tarea repetida.

ALTER TABLE task_templates ADD COLUMN client_id TEXT;
ALTER TABLE task_instances ADD COLUMN client_id TEXT;
ALTER TABLE people         ADD COLUMN client_id TEXT;

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
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    entity       TEXT   NOT NULL CHECK (entity IN ('person', 'template', 'task')),
    entity_id    INTEGER NOT NULL,
    row_version  INTEGER NOT NULL DEFAULT 0,
    deleted_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    UNIQUE (household_id, entity, entity_id)
);

CREATE INDEX idx_tombstones_sync ON sync_tombstones(household_id, row_version);

-- ── Mutaciones ───────────────────────────────────────────────────────────────
--
-- La cola de escrituras del cliente. mutation_id lo genera el cliente y es la
-- clave primaria: reenviar es gratis y no duplica.
--
-- Se guarda la respuesta completa porque un reenvío tiene que devolver lo mismo que
-- la primera vez. Si la primera aplicación creó la tarea 42, el reintento tras un
-- timeout de red tiene que volver a decir 42 y no crear la 43.

CREATE TABLE sync_mutations (
    mutation_id  TEXT PRIMARY KEY,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    person_id    INTEGER NOT NULL REFERENCES people(id),
    op           TEXT   NOT NULL,
    status       INTEGER NOT NULL,
    response     TEXT   NOT NULL,
    applied_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now'))
);

CREATE INDEX idx_mutations_household ON sync_mutations(household_id, applied_at);
