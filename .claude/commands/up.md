---
description: Arranca el backend (SQLite, sin daemon) y deja el entorno listo para probar
allowed-tools: Bash
---

Arranca el entorno de desarrollo de Toka y déjalo verificado. No hay Postgres ni Docker:
la base es un archivo SQLite (`TOKA_DB`, por defecto `toka.db`).

1. Comprueba si el server ya está arriba: `curl -s http://localhost:3000/healthz`
   (debe responder `{"status":"ok"}`). Si responde, no arranques otro.
2. Si no responde, arráncalo **desde la raíz del repo** (lee `db/migrations/` y
   `db/seed.sql` del disco) en background y espera a que `/healthz` conteste:
   - `make run` — migra al arrancar y conserva los datos existentes;
   - `make run-seed` — además carga `db/seed.sql` (idempotente). El seed es solo de
     desarrollo.
   Usa `make run-seed` solo si se pidió o si `toka.db` no existe; si no, `make run`.
3. Reporta en dos líneas: estado del server y el token de seed disponible
   (`toka-dev-token`, solo si la base tiene el seed cargado — verifícalo con
   `curl -s http://localhost:3000/me -H "Authorization: Bearer toka-dev-token"`, no lo
   asumas).

No ejecutes `make db-reset` (destruye datos) salvo que se pida explícitamente.
