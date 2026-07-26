package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"

	"toka/internal/config"
	"toka/internal/db"
	"toka/internal/server"
)

func main() {
	seed := flag.Bool("seed", false, "seed database with sample data")
	port := flag.String("port", "", "server port (default from PORT env or 3000)")
	migrateOnly := flag.Bool("migrate-only", false, "run migrations and exit")
	envFile := flag.String("env", ".env", "archivo de entorno a cargar (vacío para omitirlo)")
	flag.Parse()

	if *envFile != "" {
		if err := config.LoadDotEnv(*envFile); err != nil {
			log.Fatalf("leyendo %s: %v", *envFile, err)
		}
	}

	ctx := context.Background()

	// Dos conexiones con roles distintos.
	//
	// El migrador necesita ser dueño de las tablas (DDL) y se salta RLS, así que se
	// usa lo mínimo posible: migrar, sembrar, cerrar. El pool con el que se sirven
	// las peticiones entra como toka_app, sin DDL y sujeto a las policies.
	//
	// En desarrollo local ambas URLs suelen ser la misma y esto no cambia nada.
	migrator, err := db.ConnectMigrator(ctx)
	if err != nil {
		log.Fatalf("db (migrador): %v", err)
	}

	fmt.Println("Running migrations...")
	if err := db.Migrate(migrator); err != nil {
		migrator.Close()
		log.Fatalf("migrate: %v", err)
	}

	if *seed {
		fmt.Println("Seeding database...")
		if err := db.Seed(migrator); err != nil {
			migrator.Close()
			log.Fatalf("seed: %v", err)
		}
	}
	migrator.Close()

	if *migrateOnly {
		return
	}

	pool, err := db.Connect(ctx)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer pool.Close()

	fmt.Println(db.Describe(ctx, pool))

	listenPort := os.Getenv("PORT")
	if *port != "" {
		listenPort = *port
	}
	if listenPort == "" {
		listenPort = "3000"
	}

	handler := server.New(pool)
	addr := ":" + listenPort
	fmt.Printf("Toka running on http://localhost%s\n", addr)
	if err := http.ListenAndServe(addr, handler); err != nil {
		log.Fatalf("server: %v", err)
	}
}
