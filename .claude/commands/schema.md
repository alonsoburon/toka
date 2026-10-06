---
description: Muestra el esquema real de la base de datos y lo contrasta con los modelos de Go
allowed-tools: Bash, Read, Grep
---

Inspecciona la base SQLite en vivo (`toka.db`, o la ruta de `TOKA_DB`) y contrástala con
el código:

1. Esquema completo: `sqlite3 toka.db .schema`
2. Migraciones aplicadas: `sqlite3 toka.db "SELECT name, applied_at FROM _migrations ORDER BY name"`
   (si la columna se llama distinto, mira `.schema _migrations`).
3. Contrasta cada tabla con su struct en `internal/model/models.go`:
   - columnas presentes en la DB y ausentes en el struct (y al revés);
   - columnas nullable en la DB cuyo campo Go **no** es puntero — eso es un error de
     `Scan` esperando a ocurrir;
   - tablas sin las cuatro columnas de metadata (`created_at`, `updated_at`,
     `created_by`, `updated_by`) o con FKs a `people(id)` que no sean
     `DEFERRABLE INITIALLY DEFERRED`;
   - tablas sincronizadas (`people`, `task_templates`, `task_instances`) sin
     `row_version` ni `client_id`.
   No busques triggers `updated_at`: en SQLite no existen, lo actualiza el handler.
4. Si `sqlite3` no está instalado o `toka.db` no existe, dilo y muestra el esquema
   derivado de `db/migrations/*.up.sql`, aclarando que es lo esperado y no lo real.

Salida: una tabla por cada divergencia encontrada, o "esquema y modelos alineados".
