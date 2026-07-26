package main

import (
	"context"
	"database/sql"
	"log/slog"
	"os"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/vampeng/mineg/service/internal/platform/config"
	"github.com/vampeng/mineg/service/internal/platform/observability"
)

func main() {
	logger := observability.NewJSONLogger(os.Stdout, slog.LevelInfo)
	cfg, err := config.Load(os.LookupEnv)
	if err != nil {
		logger.Error("configuration rejected", "error", err.Error())
		os.Exit(2)
	}
	db, err := sql.Open("pgx", cfg.DatabaseURL)
	if err != nil {
		logger.Error("database setup failed", "error", err.Error())
		os.Exit(2)
	}
	defer db.Close()

	direction := "up"
	if len(os.Args) > 1 {
		direction = os.Args[1]
	}
	migrationsDir := "migrations"
	if value := os.Getenv("MINEG_MIGRATIONS_DIR"); value != "" {
		migrationsDir = value
	}
	if err := goose.SetDialect("postgres"); err != nil {
		logger.Error("migration dialect rejected", "error", err.Error())
		os.Exit(2)
	}
	ctx := context.Background()
	switch direction {
	case "up":
		err = goose.UpContext(ctx, db, migrationsDir)
	case "status":
		err = goose.StatusContext(ctx, db, migrationsDir)
	default:
		logger.Error("unsupported migration direction", "direction", direction)
		os.Exit(2)
	}
	if err != nil {
		logger.Error("migration failed", "direction", direction, "error", err.Error())
		os.Exit(1)
	}
	logger.Info("migration complete", "direction", direction)
}
