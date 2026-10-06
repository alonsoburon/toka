---
name: android-feature
description: Añadir o modificar una feature de la app Android de Toka — pantalla Compose, ViewModel, repositorio sobre Firestore y entrada en el NavGraph. Úsala para cualquier trabajo en android/, incluido leer o escribir un dato nuevo en Firestore.
---

# Feature Android (Firestore)

## Arquitectura del módulo

```
data/firebase/Firestore.kt       rutas (user/invite/household/people/templates/tasks), asFlow,
                                 retryOnPermissionDenied, mapeo DocumentSnapshot → DTO, nextTaskId
data/firebase/FirebaseSetup.kt   inicialización (emuladores en debug)
data/model/Models.kt             DTOs (ids de persona String)
data/repository/*Repository     Auth, Household, Session, Task — exponen Flow y suspend
data/SessionCache.kt             uid/householdId síncronos para workers y widget
data/di/AppContainer.kt          inyección manual — aquí se construye todo
ui/<feature>/XScreen.kt          @Composable sin estado de negocio propio
ui/<feature>/XViewModel.kt       StateFlow<XUiState>
ui/components/  ui/navigation/  ui/theme/   (Material 3)
```

Sin Hilt ni Koin: toda dependencia nueva se instancia en `AppContainer` y se pasa por constructor.
Ya no hay Retrofit, Room, DataStore ni kotlinx-serialization.

## Pasos

1. **Mira una feature completa primero.** `ui/dashboard/` (DashboardScreen + DashboardViewModel) y
   `TaskRepository` son la referencia; copia su forma.
2. **Modelo de datos:** si hace falta un campo o colección nueva, cámbialo en las tres puntas:
   `firestore.rules` (validación + miembros/asignados), `Firestore.kt`/`Models.kt` y la sección de
   modelo de `CLAUDE.md`; añade su comprobación a `scripts/test-rules.sh`.
3. **Repositorio:** lecturas como `Flow` con `asFlow()` (y `.retryOnPermissionDenied()` si el
   listener puede abrirse justo tras crear/unirse a un hogar); el `householdId` sale de la sesión.
4. **Escrituras:** no esperes la red. Lanza la escritura (`set`/`update`/`WriteBatch.commit()`) sin
   `await()` en el camino de la UI: Firestore la guarda en su caché y la sube sola. Varias escrituras
   relacionadas (como completar + crear la siguiente) van en **un solo `WriteBatch`**. Ids de
   persona siempre `String`.
5. **ViewModel** con `data class XUiState` y `MutableStateFlow` privado + `StateFlow` público; trabajo
   en `viewModelScope.launch`. Nunca toca Firestore directo: pasa por el repositorio.
6. **Screen** `@Composable` que colecta con `collectAsStateWithLifecycle()`; vacío/carga con
   `EmptyState` y `LoadingShimmer`. Muestra el estado pendiente con `pendingSync` (`hasPendingWrites`).
7. **Navegación:** ruta en `ui/navigation/Screen.kt`, `composable(...)` en `NavGraph.kt` y, si va en la
   barra inferior, `BottomBar.kt`. `MainActivity` decide Login / HouseholdSetup / app según `Session`.
8. **Tema y textos:** colores y tipografía de `ui/theme/`, sin hex literales; strings en
   `res/values/strings.xml`. Color y emoji de cada persona vienen de `people/{uid}`.

## Verificar

```bash
cd android && ./gradlew compileDebugKotlin   # rápido, atrapa errores de tipos
scripts/test-rules.sh                        # si tocaste reglas o escrituras
scripts/dev.sh                               # emuladores + "Toka DEV" para probar a mano
```

No hay tests instrumentados. En debug entra con "Entrar como Ana/Beto (dev)". Para probar offline,
activa el modo avión y comprueba que la escritura se refleja al instante y que ⟳ N baja al volver la red.
