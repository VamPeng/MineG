package observability

import (
	"io"
	"log/slog"
)

func NewJSONLogger(output io.Writer, level slog.Leveler) *slog.Logger {
	return slog.New(slog.NewJSONHandler(output, &slog.HandlerOptions{
		Level: level,
		ReplaceAttr: func(_ []string, attribute slog.Attr) slog.Attr {
			if attribute.Key == slog.TimeKey {
				return slog.Time(slog.TimeKey, attribute.Value.Time().UTC())
			}
			return attribute
		},
	}))
}
