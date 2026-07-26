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

func TestDeploymentRequiresHTTPSAdminOriginAndExplicitCursorKey(t *testing.T) {
	base := map[string]string{
		"MINEG_ENV":                 "deployment",
		"MINEG_DATABASE_URL":        "postgres://mineg:secret@localhost/mineg?sslmode=require",
		"MINEG_ADMIN_ORIGIN":        "http://admin.example.test",
		"MINEG_OSS_REGION":          "cn-hangzhou",
		"MINEG_OSS_BUCKET":          "mineg-private",
		"MINEG_OSS_INTERNAL_ORIGIN": "https://oss-cn-hangzhou-internal.aliyuncs.com",
		"MINEG_OSS_ECS_RAM_ROLE":    "mineg-api",
	}
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "https") || !strings.Contains(err.Error(), "MINEG_CURSOR_HMAC_KEY") {
		t.Fatalf("expected deployment security validation, got %v", err)
	}
	base["MINEG_ADMIN_ORIGIN"] = "https://admin.example.test"
	base["MINEG_CURSOR_HMAC_KEY"] = "deployment-cursor-secret-at-least-32-characters"
	if _, err := Load(lookup(base)); err != nil {
		t.Fatalf("valid deployment configuration rejected: %v", err)
	}
}

func TestLoadRejectsPartialOrInsecureOSSConfiguration(t *testing.T) {
	base := map[string]string{
		"MINEG_DATABASE_URL": "postgres://mineg:secret@localhost/mineg?sslmode=disable",
		"MINEG_OSS_REGION":   "cn-hangzhou",
	}
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "configured together") {
		t.Fatalf("expected partial OSS configuration rejection, got %v", err)
	}
	base["MINEG_OSS_BUCKET"] = "mineg-private"
	base["MINEG_OSS_INTERNAL_ORIGIN"] = "http://oss-cn-hangzhou-internal.aliyuncs.com"
	base["MINEG_OSS_ECS_RAM_ROLE"] = "mineg-api"
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "HTTPS") {
		t.Fatalf("expected insecure OSS endpoint rejection, got %v", err)
	}
}
