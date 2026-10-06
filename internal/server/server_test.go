package server_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"toka/internal/db"
	"toka/internal/server"
)

// El servidor lee db/migrations del disco relativo al directorio de trabajo, así que
// los tests se ejecutan desde la raíz del repo.
func TestMain(m *testing.M) {
	if err := os.Chdir("../.."); err != nil {
		panic(err)
	}
	os.Exit(m.Run())
}

type env struct {
	t   *testing.T
	srv *httptest.Server
	db  interface{ Close() error }
}

func newEnv(t *testing.T) *env {
	t.Helper()
	t.Setenv("TOKA_DB", filepath.Join(t.TempDir(), "test.db"))
	database, err := db.Connect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(database); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(server.New(database))
	t.Cleanup(func() { srv.Close(); database.Close() })
	return &env{t: t, srv: srv, db: database}
}

// call hace una petición y devuelve status y body decodificado (objeto o arreglo).
func (e *env) call(method, path, token string, body any) (int, any) {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out any
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

func (e *env) must(status int, method, path, token string, body any) map[string]any {
	e.t.Helper()
	got, out := e.call(method, path, token, body)
	if got != status {
		e.t.Fatalf("%s %s: status %d, quería %d (body %v)", method, path, got, status, out)
	}
	m, _ := out.(map[string]any)
	return m
}

type house struct {
	token  string
	person int64
	hid    int64
}

func (e *env) newHouse(name string) house {
	e.t.Helper()
	r := e.must(201, "POST", "/households", "", map[string]any{"name": name, "admin_name": "Admin " + name})
	return house{token: r["token"].(string), person: int64(r["person_id"].(float64)), hid: int64(r["id"].(float64))}
}

func (e *env) newTemplate(h house, body map[string]any) (templateID int64) {
	e.t.Helper()
	r := e.must(201, "POST", "/templates", h.token, body)
	return int64(r["id"].(float64))
}

func (e *env) pending(h house) []map[string]any {
	e.t.Helper()
	_, out := e.call("GET", "/tasks", h.token, nil)
	arr, _ := out.([]any)
	res := make([]map[string]any, 0, len(arr))
	for _, v := range arr {
		res = append(res, v.(map[string]any))
	}
	return res
}

func idOf(m map[string]any) int64 { return int64(m["id"].(float64)) }

func (e *env) pull(h house, since int64) map[string]any {
	e.t.Helper()
	return e.must(200, "GET", fmt.Sprintf("/sync?since=%d", since), h.token, nil)
}

var muSeq int

func mutation(op string, payload any) map[string]any {
	muSeq++
	return map[string]any{
		"mutation_id": fmt.Sprintf("00000000-0000-4000-8000-%012d", muSeq),
		"op":          op,
		"payload":     payload,
	}
}

func (e *env) push(h house, muts ...map[string]any) []map[string]any {
	e.t.Helper()
	r := e.must(200, "POST", "/sync/mutations", h.token, map[string]any{"mutations": muts})
	var res []map[string]any
	for _, v := range r["results"].([]any) {
		res = append(res, v.(map[string]any))
	}
	return res
}

func status(r map[string]any) int { return int(r["status"].(float64)) }

// ── Auth ─────────────────────────────────────────────────────────────────────

func TestProtectedRoutesRequireBearer(t *testing.T) {
	e := newEnv(t)
	routes := []struct{ method, path string }{
		{"GET", "/me"}, {"GET", "/households/1/people"}, {"POST", "/households/1/people"},
		{"PATCH", "/people/1"}, {"DELETE", "/people/1"},
		{"POST", "/households/1/regenerate-invite"}, {"POST", "/households/1/leave"},
		{"GET", "/templates"}, {"POST", "/templates"}, {"PATCH", "/templates/1"},
		{"POST", "/templates/1/recurrence"}, {"DELETE", "/templates/1"},
		{"GET", "/tasks"}, {"GET", "/tasks/history"}, {"POST", "/tasks/1/complete"},
		{"POST", "/tasks/1/skip"}, {"PATCH", "/tasks/1"},
		{"GET", "/sync"}, {"POST", "/sync/mutations"},
	}
	for _, r := range routes {
		if got, _ := e.call(r.method, r.path, "", nil); got != 401 {
			t.Errorf("%s %s sin token: %d, quería 401", r.method, r.path, got)
		}
		if got, _ := e.call(r.method, r.path, "token-inventado", nil); got != 401 {
			t.Errorf("%s %s token falso: %d, quería 401", r.method, r.path, got)
		}
	}
}

func TestHealthz(t *testing.T) {
	e := newEnv(t)
	e.must(200, "GET", "/healthz", "", nil)
}

func TestHealthzFailsWhenDBIsDown(t *testing.T) {
	e := newEnv(t)
	e.db.Close()
	if got, _ := e.call("GET", "/healthz", "", nil); got != 503 {
		t.Fatalf("healthz con la base cerrada: %d, quería 503", got)
	}
}

func TestHouseholdLimit(t *testing.T) {
	t.Setenv("TOKA_MAX_HOUSEHOLDS", "1")
	e := newEnv(t)
	e.newHouse("uno")
	e.must(403, "POST", "/households", "", map[string]any{"name": "dos", "admin_name": "x"})
}

// ── Aislamiento entre households ─────────────────────────────────────────────

func TestHouseholdIsolation(t *testing.T) {
	e := newEnv(t)
	a, b := e.newHouse("A"), e.newHouse("B")

	tplA := e.newTemplate(a, map[string]any{"name": "secreto de A", "recurrence_days": 3, "reminder_times": "09:00"})
	taskA := idOf(e.pending(a)[0])

	// B no ve nada de A.
	if len(e.pending(b)) != 0 {
		t.Fatal("B ve tareas de A")
	}
	_, tpls := e.call("GET", "/templates", b.token, nil)
	if len(tpls.([]any)) != 0 {
		t.Fatal("B ve plantillas de A")
	}
	pull := e.pull(b, 0)
	for _, k := range []string{"templates", "tasks"} {
		if n := len(pull[k].([]any)); n != 0 {
			t.Fatalf("pull de B trae %d %s de A", n, k)
		}
	}
	for _, p := range pull["people"].([]any) {
		if idOf(p.(map[string]any)) == a.person {
			t.Fatal("pull de B trae a la persona de A")
		}
	}

	// B no puede tocar nada de A: todo 404.
	for _, c := range []struct {
		method, path string
		body         any
	}{
		{"POST", fmt.Sprintf("/tasks/%d/complete", taskA), nil},
		{"POST", fmt.Sprintf("/tasks/%d/skip", taskA), nil},
		{"PATCH", fmt.Sprintf("/tasks/%d", taskA), map[string]any{"notes": "hackeado"}},
		{"PATCH", fmt.Sprintf("/templates/%d", tplA), map[string]any{"name": "hackeado"}},
		{"POST", fmt.Sprintf("/templates/%d/recurrence", tplA), map[string]any{"recurrence_days": 1}},
		{"DELETE", fmt.Sprintf("/templates/%d", tplA), nil},
		{"PATCH", fmt.Sprintf("/people/%d", a.person), map[string]any{"name": "hackeado"}},
		{"DELETE", fmt.Sprintf("/people/%d", a.person), nil},
	} {
		if got, _ := e.call(c.method, c.path, b.token, c.body); got != 404 {
			t.Errorf("B %s %s: %d, quería 404", c.method, c.path, got)
		}
	}

	// ...ni por la cola de sync.
	res := e.push(b,
		mutation("task.complete", map[string]any{"id": taskA}),
		mutation("task.update", map[string]any{"id": taskA, "notes": "hackeado"}),
		mutation("template.update", map[string]any{"id": tplA, "name": "hackeado"}),
		mutation("template.delete", map[string]any{"id": tplA}),
		mutation("person.update", map[string]any{"id": a.person, "name": "hackeado"}),
		mutation("person.delete", map[string]any{"id": a.person}),
	)
	// task.complete devuelve 409 si no encuentra la fila pendiente; el resto 404.
	for i, r := range res {
		if s := status(r); s != 404 && s != 409 {
			t.Errorf("mutación %d de B sobre datos de A: %d", i, s)
		}
	}

	// Lo de A sigue intacto.
	if got := e.pending(a); len(got) != 1 || got[0]["template_name"] != "secreto de A" {
		t.Fatalf("la tarea de A cambió: %v", got)
	}
}

// Asignar a una persona de otra familia filtraba su nombre/avatar por el JOIN y bloqueaba
// el borrado de esa persona. Tiene que rechazarse por todas las vías.
func TestCannotReferencePersonFromAnotherHousehold(t *testing.T) {
	e := newEnv(t)
	a, b := e.newHouse("A"), e.newHouse("B")
	tplB := e.newTemplate(b, map[string]any{"name": "b"})
	taskB := idOf(e.pending(b)[0])

	e.must(400, "POST", "/templates", b.token, map[string]any{"name": "x", "preferred_assignee_id": a.person})
	e.must(400, "PATCH", fmt.Sprintf("/templates/%d", tplB), b.token, map[string]any{"preferred_assignee_id": a.person})
	e.must(400, "PATCH", fmt.Sprintf("/tasks/%d", taskB), b.token, map[string]any{"assigned_to_id": a.person})

	res := e.push(b,
		mutation("template.create", map[string]any{"client_id": "c1", "name": "x", "preferred_assignee_id": a.person}),
		mutation("template.update", map[string]any{"id": tplB, "preferred_assignee_id": a.person}),
		mutation("task.update", map[string]any{"id": taskB, "assigned_to_id": a.person}),
	)
	for i, r := range res {
		if status(r) != 400 {
			t.Errorf("mutación %d con persona ajena: %d, quería 400", i, status(r))
		}
	}

	// Nada de B quedó apuntando a la persona de A: A puede borrar a otra sin que una FK lo bloquee.
	extra := e.must(201, "POST", fmt.Sprintf("/households/%d/people", a.hid), a.token, map[string]any{"name": "otra"})
	e.must(200, "DELETE", fmt.Sprintf("/people/%d", int64(extra["id"].(float64))), a.token, nil)
}

func TestMutationIDDoesNotLeakAcrossHouseholds(t *testing.T) {
	e := newEnv(t)
	a, b := e.newHouse("A"), e.newHouse("B")
	m := mutation("template.create", map[string]any{"client_id": "secreto-a", "name": "plantilla de A"})
	if r := e.push(a, m)[0]; status(r) != 201 {
		t.Fatalf("A: %v", r)
	}
	// B reenvía el mismo mutation_id: no debe recibir la respuesta guardada de A.
	r := e.push(b, m)[0]
	if r["duplicate"] == true || strings.Contains(fmt.Sprint(r["body"]), "secreto-a") {
		t.Fatalf("B recibió la respuesta de A: %v", r)
	}
}

// ── Recurrencia ──────────────────────────────────────────────────────────────

func parseTime(t *testing.T, v any) time.Time {
	t.Helper()
	ts, err := time.Parse(time.RFC3339Nano, v.(string))
	if err != nil {
		t.Fatal(err)
	}
	return ts
}

func TestRecurrenceCountsFromCompletion(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	e.newTemplate(h, map[string]any{"name": "basura", "recurrence_days": 7})
	first := e.pending(h)[0]

	before := time.Now()
	r := e.must(200, "POST", fmt.Sprintf("/tasks/%d/complete", idOf(first)), h.token, map[string]any{"notes": "listo"})
	next := parseTime(t, r["next_due_at"])
	want := before.Add(7 * 24 * time.Hour)
	if d := next.Sub(want); d < -time.Second || d > 5*time.Second {
		t.Fatalf("next_due_at %v, esperado ≈ %v", next, want)
	}

	p := e.pending(h)
	if len(p) != 1 || idOf(p[0]) == idOf(first) {
		t.Fatalf("debería haber exactamente 1 pendiente nueva: %v", p)
	}
	// Completar de nuevo la misma tarea: ya resuelta.
	e.must(404, "POST", fmt.Sprintf("/tasks/%d/complete", idOf(first)), h.token, nil)
}

func TestRecurrenceSkipAndOneShot(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	e.newTemplate(h, map[string]any{"name": "recurrente", "recurrence_days": 2})
	e.newTemplate(h, map[string]any{"name": "una vez"})

	for _, task := range e.pending(h) {
		path := "complete"
		if task["template_name"] == "recurrente" {
			path = "skip"
		}
		e.must(200, "POST", fmt.Sprintf("/tasks/%d/%s", idOf(task), path), h.token, nil)
	}
	p := e.pending(h)
	if len(p) != 1 || p[0]["template_name"] != "recurrente" {
		t.Fatalf("solo la recurrente debe regenerarse: %v", p)
	}
}

func TestDeletedTemplateRetiresPendingAndDoesNotRegenerate(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	tpl := e.newTemplate(h, map[string]any{"name": "vieja", "recurrence_days": 1})
	task := idOf(e.pending(h)[0])
	cursor := int64(e.pull(h, 0)["cursor"].(float64))

	e.call("DELETE", fmt.Sprintf("/templates/%d", tpl), h.token, nil)
	if p := e.pending(h); len(p) != 0 {
		t.Fatalf("tras borrar la plantilla siguen pendientes: %v", p)
	}
	// El cliente offline se entera por tombstone.
	deleted := e.pull(h, cursor)["deleted"].([]any)
	found := false
	for _, d := range deleted {
		m := d.(map[string]any)
		if m["entity"] == "task" && idOf(m) == task {
			found = true
		}
	}
	if !found {
		t.Fatalf("falta el tombstone de la tarea: %v", deleted)
	}
	// Y por la cola de sync tampoco regenera.
	e.must(404, "POST", fmt.Sprintf("/tasks/%d/complete", task), h.token, nil)
}

// ── Sync / applyOp ───────────────────────────────────────────────────────────

func TestMutationDedupe(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	m := mutation("template.create", map[string]any{"client_id": "cid-1", "name": "dup", "recurrence_days": 1})

	first := e.push(h, m)[0]
	second := e.push(h, m)[0]
	if status(first) != 201 || second["duplicate"] != true || fmt.Sprint(first["body"]) != fmt.Sprint(second["body"]) {
		t.Fatalf("reenvío no devolvió la respuesta guardada: %v / %v", first, second)
	}
	// Segunda red: otro mutation_id con el mismo client_id no duplica la plantilla.
	other := mutation("template.create", map[string]any{"client_id": "cid-1", "name": "dup", "recurrence_days": 1})
	e.push(h, other)
	_, tpls := e.call("GET", "/templates", h.token, nil)
	if n := len(tpls.([]any)); n != 1 {
		t.Fatalf("%d plantillas, quería 1", n)
	}
	if n := len(e.pending(h)); n != 1 {
		t.Fatalf("%d instancias, quería 1", n)
	}
}

func TestMutationValidation(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	for name, m := range map[string]map[string]any{
		"uuid inválido":  {"mutation_id": "no-es-uuid", "op": "task.complete", "payload": map[string]any{"id": 1}},
		"op desconocida": mutation("task.explode", map[string]any{}),
		"sin client_id":  mutation("template.create", map[string]any{"name": "x"}),
	} {
		if r := e.push(h, m)[0]; status(r) != 400 {
			t.Errorf("%s: %d, quería 400", name, status(r))
		}
	}
	// Un lote demasiado grande se rechaza entero.
	big := make([]map[string]any, 201)
	for i := range big {
		big[i] = mutation("task.explode", nil)
	}
	e.must(413, "POST", "/sync/mutations", h.token, map[string]any{"mutations": big})
}

func TestOfflineCompleteUsesClientTimeAndClampsFuture(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	e.newTemplate(h, map[string]any{"name": "a", "recurrence_days": 7})
	e.newTemplate(h, map[string]any{"name": "b", "recurrence_days": 7})
	tasks := e.pending(h)

	threeDaysAgo := time.Now().UTC().Add(-72 * time.Hour)
	nextWeek := time.Now().UTC().Add(7 * 24 * time.Hour)
	res := e.push(h,
		mutation("task.complete", map[string]any{"id": idOf(tasks[0]), "completed_at": threeDaysAgo}),
		mutation("task.complete", map[string]any{"id": idOf(tasks[1]), "completed_at": nextWeek}),
	)

	body := func(r map[string]any) map[string]any { return r["body"].(map[string]any) }
	// Sin señal tres días: la siguiente ventana cuenta desde cuando se marcó.
	if got, want := parseTime(t, body(res[0])["next_due_at"]), threeDaysAgo.Add(7*24*time.Hour); got.Sub(want).Abs() > time.Second {
		t.Errorf("next_due_at %v, esperado %v", got, want)
	}
	// Un reloj en el futuro no corre la ventana: se usa "ahora".
	if got, max := parseTime(t, body(res[1])["next_due_at"]), time.Now().Add(7*24*time.Hour+5*time.Second); got.After(max) {
		t.Errorf("completed_at futuro no se recortó: %v", got)
	}
	// Reintentar sobre una tarea ya resuelta es un conflicto (409), no un error.
	r := e.push(h, mutation("task.complete", map[string]any{"id": idOf(tasks[0])}))[0]
	if status(r) != 409 {
		t.Errorf("tarea ya resuelta: %d, quería 409", status(r))
	}
}

func TestUncompleteRemovesGeneratedInstance(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	e.newTemplate(h, map[string]any{"name": "a", "recurrence_days": 3})
	first := idOf(e.pending(h)[0])
	e.must(200, "POST", fmt.Sprintf("/tasks/%d/complete", first), h.token, nil)
	generated := idOf(e.pending(h)[0])
	cursor := int64(e.pull(h, 0)["cursor"].(float64))

	if r := e.push(h, mutation("task.uncomplete", map[string]any{"id": first}))[0]; status(r) != 200 {
		t.Fatalf("uncomplete: %v", r)
	}
	p := e.pending(h)
	if len(p) != 1 || idOf(p[0]) != first {
		t.Fatalf("tras deshacer debe quedar solo la original: %v", p)
	}
	found := false
	for _, d := range e.pull(h, cursor)["deleted"].([]any) {
		if m := d.(map[string]any); m["entity"] == "task" && idOf(m) == generated {
			found = true
		}
	}
	if !found {
		t.Fatal("falta el tombstone de la instancia generada")
	}
	// Deshacer lo que no está resuelto es un conflicto.
	if r := e.push(h, mutation("task.uncomplete", map[string]any{"id": first}))[0]; status(r) != 409 {
		t.Errorf("uncomplete de una pendiente: %d, quería 409", status(r))
	}
}

func TestPullCursorIsIncremental(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	e.newTemplate(h, map[string]any{"name": "a"})
	c1 := int64(e.pull(h, 0)["cursor"].(float64))

	p := e.pull(h, c1)
	if len(p["tasks"].([]any)) != 0 || len(p["templates"].([]any)) != 0 {
		t.Fatalf("pull sin cambios devolvió filas: %v", p)
	}
	e.newTemplate(h, map[string]any{"name": "b"})
	p = e.pull(h, c1)
	if len(p["templates"].([]any)) != 1 || len(p["tasks"].([]any)) != 1 {
		t.Fatalf("pull incremental: %v", p)
	}
	e.must(400, "GET", "/sync?since=abc", h.token, nil)
}

// ── Validación de entrada ────────────────────────────────────────────────────

func TestInputValidation(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	long := strings.Repeat("x", 500)

	e.must(400, "POST", "/templates", h.token, map[string]any{"name": long})
	e.must(400, "POST", "/templates", h.token, map[string]any{"name": "x", "recurrence_days": 100000})
	e.must(400, "POST", "/templates", h.token, map[string]any{"name": "x", "recurrence_days": -3})
	e.must(400, "POST", "/templates", h.token, map[string]any{"name": "x", "description": strings.Repeat("d", 3000)})
	e.must(400, "POST", "/households", "", map[string]any{"name": long, "admin_name": "x"})
	e.must(400, "POST", fmt.Sprintf("/households/%d/people", h.hid), h.token, map[string]any{"name": long})

	tpl := e.newTemplate(h, map[string]any{"name": "ok", "recurrence_days": 2})
	e.must(400, "POST", fmt.Sprintf("/templates/%d/recurrence", tpl), h.token, map[string]any{"recurrence_days": 100000})
	// 0 sigue significando "una sola vez".
	e.must(200, "POST", fmt.Sprintf("/templates/%d/recurrence", tpl), h.token, map[string]any{"recurrence_days": 0})

	res := e.push(h, mutation("template.create", map[string]any{"client_id": "z", "name": "x", "recurrence_days": 100000}))
	if status(res[0]) != 400 {
		t.Errorf("sync con recurrence_days enorme: %d", status(res[0]))
	}
}

func TestPersonLifecycle(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	r := e.must(201, "POST", fmt.Sprintf("/households/%d/people", h.hid), h.token, map[string]any{"name": "Ana"})
	anaID := int64(r["id"].(float64))
	tpl := e.newTemplate(h, map[string]any{"name": "t", "preferred_assignee_id": anaID})
	_ = tpl
	e.must(200, "PATCH", fmt.Sprintf("/tasks/%d", idOf(e.pending(h)[0])), h.token, map[string]any{"assigned_to_id": anaID})

	// No puedes borrarte a ti mismo; sí a Ana, y su tarea queda sin asignar.
	e.must(400, "DELETE", fmt.Sprintf("/people/%d", h.person), h.token, nil)
	e.must(200, "DELETE", fmt.Sprintf("/people/%d", anaID), h.token, nil)
	if got := e.pending(h)[0]["assigned_to_id"]; got != nil {
		t.Fatalf("la tarea de Ana sigue asignada: %v", got)
	}
	// La última persona no puede salir.
	e.must(400, "POST", fmt.Sprintf("/households/%d/leave", h.hid), h.token, nil)
}

// Una plantilla creada sin conexión se edita con su id provisional (negativo) mientras la
// creación sigue en la cola: el servidor la resuelve por client_id.
func TestProvisionalTemplateIDResolvedByClientID(t *testing.T) {
	e := newEnv(t)
	h := e.newHouse("A")
	res := e.push(h,
		mutation("template.create", map[string]any{"client_id": "prov-1", "name": "nueva", "recurrence_days": 2}),
		mutation("template.update", map[string]any{"id": -123, "client_id": "prov-1", "name": "renombrada"}),
		mutation("template.set_recurrence", map[string]any{"id": -123, "client_id": "prov-1", "recurrence_days": 5}),
		mutation("template.update", map[string]any{"id": -999, "client_id": "no-existe", "name": "x"}),
	)
	want := []int{201, 200, 200, 404}
	for i, r := range res {
		if status(r) != want[i] {
			t.Fatalf("mutación %d: %d, quería %d (%v)", i, status(r), want[i], r)
		}
	}
	_, tpls := e.call("GET", "/templates", h.token, nil)
	tpl := tpls.([]any)[0].(map[string]any)
	if tpl["name"] != "renombrada" || tpl["recurrence_days"].(float64) != 5 {
		t.Fatalf("la edición por client_id no se aplicó: %v", tpl)
	}

	// Y se puede borrar igual; las instancias pendientes se retiran.
	if r := e.push(h, mutation("template.delete", map[string]any{"id": -123, "client_id": "prov-1"}))[0]; status(r) != 200 {
		t.Fatalf("delete por client_id: %v", r)
	}
	if len(e.pending(h)) != 0 {
		t.Fatal("quedaron pendientes tras borrar la plantilla")
	}
}
