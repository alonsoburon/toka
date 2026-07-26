package handler

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"toka/internal/auth"
	"toka/internal/db"
	"toka/internal/model"

	"github.com/jackc/pgx/v5"
)

// El protocolo de sincronización tiene dos mitades:
//
//	GET  /sync?since=N       lo que cambió desde el cursor N
//	POST /sync/mutations     la cola de escrituras que el cliente hizo sin conexión
//
// El cliente guarda el cursor y lo devuelve en la siguiente petición. Todo lo que
// cambia lleva un row_version del contador global, y como ese contador se asigna
// bajo un lock que dura hasta el COMMIT, el orden de las versiones es el orden real
// de confirmación (ver la migración 003). Eso es lo que permite decir "dame todo lo
// posterior a N" sin perderse filas por el camino.

type Tombstone struct {
	Entity     string `json:"entity"`
	ID         int64  `json:"id"`
	RowVersion int64  `json:"row_version"`
}

type SyncResponse struct {
	Cursor    int64                `json:"cursor"`
	People    []model.Person       `json:"people"`
	Templates []model.TaskTemplate `json:"templates"`
	Tasks     []model.TaskInstance `json:"tasks"`
	Deleted   []Tombstone          `json:"deleted"`
}

// Pull devuelve todo lo que cambió en el household después del cursor.
//
// No hay paginación. Los datos de una casa son unas pocas centenas de filas incluso
// después de años de uso, y la primera sincronización de un cliente nuevo cabe de
// sobra en una respuesta. Si algún día dejara de ser cierto, el corte natural es
// paginar por row_version — el orden ya está garantizado.
func (s *Server) Pull(w http.ResponseWriter, r *http.Request) {
	_, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	var since int64
	if v := r.URL.Query().Get("since"); v != "" {
		parsed, err := strconv.ParseInt(v, 10, 64)
		if err != nil || parsed < 0 {
			http.Error(w, `{"error":"invalid since"}`, http.StatusBadRequest)
			return
		}
		since = parsed
	}

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	// El techo se lee primero y dentro de la misma transacción. Toda versión menor o
	// igual a este valor pertenece a una transacción ya confirmada; las mayores
	// pueden estar todavía en vuelo. Devolver un cursor más alto que este sería
	// prometerle al cliente que ya vio filas que aún no existen.
	var ceiling int64
	if err := tx.QueryRow(r.Context(), `SELECT value FROM sync_counter`).Scan(&ceiling); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	resp := SyncResponse{
		Cursor:    ceiling,
		People:    []model.Person{},
		Templates: []model.TaskTemplate{},
		Tasks:     []model.TaskInstance{},
		Deleted:   []Tombstone{},
	}

	rows, err := tx.Query(r.Context(), `
		SELECT id, household_id, name, color, avatar_emoji,
		       created_at, updated_at, created_by, updated_by, row_version, client_id::text
		FROM people
		WHERE household_id = $1 AND row_version > $2 AND row_version <= $3
		ORDER BY row_version
	`, hid, since, ceiling)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	for rows.Next() {
		var p model.Person
		if err := rows.Scan(&p.ID, &p.HouseholdID, &p.Name, &p.Color, &p.AvatarEmoji,
			&p.CreatedAt, &p.UpdatedAt, &p.CreatedBy, &p.UpdatedBy, &p.RowVersion, &p.ClientID); err != nil {
			rows.Close()
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		resp.People = append(resp.People, p)
	}
	rows.Close()
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	// Las plantillas van completas, incluidas las inactivas: is_active=false es cómo
	// se representa una baja aquí, y el cliente necesita enterarse de ella.
	rows, err = tx.Query(r.Context(), `
		SELECT id, household_id, name, description, recurrence_days,
		       preferred_assignee_id, is_active, created_at, updated_at,
		       created_by, updated_by, row_version, client_id::text
		FROM task_templates
		WHERE household_id = $1 AND row_version > $2 AND row_version <= $3
		ORDER BY row_version
	`, hid, since, ceiling)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	for rows.Next() {
		var t model.TaskTemplate
		if err := rows.Scan(&t.ID, &t.HouseholdID, &t.Name, &t.Description,
			&t.RecurrenceDays, &t.PreferredAssigneeID, &t.IsActive,
			&t.CreatedAt, &t.UpdatedAt, &t.CreatedBy, &t.UpdatedBy,
			&t.RowVersion, &t.ClientID); err != nil {
			rows.Close()
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		resp.Templates = append(resp.Templates, t)
	}
	rows.Close()
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	rows, err = tx.Query(r.Context(), `
		SELECT id, template_id, household_id, status, due_at,
		       assigned_to_id, completed_by_id, completed_at, notes,
		       created_at, updated_at, created_by, updated_by, row_version, client_id::text
		FROM task_instances
		WHERE household_id = $1 AND row_version > $2 AND row_version <= $3
		ORDER BY row_version
	`, hid, since, ceiling)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	for rows.Next() {
		var t model.TaskInstance
		if err := rows.Scan(&t.ID, &t.TemplateID, &t.HouseholdID, &t.Status, &t.DueAt,
			&t.AssignedToID, &t.CompletedByID, &t.CompletedAt, &t.Notes,
			&t.CreatedAt, &t.UpdatedAt, &t.CreatedBy, &t.UpdatedBy,
			&t.RowVersion, &t.ClientID); err != nil {
			rows.Close()
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		resp.Tasks = append(resp.Tasks, t)
	}
	rows.Close()
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	rows, err = tx.Query(r.Context(), `
		SELECT entity, entity_id, row_version FROM sync_tombstones
		WHERE household_id = $1 AND row_version > $2 AND row_version <= $3
		ORDER BY row_version
	`, hid, since, ceiling)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	for rows.Next() {
		var t Tombstone
		if err := rows.Scan(&t.Entity, &t.ID, &t.RowVersion); err != nil {
			rows.Close()
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		resp.Deleted = append(resp.Deleted, t)
	}
	rows.Close()
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

// ── Push ─────────────────────────────────────────────────────────────────────

type Mutation struct {
	MutationID string          `json:"mutation_id"`
	Op         string          `json:"op"`
	Payload    json.RawMessage `json:"payload"`
}

type MutationResult struct {
	MutationID string          `json:"mutation_id"`
	Status     int             `json:"status"`
	Body       json.RawMessage `json:"body"`
	Duplicate  bool            `json:"duplicate,omitempty"`
}

type PushRequest struct {
	Mutations []Mutation `json:"mutations"`
}

type PushResponse struct {
	Results []MutationResult `json:"results"`
	Cursor  int64            `json:"cursor"`
}

// maxBatch acota cuántas mutaciones se aceptan de una vez. Un cliente que estuvo un
// mes sin conexión manda varias tandas; el límite existe para que una petición no
// pueda tener la base ocupada indefinidamente.
const maxBatch = 200

// Push aplica la cola de escrituras del cliente.
//
// Cada mutación va en su propia transacción, no todas en una. Si la número 3 es
// inválida —por ejemplo referencia una tarea que otra persona ya completó— las
// demás igual se aplican y el cliente recibe el resultado de cada una por separado.
// Con una sola transacción, una mutación envenenada bloquearía la cola para siempre.
//
// La deduplicación es la clave del modo offline: el cliente no puede saber si un
// timeout fue "no llegó" o "llegó y se perdió la respuesta", así que reenvía. El
// mutation_id es la clave primaria de sync_mutations, de modo que un reenvío
// devuelve la respuesta guardada en vez de aplicar la operación otra vez.
func (s *Server) Push(w http.ResponseWriter, r *http.Request) {
	person, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	var req PushRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}
	if len(req.Mutations) > maxBatch {
		http.Error(w, `{"error":"too many mutations in one batch"}`, http.StatusRequestEntityTooLarge)
		return
	}

	results := make([]MutationResult, 0, len(req.Mutations))
	for _, m := range req.Mutations {
		if m.MutationID == "" {
			results = append(results, MutationResult{
				MutationID: m.MutationID,
				Status:     http.StatusBadRequest,
				Body:       json.RawMessage(`{"error":"mutation_id required"}`),
			})
			continue
		}
		results = append(results, s.applyOne(r.Context(), person, hid, m))
	}

	// El cursor se lee después de aplicar todo, así el cliente puede pedir el pull
	// siguiente sabiendo que incluye sus propias escrituras.
	var cursor int64
	if err := s.DB.QueryRow(r.Context(), `SELECT value FROM sync_counter`).Scan(&cursor); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(PushResponse{Results: results, Cursor: cursor})
}

func (s *Server) applyOne(ctx context.Context, person auth.Person, hid int64, m Mutation) MutationResult {
	fail := func(status int, msg string) MutationResult {
		body, _ := json.Marshal(map[string]string{"error": msg})
		return MutationResult{MutationID: m.MutationID, Status: status, Body: body}
	}

	tx, err := db.BeginScoped(ctx, s.DB, hid)
	if err != nil {
		return fail(http.StatusInternalServerError, "db error")
	}
	defer tx.Rollback(ctx)

	// ¿Ya la habíamos aplicado? Se devuelve la respuesta original tal cual.
	var prevStatus int
	var prevBody []byte
	err = tx.QueryRow(ctx, `
		SELECT status, response FROM sync_mutations WHERE mutation_id = $1
	`, m.MutationID).Scan(&prevStatus, &prevBody)
	if err == nil {
		return MutationResult{
			MutationID: m.MutationID,
			Status:     prevStatus,
			Body:       prevBody,
			Duplicate:  true,
		}
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		if isInvalidUUID(err) {
			return fail(http.StatusBadRequest, "mutation_id must be a uuid")
		}
		return fail(http.StatusInternalServerError, "db error")
	}

	status, payload, err := s.applyOp(ctx, tx, person, hid, m)
	if err != nil {
		return fail(http.StatusInternalServerError, "could not apply mutation")
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return fail(http.StatusInternalServerError, "could not encode result")
	}

	_, err = tx.Exec(ctx, `
		INSERT INTO sync_mutations (mutation_id, household_id, person_id, op, status, response)
		VALUES ($1, $2, $3, $4, $5, $6)
	`, m.MutationID, hid, person.ID, m.Op, status, body)
	if err != nil {
		return fail(http.StatusInternalServerError, "could not record mutation")
	}

	if err := tx.Commit(ctx); err != nil {
		return fail(http.StatusInternalServerError, "commit failed")
	}

	return MutationResult{MutationID: m.MutationID, Status: status, Body: body}
}

// applyOp traduce una operación de la cola a SQL. Devuelve el status y el cuerpo
// que se le guardará a esa mutación — y que se le devolverá idéntico si el cliente
// la reenvía.
func (s *Server) applyOp(ctx context.Context, tx pgx.Tx, person auth.Person, hid int64, m Mutation) (int, any, error) {
	switch m.Op {

	case "template.create":
		var p struct {
			ClientID            string `json:"client_id"`
			Name                string `json:"name"`
			Description         string `json:"description"`
			RecurrenceDays      *int   `json:"recurrence_days"`
			PreferredAssigneeID *int64 `json:"preferred_assignee_id"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		if p.Name == "" || p.ClientID == "" {
			return http.StatusBadRequest, errBody("name and client_id required"), nil
		}

		// Segunda red contra duplicados, independiente de sync_mutations: si dos
		// mutation_id distintos traen el mismo client_id (el cliente reinstalado
		// reenviando su cola, por ejemplo), el índice único lo absorbe.
		var id int64
		err := tx.QueryRow(ctx, `
			INSERT INTO task_templates (household_id, name, description, recurrence_days,
				preferred_assignee_id, client_id, created_by, updated_by)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $7)
			ON CONFLICT (household_id, client_id) WHERE client_id IS NOT NULL
			DO UPDATE SET client_id = task_templates.client_id
			RETURNING id
		`, hid, p.Name, nullIfEmpty(p.Description), p.RecurrenceDays,
			p.PreferredAssigneeID, p.ClientID, person.ID).Scan(&id)
		if err != nil {
			return 0, nil, err
		}

		dueDays := 7
		if p.RecurrenceDays != nil {
			dueDays = *p.RecurrenceDays
		}
		_, err = tx.Exec(ctx, `
			INSERT INTO task_instances (template_id, household_id, status, due_at,
				assigned_to_id, created_by, updated_by)
			SELECT $1, $2, 'pending', now() + $3 * INTERVAL '1 day', $4, $5, $5
			WHERE NOT EXISTS (SELECT 1 FROM task_instances WHERE template_id = $1)
		`, id, hid, dueDays, p.PreferredAssigneeID, person.ID)
		if err != nil {
			return 0, nil, err
		}
		return http.StatusCreated, map[string]any{"id": id, "client_id": p.ClientID}, nil

	case "template.update":
		var p struct {
			ID                  int64   `json:"id"`
			Name                *string `json:"name"`
			Description         *string `json:"description"`
			RecurrenceDays      *int    `json:"recurrence_days"`
			PreferredAssigneeID *int64  `json:"preferred_assignee_id"`
			IsActive            *bool   `json:"is_active"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		tag, err := tx.Exec(ctx, `
			UPDATE task_templates
			SET name = COALESCE($1, name),
			    description = COALESCE($2, description),
			    recurrence_days = COALESCE($3, recurrence_days),
			    preferred_assignee_id = COALESCE($4, preferred_assignee_id),
			    is_active = COALESCE($5, is_active),
			    updated_by = $6
			WHERE id = $7 AND household_id = $8
		`, p.Name, p.Description, p.RecurrenceDays, p.PreferredAssigneeID,
			p.IsActive, person.ID, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if tag.RowsAffected() == 0 {
			return http.StatusNotFound, errBody("template not found"), nil
		}
		return http.StatusOK, map[string]any{"id": p.ID}, nil

	case "template.delete":
		var p struct {
			ID int64 `json:"id"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		tag, err := tx.Exec(ctx, `
			UPDATE task_templates SET is_active = false, updated_by = $1
			WHERE id = $2 AND household_id = $3
		`, person.ID, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if tag.RowsAffected() == 0 {
			return http.StatusNotFound, errBody("template not found"), nil
		}
		return http.StatusOK, map[string]any{"id": p.ID}, nil

	case "task.complete", "task.skip":
		var p struct {
			ID          int64      `json:"id"`
			Notes       *string    `json:"notes"`
			CompletedAt *time.Time `json:"completed_at"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}

		// completed_at lo manda el cliente: es el momento en que la persona marcó la
		// tarea, no el momento en que el teléfono recuperó señal. Si llegara del
		// futuro se ignora — un reloj mal puesto no debería correr la ventana de la
		// siguiente tarea.
		done := time.Now()
		if p.CompletedAt != nil && p.CompletedAt.Before(done) {
			done = *p.CompletedAt
		}

		status := "done"
		if m.Op == "task.skip" {
			status = "skipped"
		}

		tag, err := tx.Exec(ctx, `
			UPDATE task_instances
			SET status = $1::task_status,
			    completed_at = $2,
			    completed_by_id = $3,
			    notes = COALESCE($4, notes),
			    updated_by = $3
			WHERE id = $5 AND household_id = $6 AND status = 'pending'
		`, status, done, person.ID, p.Notes, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if tag.RowsAffected() == 0 {
			// Alguien más la completó mientras este cliente estaba sin conexión.
			// No es un error que haya que reintentar: es el estado del mundo, y el
			// pull siguiente le traerá la versión buena.
			return http.StatusConflict, errBody("task already resolved"), nil
		}

		nextDueAt, err := s.createNextInstance(ctx, tx, p.ID, person.ID, done)
		if err != nil {
			return 0, nil, err
		}
		out := map[string]any{"id": p.ID, "status": status}
		if nextDueAt != nil {
			out["next_due_at"] = nextDueAt
		}
		return http.StatusOK, out, nil

	case "task.update":
		var p struct {
			ID           int64      `json:"id"`
			AssignedToID *int64     `json:"assigned_to_id"`
			Notes        *string    `json:"notes"`
			DueAt        *time.Time `json:"due_at"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		tag, err := tx.Exec(ctx, `
			UPDATE task_instances
			SET assigned_to_id = COALESCE($1, assigned_to_id),
			    notes = COALESCE($2, notes),
			    due_at = COALESCE($3, due_at),
			    updated_by = $4
			WHERE id = $5 AND household_id = $6
		`, p.AssignedToID, p.Notes, p.DueAt, person.ID, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if tag.RowsAffected() == 0 {
			return http.StatusNotFound, errBody("task not found"), nil
		}
		return http.StatusOK, map[string]any{"id": p.ID}, nil

	case "person.update":
		var p struct {
			ID    int64   `json:"id"`
			Name  *string `json:"name"`
			Color *string `json:"color"`
			Emoji *string `json:"avatar_emoji"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		tag, err := tx.Exec(ctx, `
			UPDATE people
			SET name = COALESCE($1, name),
			    color = COALESCE($2, color),
			    avatar_emoji = COALESCE($3, avatar_emoji),
			    updated_by = $4
			WHERE id = $5 AND household_id = $6
		`, p.Name, p.Color, p.Emoji, person.ID, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if tag.RowsAffected() == 0 {
			return http.StatusNotFound, errBody("person not found"), nil
		}
		return http.StatusOK, map[string]any{"id": p.ID}, nil

	default:
		return http.StatusBadRequest, errBody("unknown op: " + m.Op), nil
	}
}

func errBody(msg string) map[string]string {
	return map[string]string{"error": msg}
}

func isInvalidUUID(err error) bool {
	var pgErr interface{ SQLState() string }
	if errors.As(err, &pgErr) {
		return pgErr.SQLState() == "22P02" // invalid_text_representation
	}
	return false
}
