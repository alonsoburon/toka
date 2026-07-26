package handler

import (
	"encoding/json"
	"net/http"
	"strconv"

	"toka/internal/auth"
	"toka/internal/db"
	"toka/internal/model"

	"github.com/jackc/pgx/v5"
)

func (s *Server) ListTemplates(w http.ResponseWriter, r *http.Request) {
	_, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	rows, err := tx.Query(r.Context(), `
		SELECT id, household_id, name, description, recurrence_days,
		       preferred_assignee_id, is_active, created_at, updated_at,
		       created_by, updated_by
		FROM task_templates
		WHERE household_id = $1 AND is_active = true
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
			&t.RecurrenceDays, &t.PreferredAssigneeID, &t.IsActive,
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
	Name                string `json:"name"`
	Description         string `json:"description"`
	RecurrenceDays      *int   `json:"recurrence_days"`
	PreferredAssigneeID *int64 `json:"preferred_assignee_id"`
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

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	var template model.TaskTemplate
	err = tx.QueryRow(r.Context(), `
		INSERT INTO task_templates (household_id, name, description, recurrence_days,
			preferred_assignee_id, created_by, updated_by)
		VALUES ($1, $2, $3, $4, $5, $6, $6)
		RETURNING id, household_id, name, description, recurrence_days,
			preferred_assignee_id, is_active, created_at, updated_at, created_by, updated_by
	`, hid, req.Name, nullIfEmpty(req.Description), req.RecurrenceDays,
		req.PreferredAssigneeID, creator.ID).Scan(
		&template.ID, &template.HouseholdID, &template.Name, &template.Description,
		&template.RecurrenceDays, &template.PreferredAssigneeID, &template.IsActive,
		&template.CreatedAt, &template.UpdatedAt, &template.CreatedBy, &template.UpdatedBy)
	if err != nil {
		http.Error(w, `{"error":"could not create template"}`, http.StatusInternalServerError)
		return
	}

	defaultDueDays := 7
	if req.RecurrenceDays != nil {
		defaultDueDays = *req.RecurrenceDays
	}

	_, err = tx.Exec(r.Context(), `
		INSERT INTO task_instances (template_id, household_id, status, due_at,
			assigned_to_id, created_by, updated_by)
		VALUES ($1, $2, 'pending', now() + $3 * INTERVAL '1 day',
			$4, $5, $5)
	`, template.ID, hid, defaultDueDays, req.PreferredAssigneeID, creator.ID)
	if err != nil {
		http.Error(w, `{"error":"could not create first instance"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
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
		IsActive            *bool   `json:"is_active"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	var template model.TaskTemplate
	err = tx.QueryRow(r.Context(), `
		UPDATE task_templates
		SET name = COALESCE($1, name),
		    description = $2,
		    recurrence_days = COALESCE($3, recurrence_days),
		    preferred_assignee_id = $4,
		    is_active = COALESCE($5, is_active),
		    updated_by = $6
		WHERE id = $7 AND household_id = $8
		RETURNING id, household_id, name, description, recurrence_days,
			preferred_assignee_id, is_active, created_at, updated_at, created_by, updated_by
	`, req.Name, req.Description, req.RecurrenceDays,
		req.PreferredAssigneeID, req.IsActive, editor.ID, id, hid).Scan(
		&template.ID, &template.HouseholdID, &template.Name, &template.Description,
		&template.RecurrenceDays, &template.PreferredAssigneeID, &template.IsActive,
		&template.CreatedAt, &template.UpdatedAt, &template.CreatedBy, &template.UpdatedBy)
	if err != nil {
		if err == pgx.ErrNoRows {
			http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
		} else {
			http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		}
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(template)
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

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	tag, err := tx.Exec(r.Context(), `
		UPDATE task_templates SET is_active = false, updated_by = $1
		WHERE id = $2 AND household_id = $3
	`, editor.ID, id, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	if tag.RowsAffected() == 0 {
		http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
