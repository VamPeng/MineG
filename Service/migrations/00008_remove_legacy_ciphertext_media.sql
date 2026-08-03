-- +goose Up
-- +goose StatementBegin
-- stage03-v2 stores original media only. Ciphertext sessions and media are intentionally discarded.
DELETE FROM mineg.upload_sessions WHERE purpose = 'MEDIA_CIPHERTEXT';
DELETE FROM mineg.media WHERE content_sha256 IS NULL;

DROP TABLE IF EXISTS mineg.media_key_envelopes;

ALTER TABLE mineg.upload_sessions
    DROP CONSTRAINT upload_sessions_payload_check,
    DROP CONSTRAINT upload_sessions_purpose_check,
    DROP COLUMN manifest_digest,
    DROP COLUMN encrypted_manifest,
    DROP COLUMN encrypted_media_key,
    DROP COLUMN envelope_algorithm,
    ALTER COLUMN content_sha256 SET NOT NULL,
    ALTER COLUMN mime_type SET NOT NULL,
    ADD CONSTRAINT upload_sessions_purpose_check CHECK (purpose = 'MEDIA_ORIGINAL');

ALTER TABLE mineg.media
    DROP CONSTRAINT media_payload_check,
    DROP COLUMN manifest_digest,
    DROP COLUMN encrypted_manifest,
    ALTER COLUMN content_sha256 SET NOT NULL,
    ALTER COLUMN mime_type SET NOT NULL;

ALTER TABLE mineg.media_resources
    DROP CONSTRAINT media_resources_payload_check,
    DROP COLUMN ciphertext_size,
    DROP COLUMN ciphertext_sha256,
    ALTER COLUMN content_size SET NOT NULL,
    ALTER COLUMN content_sha256 SET NOT NULL;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DO $$ BEGIN RAISE EXCEPTION '00008_remove_legacy_ciphertext_media is intentionally irreversible'; END $$;
-- +goose StatementEnd
