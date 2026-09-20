package handler

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"toka/internal/auth"
	"toka/internal/db"
)

type Server struct {
	DB *sql.DB
}

type CreateHouseholdRequest struct {
	Name       string `json:"name"`
	AdminName  string `json:"admin_name"`
	AdminColor string `json:"admin_color"`
	AdminEmoji string `json:"admin_emoji"`
}

type CreateHouseholdResponse struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	InviteCode  string `json:"invite_code"`
	PersonID    int64  `json:"person_id"`
	PersonToken string `json:"token"`
}

// CreateHousehold es el único punto del sistema que crea un household desde cero.
//
// El household y su admin se crean en la misma transacción. Las FKs cread_by/
// updated_by apuntan a people y son DEFERRABLE INITIALLY DEFERRED, así que se
// pueden insertar en 0 y corregirlas antes del COMMIT.
func (s *Server) CreateHousehold(w http.ResponseWriter, r *http.Request) {
	var req CreateHouseholdRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}

	if req.Name == "" {
		http.Error(w, `{"error":"name required"}`, http.StatusBadRequest)
		return
	}
	if req.AdminName == "" {
		http.Error(w, `{"error":"admin_name required"}`, http.StatusBadRequest)
		return
	}
	if req.AdminColor == "" {
		req.AdminColor = "#a78bfa"
	}
	if req.AdminEmoji == "" {
		req.AdminEmoji = "🐣"
	}

	inviteCode := auth.NewInviteCode()
	personToken := auth.NewToken()

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var householdID int64
	err = tx.QueryRowContext(r.Context(), `
		INSERT INTO households (name, invite_code, created_by, updated_by)
		VALUES (?, ?, 0, 0) RETURNING id
	`, req.Name, inviteCode).Scan(&householdID)
	if err != nil {
		http.Error(w, `{"error":"could not create household"}`, http.StatusInternalServerError)
		return
	}

	personRowVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	var personID int64
	err = tx.QueryRowContext(r.Context(), `
		INSERT INTO people (household_id, name, color, avatar_emoji, token_hash,
			row_version, created_by, updated_by)
		VALUES (?, ?, ?, ?, ?, ?, 0, 0) RETURNING id
	`, householdID, req.AdminName, req.AdminColor, req.AdminEmoji,
		auth.HashToken(personToken), personRowVersion).Scan(&personID)
	if err != nil {
		http.Error(w, `{"error":"could not create admin"}`, http.StatusInternalServerError)
		return
	}

	now := time.Now().UTC()
	if _, err := tx.ExecContext(r.Context(), `
		UPDATE households SET created_by = ?, updated_by = ?, updated_at = ? WHERE id = ?
	`, personID, personID, now, householdID); err != nil {
		http.Error(w, `{"error":"could not update household refs"}`, http.StatusInternalServerError)
		return
	}

	if _, err := tx.ExecContext(r.Context(), `
		UPDATE people SET created_by = ?, updated_by = ? WHERE id = ?
	`, personID, personID, personID); err != nil {
		http.Error(w, `{"error":"could not update person refs"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	resp := CreateHouseholdResponse{
		ID:          householdID,
		Name:        req.Name,
		InviteCode:  inviteCode,
		PersonID:    personID,
		PersonToken: personToken, // la única vez que el token viaja en claro
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(resp)
}

type JoinHouseholdRequest struct {
	InviteCode string `json:"invite_code"`
	Name       string `json:"name"`
	Color      string `json:"color"`
	Emoji      string `json:"emoji"`
}

// JoinHousehold entra con un invite code y sin token.
func (s *Server) JoinHousehold(w http.ResponseWriter, r *http.Request) {
	var req JoinHouseholdRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
		return
	}

	if req.InviteCode == "" || req.Name == "" {
		http.Error(w, `{"error":"invite_code and name required"}`, http.StatusBadRequest)
		return
	}
	if req.Color == "" {
		req.Color = "#a78bfa"
	}
	if req.Emoji == "" {
		req.Emoji = "🐣"
	}

	personToken := auth.NewToken()

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var householdID int64
	err = tx.QueryRowContext(r.Context(), `
		SELECT id FROM households WHERE invite_code = ?
	`, req.InviteCode).Scan(&householdID)
	if err != nil {
		http.Error(w, `{"error":"invalid invite code"}`, http.StatusNotFound)
		return
	}

	personRowVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	var personID int64
	err = tx.QueryRowContext(r.Context(), `
		INSERT INTO people (household_id, name, color, avatar_emoji, token_hash,
			row_version, created_by, updated_by)
		VALUES (?, ?, ?, ?, ?, ?, 0, 0) RETURNING id
	`, householdID, req.Name, req.Color, req.Emoji,
		auth.HashToken(personToken), personRowVersion).Scan(&personID)
	if err != nil {
		http.Error(w, `{"error":"could not create person"}`, http.StatusInternalServerError)
		return
	}

	if _, err := tx.ExecContext(r.Context(), `
		UPDATE people SET created_by = ?, updated_by = ? WHERE id = ?
	`, personID, personID, personID); err != nil {
		http.Error(w, `{"error":"could not update refs"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"person_id":    personID,
		"household_id": householdID,
		"token":        personToken,
		"name":         req.Name,
	})
}

