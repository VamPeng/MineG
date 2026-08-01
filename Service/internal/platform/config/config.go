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
	Environment        string
	ServiceName        string
	HTTPAddress        string
	DatabaseURL        string
	AdminOrigin        string
	CursorHMACKey      string
	OSSRegion          string
	OSSBucket          string
	OSSPublicOrigin    string
	OSSInternalOrigin  string
	OSSECSRAMRole      string
	OSSAccessKeyID     string
	OSSAccessKeySecret string
	OSSSecurityToken   string
	OSSSTSExpiration   time.Time
	RequestTimeout     time.Duration
	ShutdownTimeout    time.Duration
	ReadHeaderTimeout  time.Duration
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
	cfg.OSSPublicOrigin, _ = lookup("MINEG_OSS_PUBLIC_ORIGIN")
	cfg.OSSInternalOrigin, _ = lookup("MINEG_OSS_INTERNAL_ORIGIN")
	cfg.OSSECSRAMRole, _ = lookup("MINEG_OSS_ECS_RAM_ROLE")
	cfg.OSSAccessKeyID, _ = lookup("MINEG_OSS_ACCESS_KEY_ID")
	cfg.OSSAccessKeySecret, _ = lookup("MINEG_OSS_ACCESS_KEY_SECRET")
	cfg.OSSSecurityToken, _ = lookup("MINEG_OSS_SECURITY_TOKEN")
	ossSTSExpiration, _ := lookup("MINEG_OSS_STS_EXPIRATION")
	cfg.OSSRegion = strings.TrimSpace(cfg.OSSRegion)
	cfg.OSSBucket = strings.TrimSpace(cfg.OSSBucket)
	cfg.OSSPublicOrigin = strings.TrimSpace(cfg.OSSPublicOrigin)
	cfg.OSSInternalOrigin = strings.TrimSpace(cfg.OSSInternalOrigin)
	cfg.OSSECSRAMRole = strings.TrimSpace(cfg.OSSECSRAMRole)
	cfg.OSSAccessKeyID = strings.TrimSpace(cfg.OSSAccessKeyID)
	cfg.OSSAccessKeySecret = strings.TrimSpace(cfg.OSSAccessKeySecret)
	cfg.OSSSecurityToken = strings.TrimSpace(cfg.OSSSecurityToken)
	ossSTSExpiration = strings.TrimSpace(ossSTSExpiration)

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
	localOSSConfigured := cfg.OSSPublicOrigin != "" || cfg.OSSAccessKeyID != "" || cfg.OSSAccessKeySecret != "" || cfg.OSSSecurityToken != "" || ossSTSExpiration != ""
	deploymentOSSConfigured := cfg.OSSInternalOrigin != "" || cfg.OSSECSRAMRole != ""
	ossConfigured := cfg.OSSRegion != "" || cfg.OSSBucket != "" || localOSSConfigured || deploymentOSSConfigured
	if ossConfigured {
		if cfg.OSSRegion == "" || cfg.OSSBucket == "" {
			problems = append(problems, "MINEG_OSS_REGION and MINEG_OSS_BUCKET are required when OSS is configured")
		}
		if strings.ContainsAny(cfg.OSSBucket, "/?#") {
			problems = append(problems, "MINEG_OSS_BUCKET must be a bucket name, not a path or URL")
		}
	}
	if cfg.OSSPublicOrigin != "" && !validHTTPSOrigin(cfg.OSSPublicOrigin) {
		problems = append(problems, "MINEG_OSS_PUBLIC_ORIGIN must be a credential-free HTTPS origin")
	}
	if cfg.OSSInternalOrigin != "" && !validHTTPSOrigin(cfg.OSSInternalOrigin) {
		problems = append(problems, "MINEG_OSS_INTERNAL_ORIGIN must be a credential-free HTTPS origin")
	}
	switch cfg.Environment {
	case "local", "test":
		if deploymentOSSConfigured {
			problems = append(problems, "MINEG_OSS_INTERNAL_ORIGIN and MINEG_OSS_ECS_RAM_ROLE are only allowed in deployment")
		}
		if ossConfigured {
			if cfg.OSSPublicOrigin == "" || cfg.OSSAccessKeyID == "" || cfg.OSSAccessKeySecret == "" || cfg.OSSSecurityToken == "" || ossSTSExpiration == "" {
				problems = append(problems, "local/test OSS requires MINEG_OSS_PUBLIC_ORIGIN and complete temporary STS credentials including MINEG_OSS_STS_EXPIRATION")
			}
			if ossSTSExpiration != "" {
				expiration, expirationErr := time.Parse(time.RFC3339, ossSTSExpiration)
				if expirationErr != nil {
					problems = append(problems, "MINEG_OSS_STS_EXPIRATION must be an RFC3339 timestamp")
				} else if !expiration.After(time.Now().UTC()) {
					problems = append(problems, "MINEG_OSS_STS_EXPIRATION must be in the future")
				} else {
					cfg.OSSSTSExpiration = expiration
				}
			}
		}
	case "deployment":
		if localOSSConfigured {
			problems = append(problems, "local STS credentials and MINEG_OSS_PUBLIC_ORIGIN are forbidden in deployment")
		}
		if cfg.OSSRegion == "" || cfg.OSSBucket == "" || cfg.OSSInternalOrigin == "" || cfg.OSSECSRAMRole == "" {
			problems = append(problems, "deployment OSS requires MINEG_OSS_REGION, MINEG_OSS_BUCKET, MINEG_OSS_INTERNAL_ORIGIN, and MINEG_OSS_ECS_RAM_ROLE")
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

func validHTTPSOrigin(value string) bool {
	endpoint, err := url.Parse(value)
	return err == nil && endpoint.Scheme == "https" && endpoint.Host != "" && endpoint.User == nil &&
		(endpoint.Path == "" || endpoint.Path == "/") && endpoint.RawQuery == "" && endpoint.Fragment == ""
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
