package config

import (
	"strings"
	"testing"
	"time"
)

func lookup(values map[string]string) LookupFunc {
	return func(key string) (string, bool) {
		value, ok := values[key]
		return value, ok
	}
}

func TestLoadRequiresDatabaseURL(t *testing.T) {
	_, err := Load(lookup(nil))
	if err == nil || !strings.Contains(err.Error(), "MINEG_DATABASE_URL") {
		t.Fatalf("expected database validation error, got %v", err)
	}
}

func TestLoadAcceptsExplicitEnvironment(t *testing.T) {
	cfg, err := Load(lookup(map[string]string{
		"MINEG_ENV":             "test",
		"MINEG_DATABASE_URL":    "postgres://mineg:secret@localhost/mineg?sslmode=disable",
		"MINEG_REQUEST_TIMEOUT": "3s",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Environment != "test" || cfg.RequestTimeout != 3*time.Second {
		t.Fatalf("unexpected config: %#v", cfg)
	}
}
