package handler

import (
	"context"
	"net/http"
	"time"
)

// Health es un chequeo sin auth. Sirve para que la app valide una URL de servidor
// antes de guardarla y para que un monitor externo sepa si el proceso responde. Toca la
// base: un proceso vivo con el archivo SQLite inaccesible no está "ok".
func (s *Server) Health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	w.Header().Set("Content-Type", "application/json")
	if err := s.DB.PingContext(ctx); err != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		w.Write([]byte(`{"status":"db unavailable"}`))
		return
	}
	w.Write([]byte(`{"status":"ok"}`))
}
