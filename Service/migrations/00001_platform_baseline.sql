-- +goose Up
-- +goose StatementBegin
CREATE SCHEMA IF NOT EXISTS mineg;

COMMENT ON SCHEMA mineg IS 'MineG application schema; business tables begin in stage 01';
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP SCHEMA IF EXISTS mineg;
-- +goose StatementEnd
