package server

import (
	"database/sql"
	"net/http"

	"toka/internal/auth"
	"toka/internal/handler"
)

func New(database *sql.DB) http.Handler {
	s := &handler.Server{DB: database}
	authMw := auth.Middleware(database)

	mux := http.NewServeMux()

	// Sin auth: sondeo de disponibilidad.
	mux.HandleFunc("GET /healthz", s.Health)

	mux.HandleFunc("POST /households", s.CreateHousehold)
	mux.HandleFunc("POST /households/join", s.JoinHousehold)

	mux.Handle("GET /me", authMw(http.HandlerFunc(s.GetMe)))
	mux.Handle("GET /households/{hid}/people", authMw(http.HandlerFunc(s.ListPeople)))
	mux.Handle("POST /households/{hid}/people", authMw(http.HandlerFunc(s.CreatePerson)))
	mux.Handle("PATCH /people/{id}", authMw(http.HandlerFunc(s.UpdatePerson)))
	mux.Handle("DELETE /people/{id}", authMw(http.HandlerFunc(s.DeletePerson)))
	mux.Handle("POST /households/{hid}/regenerate-invite", authMw(http.HandlerFunc(s.RegenerateInvite)))
	mux.Handle("POST /households/{hid}/leave", authMw(http.HandlerFunc(s.LeaveHousehold)))

	mux.Handle("GET /templates", authMw(http.HandlerFunc(s.ListTemplates)))
	mux.Handle("POST /templates", authMw(http.HandlerFunc(s.CreateTemplate)))
	mux.Handle("PATCH /templates/{id}", authMw(http.HandlerFunc(s.UpdateTemplate)))
	mux.Handle("POST /templates/{id}/recurrence", authMw(http.HandlerFunc(s.SetTemplateRecurrence)))
	mux.Handle("DELETE /templates/{id}", authMw(http.HandlerFunc(s.DeleteTemplate)))

	mux.Handle("GET /tasks", authMw(http.HandlerFunc(s.ListPendingTasks)))
	mux.Handle("GET /tasks/history", authMw(http.HandlerFunc(s.ListTaskHistory)))
	mux.Handle("POST /tasks/{id}/complete", authMw(http.HandlerFunc(s.CompleteTask)))
	mux.Handle("POST /tasks/{id}/skip", authMw(http.HandlerFunc(s.SkipTask)))
	mux.Handle("PATCH /tasks/{id}", authMw(http.HandlerFunc(s.UpdateTask)))

	// Sincronización del cliente offline.
	mux.Handle("GET /sync", authMw(http.HandlerFunc(s.Pull)))
	mux.Handle("POST /sync/mutations", authMw(http.HandlerFunc(s.Push)))

	// 10 req/s por IP con ráfaga de 30; cuerpos de hasta 1 MB; 32 peticiones
	// concurrentes. Holgado para dos teléfonos, incómodo para un script.
	return newLimits(10, 30, 1<<20, 32).middleware(mux)
}
