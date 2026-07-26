package handler

import (
	"encoding/json"
	"net/http"

	"toka/internal/auth"
	"toka/internal/db"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Server struct {
	DB *pgxpool.Pool
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
// El orden importa: primero se reserva el id con nextval y se declara el household
// activo, y recién entonces se inserta. Al revés no funcionaría — la policy
// households_scope exige que la fila que se inserta pertenezca al household activo,
// y el id no existe hasta después del INSERT. Reservarlo antes evita tener que
// abrir una policy de excepción que permita escribir sin contexto.
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

	tx, err := s.DB.Begin(r.Context())
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	householdID, err := db.NextHouseholdID(r.Context(), tx)
	if err != nil {
		http.Error(w, `{"error":"could not allocate household"}`, http.StatusInternalServerError)
		return
	}
	if err := db.SetHousehold(r.Context(), tx, householdID); err != nil {
		http.Error(w, `{"error":"could not scope transaction"}`, http.StatusInternalServerError)
		return
	}

	_, err = tx.Exec(r.Context(), `
		INSERT INTO households (id, name, invite_code, created_by, updated_by)
		VALUES ($1, $2, $3, 0, 0)
	`, householdID, req.Name, inviteCode)
	if err != nil {
		http.Error(w, `{"error":"could not create household"}`, http.StatusInternalServerError)
		return
	}

	var personID int64
	err = tx.QueryRow(r.Context(), `
		INSERT INTO people (household_id, name, color, avatar_emoji, token_hash, created_by, updated_by)
		VALUES ($1, $2, $3, $4, $5, 0, 0) RETURNING id
	`, householdID, req.AdminName, req.AdminColor, req.AdminEmoji, auth.HashToken(personToken)).Scan(&personID)
	if err != nil {
		http.Error(w, `{"error":"could not create admin"}`, http.StatusInternalServerError)
		return
	}

	// created_by/updated_by quedaron en 0 porque la persona no existía todavía.
	// Las FKs son DEFERRABLE INITIALLY DEFERRED: se validan al COMMIT, así que hay
	// margen para corregirlas aquí.
	_, err = tx.Exec(r.Context(), `
		UPDATE households SET created_by = $1, updated_by = $1 WHERE id = $2
	`, personID, householdID)
	if err != nil {
		http.Error(w, `{"error":"could not update household refs"}`, http.StatusInternalServerError)
		return
	}

	_, err = tx.Exec(r.Context(), `
		UPDATE people SET created_by = $1, updated_by = $1 WHERE id = $1
	`, personID)
	if err != nil {
		http.Error(w, `{"error":"could not update person refs"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
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
//
// Empieza con app.invite_code fijado, que por households_invite_lookup deja ver
// exactamente el household de ese código y ninguno más — no sirve para enumerar.
// En cuanto se conoce el household, la transacción lo adopta y el resto de las
// escrituras van bajo las policies normales.
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

	tx, err := db.BeginWithInviteCode(r.Context(), s.DB, req.InviteCode)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	// Sin FOR UPDATE. Bloquear una fila cuenta como intención de escribirla, así que
	// Postgres la evalúa contra la policy de UPDATE y no contra la de SELECT — y la
	// de invite code es solo de lectura, de modo que el lock devolvía cero filas.
	// Tampoco hacía falta: aquí no se lee-modifica-escribe el household, solo se
	// inserta una persona. Lo peor que puede pasar en una carrera es entrar con un
	// código que acaba de rotarse, que es inofensivo.
	var householdID int64
	err = tx.QueryRow(r.Context(), `
		SELECT id FROM households WHERE invite_code = $1
	`, req.InviteCode).Scan(&householdID)
	if err != nil {
		http.Error(w, `{"error":"invalid invite code"}`, http.StatusNotFound)
		return
	}

	if err := db.SetHousehold(r.Context(), tx, householdID); err != nil {
		http.Error(w, `{"error":"could not scope transaction"}`, http.StatusInternalServerError)
		return
	}

	var personID int64
	err = tx.QueryRow(r.Context(), `
		INSERT INTO people (household_id, name, color, avatar_emoji, token_hash, created_by, updated_by)
		VALUES ($1, $2, $3, $4, $5, 0, 0) RETURNING id
	`, householdID, req.Name, req.Color, req.Emoji, auth.HashToken(personToken)).Scan(&personID)
	if err != nil {
		http.Error(w, `{"error":"could not create person"}`, http.StatusInternalServerError)
		return
	}

	_, err = tx.Exec(r.Context(), `
		UPDATE people SET created_by = $1, updated_by = $1 WHERE id = $1
	`, personID)
	if err != nil {
		http.Error(w, `{"error":"could not update refs"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
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

func (s *Server) RegenerateInvite(w http.ResponseWriter, r *http.Request) {
	_, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	inviteCode := auth.NewInviteCode()

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	// El "WHERE id = $2" es redundante bajo RLS (la policy ya acota al household
	// activo), pero se mantiene: si algún día el servidor corriera con un rol que
	// se salta RLS, esta sentencia seguiría siendo correcta por sí sola.
	_, err = tx.Exec(r.Context(), `
		UPDATE households SET invite_code = $1 WHERE id = $2
	`, inviteCode, hid)
	if err != nil {
		http.Error(w, `{"error":"could not regenerate invite"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"invite_code": inviteCode,
	})
}
