-- 002_db_auth.down.sql
--
-- Revertir invalida todas las sesiones: los tokens en claro no se pueden recuperar
-- desde el hash, así que people.token vuelve con valores nuevos y aleatorios y todo
-- el mundo tiene que volver a entrar.

DROP POLICY IF EXISTS households_invite_lookup ON households;
DROP POLICY IF EXISTS people_token_lookup ON people;
DROP POLICY IF EXISTS instances_scope ON task_instances;
DROP POLICY IF EXISTS templates_scope ON task_templates;
DROP POLICY IF EXISTS people_scope ON people;
DROP POLICY IF EXISTS households_scope ON households;

ALTER TABLE task_instances DISABLE ROW LEVEL SECURITY;
ALTER TABLE task_templates DISABLE ROW LEVEL SECURITY;
ALTER TABLE people         DISABLE ROW LEVEL SECURITY;
ALTER TABLE households     DISABLE ROW LEVEL SECURITY;

DROP FUNCTION IF EXISTS app_invite_code();
DROP FUNCTION IF EXISTS app_token_hash();
DROP FUNCTION IF EXISTS app_household_id();

ALTER TABLE people ADD COLUMN token TEXT;
UPDATE people SET token = encode(gen_random_bytes(24), 'hex') WHERE token IS NULL;
ALTER TABLE people ALTER COLUMN token SET NOT NULL;
ALTER TABLE people ADD CONSTRAINT people_token_key UNIQUE (token);
CREATE INDEX idx_people_token ON people(token);

DROP INDEX IF EXISTS idx_people_token_hash;
ALTER TABLE people DROP COLUMN token_hash;
