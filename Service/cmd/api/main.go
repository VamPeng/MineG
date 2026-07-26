package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/platform/config"
	"github.com/vampeng/mineg/service/internal/platform/database"
	"github.com/vampeng/mineg/service/internal/platform/httpapi"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
	"github.com/vampeng/mineg/service/internal/platform/observability"
	"github.com/vampeng/mineg/service/internal/upload"
)

func main() {
	logger := observability.NewJSONLogger(os.Stdout, slog.LevelInfo)
	cfg, err := config.Load(os.LookupEnv)
	if err != nil {
		logger.Error("configuration rejected", "error", err.Error())
		os.Exit(2)
	}

	ctx := context.Background()
	tracerShutdown, err := observability.Configure(ctx, cfg.ServiceName, cfg.Environment)
	if err != nil {
		logger.Error("telemetry setup failed", "error", err.Error())
		os.Exit(2)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := tracerShutdown(shutdownCtx); err != nil {
			logger.Error("telemetry shutdown failed", "error", err.Error())
		}
	}()

	pool, err := database.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Error("database pool setup failed", "error", err.Error())
		os.Exit(2)
	}
	defer pool.Close()
	var profileObjects objectstore.ProfileObjects = objectstore.DisabledProfileObjects{}
	var mediaObjects objectstore.MediaObjects = objectstore.DisabledMediaObjects{}
	if cfg.OSSRegion != "" {
		ossObjects, objectErr := objectstore.NewOSSProfileObjects(objectstore.OSSProfileConfig{
			Region: cfg.OSSRegion, Bucket: cfg.OSSBucket, InternalEndpoint: cfg.OSSInternalOrigin,
			ECSRAMRole: cfg.OSSECSRAMRole,
		})
		if objectErr != nil {
			logger.Error("OSS object setup failed", "error", objectErr.Error())
			os.Exit(2)
		}
		profileObjects = ossObjects
		mediaObjects = ossObjects
	}
	accountService := account.New(pool, account.Config{CursorKey: []byte(cfg.CursorHMACKey), ProfileObjects: profileObjects})

	handler := httpapi.New(httpapi.Dependencies{
		Logger:         logger,
		Readiness:      pool,
		Account:        accountService,
		Upload:         upload.New(pool, upload.Config{Objects: mediaObjects}),
		AdminOrigin:    cfg.AdminOrigin,
		RequestTimeout: cfg.RequestTimeout,
	})
	server := &http.Server{
		Addr:              cfg.HTTPAddress,
		Handler:           handler,
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		IdleTimeout:       60 * time.Second,
	}

	serverErrors := make(chan error, 1)
	go func() {
		logger.Info("api listening", "address", cfg.HTTPAddress, "environment", cfg.Environment)
		serverErrors <- server.ListenAndServe()
	}()

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-signals:
		logger.Info("shutdown requested", "signal", sig.String())
	case err := <-serverErrors:
		if !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server stopped unexpectedly", "error", err.Error())
			os.Exit(1)
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown timed out", "error", err.Error())
		_ = server.Close()
		os.Exit(1)
	}
	logger.Info("api stopped")
}
