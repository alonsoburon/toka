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

	database, err := db.Connect(ctx)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	defer database.Close()

	fmt.Println("Running migrations...")
	if err := db.Migrate(database); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	if *seed {
		fmt.Println("Seeding database...")
		if err := db.Seed(database); err != nil {
			log.Fatalf("seed: %v", err)
		}
	}

	if *migrateOnly {
		return
	}

	fmt.Println(db.Describe())

	listenPort := os.Getenv("PORT")
	if *port != "" {
		listenPort = *port
	}
	if listenPort == "" {
		listenPort = "3000"
	}

	handler := server.New(database)
	addr := ":" + listenPort
	fmt.Printf("Toka running on http://localhost%s\n", addr)
	if err := http.ListenAndServe(addr, handler); err != nil {
		log.Fatalf("server: %v", err)
	}
}
