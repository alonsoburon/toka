.PHONY: run run-seed build db-reset curl-setup smoke

# ── Base de datos SQLite: no hay daemon ni roles que administrar ──

run:
	cp -n .env.example .env 2>/dev/null || true
	go run .

run-seed:
	cp -n .env.example .env 2>/dev/null || true
	go run . -seed

build:
	go build -o toka .

## Borra el archivo SQLite y lo recrea con datos de prueba. ⚠️ destruye datos.
db-reset:
	rm -f toka.db toka.db-shm toka.db-wal
	$(MAKE) run-seed

# ── Smoke tests contra una base temporal (no toca toka.db) ──

smoke:
	bash .claude/scripts/smoke-local.sh

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
