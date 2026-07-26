package observability

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
)

func TestJSONLoggerAlwaysUsesUTC(t *testing.T) {
	var output bytes.Buffer
	NewJSONLogger(&output, slog.LevelInfo).Info("probe")
	line := output.String()
	if !strings.Contains(line, `"time":"`) || !strings.Contains(line, `Z"`) {
		t.Fatalf("log timestamp is not UTC: %s", line)
	}
}
