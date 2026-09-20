package handler

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"time"

	"toka/internal/auth"
	"toka/internal/db"
	"toka/internal/model"
)

// El protocolo de sincronización tiene dos mitades:
//
//	GET  /sync?since=N       lo que cambió desde el cursor N
//	POST /sync/mutations     la cola de escrituras que el cliente hizo sin conexión
//
// El cliente guarda el cursor y lo devuelve en la siguiente petición. Todo lo que
// cambia lleva un row_version del contador global, y como ese contador se asigna
// dentro de la transacción que escribe, el orden de las versiones es el orden real
// de confirmación (ver la migración 002). Eso es lo que permite decir "dame todo lo
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

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// El techo se lee primero y dentro de la misma transacción. Toda versión menor o
	// igual a este valor pertenece a una transacción ya confirmada; las mayores
	// pueden estar todavía en vuelo. Devolver un cursor más alto que este sería
	// prometerle al cliente que ya vio filas que aún no existen.
	var ceiling int64
	if err := tx.QueryRowContext(r.Context(), `SELECT value FROM sync_counter`).Scan(&ceiling); err != nil {
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

	rows, err := tx.QueryContext(r.Context(), `
		SELECT id, household_id, name, color, avatar_emoji,
		       created_at, updated_at, created_by, updated_by, row_version, client_id
		FROM people
		WHERE household_id = ? AND row_version > ? AND row_version <= ?
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
	rows, err = tx.QueryContext(r.Context(), `
		SELECT id, household_id, name, description, recurrence_days,
		       preferred_assignee_id, reminder_times, is_active, created_at, updated_at,
		       created_by, updated_by, row_version, client_id
		FROM task_templates
		WHERE household_id = ? AND row_version > ? AND row_version <= ?
		ORDER BY row_version
	`, hid, since, ceiling)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	for rows.Next() {
		var t model.TaskTemplate
		if err := rows.Scan(&t.ID, &t.HouseholdID, &t.Name, &t.Description,
			&t.RecurrenceDays, &t.PreferredAssigneeID, &t.ReminderTimes, &t.IsActive,
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

	rows, err = tx.QueryContext(r.Context(), `
		SELECT ti.id, ti.template_id, ti.household_id, ti.status, ti.due_at,
		       ti.assigned_to_id, ti.completed_by_id, ti.completed_at, ti.notes,
		       ti.created_at, ti.updated_at, ti.created_by, ti.updated_by,
		       ti.row_version, ti.client_id, tt.name
		FROM task_instances ti
		LEFT JOIN task_templates tt ON tt.id = ti.template_id
		WHERE ti.household_id = ? AND ti.row_version > ? AND ti.row_version <= ?
		ORDER BY ti.row_version
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
			&t.RowVersion, &t.ClientID, &t.TemplateName); err != nil {
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

	rows, err = tx.QueryContext(r.Context(), `
		SELECT entity, entity_id, row_version FROM sync_tombstones
		WHERE household_id = ? AND row_version > ? AND row_version <= ?
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

var uuidPattern = regexp.MustCompile(
	`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

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
	if err := s.DB.QueryRowContext(r.Context(), `SELECT value FROM sync_counter`).Scan(&cursor); err != nil {
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

	if !uuidPattern.MatchString(m.MutationID) {
		return fail(http.StatusBadRequest, "mutation_id must be a uuid")
	}

	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return fail(http.StatusInternalServerError, "db error")
	}
	defer tx.Rollback()

	// ¿Ya la habíamos aplicado? Se devuelve la respuesta original tal cual.
	var prevStatus int
	var prevBody []byte
	err = tx.QueryRowContext(ctx, `
		SELECT status, response FROM sync_mutations WHERE mutation_id = ?
	`, m.MutationID).Scan(&prevStatus, &prevBody)
	if err == nil {
		return MutationResult{
			MutationID: m.MutationID,
			Status:     prevStatus,
			Body:       prevBody,
			Duplicate:  true,
		}
	}
	if !errors.Is(err, sql.ErrNoRows) {
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

	_, err = tx.ExecContext(ctx, `
		INSERT INTO sync_mutations (mutation_id, household_id, person_id, op, status, response)
		VALUES (?, ?, ?, ?, ?, ?)
	`, m.MutationID, hid, person.ID, m.Op, status, string(body))
	if err != nil {
		return fail(http.StatusInternalServerError, "could not record mutation")
	}

	if err := tx.Commit(); err != nil {
		return fail(http.StatusInternalServerError, "commit failed")
	}

	return MutationResult{MutationID: m.MutationID, Status: status, Body: body}
}

// applyOp traduce una operación de la cola a SQL. Devuelve el status y el cuerpo
// que se le guardará a esa mutación — y que se le devolverá idéntico si el cliente
// la reenvía.
func (s *Server) applyOp(ctx context.Context, tx *sql.Tx, person auth.Person, hid int64, m Mutation) (int, any, error) {
	switch m.Op {

	case "template.create":
		var p struct {
			ClientID            string  `json:"client_id"`
			Name                string  `json:"name"`
			Description         string  `json:"description"`
			RecurrenceDays      *int    `json:"recurrence_days"`
			PreferredAssigneeID *int64  `json:"preferred_assignee_id"`
			ReminderTimes       *string `json:"reminder_times"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		if p.Name == "" || p.ClientID == "" {
			return http.StatusBadRequest, errBody("name and client_id required"), nil
		}

		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}

		// Segunda red contra duplicados, independiente de sync_mutations: si dos
		// mutation_id distintos traen el mismo client_id (el cliente reinstalado
		// reenviando su cola, por ejemplo), el índice único lo absorbe.
		var id int64
		err = tx.QueryRowContext(ctx, `
			INSERT INTO task_templates (household_id, name, description, recurrence_days,
				preferred_assignee_id, reminder_times, client_id, row_version, created_by, updated_by)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (household_id, client_id) WHERE client_id IS NOT NULL
			DO UPDATE SET client_id = task_templates.client_id
			RETURNING id
		`, hid, p.Name, nullIfEmpty(p.Description), p.RecurrenceDays,
			p.PreferredAssigneeID, nullIfEmptyPtr(p.ReminderTimes), p.ClientID,
			rowVersion, person.ID, person.ID).Scan(&id)
		if err != nil {
			return 0, nil, err
		}

		dueDays := 7
		if p.RecurrenceDays != nil {
			dueDays = *p.RecurrenceDays
		}

		instanceVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}
		dueAt := time.Now().UTC().Add(time.Duration(dueDays) * 24 * time.Hour)
		_, err = tx.ExecContext(ctx, `
			INSERT INTO task_instances (template_id, household_id, status, due_at,
				assigned_to_id, row_version, created_by, updated_by)
			SELECT ?, ?, 'pending', ?, ?, ?, ?, ?
			WHERE NOT EXISTS (SELECT 1 FROM task_instances WHERE template_id = ?)
		`, id, hid, dueAt, p.PreferredAssigneeID, instanceVersion,
			person.ID, person.ID, id)
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
			ReminderTimes       *string `json:"reminder_times"`
			IsActive            *bool   `json:"is_active"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}
		tag, err := tx.ExecContext(ctx, `
			UPDATE task_templates
			SET name = COALESCE(?, name),
			    description = COALESCE(?, description),
			    recurrence_days = COALESCE(?, recurrence_days),
			    preferred_assignee_id = COALESCE(?, preferred_assignee_id),
			    reminder_times = COALESCE(?, reminder_times),
			    is_active = COALESCE(?, is_active),
			    updated_by = ?,
			    updated_at = ?,
			    row_version = ?
			WHERE id = ? AND household_id = ?
		`, p.Name, p.Description, p.RecurrenceDays, p.PreferredAssigneeID,
			p.ReminderTimes, p.IsActive, person.ID, time.Now().UTC(),
			rowVersion, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if n, _ := tag.RowsAffected(); n == 0 {
			return http.StatusNotFound, errBody("template not found"), nil
		}
		return http.StatusOK, map[string]any{"id": p.ID}, nil

	case "template.set_recurrence":
		// Operación aparte de template.update a propósito: allí recurrence_days usa
		// COALESCE (null = no tocar), así que no había forma de volver a "una sola
		// vez". Aquí null borra la recurrencia de verdad.
		var p struct {
			ID             int64 `json:"id"`
			RecurrenceDays *int  `json:"recurrence_days"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		var days *int
		if p.RecurrenceDays != nil && *p.RecurrenceDays > 0 {
			days = p.RecurrenceDays
		}
		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}
		tag, err := tx.ExecContext(ctx, `
			UPDATE task_templates
			SET recurrence_days = ?, updated_by = ?, updated_at = ?, row_version = ?
			WHERE id = ? AND household_id = ?
		`, days, person.ID, time.Now().UTC(), rowVersion, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if n, _ := tag.RowsAffected(); n == 0 {
			return http.StatusNotFound, errBody("template not found"), nil
		}
		return http.StatusOK, map[string]any{"id": p.ID, "recurrence_days": days}, nil

	case "template.delete":
		var p struct {
			ID int64 `json:"id"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}
		tag, err := tx.ExecContext(ctx, `
			UPDATE task_templates
			SET is_active = false, updated_by = ?, updated_at = ?, row_version = ?
			WHERE id = ? AND household_id = ?
		`, person.ID, time.Now().UTC(), rowVersion, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if n, _ := tag.RowsAffected(); n == 0 {
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
		done := time.Now().UTC()
		if p.CompletedAt != nil && p.CompletedAt.Before(done) {
			done = p.CompletedAt.UTC()
		}

		status := "done"
		if m.Op == "task.skip" {
			status = "skipped"
		}

		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}

		tag, err := tx.ExecContext(ctx, `
			UPDATE task_instances
			SET status = ?,
			    completed_at = ?,
			    completed_by_id = ?,
			    notes = COALESCE(?, notes),
			    updated_by = ?,
			    updated_at = ?,
			    row_version = ?
			WHERE id = ? AND household_id = ? AND status = 'pending'
		`, status, done, person.ID, p.Notes, person.ID, done, rowVersion, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if n, _ := tag.RowsAffected(); n == 0 {
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

	case "task.uncomplete":
		var p struct {
			ID int64 `json:"id"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}

		now := time.Now().UTC()
		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}

		tag, err := tx.ExecContext(ctx, `
			UPDATE task_instances
			SET status = 'pending',
			    completed_at = NULL,
			    completed_by_id = NULL,
			    updated_by = ?,
			    updated_at = ?,
			    row_version = ?
			WHERE id = ? AND household_id = ? AND status IN ('done', 'skipped')
		`, person.ID, now, rowVersion, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if n, _ := tag.RowsAffected(); n == 0 {
			return http.StatusConflict, errBody("task not resolved"), nil
		}

		// La instancia que nació de este completado se borra, si sigue pendiente.
		// Va con tombstone para que los clientes offline también la eliminen.
		rows, err := tx.QueryContext(ctx, `
			SELECT id FROM task_instances
			WHERE generated_from_instance_id = ? AND household_id = ? AND status = 'pending'
		`, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		var generated []int64
		for rows.Next() {
			var gid int64
			if err := rows.Scan(&gid); err != nil {
				rows.Close()
				return 0, nil, err
			}
			generated = append(generated, gid)
		}
		rows.Close()
		if rows.Err() != nil {
			return 0, nil, err
		}

		for _, gid := range generated {
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO sync_tombstones (household_id, entity, entity_id, row_version)
				VALUES (?, 'task', ?, ?)
				ON CONFLICT (household_id, entity, entity_id)
				DO UPDATE SET row_version = excluded.row_version,
				              deleted_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now')
			`, hid, gid, rowVersion); err != nil {
				return 0, nil, err
			}
			if _, err := tx.ExecContext(ctx, `
				DELETE FROM task_instances WHERE id = ? AND household_id = ?
			`, gid, hid); err != nil {
				return 0, nil, err
			}
		}

		return http.StatusOK, map[string]any{"id": p.ID, "status": "pending"}, nil

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
		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}
		tag, err := tx.ExecContext(ctx, `
			UPDATE task_instances
			SET assigned_to_id = COALESCE(?, assigned_to_id),
			    notes = COALESCE(?, notes),
			    due_at = COALESCE(?, due_at),
			    updated_by = ?,
			    updated_at = ?,
			    row_version = ?
			WHERE id = ? AND household_id = ?
		`, p.AssignedToID, p.Notes, p.DueAt, person.ID, time.Now().UTC(),
			rowVersion, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if n, _ := tag.RowsAffected(); n == 0 {
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
		rowVersion, err := db.NextRowVersion(ctx, tx)
		if err != nil {
			return 0, nil, err
		}
		tag, err := tx.ExecContext(ctx, `
			UPDATE people
			SET name = COALESCE(?, name),
			    color = COALESCE(?, color),
			    avatar_emoji = COALESCE(?, avatar_emoji),
			    updated_by = ?,
			    updated_at = ?,
			    row_version = ?
			WHERE id = ? AND household_id = ?
		`, p.Name, p.Color, p.Emoji, person.ID, time.Now().UTC(),
			rowVersion, p.ID, hid)
		if err != nil {
			return 0, nil, err
		}
		if n, _ := tag.RowsAffected(); n == 0 {
			return http.StatusNotFound, errBody("person not found"), nil
		}
		return http.StatusOK, map[string]any{"id": p.ID}, nil

	case "person.delete":
		var p struct {
			ID int64 `json:"id"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return http.StatusBadRequest, errBody("invalid payload"), nil
		}
		return s.deletePersonTx(ctx, tx, person.ID, hid, p.ID)

	default:
		return http.StatusBadRequest, errBody("unknown op: " + m.Op), nil
	}
}

func errBody(msg string) map[string]string {
	return map[string]string{"error": msg}
}
