-- db/roles.sql
--
-- Provisioning de roles de Postgres. Se ejecuta UNA VEZ por instalación, como
-- superusuario o como dueño de la base:
--
--     make db-roles
--
-- Es idempotente. Las contraseñas NO están aquí — este archivo va al repositorio.
-- Se fijan aparte, leyendo del entorno:
--
--     TOKA_APP_PASSWORD=... make db-password
--
-- ── El modelo ────────────────────────────────────────────────────────────────
--
--   toka           dueño de las tablas. Corre migraciones y seed. Se salta RLS
--                  (el dueño de una tabla la ve entera salvo que se active FORCE
--                  ROW LEVEL SECURITY, y aquí lo queremos así: las migraciones
--                  necesitan tocar todos los households).
--
--   toka_app       el rol del servidor en producción. Sin DDL, sin BYPASSRLS,
--                  sin superusuario. Sujeto a las policies de la migración 002:
--                  solo ve el household que la transacción declaró.
--
--   toka_readonly  para inspección y respaldos. SELECT y nada más, también
--                  sujeto a RLS.
--
-- El servidor elige con cuál entrar vía DATABASE_URL / DATABASE_MIGRATION_URL.
-- Si arranca como toka (o como cualquier superusuario) RLS no aplica y el
-- servidor lo avisa en el log al arrancar.

\set ON_ERROR_STOP on

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'toka_app') THEN
        CREATE ROLE toka_app LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'toka_readonly') THEN
        CREATE ROLE toka_readonly LOGIN;
    END IF;
END $$;

-- Explícito y repetido en cada corrida: si alguien le sube privilegios a mano,
-- la próxima ejecución los devuelve a su sitio.
ALTER ROLE toka_app      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;
ALTER ROLE toka_readonly NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;

GRANT CONNECT ON DATABASE toka TO toka_app, toka_readonly;
GRANT USAGE ON SCHEMA public TO toka_app, toka_readonly;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO toka_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO toka_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO toka_readonly;

-- Que las tablas futuras hereden esto sin tener que volver a correr el script.
ALTER DEFAULT PRIVILEGES FOR ROLE toka IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO toka_app;
ALTER DEFAULT PRIVILEGES FOR ROLE toka IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO toka_app;
ALTER DEFAULT PRIVILEGES FOR ROLE toka IN SCHEMA public
    GRANT SELECT ON TABLES TO toka_readonly;

-- El registro de migraciones es del migrador. La app lo lee para diagnóstico y
-- nada más; si pudiera escribirlo, podría marcar una migración como aplicada.
REVOKE INSERT, UPDATE, DELETE ON _migrations FROM toka_app;

-- Nadie crea objetos en public salvo el dueño.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
