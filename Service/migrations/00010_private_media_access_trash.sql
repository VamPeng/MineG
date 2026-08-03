-- +goose Up
-- +goose StatementBegin
-- Stage 05 stores only display metadata and authorization state. Object grants
-- remain ephemeral and must never be persisted in PostgreSQL.
ALTER TABLE mineg.upload_sessions
    ADD COLUMN width integer CHECK (width IS NULL OR width > 0),
    ADD COLUMN height integer CHECK (height IS NULL OR height > 0),
    ADD COLUMN duration_ms bigint CHECK (duration_ms IS NULL OR duration_ms >= 0);

ALTER TABLE mineg.media
    ADD COLUMN width integer CHECK (width IS NULL OR width > 0),
    ADD COLUMN height integer CHECK (height IS NULL OR height > 0),
    ADD COLUMN duration_ms bigint CHECK (duration_ms IS NULL OR duration_ms >= 0),
    ADD COLUMN access_version bigint NOT NULL DEFAULT 1 CHECK (access_version > 0);

ALTER TABLE mineg.media_resources
    ADD COLUMN mime_type text;
UPDATE mineg.media_resources AS resource
SET mime_type = session.mime_type
FROM mineg.upload_sessions AS session
WHERE resource.upload_session_id = session.id
  AND resource.mime_type IS NULL;
ALTER TABLE mineg.media_resources
    ALTER COLUMN mime_type SET NOT NULL,
    ADD CONSTRAINT media_resources_mime_type_check
        CHECK (char_length(mime_type) BETWEEN 3 AND 127);

CREATE TABLE mineg.shares (
    media_id uuid PRIMARY KEY REFERENCES mineg.media(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    shared_at timestamptz,
    unshared_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((state = 'ACTIVE' AND shared_at IS NOT NULL AND unshared_at IS NULL)
        OR (state = 'INACTIVE' AND unshared_at IS NOT NULL))
);
CREATE INDEX shares_owner_state_idx ON mineg.shares(owner_id, state);

CREATE TABLE mineg.trash_records (
    media_id uuid PRIMARY KEY REFERENCES mineg.media(id) ON DELETE RESTRICT,
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    trashed_at timestamptz NOT NULL,
    restored_at timestamptz,
    purged_at timestamptz,
    CHECK (restored_at IS NULL OR restored_at >= trashed_at),
    CHECK (purged_at IS NULL OR purged_at >= trashed_at)
);
CREATE INDEX trash_records_active_owner_idx
    ON mineg.trash_records(owner_id, trashed_at DESC)
    WHERE restored_at IS NULL AND purged_at IS NULL;

CREATE TABLE mineg.trash_requests (
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    media_id uuid NOT NULL REFERENCES mineg.media(id) ON DELETE RESTRICT,
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    outcome text NOT NULL CHECK (outcome IN ('TRASHED', 'ALREADY_TRASHED')),
    trashed_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DO $$ BEGIN RAISE EXCEPTION '00010_private_media_access_trash is intentionally irreversible'; END $$;
-- +goose StatementEnd
