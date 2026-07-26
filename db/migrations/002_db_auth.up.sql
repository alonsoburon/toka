-- 002_db_auth.up.sql
--
-- Capa de auth dentro de la base de datos.
--
-- Dos cambios independientes pero relacionados:
--
--   1. Los bearer tokens dejan de guardarse en claro. Se guarda sha256(token); el
--      token solo existe en claro en la respuesta que se le entrega al cliente. Un
--      dump de la base (o un Postgres gestionado comprometido) ya no entrega sesiones.
--
--   2. Row-Level Security. El aislamiento entre households deja de depender de que
--      cada handler recuerde poner "AND household_id = $n" y pasa a ser una policy
--      del motor. El servidor declara en qué household está trabajando con
--      set_config('app.household_id', ..., true) al abrir la transacción, y Postgres
--      filtra. Dentro de ese household no hay más granularidad: cualquier persona
--      puede operar sobre cualquier otra, que es el modelo de confianza que queremos.
--
-- Requiere el rol no-superusuario toka_app (ver db/roles.sql). El dueño de las
-- tablas sigue saltándose RLS a propósito: las migraciones y el seed corren con él.

-- pgcrypto solo para gen_random_bytes (los hashes usan sha256(), que es nativo).
-- Es una extensión "trusted": la puede crear el dueño de la base, no hace falta
-- superusuario, y está disponible en los Postgres gestionados habituales.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── 1. Tokens hasheados ──────────────────────────────────────────────────────

ALTER TABLE people ADD COLUMN token_hash TEXT;

UPDATE people SET token_hash = encode(sha256(token::bytea), 'hex') WHERE token_hash IS NULL;

ALTER TABLE people ALTER COLUMN token_hash SET NOT NULL;

DROP INDEX IF EXISTS idx_people_token;
ALTER TABLE people DROP CONSTRAINT IF EXISTS people_token_key;
ALTER TABLE people DROP COLUMN token;

CREATE UNIQUE INDEX idx_people_token_hash ON people(token_hash);

-- ── 2. Contexto de sesión ────────────────────────────────────────────────────
--
-- Los tres settings los fija el servidor con set_config(..., is_local => true), o
-- sea que valen solo hasta el fin de la transacción. Es deliberado: el pool reusa
-- conexiones entre peticiones y un setting de sesión se filtraría a la siguiente.
--
-- Sin setting, current_setting(..., true) devuelve NULL y toda policy da falso:
-- el default es negar.

CREATE FUNCTION app_household_id() RETURNS BIGINT
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.household_id', true), '')::bigint $$;

CREATE FUNCTION app_token_hash() RETURNS TEXT
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.token_hash', true), '') $$;

CREATE FUNCTION app_invite_code() RETURNS TEXT
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.invite_code', true), '') $$;

COMMENT ON FUNCTION app_household_id() IS
    'Household activo en la transacción actual. Lo fija el servidor tras resolver el bearer token.';

-- ── 3. Policies ──────────────────────────────────────────────────────────────

ALTER TABLE households     ENABLE ROW LEVEL SECURITY;
ALTER TABLE people         ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_instances ENABLE ROW LEVEL SECURITY;

-- El household activo, y nada más.
CREATE POLICY households_scope ON households
    FOR ALL USING (id = app_household_id())
            WITH CHECK (id = app_household_id());

CREATE POLICY people_scope ON people
    FOR ALL USING (household_id = app_household_id())
            WITH CHECK (household_id = app_household_id());

CREATE POLICY templates_scope ON task_templates
    FOR ALL USING (household_id = app_household_id())
            WITH CHECK (household_id = app_household_id());

CREATE POLICY instances_scope ON task_instances
    FOR ALL USING (household_id = app_household_id())
            WITH CHECK (household_id = app_household_id());

-- Login: resolver un bearer token sin saber todavía a qué household pertenece.
-- Deja pasar exactamente la fila cuyo hash coincide con el que se está presentando,
-- así que no sirve para enumerar personas: hay que traer el token correcto de antemano.
CREATE POLICY people_token_lookup ON people
    FOR SELECT USING (token_hash = app_token_hash());

-- Join: encontrar el household por invite_code, con la misma lógica.
CREATE POLICY households_invite_lookup ON households
    FOR SELECT USING (invite_code = app_invite_code());

-- ── 4. Endurecimiento de la generación de códigos ────────────────────────────
--
-- El servidor genera tokens e invite codes con crypto/rand a partir de esta
-- migración. Los invite codes viejos se generaron con math/rand, que es predecible
-- y basta un invite code para entrar a un household: se invalidan todos.

UPDATE households SET invite_code = encode(gen_random_bytes(6), 'hex');
