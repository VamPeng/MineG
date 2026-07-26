package httpapi

import (
	"encoding/json"
	"net/http"
)

const problemContentType = "application/problem+json"

type Problem struct {
	Type      string         `json:"type"`
	Title     string         `json:"title"`
	Status    int            `json:"status"`
	Detail    string         `json:"detail,omitempty"`
	Instance  string         `json:"instance,omitempty"`
	Code      string         `json:"code"`
	RequestID string         `json:"request_id"`
	Retryable bool           `json:"retryable"`
	Details   map[string]any `json:"details,omitempty"`
}

func writeProblem(w http.ResponseWriter, r *http.Request, status int, code, title, detail string, retryable bool) {
	w.Header().Set("Content-Type", problemContentType)
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(Problem{
		Type:      "https://errors.mineg.example/problems/" + code,
		Title:     title,
		Status:    status,
		Detail:    detail,
		Instance:  r.URL.Path,
		Code:      code,
		RequestID: RequestIDFromContext(r.Context()),
		Retryable: retryable,
	})
}
