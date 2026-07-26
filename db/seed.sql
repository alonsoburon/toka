-- seed.sql
-- Idempotent: safe to run multiple times. ON CONFLICT skips existing rows.
-- Creates a test household, admin, extra people, templates, and instances.

BEGIN;

INSERT INTO households (id, name, invite_code, created_by, updated_by)
VALUES (1, 'Casa Dulce Hogar', 'SEEDHOME', 0, 0)
ON CONFLICT DO NOTHING;

-- Los tokens de desarrollo siguen siendo los mismos en claro; lo que se guarda es
-- su sha256, igual que hace el servidor al crear una persona de verdad.
INSERT INTO people (id, household_id, name, color, avatar_emoji, token_hash, created_by, updated_by)
VALUES (1, 1, 'Alonso', '#f472b6', '🐱', encode(sha256('seed-token-alonso-abc123'::bytea), 'hex'), 0, 0)
ON CONFLICT DO NOTHING;

UPDATE households SET created_by = 1, updated_by = 1 WHERE id = 1 AND created_by = 0;
UPDATE people SET created_by = 1, updated_by = 1 WHERE id = 1 AND created_by = 0;

INSERT INTO people (id, household_id, name, color, avatar_emoji, token_hash, created_by, updated_by) VALUES
(2, 1, 'Ana',    '#60a5fa', '🐶', encode(sha256('seed-token-ana-def456'::bytea), 'hex'),    1, 1),
(3, 1, 'Carlos', '#a3e635', '🦊', encode(sha256('seed-token-carlos-ghi789'::bytea), 'hex'), 1, 1)
ON CONFLICT DO NOTHING;

INSERT INTO task_templates (id, household_id, name, description, recurrence_days, preferred_assignee_id, is_active, created_by, updated_by) VALUES
(1, 1, 'Limpiar el refrigerador', 'Limpiar a fondo cada mes',    30, 1,    true, 1, 1),
(2, 1, 'Sacar la basura',         'Sacar la basura cada semana',  7, 2,    true, 1, 1),
(3, 1, 'Regar las plantas',       'Regar todas las plantas de la casa', 3, 3, true, 1, 1),
(4, 1, 'Pintar la sala',          'Pintar la sala de estar',    NULL, NULL, true, 1, 1),
(5, 1, 'Lavar los platos',        'Lavar los platos diario',      1, NULL, true, 1, 1),
(6, 1, 'Organizar el closet',     'Organizar el closet principal', NULL, 2, true, 1, 1)
ON CONFLICT DO NOTHING;

INSERT INTO task_instances (id, template_id, household_id, status, due_at, assigned_to_id, completed_by_id, completed_at, notes, created_by, updated_by) VALUES
(1,  1, 1, 'pending',  now() + interval '5 days',   1, NULL, NULL,                         NULL,                              1, 1),
(2,  2, 1, 'pending',  now() - interval '2 days',   2, NULL, NULL,                         'Basura sin sacar, urgente',       1, 1),
(3,  3, 1, 'done',     now() - interval '1 day',    3, 3,    now() - interval '1 day',     'Plantas regadas',                 1, 1),
(4,  4, 1, 'pending',  now() + interval '14 days',  NULL, NULL, NULL,                     NULL,                              1, 1),
(5,  5, 1, 'pending',  now() + interval '12 hours', NULL, NULL, NULL,                     NULL,                              1, 1),
(6,  2, 1, 'skipped',  now() - interval '14 days',  2, NULL, NULL,                        'No habia basura que sacar',       1, 1),
(7,  6, 1, 'pending',  now() + interval '7 days',   2, NULL, NULL,                        NULL,                              1, 1),
(8,  5, 1, 'done',     now() - interval '3 days',   1, 1,    now() - interval '3 days',   'Platos limpios',                  1, 1),
(9,  3, 1, 'pending',  now() + interval '2 days',   3, NULL, NULL,                        NULL,                              1, 1),
(10, 1, 1, 'skipped',  now() - interval '35 days',  1, NULL, NULL,                        'Refrigerador estaba limpio',      1, 1)
ON CONFLICT DO NOTHING;

SELECT setval('households_id_seq', COALESCE((SELECT MAX(id) FROM households), 0));
SELECT setval('people_id_seq', COALESCE((SELECT MAX(id) FROM people), 0));
SELECT setval('task_templates_id_seq', COALESCE((SELECT MAX(id) FROM task_templates), 0));
SELECT setval('task_instances_id_seq', COALESCE((SELECT MAX(id) FROM task_instances), 0));

COMMIT;
