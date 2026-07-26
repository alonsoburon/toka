# Toka

Tareas domésticas compartidas. Una casa, varias personas, plantillas de tarea que se
regeneran solas cuando alguien las completa.

Backend en Go con PostgreSQL, cliente Android en Compose que funciona sin conexión.

## Cómo funciona

Un **household** agrupa a varias **people**. Cada **task_template** describe una tarea
("sacar la basura, cada 7 días") y va generando **task_instances** concretas.

Al completar una instancia recurrente se crea la siguiente con
`due_at = momento de completar + recurrence_days`. Se cuenta desde el completado y no
desde el vencimiento original, a propósito: atrasarse un día no deja la siguiente
tarea a un día de distancia. La recurrencia no castiga.

## Puesta en marcha

Requiere Go 1.26 y PostgreSQL 18.

```bash
make db-up                              # arranca PostgreSQL
make db-roles                           # crea los roles toka_app / toka_readonly
TOKA_APP_PASSWORD=... make db-password  # su contraseña
cp .env.example .env                    # y ajusta las URLs
make run-seed                           # migra, siembra y sirve en :3000
```

Datos de prueba listos para usar tras el seed:

```bash
curl -s localhost:3000/tasks -H "Authorization: Bearer seed-token-alonso-abc123" | jq
```

`make curl-setup` imprime el resto de los endpoints.

Android:

```bash
cd android && ./gradlew assembleDebug
```

La URL del backend está en `buildConfigField("String", "BASE_URL", ...)` dentro de
`android/app/build.gradle.kts`.

## Decisiones que vale la pena conocer

**El aislamiento entre casas lo aplica Postgres, no el código.** Cada tabla tiene
Row-Level Security y el servidor declara en qué household trabaja al abrir la
transacción. Una consulta a la que se le olvide el `WHERE household_id` devuelve cero
filas en vez de las de otra familia. `make db-check-rls` lo comprueba.

**El servidor entra a la base con un rol sin privilegios.** `toka_app` no tiene DDL ni
`BYPASSRLS`; el dueño de las tablas solo se usa para migrar. Contra un host remoto sin
TLS, el servidor se niega a arrancar en vez de mandar las credenciales en claro.

**Los bearer tokens se guardan hasheados.** Un dump de la base no entrega sesiones.

**El cliente Android es local-first.** La UI lee de SQLite y nunca espera a la red. Las
escrituras se aplican al instante y se encolan; al recuperar señal suben con un id de
mutación estable, así que reintentar tras un timeout no duplica nada. Una tarea marcada
en el metro llega al servidor con la hora en que se marcó, no con la hora en que volvió
la señal.

**El cursor de sincronización es un contador con lock, no una secuencia.** Una secuencia
reparte números antes del COMMIT, y dos transacciones pueden confirmar en orden inverso
al de asignación: un cliente que sincroniza justo en medio se salta una fila y no se
entera nunca. El lock fuerza que ambos órdenes coincidan.

Está todo desarrollado en `CLAUDE.md`, que es también el contexto que usan los agentes
que trabajan en este repositorio.

## Estado

Funciona y está verificado de punta a punta con `.claude/scripts/smoke.sh` y
`.claude/scripts/smoke-sync.sh`, que ejercitan la API real contra Postgres. No hay
tests unitarios en Go todavía.

Pendiente conocido: las pantallas de Compose siguen leyendo por las funciones puntuales
del repositorio en vez de por los `Flow` que ya exponen, así que el modo offline
funciona pero la UI todavía no se redibuja sola al entrar un sync.
