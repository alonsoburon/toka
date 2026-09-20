-- 001_init.up.sql
--
-- Esquema base de Toka sobre SQLite.
--
-- SQLite no tiene tipos nativos de fecha ni booleanos. Las fechas se declaran
-- TIMESTAMP (afinidad NUMERIC, pero guardan el texto tal cual) para que el driver
-- las entregue como time.Time; el formato es ISO-8601 UTC de ancho fijo, que ordena
-- lexicográficamente. Los booleanos son INTEGER 0/1.

-- `households` y `people` se referencian mutuamente. SQLite resuelve las FKs de
-- forma diferida: la tabla puede referenciar a otra que todavía no existe, y con
-- DEFERRABLE INITIALLY DEFERRED la fila se valida recién al COMMIT. Eso permite
-- crear el household y su admin en la misma transacción.

CREATE TABLE households (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT NOT NULL,
    invite_code  TEXT UNIQUE NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    updated_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    created_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED,
    updated_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE people (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    color        TEXT NOT NULL DEFAULT '#a78bfa',
    avatar_emoji TEXT NOT NULL DEFAULT '🐣',
    token_hash   TEXT UNIQUE NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    updated_at   TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    created_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED,
    updated_by   INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE task_templates (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    household_id          INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,
    description           TEXT,
    recurrence_days       INTEGER,
    preferred_assignee_id INTEGER REFERENCES people(id),
    is_active             BOOLEAN NOT NULL DEFAULT true,
    created_at            TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    updated_at            TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    created_by            INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED,
    updated_by            INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE task_instances (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id     INTEGER NOT NULL REFERENCES task_templates(id) ON DELETE CASCADE,
    household_id    INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'skipped')),
    due_at          TIMESTAMP NOT NULL,
    assigned_to_id  INTEGER REFERENCES people(id),
    completed_by_id INTEGER REFERENCES people(id),
    completed_at    TIMESTAMP,
    notes           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    updated_at      TIMESTAMP NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now')),
    created_by      INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED,
    updated_by      INTEGER NOT NULL DEFAULT 0 REFERENCES people(id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_people_household           ON people(household_id);
CREATE INDEX idx_templates_household        ON task_templates(household_id) WHERE is_active = true;
CREATE INDEX idx_instances_household_status ON task_instances(household_id, status);
CREATE INDEX idx_instances_template         ON task_instances(template_id);
CREATE INDEX idx_instances_due_at           ON task_instances(household_id, due_at) WHERE status = 'pending';
