package handler

import (
	"encoding/json"
	"net/http"

	"toka/internal/auth"
)

type MeHousehold struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	InviteCode string `json:"invite_code"`
}

type MeResponse struct {
	Person    auth.Person `json:"person"`
	Household MeHousehold `json:"household"`
}

// GetMe devuelve la persona y el hogar del token autenticado. Es lo que permite
// entrar a la app con un token ya existente (por ejemplo el del seed) sin pasar por
// el código de invitación, que crearía una persona nueva.
func (s *Server) GetMe(w http.ResponseWriter, r *http.Request) {
	person, hid, ok := auth.RequireAuth(w, r)
	if !ok {
		return
	}

	var household MeHousehold
	err := s.DB.QueryRowContext(r.Context(), `
		SELECT id, name, invite_code FROM households WHERE id = ?
	`, hid).Scan(&household.ID, &household.Name, &household.InviteCode)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(MeResponse{Person: person, Household: household})
}
