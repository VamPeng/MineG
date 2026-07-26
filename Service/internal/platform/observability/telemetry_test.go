package observability

import (
	"context"
	"testing"
)

func TestConfigureAcceptsDefaultResourceSchema(t *testing.T) {
	shutdown, err := Configure(context.Background(), "mineg-test", "test")
	if err != nil {
		t.Fatal(err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
}
