# Toka

Tareas domésticas compartidas. Una casa, varias personas, plantillas de tarea que se
regeneran solas cuando alguien las completa.

App Android (Kotlin + Compose) que usa **Firebase Auth (Google) + Firestore directamente**,
sin servidor propio, y funciona sin conexión gracias a la caché persistente de Firestore.
Misma arquitectura que la app Finanzas.

## Cómo funciona

Un **household** agrupa a varias **people**. Cada **template** describe una tarea
("sacar la basura, cada 7 días") y va generando **tasks** concretas.

Al completar una tarea recurrente se crea la siguiente con
`dueAt = momento de completar + recurrenceDays`, en el mismo `WriteBatch`. Se cuenta desde el
completado y no desde el vencimiento original, a propósito: atrasarse un día no deja la
siguiente a un día de distancia. La recurrencia no castiga.

Modelo de datos y reglas de seguridad: ver `CLAUDE.md` y `firestore.rules`.

## Desarrollo local (sin Google ni Firebase reales)

Requiere Android SDK, Java 21+ (para los emuladores de Firebase) y `npx`.

```bash
scripts/dev.sh            # emuladores Firebase + emulador Android + instala "Toka DEV"
scripts/test-rules.sh     # prueba firestore.rules (50 comprobaciones, emulador propio)
cd android && ./gradlew compileDebugKotlin
```

La build debug (`com.toka.app.dev`, "Toka DEV") apunta a los emuladores y ofrece "Entrar como
Ana/Beto (dev)". Los emuladores de Toka usan Auth 9199 / Firestore 8185 / UI 4100, para no
chocar con los de Finanzas (9099 / 8085), que nunca se tocan. Con un teléfono real:
`DISPOSITIVO=<serial> scripts/dev.sh` (pasa `-Ptoka.emulador=<ip del PC>`).

## Firebase real: checklist de consola

Proyecto `toka-hogar-e194` (cuenta nuxapower@gmail.com), Firestore en `southamerica-west1`, plan Spark.
Ya hecho por API: proyecto de Google Cloud, base de datos Firestore y reglas publicadas
(`scripts/publicar-reglas.sh`). Pasos manuales pendientes (la cuenta aún no aceptó los términos de
Firebase y la API de gestión lo exige desde la consola):

- [ ] Agregar Firebase al proyecto de Google Cloud existente (consola → "Agregar proyecto" → elegir `toka-hogar-e194`).
- [ ] Registrar la app Android `com.toka.app` con la huella SHA-1 de release
      `88:7E:EC:40:9C:3C:D5:A5:F0:CA:F6:48:09:F3:7A:BB:17:37:24:32`.
- [ ] Habilitar Authentication → proveedor Google.
- [ ] Descargar `google-services.json` a `android/app/` (gitignoreado) y, para CI, guardarlo en base64
      como secret `GOOGLE_SERVICES_JSON_BASE64`.
- [x] Reglas publicadas (repetir `scripts/publicar-reglas.sh` cada vez que cambie `firestore.rules`).

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
`ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`; además necesita `GOOGLE_SERVICES_JSON_BASE64`
(el `google-services.json` real) y falla si falta.

**Guarda `android/release.jks` y `android/keystore.properties`** (están gitignoreados).
Son la única forma de firmar actualizaciones que Android acepte como del mismo
desarrollador; si se pierden, hay que desinstalar y reinstalar. El APK de release y el de
debug tienen firmas distintas: para pasar de uno a otro hay que desinstalar.

## Backend legado en producción (a retirar tras migrar)

Local: `make run-seed` (Go 1.26, SQLite en `toka.db`, sirve en :3000, lee `db/` desde la raíz del repo);
`make smoke` lo prueba contra una base temporal. El seed es solo de desarrollo (hogar "Demo" con
credenciales públicas).

Ya no es parte de la arquitectura vigente (la app usa Firebase), pero sigue sirviendo hasta que se migren los datos y se retire. Corre en el VPS OVH (Debian, Podman rootless + Quadlet, Caddy delante, Cloudflare):

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

El volumen es de un uid remapeado del contenedor rootless, así que los scripts de `deploy/` se corren con
`podman unshare` (y necesitan `sqlite3` en el host). `deploy/backup-toka.sh` hace una copia consistente con `sqlite3 .backup` (sin parar el
servicio), verifica `PRAGMA integrity_check` y la sube a R2 con `deploy/upload-r2.sh` (`tucunar-backups/toka/<fecha>/`, retención 14 días); `deploy/restore-check.sh`
restaura la última copia a un archivo temporal y compara conteos. Si el seed llegó a producción (token/invite
públicos), `deploy/rotate-household-credentials.sh PERSON_ID HOUSEHOLD_ID` hace respaldo verificado, muestra una
auditoría de quién hay en el hogar y rota el token y el invite; verifica que la base cambió antes de imprimirlos. Los units de systemd
están en `deploy/`. Un respaldo sin restauración probada no cuenta: corre
`restore-check.sh` después de instalar el timer.

## Decisiones que vale la pena conocer

**El aislamiento entre casas lo aplican las reglas de Firestore.** No hay servidor que filtre:
`firestore.rules` exige ser miembro del hogar para leer o escribir, y valida que asignados y
miembros pertenezcan al hogar. Se prueban con `scripts/test-rules.sh`.

**Las escrituras no esperan la red.** Firestore las guarda en su caché y las sube solas; la UI
nunca hace `await` de una escritura. El indicador ⟳ N cuenta los documentos con escrituras pendientes.

**La recurrencia vive en el cliente y es idempotente.** La tarea siguiente tiene un id determinista
(hash del id de la actual), así que dos teléfonos que completan lo mismo sin conexión no la duplican.
Deshacer devuelve la tarea a pendiente y borra la siguiente si aún está pendiente.

**Unirse a un hogar va en dos pasos** (miembros + perfil, luego el puntero `users/{uid}`) para evitar
una carrera de permisos, y los listeners reintentan ante `PERMISSION_DENIED`.

**Recordatorios locales.** `ReminderWorker` (WorkManager, cada 15 min) y el widget "Mis tareas de
hoy" leen de la caché de Firestore; no hay push remoto.

Todo está desarrollado en `CLAUDE.md`, que es también el contexto que usan los agentes que
trabajan en este repositorio.

## Estado

Migración a Firestore hecha en la rama `firestore`: auth, hogar, tareas y recurrencia en el cliente,
con reglas probadas en emulador. Falta completar el checklist de consola y migrar los datos del
backend legado antes de retirarlo.
