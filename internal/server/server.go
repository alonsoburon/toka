package server

import (
	"net/http"

	"toka/internal/auth"
	"toka/internal/handler"

	"github.com/jackc/pgx/v5/pgxpool"
)

func New(pool *pgxpool.Pool) http.Handler {
	s := &handler.Server{DB: pool}
	authMw := auth.Middleware(pool)

	mux := http.NewServeMux()

	mux.HandleFunc("POST /households", s.CreateHousehold)
	mux.HandleFunc("POST /households/join", s.JoinHousehold)

	mux.Handle("GET /households/{hid}/people", authMw(http.HandlerFunc(s.ListPeople)))
	mux.Handle("POST /households/{hid}/people", authMw(http.HandlerFunc(s.CreatePerson)))
	mux.Handle("PATCH /people/{id}", authMw(http.HandlerFunc(s.UpdatePerson)))
	mux.Handle("POST /households/{hid}/regenerate-invite", authMw(http.HandlerFunc(s.RegenerateInvite)))

	mux.Handle("GET /templates", authMw(http.HandlerFunc(s.ListTemplates)))
	mux.Handle("POST /templates", authMw(http.HandlerFunc(s.CreateTemplate)))
	mux.Handle("PATCH /templates/{id}", authMw(http.HandlerFunc(s.UpdateTemplate)))
	mux.Handle("DELETE /templates/{id}", authMw(http.HandlerFunc(s.DeleteTemplate)))

	mux.Handle("GET /tasks", authMw(http.HandlerFunc(s.ListPendingTasks)))
	mux.Handle("GET /tasks/history", authMw(http.HandlerFunc(s.ListTaskHistory)))
	mux.Handle("POST /tasks/{id}/complete", authMw(http.HandlerFunc(s.CompleteTask)))
	mux.Handle("POST /tasks/{id}/skip", authMw(http.HandlerFunc(s.SkipTask)))
	mux.Handle("PATCH /tasks/{id}", authMw(http.HandlerFunc(s.UpdateTask)))

	// Sincronización del cliente offline.
	mux.Handle("GET /sync", authMw(http.HandlerFunc(s.Pull)))
	mux.Handle("POST /sync/mutations", authMw(http.HandlerFunc(s.Push)))

	return mux
}
