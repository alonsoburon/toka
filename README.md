# Toka

Tareas domésticas compartidas. Una casa, varias personas, plantillas de tarea que se
regeneran solas cuando alguien las completa.

Backend en Go con SQLite (un solo archivo, sin daemon), cliente Android en Compose que
funciona sin conexión.

## Cómo funciona

Un **household** agrupa a varias **people**. Cada **task_template** describe una tarea
("sacar la basura, cada 7 días") y va generando **task_instances** concretas.

Al completar una instancia recurrente se crea la siguiente con
`due_at = momento de completar + recurrence_days`. Se cuenta desde el completado y no
desde el vencimiento original, a propósito: atrasarse un día no deja la siguiente
tarea a un día de distancia. La recurrencia no castiga.

## Puesta en marcha

Requiere Go 1.26. No hace falta instalar ningún motor de base de datos: SQLite vive en
un solo archivo (`toka.db`).

```bash
make run-seed   # migra, siembra y sirve en :3000 (crea .env si falta)
```

La base se migra sola al arrancar, leyendo `db/migrations/` del disco, así que el
servidor se ejecuta desde la raíz del repo.

El seed (`db/seed.sql`) es **solo para desarrollo**: crea un hogar "Demo" con credenciales
públicas (token `toka-dev-token`, invite `DEVDEMO234`). Nunca lo cargues en un servidor
expuesto.

```bash
curl -s localhost:3000/tasks -H "Authorization: Bearer toka-dev-token" | jq
```

`make curl-setup` imprime el resto de los endpoints.

Android:

```bash
cd android && ./gradlew assembleDebug
```

La URL del backend por defecto (`BASE_URL` en `android/app/build.gradle.kts`) es
`https://toka.nuxapower.cl/`; se puede cambiar en la pantalla "Conectar al servidor". Desde
el emulador, el backend local del host se ve como `http://10.0.2.2:3000`.

## Servidor en producción

Corre en el VPS OVH (Debian, Podman rootless + Quadlet, Caddy delante, Cloudflare):

- URL pública: `https://toka.nuxapower.cl` (Caddy → `127.0.0.1:3001`; el contenedor no
  publica nada más). Sondeo: `GET /healthz`.
- Quadlet en `~/.config/containers/systemd/`, fuentes en `~/apps/toka/`, imagen
  `localhost/toka:latest` (ver `Containerfile`). La infraestructura común está descrita
  en `~/code/AGENTS.md`.
- La base es un solo archivo SQLite (`TOKA_DB`) en un volumen del contenedor.

Deploy de una versión nueva (en el VPS):

```bash
cd ~/apps/toka && git pull --ff-only
podman build -t localhost/toka:latest -f Containerfile .
systemctl --user restart toka
curl -s https://toka.nuxapower.cl/healthz
```

**No cargues el seed en producción.** Los hogares reales se crean desde la app.

### Respaldo

`deploy/backup-toka.sh` hace una copia consistente con `sqlite3 .backup` (sin parar el
servicio), verifica `PRAGMA integrity_check` y la sube a R2; `deploy/restore-check.sh`
restaura la última copia a un archivo temporal y compara conteos. Los units de systemd
están en `deploy/`. Un respaldo sin restauración probada no cuenta: corre
`restore-check.sh` después de instalar el timer.

## Releases e instalación con Obtainium

Cada tag `vX.Y.Z` dispara `.github/workflows/release.yml`, que compila un APK firmado y
lo publica como GitHub Release. [Obtainium](https://github.com/ImranR98/Obtainium) lee
esos releases y actualiza la app sola.

1. Instala Obtainium.
2. "Add App" → pega `https://github.com/alonsoburon/toka`.
3. Obtainium detecta los releases y el asset `.apk` sin configuración extra.

Para publicar una versión:

```bash
git tag v0.2.0
git push origin v0.2.0
```

El workflow verifica la firma (`apksigner verify`) y falla si faltan los secrets, en vez de
publicar un APK sin firmar. Deriva `versionName` del tag y `versionCode = major*10000 + minor*100 + patch`,
así que un tag más alto siempre instala por encima del anterior. La firma usa el keystore
guardado en los secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`.

**Guarda `android/release.jks` y `android/keystore.properties`** (están gitignoreados).
Son la única forma de firmar actualizaciones que Android acepte como del mismo
desarrollador; si se pierden, hay que desinstalar y reinstalar. El APK de release y el de
debug tienen firmas distintas: para pasar de uno a otro hay que desinstalar.

## Decisiones que vale la pena conocer

**El aislamiento entre casas lo aplica el código Go.** SQLite no trae Row-Level
Security, así que cada query autenticada filtra por `household_id`; olvidarlo es una
fuga entre familias. Es la frontera que antes garantizaba RLS en Postgres.

**Los bearer tokens se guardan hasheados** (`people.token_hash`, sha256). Un dump de la
base no entrega sesiones.

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

Funciona y está verificado de punta a punta con `go test ./...` (aislamiento entre hogares,
sync/`applyOp`, recurrencia, validación) y con `make smoke`, que ejercita la API real contra una
SQLite temporal.

El Dashboard, Historial y Personas ya leen de los `Flow` de Room, así que se redibujan
solos cuando entra un sync. El detalle de tarea y "crear plantilla" también leen de Room.
