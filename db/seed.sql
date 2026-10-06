-- seed.sql
-- SOLO PARA DESARROLLO. Idempotente: ON CONFLICT DO NOTHING salta lo que ya existe.
-- Define un hogar de demostración con credenciales públicas a propósito
-- (token "toka-dev-token", invite code "DEVDEMO234"). Nunca cargues este seed en un
-- servidor accesible desde internet: cualquiera que lea el repo entraría. Los hogares
-- reales se crean desde la app (POST /households) y sus credenciales no viven en git.
--
-- La base guarda el sha256 del token, precomputado porque SQLite no trae sha256():
--   printf 'toka-dev-token' | sha256sum

BEGIN;

INSERT INTO households (id, name, invite_code, created_by, updated_by)
VALUES (1, 'Demo', 'DEVDEMO234', 0, 0)
ON CONFLICT DO NOTHING;

INSERT INTO people (id, household_id, name, color, avatar_emoji, token_hash, row_version, created_by, updated_by)
VALUES (1, 1, 'Dev', '#a78bfa', '👽',
        '6508467d2ff3e89531eedcf99be6e35f5d2dce09e88bf7ea5259f5c92203100b', 1, 0, 0)
ON CONFLICT DO NOTHING;

UPDATE households SET created_by = 1, updated_by = 1 WHERE id = 1 AND created_by = 0;
UPDATE people SET created_by = 1, updated_by = 1 WHERE id = 1 AND created_by = 0;

-- Un par de plantillas domésticas para arrancar, asignadas a la persona de demo.
INSERT INTO task_templates (id, household_id, name, description, recurrence_days, preferred_assignee_id, is_active, row_version, created_by, updated_by) VALUES
(1, 1, 'Sacar la basura',  'Sacar la basura cada semana', 7, 1, true, 2, 1, 1),
(2, 1, 'Lavar los platos', 'Lavar los platos diario',      1, 1, true, 3, 1, 1),
(3, 1, 'Regar las plantas', 'Regar todas las plantas',     3, 1, true, 4, 1, 1)
ON CONFLICT DO NOTHING;

INSERT INTO task_instances (id, template_id, household_id, status, due_at, assigned_to_id, completed_by_id, completed_at, notes, row_version, created_by, updated_by) VALUES
(1, 1, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+7 days'),  1, NULL, NULL, NULL, 5, 1, 1),
(2, 2, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+1 days'),  1, NULL, NULL, NULL, 6, 1, 1),
(3, 3, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+3 days'),  1, NULL, NULL, NULL, 7, 1, 1)
ON CONFLICT DO NOTHING;

-- El contador global tiene que arrancar por encima de las row_version del seed.
UPDATE sync_counter SET value = MAX(value, 100);

COMMIT;
