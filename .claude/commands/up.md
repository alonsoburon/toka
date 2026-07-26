---
description: Arranca Postgres y el backend, y deja el entorno listo para probar
allowed-tools: Bash
---

Arranca el entorno de desarrollo de Toka y déjalo verificado:

1. Comprueba si Postgres ya responde: `pg_isready -h /run/postgresql`.
   Si no, arráncalo con `make db-up`. Si falla con
   `could not create lock file "/run/postgresql/..."`, el directorio de runtime no existe
   tras el reinicio — créalo con
   `sudo install -d -o postgres -g postgres -m 2775 /run/postgresql` y reintenta.
2. Comprueba si el server ya está arriba: `curl -s -o /dev/null http://localhost:3000/tasks`.
   Si no, arráncalo en background y espera a que responda.
3. Reporta en tres líneas: estado de Postgres, estado del server, y el token de seed
   disponible (`seed-token-alonso-abc123`, solo si la base tiene el seed cargado — verifica
   con una consulta, no lo asumas).

No cargues el seed ni resetees la base salvo que se pida explícitamente.
