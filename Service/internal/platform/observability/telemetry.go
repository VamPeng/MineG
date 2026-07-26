package observability

import (
	"context"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	traceSdk "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.34.0"
)

func Configure(_ context.Context, serviceName, environment string) (func(context.Context) error, error) {
	res, err := resource.Merge(resource.Default(), resource.NewSchemaless(
		semconv.ServiceName(serviceName),
		semconv.DeploymentEnvironmentName(environment),
	))
	if err != nil {
		return nil, err
	}
	provider := traceSdk.NewTracerProvider(traceSdk.WithResource(res))
	otel.SetTracerProvider(provider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	return provider.Shutdown, nil
}
