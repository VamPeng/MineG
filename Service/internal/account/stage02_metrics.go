package account

import (
	"context"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

var (
	stage02Meter       = otel.Meter("github.com/vampeng/mineg/service/internal/account/stage02")
	keyGrantBacklog, _ = stage02Meter.Int64Histogram(
		"mineg.key_grant.eligible_backlog",
		metric.WithDescription("Eligible pending family key grants visible to the current member."),
	)
	keyGrantAttempts, _ = stage02Meter.Int64Counter(
		"mineg.key_grant.completion_attempts",
		metric.WithDescription("Family key grant completion attempts by stable outcome."),
	)
	keyGrantReviewToReady, _ = stage02Meter.Float64Histogram(
		"mineg.key_grant.review_to_ready_seconds",
		metric.WithUnit("s"),
		metric.WithDescription("Elapsed time from administrative review to an envelope becoming ready."),
	)
	keyGrantOperationDuration, _ = stage02Meter.Float64Histogram(
		"mineg.key_grant.completion_duration_seconds",
		metric.WithUnit("s"),
		metric.WithDescription("Server-side key grant completion request duration."),
	)
)

func recordKeyGrantBacklog(ctx context.Context, count int64) {
	keyGrantBacklog.Record(ctx, count)
}

func recordKeyGrantCompletion(ctx context.Context, started time.Time, reviewedAt time.Time, outcome string) {
	attributes := metric.WithAttributes(attribute.String("mineg.key_grant.outcome", outcome))
	keyGrantAttempts.Add(ctx, 1, attributes)
	keyGrantOperationDuration.Record(ctx, time.Since(started).Seconds(), attributes)
	if !reviewedAt.IsZero() && (outcome == "COMPLETED" || outcome == "ALREADY_COMPLETED") {
		keyGrantReviewToReady.Record(ctx, time.Since(reviewedAt).Seconds(), attributes)
	}
}
