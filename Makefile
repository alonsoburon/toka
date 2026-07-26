.PHONY: db-up db-down db-reset db-roles db-password db-check-rls run run-seed build curl-setup

# ── Database (native PostgreSQL, no Docker) ──

db-up:
	@# /run/postgresql vive en tmpfs y desaparece al reiniciar. Sin él, pg_ctl
	@# arranca y muere con "could not create lock file".
	@sudo install -d -o postgres -g postgres -m 2775 /run/postgresql
	@sudo -u postgres pg_ctl -D /var/lib/postgres/data start 2>/dev/null || true
	@until sudo -u postgres pg_isready >/dev/null 2>&1; do sleep 0.5; done
	@echo "PostgreSQL ready"

db-down:
	@sudo -u postgres pg_ctl -D /var/lib/postgres/data stop 2>/dev/null || true

db-reset:
	@sudo -u postgres dropdb --if-exists toka
	@sudo -u postgres createdb -O toka toka
	@echo "Database toka recreated"
	@$(MAKE) run-seed

# ── Roles y RLS ──

## Crea toka_app / toka_readonly y sus grants. Idempotente, una vez por instalación.
db-roles:
	@sudo -u postgres psql -d toka -v ON_ERROR_STOP=1 -f db/roles.sql
	@echo "Roles listos. Fija la contraseña con: TOKA_APP_PASSWORD=... make db-password"

## Fija la contraseña de toka_app desde el entorno, sin que quede en el historial
## del shell ni en el repositorio.
db-password:
	@test -n "$$TOKA_APP_PASSWORD" || { echo "falta TOKA_APP_PASSWORD"; exit 1; }
	@# \getenv lee la contraseña del entorno del propio psql. Pasarla con -v la
	@# dejaría en la línea de comandos, visible en ps para cualquier usuario de
	@# la máquina mientras dure el comando.
	@printf '%s\n' "\\getenv pw TOKA_APP_PASSWORD" "ALTER ROLE toka_app PASSWORD :'pw';" \
		| sudo --preserve-env=TOKA_APP_PASSWORD -u postgres \
		  psql -d toka -v ON_ERROR_STOP=1 -q
	@echo "Contraseña de toka_app actualizada (guardada con scram-sha-256)"

## Comprueba que el aislamiento lo está aplicando la base y no el WHERE del handler.
db-check-rls:
	@sudo -u postgres psql -d toka -v ON_ERROR_STOP=1 -f db/check_rls.sql

# ── Server ──

run:
	cp -n .env.example .env 2>/dev/null || true
	go run .

run-seed:
	cp -n .env.example .env 2>/dev/null || true
	go run . -seed

build:
	go build -o toka .

# ── Curl cheatsheet ──

curl-setup:
	@echo ""
	@echo "=== Create household + admin ==="
	@echo 'curl -s http://localhost:3000/households -X POST -H "Content-Type: application/json" \'
	@echo '  -d '"'"'{"name":"Home Sweet Home","admin_name":"Alonso","admin_color":"#f472b6","admin_emoji":"🐱"}'"'"' | python3 -m json.tool'
	@echo ""
	@echo "=== Join household ==="
	@echo 'curl -s http://localhost:3000/households/join -X POST -H "Content-Type: application/json" \'
	@echo '  -d '"'"'{"invite_code":"<code>","name":"Ana","color":"#60a5fa","emoji":"🐶"}'"'"' | python3 -m json.tool'
	@echo ""
	@echo "=== Pending tasks ==="
	@echo 'curl -s http://localhost:3000/tasks -H "Authorization: Bearer <token>" | python3 -m json.tool'
	@echo ""
	@echo "=== Complete task ==="
	@echo 'curl -s -X POST http://localhost:3000/tasks/1/complete \'
	@echo '  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \'
	@echo '  -d '"'"'{"notes":"done!"}'"'"' | python3 -m json.tool'
	@echo ""
	@echo "=== History (last 30 days) ==="
	@echo 'curl -s "http://localhost:3000/tasks/history?days=30" -H "Authorization: Bearer <token>" | python3 -m json.tool'
	@echo ""
