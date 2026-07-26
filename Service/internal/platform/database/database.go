package database

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Pool struct {
	*pgxpool.Pool
}

func Open(ctx context.Context, databaseURL string) (*Pool, error) {
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		// Never propagate a parser error that may echo a connection password.
		return nil, fmt.Errorf("parse database config: invalid PostgreSQL configuration")
	}
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, fmt.Errorf("create database pool: configuration rejected")
	}
	return &Pool{Pool: pool}, nil
}

func (p *Pool) Ready(ctx context.Context) error {
	return p.Ping(ctx)
}

func (p *Pool) WithinTransaction(ctx context.Context, fn func(pgx.Tx) error) error {
	tx, err := p.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(tx); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit transaction: %w", err)
	}
	return nil
}
