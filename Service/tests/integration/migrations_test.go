//go:build integration

package integration_test

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
)

func TestMigrationsFromEmptyAndPreviousSnapshot(t *testing.T) {
	databaseURL := os.Getenv("MINEG_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MINEG_TEST_DATABASE_URL is not configured")
	}
	db, err := sql.Open("pgx", databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	if _, err := db.ExecContext(ctx, `DROP SCHEMA IF EXISTS mineg CASCADE; DROP TABLE IF EXISTS goose_db_version`); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = db.ExecContext(ctx, `DROP SCHEMA IF EXISTS mineg CASCADE; DROP TABLE IF EXISTS goose_db_version`)
	})

	_, source, _, _ := runtime.Caller(0)
	migrationDir := filepath.Join(filepath.Dir(source), "..", "..", "migrations")
	if err := goose.SetDialect("postgres"); err != nil {
		t.Fatal(err)
	}
	if err := goose.UpContext(ctx, db, migrationDir); err != nil {
		t.Fatalf("empty database migration: %v", err)
	}
	if err := goose.UpContext(ctx, db, migrationDir); err != nil {
		t.Fatalf("repeat migration: %v", err)
	}
	if err := goose.DownContext(ctx, db, migrationDir); err != nil {
		t.Fatalf("previous snapshot setup: %v", err)
	}
	if err := goose.UpContext(ctx, db, migrationDir); err != nil {
		t.Fatalf("previous snapshot upgrade: %v", err)
	}
}

func TestFailedMigrationTransactionLeavesNoPartialState(t *testing.T) {
	databaseURL := os.Getenv("MINEG_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MINEG_TEST_DATABASE_URL is not configured")
	}
	db, err := sql.Open("pgx", databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tx.ExecContext(ctx, `CREATE TABLE mineg_partial_probe(id bigint)`); err != nil {
		t.Fatal(err)
	}
	if _, err = tx.ExecContext(ctx, `SELECT invalid_syntax(`); err == nil {
		t.Fatal("expected transaction to fail")
	}
	if err := tx.Rollback(); err != nil {
		t.Fatal(err)
	}
	var exists bool
	if err := db.QueryRowContext(ctx, `SELECT to_regclass('mineg_partial_probe') IS NOT NULL`).Scan(&exists); err != nil {
		t.Fatal(err)
	}
	if exists {
		t.Fatal("failed migration left a partial table")
	}
}
