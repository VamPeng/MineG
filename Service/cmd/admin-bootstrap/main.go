package main

import (
	"context"
	"log/slog"
	"os"
	"strings"

	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/platform/config"
	"github.com/vampeng/mineg/service/internal/platform/database"
	"github.com/vampeng/mineg/service/internal/platform/observability"
)

func main() {
	logger := observability.NewJSONLogger(os.Stdout, slog.LevelInfo)
	cfg, err := config.Load(os.LookupEnv)
	if err != nil {
		logger.Error("configuration rejected", "error", err.Error())
		os.Exit(2)
	}
	username := strings.TrimSpace(os.Getenv("MINEG_BOOTSTRAP_ADMIN_USERNAME"))
	password := os.Getenv("MINEG_BOOTSTRAP_ADMIN_PASSWORD")
	if username == "" || password == "" {
		logger.Error("bootstrap credentials are required")
		os.Exit(2)
	}
	pool, err := database.Open(context.Background(), cfg.DatabaseURL)
	if err != nil {
		logger.Error("database setup failed", "error", err.Error())
		os.Exit(2)
	}
	defer pool.Close()
	adminID, err := account.BootstrapAdmin(context.Background(), pool, username, password)
	if err != nil {
		logger.Error("administrator bootstrap failed", "error", err.Error())
		os.Exit(1)
	}
	logger.Info("administrator bootstrap complete", "admin_id", adminID, "username", strings.ToLower(username))
}
