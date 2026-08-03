-- +goose Up
-- +goose StatementBegin
-- account-v3 no longer stores client key bundles or coordinates family envelopes.
DROP TABLE IF EXISTS mineg.family_key_envelopes;
DROP TABLE IF EXISTS mineg.key_grant_tasks;
DROP TABLE IF EXISTS mineg.user_key_bundles;
DROP TABLE IF EXISTS mineg.families;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DO $$ BEGIN
  RAISE EXCEPTION '00007_remove_legacy_key_bundle is intentionally irreversible';
END $$;
-- +goose StatementEnd
