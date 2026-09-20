package handler

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"toka/internal/auth"
	"toka/internal/db"
	"toka/internal/model"
)

func (s *Server) ListTemplates(w http.ResponseWriter, r *http.Request) {
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
		SELECT id, household_id, name, description, recurrence_days,
		       preferred_assignee_id, reminder_times, is_active, created_at, updated_at,
		       created_by, updated_by
		FROM task_templates
		WHERE household_id = ? AND is_active = true
		ORDER BY id
	`, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	templates := make([]model.TaskTemplate, 0)
	for rows.Next() {
		var t model.TaskTemplate
		if err := rows.Scan(&t.ID, &t.HouseholdID, &t.Name, &t.Description,
			&t.RecurrenceDays, &t.PreferredAssigneeID, &t.ReminderTimes, &t.IsActive,
			&t.CreatedAt, &t.UpdatedAt, &t.CreatedBy, &t.UpdatedBy); err != nil {
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		templates = append(templates, t)
	}
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(templates)
}

type CreateTemplateRequest struct {
	Name                string  `json:"name"`
	Description         string  `json:"description"`
	RecurrenceDays      *int    `json:"recurrence_days"`
	PreferredAssigneeID *int64  `json:"preferred_assignee_id"`
	ReminderTimes       *string `json:"reminder_times"`
}

func (s *Server) CreateTemplate(w http.ResponseWriter, r *http.Request) {
	creator, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	var req CreateTemplateRequest
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

	templateVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	var template model.TaskTemplate
	err = tx.QueryRowContext(r.Context(), `
		INSERT INTO task_templates (household_id, name, description, recurrence_days,
			preferred_assignee_id, reminder_times, row_version, created_by, updated_by)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		RETURNING id, household_id, name, description, recurrence_days,
			preferred_assignee_id, reminder_times, is_active, created_at, updated_at,
			created_by, updated_by, row_version
	`, hid, req.Name, nullIfEmpty(req.Description), req.RecurrenceDays,
		req.PreferredAssigneeID, nullIfEmptyPtr(req.ReminderTimes),
		templateVersion, creator.ID, creator.ID).Scan(
		&template.ID, &template.HouseholdID, &template.Name, &template.Description,
		&template.RecurrenceDays, &template.PreferredAssigneeID, &template.ReminderTimes,
		&template.IsActive, &template.CreatedAt, &template.UpdatedAt, &template.CreatedBy,
		&template.UpdatedBy, &template.RowVersion)
	if err != nil {
		http.Error(w, `{"error":"could not create template"}`, http.StatusInternalServerError)
		return
	}

	defaultDueDays := 7
	if req.RecurrenceDays != nil {
		defaultDueDays = *req.RecurrenceDays
	}

	instanceVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	dueAt := time.Now().UTC().Add(time.Duration(defaultDueDays) * 24 * time.Hour)
	_, err = tx.ExecContext(r.Context(), `
		INSERT INTO task_instances (template_id, household_id, status, due_at,
			assigned_to_id, row_version, created_by, updated_by)
		VALUES (?, ?, 'pending', ?, ?, ?, ?, ?)
	`, template.ID, hid, dueAt, req.PreferredAssigneeID, instanceVersion, creator.ID, creator.ID)
	if err != nil {
		http.Error(w, `{"error":"could not create first instance"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(template)
}

func (s *Server) UpdateTemplate(w http.ResponseWriter, r *http.Request) {
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

	var req struct {
		Name                *string `json:"name"`
		Description         *string `json:"description"`
		RecurrenceDays      *int    `json:"recurrence_days"`
		PreferredAssigneeID *int64  `json:"preferred_assignee_id"`
		ReminderTimes       *string `json:"reminder_times"`
		IsActive            *bool   `json:"is_active"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
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

	var template model.TaskTemplate
	err = tx.QueryRowContext(r.Context(), `
		UPDATE task_templates
		-- COALESCE igual que la cola de sync ("template.update"): idénticas reglas
		-- online y offline. "template.delete" es quien apaga is_active.
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
		RETURNING id, household_id, name, description, recurrence_days,
			preferred_assignee_id, reminder_times, is_active, created_at, updated_at,
			created_by, updated_by, row_version
	`, req.Name, req.Description, req.RecurrenceDays,
		req.PreferredAssigneeID, req.ReminderTimes, req.IsActive, editor.ID, time.Now().UTC(),
		rowVersion, id, hid).Scan(
		&template.ID, &template.HouseholdID, &template.Name, &template.Description,
		&template.RecurrenceDays, &template.PreferredAssigneeID, &template.ReminderTimes,
		&template.IsActive, &template.CreatedAt, &template.UpdatedAt, &template.CreatedBy,
		&template.UpdatedBy, &template.RowVersion)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
		} else {
			http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		}
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(template)
}

// SetTemplateRecurrence cambia solo la recurrencia, y a diferencia de UpdateTemplate
// sí puede volver a "una sola vez" (recurrence_days = NULL). Va aparte porque
// UpdateTemplate usa COALESCE y un null significa "no tocar".
func (s *Server) SetTemplateRecurrence(w http.ResponseWriter, r *http.Request) {
	editor, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, `{"error":"invalid id"}`, http.StatusBadRequest)
		return
	}

	var req struct {
		RecurrenceDays *int `json:"recurrence_days"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}

	var days *int
	if req.RecurrenceDays != nil && *req.RecurrenceDays > 0 {
		days = req.RecurrenceDays
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
		UPDATE task_templates
		SET recurrence_days = ?, updated_by = ?, updated_at = ?, row_version = ?
		WHERE id = ? AND household_id = ?
	`, days, editor.ID, time.Now().UTC(), rowVersion, id, hid)
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
	json.NewEncoder(w).Encode(map[string]any{"id": id, "recurrence_days": days})
}

func (s *Server) DeleteTemplate(w http.ResponseWriter, r *http.Request) {
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
		UPDATE task_templates
		SET is_active = false, updated_by = ?, updated_at = ?, row_version = ?
		WHERE id = ? AND household_id = ?
	`, editor.ID, time.Now().UTC(), rowVersion, id, hid)
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

	w.WriteHeader(http.StatusNoContent)
}
