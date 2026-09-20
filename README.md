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

## Servidor en producción

El backend corre en una VM **e2-micro Always Free** de GCP:

- Instancia `toka` · proyecto `toka-personal` · zona `us-west1-b`
- URL pública: `https://8-235-73-211.sslip.io` (Caddy + Let's Encrypt)
- systemd `toka` · binario `/opt/toka/toka` · SQLite `/opt/toka/toka.db`

Deploy de una versión nueva:

```bash
GOOS=linux GOARCH=amd64 go build -o /tmp/toka-linux .
gcloud compute scp /tmp/toka-linux toka:/tmp/ \
  --zone us-west1-b --project toka-personal --tunnel-through-iap
gcloud compute ssh toka --zone us-west1-b --project toka-personal --tunnel-through-iap \
  --command "sudo install -o toka -g toka -m755 /tmp/toka-linux /opt/toka/toka && sudo systemctl restart toka"
```

El SSH solo entra por IAP: requiere `gcloud auth login nuxapower@gmail.com`.

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

El workflow deriva `versionName` del tag y `versionCode = major*10000 + minor*100 + patch`,
así que un tag más alto siempre instala por encima del anterior. La firma usa el keystore
guardado en los secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`.

**Guardá `android/release.jks` y `android/keystore.properties`** (están gitignoreados).
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

Funciona y está verificado de punta a punta con `.claude/scripts/smoke.sh` y
`.claude/scripts/smoke-sync.sh`, que ejercitan la API real contra SQLite. No hay tests
unitarios en Go todavía.

El Dashboard, Historial y Personas ya leen de los `Flow` de Room, así que se redibujan
solos cuando entra un sync. Quedan por migrar algunas lecturas puntuales.
