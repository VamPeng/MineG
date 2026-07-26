package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type readinessStub struct{ err error }

func (s readinessStub) Ready(context.Context) error { return s.err }

func testRouter(ready Readiness) http.Handler {
	return New(Dependencies{
		Logger:    slog.New(slog.NewTextHandler(io.Discard, nil)),
		Readiness: ready,
		Now:       func() time.Time { return time.Date(2026, 7, 26, 0, 0, 0, 0, time.UTC) },
	})
}

func TestProbeContract(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/platform/probe", nil)
	request.Header.Set("X-Request-ID", "contract-request-001")
	recorder := httptest.NewRecorder()
	testRouter(readinessStub{}).ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if got := recorder.Header().Get("X-Request-ID"); got != "contract-request-001" {
		t.Fatalf("request id = %q", got)
	}
	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("content type = %q", got)
	}
}

func TestUnknownRouteUsesRFC9457Problem(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/does-not-exist", nil)
	recorder := httptest.NewRecorder()
	testRouter(readinessStub{}).ServeHTTP(recorder, request)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d", recorder.Code)
	}
	if got := recorder.Header().Get("Content-Type"); got != problemContentType {
		t.Fatalf("content type = %q", got)
	}
	var problem Problem
	if err := json.NewDecoder(recorder.Body).Decode(&problem); err != nil {
		t.Fatal(err)
	}
	if problem.Status != 404 || problem.Code != "ROUTE_NOT_FOUND" || problem.RequestID == "" {
		t.Fatalf("unexpected problem: %#v", problem)
	}
	if problem.Detail == "" || problem.Type == "" {
		t.Fatalf("missing RFC 9457 fields: %#v", problem)
	}
}

func TestHealthSeparatesLivenessAndReadiness(t *testing.T) {
	handler := testRouter(readinessStub{err: errors.New("database unavailable")})
	for path, want := range map[string]int{
		"/health/live":  http.StatusOK,
		"/health/ready": http.StatusServiceUnavailable,
	} {
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))
		if recorder.Code != want {
			t.Errorf("%s status = %d, want %d", path, recorder.Code, want)
		}
	}
}

func TestInvalidRequestIDIsReplaced(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/platform/probe", nil)
	request.Header.Set("X-Request-ID", "contains secret whitespace")
	recorder := httptest.NewRecorder()
	testRouter(readinessStub{}).ServeHTTP(recorder, request)
	if got := recorder.Header().Get("X-Request-ID"); got == "" || got == "contains secret whitespace" {
		t.Fatalf("invalid request id was not replaced: %q", got)
	}
}

func TestPanicRecoveryDoesNotExposePanicValue(t *testing.T) {
	var logs bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&logs, nil))
	handler := requestID(recoverer(logger)(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		panic("token=must-not-leak")
	})))
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/panic", nil))
	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d", recorder.Code)
	}
	if bytes.Contains(recorder.Body.Bytes(), []byte("must-not-leak")) || bytes.Contains(logs.Bytes(), []byte("must-not-leak")) {
		t.Fatal("panic value leaked to response or log")
	}
}
