package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/upload"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
)

type Readiness interface {
	Ready(context.Context) error
}

type Dependencies struct {
	Logger         *slog.Logger
	Readiness      Readiness
	Account        *account.Service
	Upload         *upload.Service
	AdminOrigin    string
	RequestTimeout time.Duration
	Now            func() time.Time
}

func New(deps Dependencies) http.Handler {
	if deps.Logger == nil {
		deps.Logger = slog.Default()
	}
	if deps.RequestTimeout <= 0 {
		deps.RequestTimeout = 15 * time.Second
	}
	if deps.Now == nil {
		deps.Now = time.Now
	}
	router := chi.NewRouter()
	router.Use(requestID)
	router.Use(middleware.RealIP)
	router.Use(recoverer(deps.Logger))
	router.Use(requestTimeout(deps.RequestTimeout))
	router.Use(accessLog(deps.Logger))

	router.Get("/health/live", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "alive"})
	})
	router.Get("/health/ready", func(w http.ResponseWriter, r *http.Request) {
		if deps.Readiness == nil || deps.Readiness.Ready(r.Context()) != nil {
			writeProblem(w, r, http.StatusServiceUnavailable, "DATABASE_NOT_READY", "Service not ready", "The database is not ready.", true)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})

	router.Route("/api/v1", func(api chi.Router) {
		api.Get("/platform/probe", func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, http.StatusOK, map[string]string{
				"status":      "ok",
				"api_version": "v1",
				"server_time": deps.Now().UTC().Truncate(time.Millisecond).Format("2006-01-02T15:04:05.000Z07:00"),
			})
		})
		if deps.Account != nil {
			mountAccountRoutes(api, deps.Account, deps.AdminOrigin)
			if deps.Upload != nil {
				mountUploadRoutes(api, deps.Account, deps.Upload)
			}
		}
	})

	router.NotFound(func(w http.ResponseWriter, r *http.Request) {
		writeProblem(w, r, http.StatusNotFound, "ROUTE_NOT_FOUND", "Route not found", "The requested route does not exist.", false)
	})
	router.MethodNotAllowed(func(w http.ResponseWriter, r *http.Request) {
		writeProblem(w, r, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "Method not allowed", "The HTTP method is not supported for this route.", false)
	})
	return otelhttp.NewHandler(router, "mineg.http")
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
