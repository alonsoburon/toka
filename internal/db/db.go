package db

import (
	"context"
	"fmt"
	"net"
	"os"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Connect abre el pool con el que corre el servidor.
//
// El rol es parte de la configuración, no un detalle: en producción hay que entrar
// como toka_app (sin DDL, sin BYPASSRLS) para que las policies de RLS de la
// migración 002 apliquen. Ver db/roles.sql.
func Connect(ctx context.Context) (*pgxpool.Pool, error) {
	return connect(ctx, "DATABASE_URL")
}

// ConnectMigrator abre un pool aparte para DDL y seed, que necesitan ser dueños de
// las tablas. Si no hay DATABASE_MIGRATION_URL cae en DATABASE_URL, que es lo
// razonable en desarrollo local donde ambos roles son el mismo.
func ConnectMigrator(ctx context.Context) (*pgxpool.Pool, error) {
	if os.Getenv("DATABASE_MIGRATION_URL") != "" {
		return connect(ctx, "DATABASE_MIGRATION_URL")
	}
	return connect(ctx, "DATABASE_URL")
}

func connect(ctx context.Context, envVar string) (*pgxpool.Pool, error) {
	url := os.Getenv(envVar)
	if url == "" {
		// Sin default. El fallback que había antes traía usuario y contraseña
		// escritos en el binario, y funcionaba lo bastante bien como para que
		// nadie notara que la configuración de producción no se estaba leyendo.
		return nil, fmt.Errorf("%s no está definida (copia .env.example a .env)", envVar)
	}

	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, fmt.Errorf("%s inválida: %w", envVar, err)
	}

	if err := requireTLSIfRemote(cfg.ConnConfig); err != nil {
		return nil, err
	}

	if cfg.MaxConns == 0 {
		cfg.MaxConns = 10
	}

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}

	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("no se pudo conectar a Postgres: %w", err)
	}

	return pool, nil
}

// requireTLSIfRemote rechaza arrancar contra un Postgres remoto sin cifrar.
//
// sslmode=disable contra localhost es normal en desarrollo. Contra un host remoto
// significa que las credenciales y todos los datos del household viajan en claro,
// y es el error que se cuela al mover la app a Postgres gestionado copiando el
// DATABASE_URL de desarrollo y cambiándole solo el host.
func requireTLSIfRemote(cfg *pgx.ConnConfig) error {
	if cfg.TLSConfig != nil {
		return nil // sslmode=require / verify-ca / verify-full
	}
	if isLocal(cfg.Host) {
		return nil
	}
	return fmt.Errorf(
		"la conexión a %q no usa TLS: añade sslmode=require al DATABASE_URL "+
			"(o sslmode=verify-full con sslrootcert=..., que además valida el certificado "+
			"del servidor y es lo que corresponde en la nube)",
		cfg.Host,
	)
}

func isLocal(host string) bool {
	if host == "" || strings.HasPrefix(host, "/") {
		return true // socket unix
	}
	switch host {
	case "localhost", "127.0.0.1", "::1":
		return true
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		return true
	}
	return false
}

// Describe resume con qué rol y con qué cifrado quedó abierta la conexión, para
// dejarlo en el log de arranque. Un servidor corriendo como superusuario se salta
// RLS entero sin decir nada, y eso tiene que ser visible.
func Describe(ctx context.Context, pool *pgxpool.Pool) string {
	var user string
	var super, bypassRLS bool
	err := pool.QueryRow(ctx, `
		SELECT current_user, rolsuper, rolbypassrls
		FROM pg_roles WHERE rolname = current_user
	`).Scan(&user, &super, &bypassRLS)
	if err != nil {
		return "rol: desconocido (" + err.Error() + ")"
	}

	var ssl bool
	var version string
	_ = pool.QueryRow(ctx, `
		SELECT coalesce(s.ssl, false), current_setting('server_version')
		FROM pg_stat_ssl s WHERE s.pid = pg_backend_pid()
	`).Scan(&ssl, &version)

	desc := fmt.Sprintf("Postgres %s · rol %q · TLS %v", version, user, ssl)
	if super || bypassRLS {
		desc += "\n  ⚠  este rol se salta Row-Level Security: el aislamiento entre" +
			"\n     households NO lo está aplicando la base. Para producción usa toka_app" +
			"\n     (ver db/roles.sql)."
	}
	return desc
}

func Migrate(pool *pgxpool.Pool) error {
	ctx := context.Background()

	_, err := pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS _migrations (
			name TEXT PRIMARY KEY,
			applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
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
		err := pool.QueryRow(ctx,
			"SELECT EXISTS(SELECT 1 FROM _migrations WHERE name=$1)", name).Scan(&exists)
		if err != nil {
			return fmt.Errorf("checking migration %s: %w", name, err)
		}
		if exists {
			continue
		}

		sql, err := os.ReadFile("db/migrations/" + name)
		if err != nil {
			return fmt.Errorf("reading migration file %s: %w", name, err)
		}

		// Cada migración en su propia transacción: si falla a medias no queda un
		// esquema a mitad de camino con la migración sin registrar.
		tx, err := pool.Begin(ctx)
		if err != nil {
			return fmt.Errorf("begin for migration %s: %w", name, err)
		}

		if _, err = tx.Exec(ctx, string(sql)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("applying migration %s: %w", name, err)
		}

		if _, err = tx.Exec(ctx, "INSERT INTO _migrations (name) VALUES ($1)", name); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("recording migration %s: %w", name, err)
		}

		if err = tx.Commit(ctx); err != nil {
			return fmt.Errorf("commit migration %s: %w", name, err)
		}

		fmt.Printf("  ✓ %s\n", name)
	}
	return nil
}

func Seed(pool *pgxpool.Pool) error {
	seedSQL, err := os.ReadFile("db/seed.sql")
	if err != nil {
		return fmt.Errorf("reading seed file: %w", err)
	}

	_, err = pool.Exec(context.Background(), string(seedSQL))
	if err != nil {
		return fmt.Errorf("applying seed: %w", err)
	}
	fmt.Println("  ✓ seed data loaded")
	return nil
}
