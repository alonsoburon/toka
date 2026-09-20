package handler

import "net/http"

// Health es un chequeo sin auth. Sirve para que la app valide una URL de servidor
// antes de guardarla y para que un monitor externo sepa si el proceso responde.
func (s *Server) Health(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"status":"ok"}`))
}
