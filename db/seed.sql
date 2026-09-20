-- seed.sql
-- Idempotente: se puede correr varias veces. ON CONFLICT DO NOTHING salta lo que
-- ya existe. Crea un household de prueba, su admin, más personas, plantillas e
-- instancias.
--
-- Los tokens de desarrollo siguen siendo los mismos en claro; lo que se guarda es
-- su sha256, precomputado porque SQLite no trae sha256().

BEGIN;

INSERT INTO households (id, name, invite_code, created_by, updated_by)
VALUES (1, 'Casa Dulce Hogar', 'SEEDHOME', 0, 0)
ON CONFLICT DO NOTHING;

INSERT INTO people (id, household_id, name, color, avatar_emoji, token_hash, row_version, created_by, updated_by)
VALUES (1, 1, 'Alonso', '#f472b6', '🐱',
        'aaa785449aa6b58b6f38884b5340c79cbee5f1a1cf504bcf76fa1483b8c417df', 1, 0, 0)
ON CONFLICT DO NOTHING;

UPDATE households SET created_by = 1, updated_by = 1 WHERE id = 1 AND created_by = 0;
UPDATE people SET created_by = 1, updated_by = 1 WHERE id = 1 AND created_by = 0;

INSERT INTO people (id, household_id, name, color, avatar_emoji, token_hash, row_version, created_by, updated_by) VALUES
(2, 1, 'Ana',    '#60a5fa', '🐶', '9e21b6adb8cc6fadabda00eea9b5ffc53920e433356e932e9465b4d2198b903f', 2, 1, 1),
(3, 1, 'Carlos', '#a3e635', '🦊', 'a75a657eabe64871c4ec11e259bacc626a3bc169a5580f4b02afada1390ecfd6', 3, 1, 1)
ON CONFLICT DO NOTHING;

INSERT INTO task_templates (id, household_id, name, description, recurrence_days, preferred_assignee_id, is_active, row_version, created_by, updated_by) VALUES
(1, 1, 'Limpiar el refrigerador', 'Limpiar a fondo cada mes',            30,   1,    true, 4, 1, 1),
(2, 1, 'Sacar la basura',         'Sacar la basura cada semana',          7,   2,    true, 5, 1, 1),
(3, 1, 'Regar las plantas',       'Regar todas las plantas de la casa',   3,   3,    true, 6, 1, 1),
(4, 1, 'Pintar la sala',          'Pintar la sala de estar',           NULL, NULL, true, 7, 1, 1),
(5, 1, 'Lavar los platos',        'Lavar los platos diario',              1, NULL, true, 8, 1, 1),
(6, 1, 'Organizar el closet',     'Organizar el closet principal',     NULL,   2, true, 9, 1, 1)
ON CONFLICT DO NOTHING;

INSERT INTO task_instances (id, template_id, household_id, status, due_at, assigned_to_id, completed_by_id, completed_at, notes, row_version, created_by, updated_by) VALUES
(1,  1, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+5 days'),   1, NULL, NULL, NULL,                              10, 1, 1),
(2,  2, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','-2 days'),   2, NULL, NULL, 'Basura sin sacar, urgente',       11, 1, 1),
(3,  3, 1, 'done',    strftime('%Y-%m-%d %H:%M:%f+00:00','now','-1 days'),   3, 3, strftime('%Y-%m-%d %H:%M:%f+00:00','now','-1 days'), 'Plantas regadas', 12, 1, 1),
(4,  4, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+14 days'),  NULL, NULL, NULL, NULL,                           13, 1, 1),
(5,  5, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+12 hours'), NULL, NULL, NULL, NULL,                           14, 1, 1),
(6,  2, 1, 'skipped', strftime('%Y-%m-%d %H:%M:%f+00:00','now','-14 days'),  2, NULL, NULL, 'No habia basura que sacar',        15, 1, 1),
(7,  6, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+7 days'),   2, NULL, NULL, NULL,                            16, 1, 1),
(8,  5, 1, 'done',    strftime('%Y-%m-%d %H:%M:%f+00:00','now','-3 days'),   1, 1, strftime('%Y-%m-%d %H:%M:%f+00:00','now','-3 days'), 'Platos limpios', 17, 1, 1),
(9,  3, 1, 'pending', strftime('%Y-%m-%d %H:%M:%f+00:00','now','+2 days'),   3, NULL, NULL, NULL,                            18, 1, 1),
(10, 1, 1, 'skipped', strftime('%Y-%m-%d %H:%M:%f+00:00','now','-35 days'),  1, NULL, NULL, 'Refrigerador estaba limpio',       19, 1, 1)
ON CONFLICT DO NOTHING;

-- El contador global tiene que arrancar por encima de las row_version del seed.
UPDATE sync_counter SET value = MAX(value, 100);

COMMIT;