// LeaveHousehold borra a la persona autenticada de su household.
//
// Es distinto de cerrar sesión: el token deja de existir y las tareas que tuviera
// asignadas quedan libres. La autoría (created_by/updated_by) se reapunta a otra
// persona del hogar para no violar las FKs. Se niega si es la última persona, porque
// dejaría el household huérfano.
func (s *Server) LeaveHousehold(w http.ResponseWriter, r *http.Request) {
	person, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var survivor int64
	err = tx.QueryRowContext(r.Context(), `
		SELECT id FROM people WHERE household_id = ? AND id != ? ORDER BY id LIMIT 1
	`, hid, person.ID).Scan(&survivor)
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, `{"error":"no puedes salir siendo la última persona del hogar"}`, http.StatusBadRequest)
		return
	}
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	now := time.Now().UTC()
	rowVersion, err := db.NextRowVersion(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	// Lo que dependía del que se va queda libre o reapuntado a quien se queda.
	if _, err := tx.ExecContext(r.Context(), `
		UPDATE task_instances SET assigned_to_id = NULL, updated_by = ?, updated_at = ?, row_version = ?
		WHERE household_id = ? AND assigned_to_id = ?
	`, survivor, now, rowVersion, hid, person.ID); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	if _, err := tx.ExecContext(r.Context(), `
		UPDATE task_instances SET completed_by_id = NULL, updated_by = ?, updated_at = ?, row_version = ?
		WHERE household_id = ? AND completed_by_id = ?
	`, survivor, now, rowVersion, hid, person.ID); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	if _, err := tx.ExecContext(r.Context(), `
		UPDATE task_templates SET preferred_assignee_id = NULL, updated_by = ?, updated_at = ?, row_version = ?
		WHERE household_id = ? AND preferred_assignee_id = ?
	`, survivor, now, rowVersion, hid, person.ID); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

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
		if _, err := tx.ExecContext(r.Context(), q, survivor, person.ID); err != nil {
			http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
			return
		}
	}

	if _, err := tx.ExecContext(r.Context(), `
		INSERT INTO sync_tombstones (household_id, entity, entity_id, row_version)
		VALUES (?, 'person', ?, ?)
		ON CONFLICT (household_id, entity, entity_id)
		DO UPDATE SET row_version = excluded.row_version,
		              deleted_at = strftime('%Y-%m-%d %H:%M:%f+00:00','now')
	`, hid, person.ID, rowVersion); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	if _, err := tx.ExecContext(r.Context(), `
		DELETE FROM people WHERE id = ? AND household_id = ?
	`, person.ID, hid); err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "left"})
}

func (s *Server) RegenerateInvite(w http.ResponseWriter, r *http.Request) {
	_, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	inviteCode := auth.NewInviteCode()

	tx, err := s.DB.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(r.Context(), `
		UPDATE households SET invite_code = ?, updated_at = ? WHERE id = ?
	`, inviteCode, time.Now().UTC(), hid); err != nil {
		http.Error(w, `{"error":"could not regenerate invite"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"invite_code": inviteCode,
	})
}
