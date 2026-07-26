---
description: Muestra el esquema real de la base de datos y lo contrasta con los modelos de Go
allowed-tools: Bash, Read, Grep
---

Inspecciona la base `toka` en vivo y contrástala con el código:

1. Migraciones aplicadas:
   `sudo -u postgres psql -d toka -c "SELECT name, applied_at FROM _migrations ORDER BY name"`
2. Tablas y su forma: `sudo -u postgres psql -d toka -c "\dt"` y un `\d <tabla>` por cada una.
3. Contrasta cada tabla con su struct en `internal/model/models.go`:
   - columnas presentes en la DB y ausentes en el struct (y al revés);
   - columnas nullable en la DB cuyo campo Go **no** es puntero — eso es un panic esperando
     a ocurrir en el `Scan`;
   - tablas sin las cuatro columnas de metadata o sin su trigger `trg_<tabla>_updated_at`
     (verifica los triggers con `\d`).
4. Si la base no está arriba, dilo y muestra el esquema derivado de `db/migrations/` en su
   lugar, aclarando que es lo esperado y no lo real.

Salida: una tabla por cada divergencia encontrada, o "esquema y modelos alineados".
