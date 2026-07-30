-- +goose Up
-- +goose StatementBegin
ALTER TABLE mineg.upload_sessions
    DROP CONSTRAINT upload_sessions_purpose_check,
    ADD CONSTRAINT upload_sessions_purpose_check
        CHECK (purpose IN ('MEDIA_CIPHERTEXT', 'MEDIA_ORIGINAL')),
    ALTER COLUMN manifest_digest DROP NOT NULL,
    ALTER COLUMN encrypted_manifest DROP NOT NULL,
    ALTER COLUMN encrypted_media_key DROP NOT NULL,
    ALTER COLUMN envelope_algorithm DROP NOT NULL,
    ADD COLUMN content_sha256 bytea CHECK (content_sha256 IS NULL OR octet_length(content_sha256) = 32),
    ADD COLUMN mime_type text CHECK (mime_type IS NULL OR char_length(mime_type) BETWEEN 3 AND 127),
    ADD CONSTRAINT upload_sessions_payload_check CHECK (
        (purpose = 'MEDIA_CIPHERTEXT'
            AND manifest_digest IS NOT NULL
            AND encrypted_manifest IS NOT NULL
            AND encrypted_media_key IS NOT NULL
            AND envelope_algorithm = 'XCHACHA20_POLY1305'
            AND content_sha256 IS NULL)
        OR
        (purpose = 'MEDIA_ORIGINAL'
            AND manifest_digest IS NULL
            AND encrypted_manifest IS NULL
            AND encrypted_media_key IS NULL
            AND envelope_algorithm IS NULL
            AND content_sha256 IS NOT NULL
            AND mime_type IS NOT NULL)
    );

ALTER TABLE mineg.media
    ALTER COLUMN manifest_digest DROP NOT NULL,
    ALTER COLUMN encrypted_manifest DROP NOT NULL,
    ADD COLUMN content_sha256 bytea CHECK (content_sha256 IS NULL OR octet_length(content_sha256) = 32),
    ADD COLUMN mime_type text CHECK (mime_type IS NULL OR char_length(mime_type) BETWEEN 3 AND 127),
    ADD CONSTRAINT media_payload_check CHECK (
        (manifest_digest IS NOT NULL AND encrypted_manifest IS NOT NULL AND content_sha256 IS NULL)
        OR
        (manifest_digest IS NULL AND encrypted_manifest IS NULL AND content_sha256 IS NOT NULL AND mime_type IS NOT NULL)
    );

ALTER TABLE mineg.media_resources
    ALTER COLUMN ciphertext_size DROP NOT NULL,
    ALTER COLUMN ciphertext_sha256 DROP NOT NULL,
    ADD COLUMN content_size bigint CHECK (content_size IS NULL OR content_size > 0),
    ADD COLUMN content_sha256 bytea CHECK (content_sha256 IS NULL OR octet_length(content_sha256) = 32),
    ADD CONSTRAINT media_resources_payload_check CHECK (
        (ciphertext_size IS NOT NULL AND ciphertext_sha256 IS NOT NULL
            AND content_size IS NULL AND content_sha256 IS NULL)
        OR
        (ciphertext_size IS NULL AND ciphertext_sha256 IS NULL
            AND content_size IS NOT NULL AND content_sha256 IS NOT NULL)
    );
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
ALTER TABLE mineg.media_resources
    DROP CONSTRAINT media_resources_payload_check,
    DROP COLUMN content_sha256,
    DROP COLUMN content_size,
    ALTER COLUMN ciphertext_size SET NOT NULL,
    ALTER COLUMN ciphertext_sha256 SET NOT NULL;

ALTER TABLE mineg.media
    DROP CONSTRAINT media_payload_check,
    DROP COLUMN mime_type,
    DROP COLUMN content_sha256,
    ALTER COLUMN manifest_digest SET NOT NULL,
    ALTER COLUMN encrypted_manifest SET NOT NULL;

ALTER TABLE mineg.upload_sessions
    DROP CONSTRAINT upload_sessions_payload_check,
    DROP COLUMN mime_type,
    DROP COLUMN content_sha256,
    DROP CONSTRAINT upload_sessions_purpose_check,
    ADD CONSTRAINT upload_sessions_purpose_check CHECK (purpose = 'MEDIA_CIPHERTEXT'),
    ALTER COLUMN manifest_digest SET NOT NULL,
    ALTER COLUMN encrypted_manifest SET NOT NULL,
    ALTER COLUMN encrypted_media_key SET NOT NULL,
    ALTER COLUMN envelope_algorithm SET NOT NULL;
-- +goose StatementEnd
