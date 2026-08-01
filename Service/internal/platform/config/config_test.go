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
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "MINEG_OSS_REGION and MINEG_OSS_BUCKET") {
		t.Fatalf("expected partial OSS configuration rejection, got %v", err)
	}
	base["MINEG_OSS_BUCKET"] = "mineg-private"
	base["MINEG_OSS_PUBLIC_ORIGIN"] = "http://oss-cn-hangzhou.aliyuncs.com"
	base["MINEG_OSS_ACCESS_KEY_ID"] = "temporary-id"
	base["MINEG_OSS_ACCESS_KEY_SECRET"] = "temporary-secret"
	base["MINEG_OSS_SECURITY_TOKEN"] = "temporary-token"
	base["MINEG_OSS_STS_EXPIRATION"] = time.Now().UTC().Add(time.Hour).Format(time.RFC3339)
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "HTTPS") {
		t.Fatalf("expected insecure OSS endpoint rejection, got %v", err)
	}
}

func TestLoadAcceptsCompleteLocalTemporarySTSConfiguration(t *testing.T) {
	expiration := time.Now().UTC().Add(time.Hour).Truncate(time.Second)
	cfg, err := Load(lookup(map[string]string{
		"MINEG_ENV":                   "local",
		"MINEG_DATABASE_URL":          "postgres://mineg:secret@localhost/mineg?sslmode=disable",
		"MINEG_OSS_REGION":            "cn-hangzhou",
		"MINEG_OSS_BUCKET":            "mineg-private",
		"MINEG_OSS_PUBLIC_ORIGIN":     "https://oss-cn-hangzhou.aliyuncs.com",
		"MINEG_OSS_ACCESS_KEY_ID":     "temporary-id",
		"MINEG_OSS_ACCESS_KEY_SECRET": "temporary-secret",
		"MINEG_OSS_SECURITY_TOKEN":    "temporary-token",
		"MINEG_OSS_STS_EXPIRATION":    expiration.Format(time.RFC3339),
	}))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.OSSPublicOrigin != "https://oss-cn-hangzhou.aliyuncs.com" || !cfg.OSSSTSExpiration.Equal(expiration) {
		t.Fatalf("unexpected local OSS config: endpoint=%q expiration=%s", cfg.OSSPublicOrigin, cfg.OSSSTSExpiration)
	}
}

func TestLoadRejectsIncompleteExpiredOrMixedLocalSTSConfiguration(t *testing.T) {
	base := map[string]string{
		"MINEG_ENV":                   "local",
		"MINEG_DATABASE_URL":          "postgres://mineg:secret@localhost/mineg?sslmode=disable",
		"MINEG_OSS_REGION":            "cn-hangzhou",
		"MINEG_OSS_BUCKET":            "mineg-private",
		"MINEG_OSS_PUBLIC_ORIGIN":     "https://oss-cn-hangzhou.aliyuncs.com",
		"MINEG_OSS_ACCESS_KEY_ID":     "temporary-id",
		"MINEG_OSS_ACCESS_KEY_SECRET": "temporary-secret",
		"MINEG_OSS_STS_EXPIRATION":    time.Now().UTC().Add(time.Hour).Format(time.RFC3339),
	}
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "complete temporary STS") {
		t.Fatalf("expected incomplete STS rejection, got %v", err)
	}
	base["MINEG_OSS_SECURITY_TOKEN"] = "temporary-token"
	base["MINEG_OSS_STS_EXPIRATION"] = time.Now().UTC().Add(-time.Minute).Format(time.RFC3339)
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "in the future") {
		t.Fatalf("expected expired STS rejection, got %v", err)
	}
	base["MINEG_OSS_STS_EXPIRATION"] = time.Now().UTC().Add(time.Hour).Format(time.RFC3339)
	base["MINEG_OSS_ECS_RAM_ROLE"] = "mineg-api"
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "only allowed in deployment") {
		t.Fatalf("expected mixed credential mode rejection, got %v", err)
	}
}

func TestDeploymentRejectsLocalTemporaryCredentials(t *testing.T) {
	base := map[string]string{
		"MINEG_ENV":                   "deployment",
		"MINEG_DATABASE_URL":          "postgres://mineg:secret@localhost/mineg?sslmode=require",
		"MINEG_ADMIN_ORIGIN":          "https://admin.example.test",
		"MINEG_CURSOR_HMAC_KEY":       "deployment-cursor-secret-at-least-32-characters",
		"MINEG_OSS_REGION":            "cn-hangzhou",
		"MINEG_OSS_BUCKET":            "mineg-private",
		"MINEG_OSS_INTERNAL_ORIGIN":   "https://oss-cn-hangzhou-internal.aliyuncs.com",
		"MINEG_OSS_ECS_RAM_ROLE":      "mineg-api",
		"MINEG_OSS_PUBLIC_ORIGIN":     "https://oss-cn-hangzhou.aliyuncs.com",
		"MINEG_OSS_ACCESS_KEY_ID":     "temporary-id",
		"MINEG_OSS_ACCESS_KEY_SECRET": "temporary-secret",
		"MINEG_OSS_SECURITY_TOKEN":    "temporary-token",
		"MINEG_OSS_STS_EXPIRATION":    time.Now().UTC().Add(time.Hour).Format(time.RFC3339),
	}
	if _, err := Load(lookup(base)); err == nil || !strings.Contains(err.Error(), "forbidden in deployment") {
		t.Fatalf("expected deployment local credential rejection, got %v", err)
	}
}
