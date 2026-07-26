-- +goose Up
-- +goose StatementBegin
CREATE TABLE mineg.albums (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    kind text NOT NULL DEFAULT 'LIBRARY' CHECK (kind IN ('LIBRARY', 'CLIENT')),
    client_album_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, kind, client_album_id)
);

CREATE UNIQUE INDEX albums_owner_library_unique
    ON mineg.albums(owner_id) WHERE kind = 'LIBRARY';

CREATE TABLE mineg.upload_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    purpose text NOT NULL CHECK (purpose = 'MEDIA_CIPHERTEXT'),
    state text NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'VERIFYING', 'COMPLETED', 'EXPIRED', 'INVALID')),
    dedupe_fingerprint bytea NOT NULL CHECK (octet_length(dedupe_fingerprint) = 32),
    content_revision integer NOT NULL CHECK (content_revision > 0),
    client_media_id uuid NOT NULL,
    media_type text NOT NULL CHECK (media_type IN ('PHOTO', 'VIDEO', 'GIF', 'LIVE_PHOTO', 'DYNAMIC')),
    captured_at timestamptz NOT NULL,
    manifest_digest bytea NOT NULL CHECK (octet_length(manifest_digest) = 32),
    encrypted_manifest bytea NOT NULL CHECK (octet_length(encrypted_manifest) BETWEEN 48 AND 1048576),
    encrypted_media_key bytea NOT NULL CHECK (octet_length(encrypted_media_key) BETWEEN 64 AND 1024),
    envelope_algorithm text NOT NULL CHECK (envelope_algorithm = 'XCHACHA20_POLY1305'),
    media_id uuid,
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, idempotency_key),
    UNIQUE (owner_id, client_media_id),
    CHECK ((state = 'COMPLETED' AND media_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (state <> 'COMPLETED' AND completed_at IS NULL))
);

CREATE INDEX upload_sessions_owner_state_idx
    ON mineg.upload_sessions(owner_id, state, created_at DESC);
CREATE INDEX upload_sessions_expiry_idx
    ON mineg.upload_sessions(expires_at) WHERE state IN ('PENDING', 'VERIFYING');

CREATE TABLE mineg.media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    source_upload_id uuid NOT NULL UNIQUE REFERENCES mineg.upload_sessions(id),
    media_type text NOT NULL CHECK (media_type IN ('PHOTO', 'VIDEO', 'GIF', 'LIVE_PHOTO', 'DYNAMIC')),
    dedupe_fingerprint bytea NOT NULL CHECK (octet_length(dedupe_fingerprint) = 32),
    content_revision integer NOT NULL CHECK (content_revision > 0),
    captured_at timestamptz NOT NULL,
    manifest_digest bytea NOT NULL CHECK (octet_length(manifest_digest) = 32),
    encrypted_manifest bytea NOT NULL CHECK (octet_length(encrypted_manifest) BETWEEN 48 AND 1048576),
    upload_status text NOT NULL CHECK (upload_status = 'COMPLETED'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, dedupe_fingerprint, content_revision)
);

ALTER TABLE mineg.upload_sessions
    ADD CONSTRAINT upload_sessions_media_fk FOREIGN KEY (media_id) REFERENCES mineg.media(id);

CREATE INDEX media_owner_capture_idx
    ON mineg.media(owner_id, captured_at DESC, id DESC)
    WHERE upload_status = 'COMPLETED';

CREATE TABLE mineg.media_resources (
    id uuid PRIMARY KEY,
    upload_session_id uuid NOT NULL REFERENCES mineg.upload_sessions(id) ON DELETE RESTRICT,
    media_id uuid REFERENCES mineg.media(id) ON DELETE CASCADE,
    resource_type text NOT NULL
        CHECK (resource_type IN ('ORIGINAL', 'THUMBNAIL', 'VIDEO_COVER', 'PREVIEW', 'LIVE_PHOTO_VIDEO', 'DYNAMIC_PREVIEW')),
    object_key text NOT NULL UNIQUE CHECK (object_key LIKE 'media/%'),
    multipart_upload_id text NOT NULL,
    ciphertext_size bigint NOT NULL CHECK (ciphertext_size > 0),
    ciphertext_sha256 bytea NOT NULL CHECK (octet_length(ciphertext_sha256) = 32),
    part_count integer NOT NULL CHECK (part_count BETWEEN 1 AND 10000),
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'READY', 'INVALID')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (upload_session_id, resource_type),
    CHECK ((state = 'READY' AND media_id IS NOT NULL) OR state <> 'READY')
);

CREATE INDEX media_resources_media_idx ON mineg.media_resources(media_id) WHERE media_id IS NOT NULL;

CREATE TABLE mineg.upload_parts (
    upload_session_id uuid NOT NULL REFERENCES mineg.upload_sessions(id) ON DELETE CASCADE,
    resource_id uuid NOT NULL REFERENCES mineg.media_resources(id) ON DELETE CASCADE,
    part_number integer NOT NULL CHECK (part_number BETWEEN 1 AND 10000),
    expected_size bigint NOT NULL CHECK (expected_size > 0 AND expected_size <= 4194320),
    expected_sha256 bytea NOT NULL CHECK (octet_length(expected_sha256) = 32),
    etag text,
    reported_size bigint,
    reported_sha256 bytea,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'UPLOADED', 'VERIFIED')),
    reported_at timestamptz,
    PRIMARY KEY (resource_id, part_number),
    CHECK ((state = 'PENDING' AND etag IS NULL AND reported_size IS NULL AND reported_sha256 IS NULL)
        OR (state <> 'PENDING' AND etag IS NOT NULL AND reported_size IS NOT NULL
            AND reported_sha256 IS NOT NULL AND octet_length(reported_sha256) = 32))
);

CREATE INDEX upload_parts_session_state_idx
    ON mineg.upload_parts(upload_session_id, state, resource_id, part_number);

CREATE TABLE mineg.media_album_links (
    media_id uuid NOT NULL REFERENCES mineg.media(id) ON DELETE CASCADE,
    album_id uuid NOT NULL REFERENCES mineg.albums(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (media_id, album_id)
);

CREATE TABLE mineg.media_key_envelopes (
    media_id uuid NOT NULL REFERENCES mineg.media(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    encrypted_media_key bytea NOT NULL CHECK (octet_length(encrypted_media_key) BETWEEN 64 AND 1024),
    algorithm text NOT NULL CHECK (algorithm = 'XCHACHA20_POLY1305'),
    envelope_version integer NOT NULL CHECK (envelope_version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (media_id, owner_id)
);
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP TABLE mineg.media_key_envelopes;
DROP TABLE mineg.media_album_links;
DROP TABLE mineg.upload_parts;
DROP TABLE mineg.media_resources;
ALTER TABLE mineg.upload_sessions DROP CONSTRAINT upload_sessions_media_fk;
DROP TABLE mineg.media;
DROP TABLE mineg.upload_sessions;
DROP TABLE mineg.albums;
-- +goose StatementEnd
