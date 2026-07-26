-- db/check_rls.sql
--
-- Comprueba que el aislamiento entre households lo aplica Postgres y no el WHERE
-- del handler. Se ejecuta como superusuario y hace SET ROLE a toka_app, que es el
-- rol con el que corre el servidor en producción.
--
--     make db-check-rls
--
-- Un fallo aquí significa que un token de una familia puede llegar a los datos de
-- otra aunque el código Go esté bien escrito.

\set ON_ERROR_STOP on
\pset pager off

BEGIN;

-- Dos households con datos, creados al margen de la app.
SET LOCAL app.household_id = '';
INSERT INTO households (id, name, invite_code, created_by, updated_by)
VALUES (900001, 'RLS Casa A', 'RLSCHECKA', 0, 0), (900002, 'RLS Casa B', 'RLSCHECKB', 0, 0);

INSERT INTO people (id, household_id, name, color, avatar_emoji, token_hash, created_by, updated_by)
VALUES (900001, 900001, 'A', '#fff', '🅰', encode(sha256('rls-check-a'::bytea), 'hex'), 900001, 900001),
       (900002, 900002, 'B', '#fff', '🅱', encode(sha256('rls-check-b'::bytea), 'hex'), 900002, 900002);

UPDATE households SET created_by = 900001, updated_by = 900001 WHERE id = 900001;
UPDATE households SET created_by = 900002, updated_by = 900002 WHERE id = 900002;

INSERT INTO task_templates (id, household_id, name, recurrence_days, is_active, created_by, updated_by)
VALUES (900001, 900001, 'Tarea A', 7, true, 900001, 900001),
       (900002, 900002, 'Tarea B', 7, true, 900002, 900002);

SET ROLE toka_app;

DO $$
DECLARE
    n bigint;
BEGIN
    -- 1. Sin contexto no se ve nada. El default es negar, no permitir.
    PERFORM set_config('app.household_id', '', true);
    SELECT count(*) INTO n FROM people;
    IF n <> 0 THEN
        RAISE EXCEPTION 'FALLA: sin app.household_id se ven % personas (deberían ser 0)', n;
    END IF;
    RAISE NOTICE 'OK  sin contexto: 0 filas visibles';

    -- 2. Con contexto se ve el household propio, y solo ese.
    PERFORM set_config('app.household_id', '900001', true);
    SELECT count(*) INTO n FROM people WHERE household_id = 900002;
    IF n <> 0 THEN
        RAISE EXCEPTION 'FALLA: la casa A ve % personas de la casa B', n;
    END IF;
    SELECT count(*) INTO n FROM task_templates;
    IF n <> 1 THEN
        RAISE EXCEPTION 'FALLA: la casa A ve % plantillas (debería ver 1)', n;
    END IF;
    RAISE NOTICE 'OK  con contexto: solo el household propio';

    -- 3. Una consulta SIN filtro de household — el bug clásico — tampoco filtra.
    --    Este es el punto de todo el ejercicio: da igual lo que escriba el handler.
    SELECT count(*) INTO n FROM task_templates WHERE id = 900002;
    IF n <> 0 THEN
        RAISE EXCEPTION 'FALLA: un SELECT por id sin filtrar alcanzó otro household';
    END IF;
    RAISE NOTICE 'OK  SELECT por id sin filtro: bloqueado por la policy';

    -- 4. Escribir en otro household tampoco.
    UPDATE task_templates SET name = 'secuestrada' WHERE id = 900002;
    IF FOUND THEN
        RAISE EXCEPTION 'FALLA: se pudo editar una plantilla de otro household';
    END IF;
    RAISE NOTICE 'OK  UPDATE cruzado: 0 filas afectadas';

    -- 5. Insertar en otro household lo rechaza el WITH CHECK.
    BEGIN
        INSERT INTO task_templates (household_id, name, is_active, created_by, updated_by)
        VALUES (900002, 'infiltrada', true, 900001, 900001);
        RAISE EXCEPTION 'FALLA: se pudo insertar en otro household';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'OK  INSERT cruzado: rechazado por WITH CHECK';
    END;

    -- 6. El login por token no necesita household previo, y solo devuelve su fila.
    PERFORM set_config('app.household_id', '', true);
    PERFORM set_config('app.token_hash', encode(sha256('rls-check-a'::bytea), 'hex'), true);
    SELECT count(*) INTO n FROM people;
    IF n <> 1 THEN
        RAISE EXCEPTION 'FALLA: el lookup por token devolvió % filas (debería ser 1)', n;
    END IF;
    RAISE NOTICE 'OK  lookup por token: exactamente 1 fila';
END $$;

RESET ROLE;

ROLLBACK;

\echo ''
\echo 'RLS verificado.'
