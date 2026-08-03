//go:build integration

package integration_test

import (
	"context"
	"os"
	"testing"

	"github.com/vampeng/mineg/service/internal/platform/database"
)

func TestLegacyDataSchemaIsRetired(t *testing.T) {
	databaseURL := os.Getenv("MINEG_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MINEG_TEST_DATABASE_URL is not configured")
	}
	pool, err := database.Open(context.Background(), databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()

	for _, name := range []string{
		"mineg.user_key_bundles",
		"mineg.key_grant_tasks",
		"mineg.family_key_envelopes",
		"mineg.media_key_envelopes",
	} {
		var relation *string
		if err := pool.QueryRow(context.Background(), "SELECT to_regclass($1)", name).Scan(&relation); err != nil {
			t.Fatal(err)
		}
		if relation != nil {
			t.Fatalf("retired relation still exists: %s", name)
		}
	}
}
