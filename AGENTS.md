# AGENTS.md — Toka

Backend Go + SQLite (un solo archivo, sin daemon ni roles) y cliente Android (Compose,
offline-first) para tareas domésticas compartidas. El contexto profundo vive en
**`CLAUDE.md`** (esquema, auth, sync, tabla de rutas): léelo antes de tocar el backend.
El `/migration`, `/endpoint`, `/smoke` y `/android-feature` de `.claude/skills/` tienen
el paso a paso de cada tarea.

## Comandos

```bash
make run-seed   # migra + siembra + sirve en :3000 (copia .env.example → .env si falta)
make run        # migra + sirve
make db-reset   # ⚠️ borra toka.db y lo recrea con seed
make curl-setup # cheat-sheet de todos los endpoints
```

- El server **lee `db/migrations/` y `db/seed.sql` del disco en runtime**: ejecútalo
  desde la raíz del repo. Flags: `-seed`, `-port`, `-migrate-only`, `-env <archivo>`.
- `TOKA_DB` elige el archivo SQLite (default `toka.db`).
- Android: `cd android && ./gradlew assembleDebug` (o `compileDebugKotlin` para verificar
  rápido). `BASE_URL` está hardcodeada como IP de LAN en `android/app/build.gradle.kts`.

## Verificación

No hay tests unitarios en Go. Al terminar un cambio:

1. `gofmt -w .` y luego `gofmt -l .` vacío (no hay target de `make` para esto).
2. `go build ./...` y `go vet ./...`.
3. Con el server arriba: `.claude/scripts/smoke.sh` y `.claude/scripts/smoke-sync.sh`.
4. Android: `cd android && ./gradlew compileDebugKotlin`.

`.claude/commands/check.md` describe el orden completo. El repo tiene un solo commit y
sin flujo de PR documentado; no inventes convenciones de ramas.

## Reglas que un agente suele romper

- **Aislamiento por household: lo aplica el código, no la DB.** SQLite no trae Row-Level
  Security; **toda query autenticada filtra por `household_id`** y es la única frontera
  entre familias. Olvidarla es una fuga entre familias.
- **Contrato JSON sincronizado en dos puntas:** `internal/server/server.go` ↔
  `data/api/TokaApi.kt` + `ApiModels.kt`. Si es una escritura offline, añádela al dispatch
  de `applyOp` en `internal/handler/sync.go` o la app no podrá hacerla sin red.
- **Toda escritura versionada asigna `row_version = db.NextRowVersion(tx)`** y, si es un
  UPDATE, actualiza `updated_at` a mano (SQLite no tiene trigger `set_updated_at()`).
- **Migraciones:** par `.up.sql`/`.down.sql`, numeradas, nunca editar una ya aplicada.
  Toda tabla lleva `created_at/updated_at/created_by/updated_by`, FKs a `people` como
  `DEFERRABLE INITIALLY DEFERRED` e índices.
- **Escrituras en transacción:** `BeginTx` + `defer Rollback` + `Commit` explícito;
  `created_by`/`updated_by` salen de `auth.RequireAuth`, nunca del body.
- **PATCH usa `COALESCE(?, columna)`** en los handlers HTTP y en `applyOp`, para que un
  campo ausente no borre el valor existente y online/offline se comporten igual.
- **Recurrencia:** la siguiente instancia nace con `due_at = <momento de completar> +
  recurrence_days`, no desde el `due_at` original. `recurrence_days IS NULL` → no genera.
- Tokens/invite codes con `crypto/rand`, nunca `math/rand`. Sin dependencias nuevas salvo
  que se pidan.
