package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

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

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

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

	// Poda al arrancar y luego cada día: las mutaciones viejas ya no sirven para dedupe.
	go func() {
		for {
			if n, err := db.PruneMutations(ctx, database, 90*24*time.Hour); err != nil {
				log.Printf("prune mutations: %v", err)
			} else if n > 0 {
				log.Printf("prune mutations: %d registros", n)
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(24 * time.Hour):
			}
		}
	}()

	srv := &http.Server{
		Addr:              ":" + listenPort,
		Handler:           server.New(database),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	// SIGTERM (podman stop, systemctl restart) deja terminar las peticiones en vuelo en
	// vez de cortar una transacción a medias.
	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			log.Printf("shutdown: %v", err)
		}
	}()

	fmt.Printf("Toka running on http://localhost%s\n", srv.Addr)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("server: %v", err)
	}
	// ListenAndServe vuelve apenas empieza Shutdown; esperar a que termine de verdad.
	<-shutdownDone
}
