package auth

import (
	"context"
	"database/sql"
	"net/http"
	"strings"
)

type ctxKey string

const (
	PersonKey    ctxKey = "person"
	HouseholdKey ctxKey = "household"
)

type Person struct {
	ID          int64  `json:"id"`
	HouseholdID int64  `json:"household_id"`
	Name        string `json:"name"`
	Color       string `json:"color"`
	AvatarEmoji string `json:"avatar_emoji"`
}

// Middleware resuelve el bearer token a una persona y deja en el contexto tanto la
// persona como su household.
//
// La resolución es una lectura simple por token_hash, que es único a nivel global.
// El handler abre después su propia transacción y filtra por household_id en cada
// consulta — ya no hay RLS que lo haga por el código.
func Middleware(database *sql.DB) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			header := r.Header.Get("Authorization")
			if !strings.HasPrefix(header, "Bearer ") {
				unauthorized(w)
				return
			}
			token := strings.TrimPrefix(header, "Bearer ")
			if token == "" {
				unauthorized(w)
				return
			}

			hash := HashToken(token)

			var p Person
			err := database.QueryRowContext(r.Context(), `
				SELECT id, household_id, name, color, avatar_emoji
				FROM people WHERE token_hash = ?
			`, hash).Scan(&p.ID, &p.HouseholdID, &p.Name, &p.Color, &p.AvatarEmoji)
			if err != nil {
				unauthorized(w)
				return
			}

			ctx := context.WithValue(r.Context(), PersonKey, p)
			ctx = context.WithValue(ctx, HouseholdKey, p.HouseholdID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func unauthorized(w http.ResponseWriter) {
	http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
}

func PersonFromCtx(ctx context.Context) (Person, bool) {
	p, ok := ctx.Value(PersonKey).(Person)
	return p, ok
}

func HouseholdIDFromCtx(ctx context.Context) (int64, bool) {
	id, ok := ctx.Value(HouseholdKey).(int64)
	return id, ok
}

func RequireAuth(w http.ResponseWriter, r *http.Request) (Person, int64, bool) {
	p, ok := PersonFromCtx(r.Context())
	if !ok {
		unauthorized(w)
		return Person{}, 0, false
	}
	hid, ok := HouseholdIDFromCtx(r.Context())
	if !ok {
		unauthorized(w)
		return Person{}, 0, false
	}
	return p, hid, true
}
