---
name: android-feature
description: Añadir o modificar una feature de la app Android de Toka — pantalla Compose, ViewModel, repositorio y entrada en el NavGraph. Úsala para cualquier trabajo en android/, incluido conectar la UI a un endpoint nuevo del backend.
---

# Feature Android

## Arquitectura del módulo

```
data/api/TokaApi.kt          interfaz Retrofit — un método por endpoint
data/api/ApiModels.kt        DTOs @Serializable (request + response)
data/TokenStore.kt           token + base URL persistidos en DataStore
data/di/AppContainer.kt      inyección manual — aquí se construye todo
data/repository/*Repository  envuelve TokaApi, expone Result<T> al ViewModel
ui/<feature>/XScreen.kt      @Composable sin estado propio de negocio
ui/<feature>/XViewModel.kt   StateFlow<XUiState>
ui/components/               TaskCard, PersonChip, EmptyState, LoadingShimmer
ui/navigation/               Screen (rutas), NavGraph, BottomBar
ui/theme/                    Color, Type, Theme — Material 3
```

Sin Hilt ni Koin: la inyección es manual en `AppContainer`. Toda dependencia nueva
(repositorio, api) se instancia ahí y se pasa por constructor.

## Pasos

1. **Mira primero una feature existente completa.** `ui/dashboard/` (DashboardScreen +
   DashboardViewModel) es la referencia; cópiale la forma en vez de inventar una nueva.

2. **Repositorio** en `data/repository/` — un método `suspend` por operación, que llama a
   `TokaApi` y devuelve `Result`. El token se lee de `TokenStore` y se pasa como
   `"Bearer $token"`.

3. **ViewModel** con una `data class XUiState` (con `isLoading`, `error`, y los datos) y un
   `MutableStateFlow` privado + `StateFlow` público. Trabajo en `viewModelScope.launch`.
   El ViewModel nunca toca Retrofit directo, siempre pasa por el repositorio.

4. **Screen** `@Composable` que recibe el ViewModel y colecta el estado con
   `collectAsStateWithLifecycle()`. Estados vacío y de carga con los componentes que ya
   existen (`EmptyState`, `LoadingShimmer`) — no reinventes skeletons.

5. **Navegación**: ruta nueva en `ui/navigation/Screen.kt`, `composable(...)` en `NavGraph.kt`,
   y si va en la barra inferior, entrada en `BottomBar.kt`.

6. **Colores y tipografía** salen de `ui/theme/` (`MaterialTheme.colorScheme`, `Typography`).
   Nada de hex literales en las pantallas. El color y el emoji de cada persona vienen del
   backend (`color`, `avatar_emoji`) — úsalos, es la identidad visual de cada miembro.

7. **Strings** en `res/values/strings.xml`, no literales en el Composable.

## Contrato con el backend

- `BASE_URL` está hardcodeada como IP de LAN en `android/app/build.gradle.kts`
  (`buildConfigField`). Si no responde, casi siempre es que cambió la IP de la máquina.
- Un campo nullable en Go (`*string`, `*int`) tiene que ser nullable en Kotlin (`String?`).
  Si no, kotlinx.serialization revienta en runtime con un `null` inesperado.
- Los nombres JSON van en snake_case; usa `@SerialName("recurrence_days")` cuando la
  propiedad Kotlin sea camelCase.
- Antes de asumir que un endpoint existe, compruébalo en `internal/server/server.go`: hay
  métodos de handler escritos que nunca se registraron (ver el aviso en `CLAUDE.md`).

## Verificar

```bash
cd android && ./gradlew compileDebugKotlin   # rápido, atrapa errores de tipos
cd android && ./gradlew assembleDebug        # APK completo
```

No hay tests instrumentados. Para probar de verdad hace falta el backend arriba
(`make db-up && make run-seed`) y el dispositivo en la misma red que la IP de `BASE_URL`.
