package db

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"sort"
	"strings"

	_ "modernc.org/sqlite"
)

const defaultPath = "toka.db"

// Connect abre la base SQLite. Toda la instalación es un solo archivo.
//
// El DSN fija los pragmas:
//   - WAL: las lecturas no bloquean a la escritura.
//   - busy_timeout: reintenta ante un lock momentáneo en vez de fallar.
//   - foreign_keys: valida las FKs (diferidas) al COMMIT.
//   - _time_format=sqlite: guarda los time.Time en un formato de ancho fijo
//     (YYYY-MM-DD HH:MM:SS.SSS+00:00), que ordena lexicográficamente.
func Connect(ctx context.Context) (*sql.DB, error) {
	path := Path()
	dsn := fmt.Sprintf(
		"file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)"+
			"&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(1)&_time_format=sqlite",
		path,
	)

	database, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}

	// Un solo escritor. SQLite serializa las escrituras igual; limitar el pool a
	// una conexión elimina de raíz los SQLITE_BUSY y las carreras en sync_counter.
	// A escala doméstica no cuesta nada.
	database.SetMaxOpenConns(1)

	if err := database.PingContext(ctx); err != nil {
		database.Close()
		return nil, fmt.Errorf("no se pudo abrir %s: %w", path, err)
	}
	return database, nil
}

// Path devuelve la ruta del archivo de base de datos.
func Path() string {
	if p := os.Getenv("TOKA_DB"); p != "" {
		return p
	}
	return defaultPath
}

// Describe resume con qué archivo quedó abierta la base, para el log de arranque.
func Describe() string {
	return "SQLite · archivo " + Path()
}

// Migrate aplica en orden los archivos db/migrations/*.up.sql que no estén en
// _migrations. El servidor los lee del disco en runtime, así que hay que correrlo
// desde la raíz del repo.
func Migrate(database *sql.DB) error {
	ctx := context.Background()

	_, err := database.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS _migrations (
			name       TEXT PRIMARY KEY,
			applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f+00:00','now'))
		)
	`)
	if err != nil {
		return fmt.Errorf("creating _migrations table: %w", err)
	}

	entries, err := os.ReadDir("db/migrations")
	if err != nil {
		return fmt.Errorf("reading migrations dir: %w", err)
	}

	names := make([]string, 0)
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".up.sql") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)

	for _, name := range names {
		var exists bool
		err := database.QueryRowContext(ctx,
			"SELECT EXISTS(SELECT 1 FROM _migrations WHERE name = ?)", name).Scan(&exists)
		if err != nil {
			return fmt.Errorf("checking migration %s: %w", name, err)
		}
		if exists {
			continue
		}

		sqlBytes, err := os.ReadFile("db/migrations/" + name)
		if err != nil {
			return fmt.Errorf("reading migration file %s: %w", name, err)
		}

		// Cada migración en su propia transacción: si falla a medias no queda un
		// esquema a mitad de camino con la migración sin registrar.
		tx, err := database.BeginTx(ctx, nil)
		if err != nil {
			return fmt.Errorf("begin for migration %s: %w", name, err)
		}

		if _, err = tx.ExecContext(ctx, string(sqlBytes)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("applying migration %s: %w", name, err)
		}

		if _, err = tx.ExecContext(ctx, "INSERT INTO _migrations (name) VALUES (?)", name); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("recording migration %s: %w", name, err)
		}

		if err = tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %s: %w", name, err)
		}

		fmt.Printf("  ✓ %s\n", name)
	}
	return nil
}

// Seed carga db/seed.sql (idempotente).
func Seed(database *sql.DB) error {
	seedSQL, err := os.ReadFile("db/seed.sql")
	if err != nil {
		return fmt.Errorf("reading seed file: %w", err)
	}

	if _, err := database.ExecContext(context.Background(), string(seedSQL)); err != nil {
		return fmt.Errorf("applying seed: %w", err)
	}
	fmt.Println("  ✓ seed data loaded")
	return nil
}
