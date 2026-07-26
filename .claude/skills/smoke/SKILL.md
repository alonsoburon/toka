---
name: smoke
description: Probar el flujo completo de la API de Toka con curl contra el server local — crear household, unir persona, crear plantilla, listar tareas, completar y verificar que se generó la siguiente instancia recurrente. Úsala para verificar que un cambio funciona de verdad, no solo que compila.
---

# Smoke test de la API

El proyecto no tiene tests automatizados. Esto es el sustituto: ejercita el flujo real
contra Postgres y verifica los invariantes que importan.

## Ejecutar

```bash
make db-up
make run &          # o en otra terminal; espera a ver "Toka running on ..."
.claude/scripts/smoke.sh
```

El script crea su propio household desechable en cada corrida (no depende del seed, así
que es seguro correrlo N veces) e imprime `PASS`/`FAIL` por aserción. Sale con código 1
si algo falla.

Contra otro puerto o host: `BASE=http://localhost:4000 .claude/scripts/smoke.sh`

## Qué verifica

1. `POST /households` devuelve `invite_code` y el token del admin.
2. `POST /households/join` con ese código devuelve un token distinto en el mismo household.
3. Una petición autenticada sin header (o con token basura) responde `401`.
4. `POST /templates` con `recurrence_days` crea la plantilla **y** su primera instancia.
5. `GET /tasks` lista esa instancia como `pending`.
6. `POST /tasks/{id}/complete` la marca `done` **y** genera una instancia nueva `pending`
   con `due_at ≈ ahora + recurrence_days` — el invariante central de la recurrencia
   (se cuenta desde el completado, no desde el `due_at` original).
7. Una plantilla sin `recurrence_days` (one-shot) **no** regenera al completarse.
8. `GET /tasks/history?days=30` incluye la tarea completada.
9. Aislamiento entre households: el token de la casa A no ve las tareas ni las plantillas
   de la casa B.

## Si falla

- `No hay server` → arráncalo con `make run`, o pasa `BASE=` si está en otro puerto.
- `unauthorized` donde no toca → mira que el handler llame a `auth.RequireAuth` y que la
  ruta esté envuelta en `authMw` en `server.go`.
- 404 en una ruta que existe como handler → falta el `mux.Handle` en `server.go`.
- La aserción 6 falla → revisa `createNextInstance` en `internal/handler/instances.go`:
  `recurrence_days` NULL, el `INSERT` fuera de la transacción, o `due_at` calculado
  desde el `due_at` viejo en vez de desde `now`.
- La aserción 9 falla → hay una query sin `household_id` en el `WHERE`. Es una fuga de
  datos entre familias; arréglala antes de cualquier otra cosa.

## Ampliar

Al añadir un endpoint, añade su bloque al script siguiendo el patrón `step`/`req`/`check`
que ya usa. Cada aserción imprime una línea y no aborta las siguientes.
