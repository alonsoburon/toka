package handler

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"toka/internal/auth"
	"toka/internal/db"
	"toka/internal/model"
)

func (s *Server) ListPendingTasks(w http.ResponseWriter, r *http.Request) {
	_, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	rows, err := tx.QueryContext(r.Context(), `
		SELECT ti.id, ti.template_id, ti.household_id, ti.status, ti.due_at,
		       ti.assigned_to_id, ti.completed_by_id, ti.completed_at, ti.notes,
		       ti.created_at, ti.updated_at, ti.created_by, ti.updated_by,
		       tt.name,
		       pa.name, pa.color, pa.avatar_emoji
		FROM task_instances ti
		JOIN task_templates tt ON tt.id = ti.template_id
		LEFT JOIN people pa ON pa.id = ti.assigned_to_id
		WHERE ti.household_id = ? AND ti.status = 'pending'
		ORDER BY
			CASE WHEN ti.due_at < ? THEN 0 ELSE 1 END,
			ti.due_at ASC
	`, hid, time.Now().UTC())
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	tasks := make([]model.TaskInstance, 0)
	for rows.Next() {
		var t model.TaskInstance
		if err := rows.Scan(&t.ID, &t.TemplateID, &t.HouseholdID, &t.Status, &t.DueAt,
			&t.AssignedToID, &t.CompletedByID, &t.CompletedAt, &t.Notes,
			&t.CreatedAt, &t.UpdatedAt, &t.CreatedBy, &t.UpdatedBy,
			&t.TemplateName, &t.AssignedToName, &t.AssignedToColor, &t.AssignedToEmoji); err != nil {
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		tasks = append(tasks, t)
	}
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(tasks)
}

func (s *Server) ListTaskHistory(w http.ResponseWriter, r *http.Request) {
	_, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	daysStr := r.URL.Query().Get("days")
	days := 30
	if daysStr != "" {
		if d, err := strconv.Atoi(daysStr); err == nil && d > 0 {
			days = d
		}
	}

	cutoff := time.Now().UTC().AddDate(0, 0, -days)

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	rows, err := tx.QueryContext(r.Context(), `
		SELECT ti.id, ti.template_id, ti.household_id, ti.status, ti.due_at,
		       ti.assigned_to_id, ti.completed_by_id, ti.completed_at, ti.notes,
		       ti.created_at, ti.updated_at, ti.created_by, ti.updated_by,
		       tt.name,
		       pc.name, pc.color, pc.avatar_emoji
		FROM task_instances ti
		JOIN task_templates tt ON tt.id = ti.template_id
		LEFT JOIN people pc ON pc.id = ti.completed_by_id
		WHERE ti.household_id = ? AND ti.status IN ('done', 'skipped')
		  AND ti.completed_at > ?
		ORDER BY ti.completed_at DESC
	`, hid, cutoff)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	tasks := make([]model.TaskInstance, 0)
	for rows.Next() {
		var t model.TaskInstance
		if err := rows.Scan(&t.ID, &t.TemplateID, &t.HouseholdID, &t.Status, &t.DueAt,
			&t.AssignedToID, &t.CompletedByID, &t.CompletedAt, &t.Notes,
			&t.CreatedAt, &t.UpdatedAt, &t.CreatedBy, &t.UpdatedBy,
			&t.TemplateName, &t.CompletedByName, &t.AssignedToColor, &t.AssignedToEmoji); err != nil {
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		tasks = append(tasks, t)
	}
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(tasks)
}

func (s *Server) createNextInstance(ctx context.Context, tx *sql.Tx, instanceID, personID int64, now time.Time) (*time.Time, error) {
	var recurrenceDays *int
	err := tx.QueryRowContext(ctx, `
		SELECT tt.recurrence_days
		FROM task_instances ti
		JOIN task_templates tt ON tt.id = ti.template_id
		WHERE ti.id = ?
	`, instanceID).Scan(&recurrenceDays)
	if err != nil || recurrenceDays == nil {
		return nil, nil
	}

	var templateID, householdID int64
	var preferredAssigneeID *int64
	err = tx.QueryRowContext(ctx, `
		SELECT ti.template_id, ti.household_id, tt.preferred_assignee_id
		FROM task_instances ti
		JOIN task_templates tt ON tt.id = ti.template_id
		WHERE ti.id = ?
	`, instanceID).Scan(&templateID, &householdID, &preferredAssigneeID)
	if err != nil {
		return nil, nil
	}

	rowVersion, err := db.NextRowVersion(ctx, tx)
	if err != nil {
		return nil, err
	}

	nextDueAt := now.Add(time.Duration(*recurrenceDays) * 24 * time.Hour)
	_, err = tx.ExecContext(ctx, `
		INSERT INTO task_instances (template_id, household_id, status, due_at,
			assigned_to_id, row_version, created_by, updated_by, generated_from_instance_id)
		VALUES (?, ?, 'pending', ?, ?, ?, ?, ?, ?)
	`, templateID, householdID, nextDueAt, preferredAssigneeID, rowVersion, personID, personID, instanceID)
	if err != nil {
		return nil, err
	}

	return &nextDueAt, nil
}

type CompleteTaskRequest struct {
	Notes *string `json:"notes"`
}

func (s *Server) CompleteTask(w http.ResponseWriter, r *http.Request) {
	person, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	idStr := r.PathValue("id")
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		http.Error(w, `{"error":"invalid id"}`, http.StatusBadRequest)
		return
	}

	var req CompleteTaskRequest
	json.NewDecoder(r.Body).Decode(&req)

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	now := time.Now().UTC()

	rowVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	tag, err := tx.ExecContext(r.Context(), `
		UPDATE task_instances
		SET status = 'done',
		    completed_at = ?,
		    completed_by_id = ?,
		    notes = COALESCE(?, notes),
		    updated_by = ?,
		    updated_at = ?,
		    row_version = ?
		WHERE id = ? AND household_id = ? AND status = 'pending'
	`, now, person.ID, req.Notes, person.ID, now, rowVersion, id, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	if n, _ := tag.RowsAffected(); n == 0 {
		http.Error(w, `{"error":"task not found or already completed/skipped"}`, http.StatusNotFound)
		return
	}

	nextDueAt, err := s.createNextInstance(r.Context(), tx, id, person.ID, now)
	if err != nil {
		http.Error(w, `{"error":"could not create next instance"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	resp := map[string]interface{}{"status": "done"}
	if nextDueAt != nil {
		resp["next_due_at"] = nextDueAt
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func (s *Server) SkipTask(w http.ResponseWriter, r *http.Request) {
	person, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	idStr := r.PathValue("id")
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		http.Error(w, `{"error":"invalid id"}`, http.StatusBadRequest)
		return
	}

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	now := time.Now().UTC()

	rowVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	tag, err := tx.ExecContext(r.Context(), `
		UPDATE task_instances
		SET status = 'skipped',
		    completed_at = ?,
		    completed_by_id = ?,
		    updated_by = ?,
		    updated_at = ?,
		    row_version = ?
		WHERE id = ? AND household_id = ? AND status = 'pending'
	`, now, person.ID, person.ID, now, rowVersion, id, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	if n, _ := tag.RowsAffected(); n == 0 {
		http.Error(w, `{"error":"task not found or already completed/skipped"}`, http.StatusNotFound)
		return
	}

	nextDueAt, err := s.createNextInstance(r.Context(), tx, id, person.ID, now)
	if err != nil {
		http.Error(w, `{"error":"could not create next instance"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	resp := map[string]interface{}{"status": "skipped"}
	if nextDueAt != nil {
		resp["next_due_at"] = nextDueAt
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

type UpdateTaskRequest struct {
	AssignedToID *int64  `json:"assigned_to_id"`
	Notes        *string `json:"notes"`
	DueAt        *string `json:"due_at"`
}

func (s *Server) UpdateTask(w http.ResponseWriter, r *http.Request) {
	editor, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	idStr := r.PathValue("id")
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		http.Error(w, `{"error":"invalid id"}`, http.StatusBadRequest)
		return
	}

	var req UpdateTaskRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}

	var dueAt *time.Time
	if req.DueAt != nil && *req.DueAt != "" {
		parsed, err := time.Parse(time.RFC3339Nano, *req.DueAt)
		if err != nil {
			parsed, err = time.Parse(time.RFC3339, *req.DueAt)
			if err != nil {
				http.Error(w, `{"error":"invalid due_at format"}`, http.StatusBadRequest)
				return
			}
		}
		parsed = parsed.UTC()
		dueAt = &parsed
	}

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	rowVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	tag, err := tx.ExecContext(r.Context(), `
		UPDATE task_instances
		-- COALESCE igual que la cola de sync ("task.update"): un campo ausente no
		-- borra el valor existente, y las dos rutas de escritura se comportan igual.
		SET assigned_to_id = COALESCE(?, assigned_to_id),
		    notes = COALESCE(?, notes),
		    due_at = COALESCE(?, due_at),
		    updated_by = ?,
		    updated_at = ?,
		    row_version = ?
		WHERE id = ? AND household_id = ?
	`, req.AssignedToID, req.Notes, dueAt, editor.ID, time.Now().UTC(),
		rowVersion, id, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	if n, _ := tag.RowsAffected(); n == 0 {
		http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "updated"})
}
