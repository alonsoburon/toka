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

func (s *Server) ListPeople(w http.ResponseWriter, r *http.Request) {
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

	// token_hash no aparece en el SELECT. No hay ninguna respuesta de la API que
	// deba incluirlo, y no traerlo evita que alguien lo agregue sin querer a un
	// struct que sí se serializa.
	rows, err := tx.QueryContext(r.Context(), `
		SELECT id, household_id, name, color, avatar_emoji, created_at, updated_at, created_by, updated_by
		FROM people WHERE household_id = ? ORDER BY id
	`, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	people := make([]model.Person, 0)
	for rows.Next() {
		var p model.Person
		if err := rows.Scan(&p.ID, &p.HouseholdID, &p.Name, &p.Color, &p.AvatarEmoji,
			&p.CreatedAt, &p.UpdatedAt, &p.CreatedBy, &p.UpdatedBy); err != nil {
			http.Error(w, `{"error":"scan error"}`, http.StatusInternalServerError)
			return
		}
		people = append(people, p)
	}
	if rows.Err() != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(people)
}

type CreatePersonRequest struct {
	Name  string `json:"name"`
	Color string `json:"color"`
	Emoji string `json:"emoji"`
}

func (s *Server) CreatePerson(w http.ResponseWriter, r *http.Request) {
	admin, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	var req CreatePersonRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}

	if req.Name == "" {
		http.Error(w, `{"error":"name required"}`, http.StatusBadRequest)
		return
	}
	if req.Color == "" {
		req.Color = "#a78bfa"
	}
	if req.Emoji == "" {
		req.Emoji = "🐣"
	}

	token := auth.NewToken()

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

	var personID int64
	err = tx.QueryRowContext(r.Context(), `
		INSERT INTO people (household_id, name, color, avatar_emoji, token_hash,
			row_version, created_by, updated_by)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
	`, hid, req.Name, req.Color, req.Emoji, auth.HashToken(token),
		rowVersion, admin.ID, admin.ID).Scan(&personID)
	if err != nil {
		http.Error(w, `{"error":"could not create person"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"id":    personID,
		"name":  req.Name,
		"color": req.Color,
		"emoji": req.Emoji,
		"token": token,
	})
}

type UpdatePersonRequest struct {
	Name  *string `json:"name"`
	Color *string `json:"color"`
	Emoji *string `json:"avatar_emoji"`
}

func (s *Server) UpdatePerson(w http.ResponseWriter, r *http.Request) {
	editor, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	idStr := r.PathValue("id")
	if idStr == "" {
		http.Error(w, `{"error":"missing id"}`, http.StatusBadRequest)
		return
	}
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		http.Error(w, `{"error":"invalid id"}`, http.StatusBadRequest)
		return
	}

	var req UpdatePersonRequest
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

	var person model.Person
	err = tx.QueryRowContext(r.Context(), `
		UPDATE people
		SET name = COALESCE(?, name),
		    color = COALESCE(?, color),
		    avatar_emoji = COALESCE(?, avatar_emoji),
		    updated_by = ?,
		    updated_at = ?,
		    row_version = ?
		WHERE id = ? AND household_id = ?
		RETURNING id, household_id, name, color, avatar_emoji, updated_at
	`, req.Name, req.Color, req.Emoji, editor.ID, time.Now().UTC(),
		rowVersion, id, hid).Scan(
		&person.ID, &person.HouseholdID, &person.Name, &person.Color,
		&person.AvatarEmoji, &person.UpdatedAt)
	if err != nil {
		http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(person)
}

// DeletePerson borra a una persona del household.
//
// No es un DELETE pelado: media docena de tablas referencian people(id). Antes de
// borrar la fila se limpian las asignaciones (la tarea queda sin asignar, no rota) y
// se reapuntan las columnas de autoría al que borra, para no violar las FKs. Después
// se deja un tombstone para que los clientes offline también la eliminen.
func (s *Server) DeletePerson(w http.ResponseWriter, r *http.Request) {
	actor, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
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

	status, payload, err := s.deletePersonTx(r.Context(), tx, actor.ID, hid, id)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	if status != http.StatusOK {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		json.NewEncoder(w).Encode(payload)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "deleted"})
}

// deletePersonTx concentra la lógica para que el endpoint y la cola de sync
// ("person.delete") se comporten igual. Devuelve un status y un body para reutilizar
// tal cual en la respuesta de la mutación.
func (s *Server) deletePersonTx(ctx context.Context, tx *sql.Tx, actorID, hid, targetID int64) (int, any, error) {
	if targetID == actorID {
		return http.StatusBadRequest, errBody("no puedes eliminarte a ti mismo"), nil
	}

	var exists bool
	if err := tx.QueryRowContext(ctx, `
		SELECT EXISTS(SELECT 1 FROM people WHERE id = ? AND household_id = ?)
	`, targetID, hid).Scan(&exists); err != nil {
		return 0, nil, err
	}
	if !exists {
		return http.StatusNotFound, errBody("person not found"), nil
	}

	var count int
	if err := tx.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM people WHERE household_id = ?
	`, hid).Scan(&count); err != nil {
		return 0, nil, err
	}
	if count <= 1 {
		return http.StatusBadRequest, errBody("no puedes dejar el hogar sin personas"), nil
	}

	now := time.Now().UTC()
	rowVersion, err := db.NextRowVersion(ctx, tx)
	if err != nil {
		return 0, nil, err
	}

	// Cambios visibles para el cliente: la tarea queda sin asignar/completar y suma
	// versión para que el pull la actualice.
	if _, err := tx.ExecContext(ctx, `
		UPDATE task_instances SET assigned_to_id = NULL, updated_by = ?, updated_at = ?, row_version = ?
		WHERE household_id = ? AND assigned_to_id = ?
	`, actorID, now, rowVersion, hid, targetID); err != nil {
		return 0, nil, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE task_instances SET completed_by_id = NULL, updated_by = ?, updated_at = ?, row_version = ?
		WHERE household_id = ? AND completed_by_id = ?
	`, actorID, now, rowVersion, hid, targetID); err != nil {
		return 0, nil, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE task_templates SET preferred_assignee_id = NULL, updated_by = ?, updated_at = ?, row_version = ?
		WHERE household_id = ? AND preferred_assignee_id = ?
	`, actorID, now, rowVersion, hid, targetID); err != nil {
		return 0, nil, err
	}

	// Columnas de autoría: solo existen por integridad, se reapuntan al que borra.
	for _, q := range []string{
		`UPDATE task_instances SET created_by = ? WHERE created_by = ?`,
		`UPDATE task_instances SET updated_by = ? WHERE updated_by = ?`,
		`UPDATE task_templates SET created_by = ? WHERE created_by = ?`,
		`UPDATE task_templates SET updated_by = ? WHERE updated_by = ?`,
		`UPDATE people SET created_by = ? WHERE created_by = ?`,
		`UPDATE people SET updated_by = ? WHERE updated_by = ?`,
		`UPDATE households SET created_by = ? WHERE created_by = ?`,
		`UPDATE households SET updated_by = ? WHERE updated_by = ?`,
		`UPDATE sync_mutations SET person_id = ? WHERE person_id = ?`,
	} {
		if _, err := tx.ExecContext(ctx, q, actorID, targetID); err != nil {
			return 0, nil, err
		}
	}

	if _, err := tx.ExecContext(ctx, `
		INSERT INTO sync_tombstones (household_id, entity, entity_id, row_version)
		VALUES (?, 'person', ?, ?)
		ON CONFLICT (household_id, entity, entity_id)
		DO UPDATE SET row_version = excluded.row_version,
		              deleted_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now')
	`, hid, targetID, rowVersion); err != nil {
		return 0, nil, err
	}

	if _, err := tx.ExecContext(ctx, `
		DELETE FROM people WHERE id = ? AND household_id = ?
	`, targetID, hid); err != nil {
		return 0, nil, err
	}

	return http.StatusOK, map[string]any{"id": targetID}, nil
}
