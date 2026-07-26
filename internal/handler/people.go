package handler

import (
	"encoding/json"
	"net/http"
	"strconv"

	"toka/internal/auth"
	"toka/internal/db"
	"toka/internal/model"
)

func (s *Server) ListPeople(w http.ResponseWriter, r *http.Request) {
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

	// token_hash no aparece en el SELECT. No hay ninguna respuesta de la API que
	// deba incluirlo, y no traerlo evita que alguien lo agregue sin querer a un
	// struct que sí se serializa.
	rows, err := tx.Query(r.Context(), `
		SELECT id, household_id, name, color, avatar_emoji, created_at, updated_at, created_by, updated_by
		FROM people WHERE household_id = $1 ORDER BY id
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

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	var personID int64
	err = tx.QueryRow(r.Context(), `
		INSERT INTO people (household_id, name, color, avatar_emoji, token_hash, created_by, updated_by)
		VALUES ($1, $2, $3, $4, $5, $6, $6) RETURNING id
	`, hid, req.Name, req.Color, req.Emoji, auth.HashToken(token), admin.ID).Scan(&personID)
	if err != nil {
		http.Error(w, `{"error":"could not create person"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
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

	tx, err := db.BeginScoped(r.Context(), s.DB, hid)
	if err != nil {
		http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback(r.Context())

	// Este UPDATE no llevaba filtro por household y editaba cualquier persona del
	// sistema por id. Ahora la policy people_scope lo acota; el "AND household_id"
	// explícito va igual, para que la sentencia no dependa de que RLS esté activo.
	var person model.Person
	err = tx.QueryRow(r.Context(), `
		UPDATE people
		SET name = COALESCE($1, name),
		    color = COALESCE($2, color),
		    avatar_emoji = COALESCE($3, avatar_emoji),
		    updated_by = $4
		WHERE id = $5 AND household_id = $6
		RETURNING id, household_id, name, color, avatar_emoji, updated_at
	`, req.Name, req.Color, req.Emoji, editor.ID, id, hid).Scan(
		&person.ID, &person.HouseholdID, &person.Name, &person.Color,
		&person.AvatarEmoji, &person.UpdatedAt)
	if err != nil {
		http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
		return
	}

	if err := tx.Commit(r.Context()); err != nil {
		http.Error(w, `{"error":"commit failed"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(person)
}
