---
name: endpoint
description: (Solo backend Go/SQLite legado) Añadir o modificar un endpoint HTTP de Toka de punta a punta — handler en internal/handler, registro en server.go, y el cliente Retrofit en Android. Úsala cuando se pida una ruta nueva, cambiar un contrato JSON, o cuando la app Android llame a algo que el backend no expone.
---

> **Solo backend legado.** Esta skill describe las rutas HTTP del backend Go (Go + SQLite), que sigue en producción hasta migrar y retirarlo. La arquitectura vigente es Firebase Auth + Firestore (ver `CLAUDE.md`); para cambios de datos ahí usa `firestore.rules`, `scripts/test-rules.sh` y `/android-feature`.

# Endpoint de punta a punta

Las cuatro puntas que hay que tocar. Saltarse la 2 es el bug más común del repo
(hay handlers escritos que nunca se registraron → 404).

## 1. Handler — `internal/handler/<recurso>.go`

Un archivo por recurso. Método sobre `*Server`. Plantilla de escritura:

```go
type CreateCosaRequest struct {
	Name string `json:"name"`
}

func (s *Server) CreateCosa(w http.ResponseWriter, r *http.Request) {
	creator, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	var req CreateCosaRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}
	if req.Name == "" {
		http.Error(w, `{"error":"name required"}`, http.StatusBadRequest)
		return
	}

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Tabla sincronizada (people, task_templates, task_instances): row_version
	// dentro de la misma transacción que la escritura.
	version, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	var cosa model.Cosa
	err = tx.QueryRowContext(r.Context(), `
		INSERT INTO cosas (household_id, name, row_version, created_by, updated_by)
		VALUES (?, ?, ?, ?, ?)
		RETURNING id, household_id, name, created_at, updated_at,
			created_by, updated_by, row_version
	`, hid, req.Name, version, creator.ID, creator.ID).Scan(
		&cosa.ID, &cosa.HouseholdID, &cosa.Name,
		&cosa.CreatedAt, &cosa.UpdatedAt, &cosa.CreatedBy, &cosa.UpdatedBy,
		&cosa.RowVersion)
	if err != nil {
		http.Error(w, `{"error":"could not create cosa"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(cosa)
}
```

Mira `CreateTemplate` en `internal/handler/templates.go` como referencia viva del estilo.
Un `UPDATE` además pide `row_version = ?` (otro `NextRowVersion`) y
`updated_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now')` a mano — no hay trigger.

Reglas no negociables:

- **Todo `WHERE` lleva `household_id = ?`.** Es la única frontera entre familias.
  En un `UPDATE`/`DELETE` por id: `WHERE id = ? AND household_id = ?`. Sin eso, un
  token de una casa puede tocar los datos de otra.
- Escrituras en transacción: `s.DB.BeginTx(r.Context(), nil)` + `defer tx.Rollback()` +
  `tx.Commit()` explícito.
  El `Rollback` después de un `Commit` correcto es un no-op, por eso el patrón es seguro.
- `created_by`/`updated_by` = `person.ID`, nunca del body.
- Lecturas: `tx.QueryContext` / `tx.QueryRowContext` (como `ListTemplates`); una fila
  ausente es `errors.Is(err, sql.ErrNoRows)`.
- Escrituras sobre tablas sincronizadas: `row_version = NextRowVersion(...)` y, en UPDATE,
  `updated_at` a mano. Además añade la operación al dispatch de `applyOp` en
  `internal/handler/sync.go` para que funcione sin conexión.
- SQL parametrizado con `?`; nunca concatenar valores.
- Path params: `id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)` → 400 si falla.
- Query params: `r.URL.Query().Get("days")` con un default explícito.
- En `PATCH`, campos punteros + `COALESCE(?, columna)` para no borrar lo ausente.
- Errores: `http.Error(w, `{"error":"..."}`, status)` — JSON, no texto pelado.
- Nada de `panic`, nada de logs por request.
- Si el recurso es soft-deletable, `DELETE` = `UPDATE ... SET is_active = false`.

## 2. Registro — `internal/server/server.go`

Sin esto el handler no existe. Con auth:

```go
mux.Handle("POST /cosas", authMw(http.HandlerFunc(s.CreateCosa)))
```

Sin auth (solo onboarding: crear/unirse a household):

```go
mux.HandleFunc("POST /cosas", s.CreateCosa)
```

Ojo con el routing de stdlib: `GET /tasks/history` y `GET /tasks/{id}` conviven, el
patrón literal gana. Los patrones llevan el método al principio, en mayúsculas.

## 3. Modelo — `internal/model/models.go`

Struct con tags `json` en snake_case. Nullable → puntero (`*string`, `*int`, `*time.Time`).
Campos que vienen de un JOIN y no siempre están: puntero + `,omitempty`
(ver `TaskInstance.TemplateName`).

## 4. Cliente Android

`android/app/src/main/java/com/toka/app/data/api/TokaApi.kt`:

```kotlin
@POST("cosas")
suspend fun createCosa(
    @Body request: CreateCosaRequest,
    @Header("Authorization") token: String
): CosaDTO
```

- El path va **sin** slash inicial (`BASE_URL` termina en `/`).
- Todo endpoint autenticado lleva `@Header("Authorization") token: String`; el llamador
  pasa `"Bearer $token"` completo.
- DTO y request en `ApiModels.kt` con `@Serializable` y `@SerialName` cuando el nombre
  Kotlin difiere del JSON.
- Luego el método en el repositorio correspondiente de `data/repository/`, y el ViewModel.

## 5. Verifica

```bash
go build ./...
make run-seed     # en otra terminal; migra y carga el seed
```

Prueba real con curl — hay tokens de seed listos (`toka-dev-token`):

```bash
curl -s -X POST http://localhost:3000/cosas \
  -H "Authorization: Bearer toka-dev-token" \
  -H "Content-Type: application/json" \
  -d '{"name":"prueba"}' | python3 -m json.tool
```

Comprueba también el camino de error: sin header debe dar
`401 {"error":"unauthorized"}`. Y actualiza la tabla de rutas de `CLAUDE.md`.
