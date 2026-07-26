package config

import (
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type LookupFunc func(string) (string, bool)

type Config struct {
	Environment       string
	ServiceName       string
	HTTPAddress       string
	DatabaseURL       string
	AdminOrigin       string
	CursorHMACKey     string
	OSSRegion         string
	OSSBucket         string
	OSSInternalOrigin string
	OSSECSRAMRole     string
	RequestTimeout    time.Duration
	ShutdownTimeout   time.Duration
	ReadHeaderTimeout time.Duration
}

func Load(lookup LookupFunc) (Config, error) {
	cfg := Config{
		Environment:       valueOr(lookup, "MINEG_ENV", "local"),
		ServiceName:       "mineg-api",
		HTTPAddress:       valueOr(lookup, "MINEG_HTTP_ADDRESS", ":8080"),
		AdminOrigin:       valueOr(lookup, "MINEG_ADMIN_ORIGIN", "http://localhost:5173"),
		CursorHMACKey:     valueOr(lookup, "MINEG_CURSOR_HMAC_KEY", "mineg-local-cursor-key-change-before-deployment"),
		RequestTimeout:    durationOr(lookup, "MINEG_REQUEST_TIMEOUT", 15*time.Second),
		ShutdownTimeout:   durationOr(lookup, "MINEG_SHUTDOWN_TIMEOUT", 20*time.Second),
		ReadHeaderTimeout: durationOr(lookup, "MINEG_READ_HEADER_TIMEOUT", 5*time.Second),
	}
	cfg.DatabaseURL, _ = lookup("MINEG_DATABASE_URL")
	cfg.OSSRegion, _ = lookup("MINEG_OSS_REGION")
	cfg.OSSBucket, _ = lookup("MINEG_OSS_BUCKET")
	cfg.OSSInternalOrigin, _ = lookup("MINEG_OSS_INTERNAL_ORIGIN")
	cfg.OSSECSRAMRole, _ = lookup("MINEG_OSS_ECS_RAM_ROLE")
	cfg.OSSRegion = strings.TrimSpace(cfg.OSSRegion)
	cfg.OSSBucket = strings.TrimSpace(cfg.OSSBucket)
	cfg.OSSInternalOrigin = strings.TrimSpace(cfg.OSSInternalOrigin)
	cfg.OSSECSRAMRole = strings.TrimSpace(cfg.OSSECSRAMRole)

	var problems []string
	switch cfg.Environment {
	case "local", "test", "deployment":
	default:
		problems = append(problems, "MINEG_ENV must be local, test, or deployment")
	}
	if strings.TrimSpace(cfg.DatabaseURL) == "" {
		problems = append(problems, "MINEG_DATABASE_URL is required")
	} else if parsed, err := url.Parse(cfg.DatabaseURL); err != nil || (parsed.Scheme != "postgres" && parsed.Scheme != "postgresql") {
		problems = append(problems, "MINEG_DATABASE_URL must be a postgres URL")
	}
	if cfg.RequestTimeout <= 0 || cfg.ShutdownTimeout <= 0 || cfg.ReadHeaderTimeout <= 0 {
		problems = append(problems, "timeout values must be positive durations")
	}
	adminOrigin, originErr := url.Parse(cfg.AdminOrigin)
	if originErr != nil || adminOrigin.Host == "" || (adminOrigin.Scheme != "http" && adminOrigin.Scheme != "https") || adminOrigin.Path != "" || adminOrigin.RawQuery != "" || adminOrigin.Fragment != "" {
		problems = append(problems, "MINEG_ADMIN_ORIGIN must be an http(s) origin without a path")
	} else if cfg.Environment == "deployment" && adminOrigin.Scheme != "https" {
		problems = append(problems, "MINEG_ADMIN_ORIGIN must use https in deployment")
	}
	if len(cfg.CursorHMACKey) < 32 {
		problems = append(problems, "MINEG_CURSOR_HMAC_KEY must contain at least 32 characters")
	}
	ossConfigured := cfg.OSSRegion != "" || cfg.OSSBucket != "" || cfg.OSSInternalOrigin != "" || cfg.OSSECSRAMRole != ""
	if ossConfigured {
		if cfg.OSSRegion == "" || cfg.OSSBucket == "" || cfg.OSSInternalOrigin == "" || cfg.OSSECSRAMRole == "" {
			problems = append(problems, "MINEG_OSS_REGION, MINEG_OSS_BUCKET, MINEG_OSS_INTERNAL_ORIGIN, and MINEG_OSS_ECS_RAM_ROLE must be configured together")
		}
		if strings.ContainsAny(cfg.OSSBucket, "/?#") {
			problems = append(problems, "MINEG_OSS_BUCKET must be a bucket name, not a path or URL")
		}
		endpoint, endpointErr := url.Parse(cfg.OSSInternalOrigin)
		if endpointErr != nil || endpoint.Scheme != "https" || endpoint.Host == "" || endpoint.User != nil ||
			(endpoint.Path != "" && endpoint.Path != "/") || endpoint.RawQuery != "" || endpoint.Fragment != "" {
			problems = append(problems, "MINEG_OSS_INTERNAL_ORIGIN must be a credential-free HTTPS origin")
		}
	}
	if cfg.Environment == "deployment" {
		if value, ok := lookup("MINEG_CURSOR_HMAC_KEY"); !ok || strings.TrimSpace(value) == "" {
			problems = append(problems, "MINEG_CURSOR_HMAC_KEY is required in deployment")
		}
		if !ossConfigured {
			problems = append(problems, "OSS profile object configuration is required in deployment")
		}
	}
	if len(problems) > 0 {
		return Config{}, errors.New(strings.Join(problems, "; "))
	}
	return cfg, nil
}

func valueOr(lookup LookupFunc, key, fallback string) string {
	if value, ok := lookup(key); ok && strings.TrimSpace(value) != "" {
		return value
	}
	return fallback
}

func durationOr(lookup LookupFunc, key string, fallback time.Duration) time.Duration {
	value, ok := lookup(key)
	if !ok || value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err == nil {
		return parsed
	}
	if seconds, intErr := strconv.Atoi(value); intErr == nil {
		return time.Duration(seconds) * time.Second
	}
	return -1
}

func (c Config) String() string {
	return fmt.Sprintf("environment=%s service=%s address=%s", c.Environment, c.ServiceName, c.HTTPAddress)
}
