package auth

import (
	"context"
	"net/http"
	"strings"

	"toka/internal/db"

	"github.com/jackc/pgx/v5/pgxpool"
)

type ctxKey string

const (
	PersonKey    ctxKey = "person"
	HouseholdKey ctxKey = "household"
)

type Person struct {
	ID          int64
	HouseholdID int64
	Name        string
	Color       string
	AvatarEmoji string
}

// Middleware resuelve el bearer token a una persona y deja en el contexto tanto la
// persona como su household.
//
// La resolución va en su propia transacción corta, con app.token_hash fijado, para
// que la policy people_token_lookup deje pasar exactamente esa fila. El handler
// abrirá después su propia transacción declarando el household — dos viajes a la
// base por petición, a cambio de que el aislamiento lo garantice el motor y no la
// disciplina de quien escribe el SQL.
func Middleware(pool *pgxpool.Pool) func(http.Handler) http.Handler {
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

			tx, err := db.BeginWithTokenHash(r.Context(), pool, hash)
			if err != nil {
				http.Error(w, `{"error":"db error"}`, http.StatusInternalServerError)
				return
			}
			defer tx.Rollback(r.Context()) // solo lectura: no hay nada que confirmar

			var p Person
			err = tx.QueryRow(r.Context(), `
				SELECT id, household_id, name, color, avatar_emoji
				FROM people WHERE token_hash = $1
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
