.PHONY: format lint test test-integration generate migrate-up run openapi-check openapi-breaking

format:
	gofmt -w $$(find cmd internal api -name '*.go' -type f)

lint:
	go vet ./...

test:
	go test -race ./...

test-integration:
	MINEG_TEST_DATABASE_URL="$${MINEG_TEST_DATABASE_URL}" go test -tags=integration ./tests/integration/...

generate:
	go run github.com/sqlc-dev/sqlc/cmd/sqlc@v1.29.0 generate

migrate-up:
	go run ./cmd/migrate up

run:
	go run ./cmd/api

openapi-check:
	go test ./api

openapi-breaking:
	bash scripts/check-openapi-breaking.sh
