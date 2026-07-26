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
	RequestTimeout    time.Duration
	ShutdownTimeout   time.Duration
	ReadHeaderTimeout time.Duration
}

func Load(lookup LookupFunc) (Config, error) {
	cfg := Config{
		Environment:       valueOr(lookup, "MINEG_ENV", "local"),
		ServiceName:       "mineg-api",
		HTTPAddress:       valueOr(lookup, "MINEG_HTTP_ADDRESS", ":8080"),
		RequestTimeout:    durationOr(lookup, "MINEG_REQUEST_TIMEOUT", 15*time.Second),
		ShutdownTimeout:   durationOr(lookup, "MINEG_SHUTDOWN_TIMEOUT", 20*time.Second),
		ReadHeaderTimeout: durationOr(lookup, "MINEG_READ_HEADER_TIMEOUT", 5*time.Second),
	}
	cfg.DatabaseURL, _ = lookup("MINEG_DATABASE_URL")

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
