-- +goose Up
-- +goose StatementBegin
CREATE TABLE mineg.families (
    id uuid PRIMARY KEY,
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO mineg.families(id) VALUES ('00000000-0000-4000-8000-000000000001');

ALTER TABLE mineg.key_grant_tasks
    DROP CONSTRAINT key_grant_tasks_state_check,
    ADD COLUMN family_id uuid NOT NULL DEFAULT '00000000-0000-4000-8000-000000000001'
        REFERENCES mineg.families(id),
    ADD COLUMN completed_by uuid REFERENCES mineg.users(id),
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT key_grant_tasks_state_check CHECK (state IN ('PENDING', 'READY'));

CREATE TABLE mineg.family_key_envelopes (
    family_id uuid NOT NULL REFERENCES mineg.families(id),
    user_id uuid NOT NULL UNIQUE REFERENCES mineg.users(id) ON DELETE CASCADE,
    created_by uuid NOT NULL REFERENCES mineg.users(id),
    recipient_public_key_hash bytea NOT NULL CHECK (octet_length(recipient_public_key_hash) = 32),
    encrypted_envelope bytea NOT NULL CHECK (octet_length(encrypted_envelope) = 80),
    algorithm text NOT NULL CHECK (algorithm = 'X25519_SEALED_BOX'),
    envelope_version integer NOT NULL CHECK (envelope_version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (family_id, user_id)
);
CREATE INDEX family_key_envelopes_creator_idx
    ON mineg.family_key_envelopes(created_by, created_at DESC);

CREATE TABLE mineg.avatar_uploads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    object_key text NOT NULL UNIQUE CHECK (object_key LIKE 'avatars/%'),
    content_type text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/heic', 'image/heif', 'image/webp')),
    source_size bigint NOT NULL CHECK (source_size BETWEEN 1 AND 10485760),
    display_size bigint NOT NULL CHECK (display_size BETWEEN 1 AND 10485760),
    width integer NOT NULL CHECK (width BETWEEN 1 AND 1024),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 1024),
    content_sha256 bytea NOT NULL CHECK (octet_length(content_sha256) = 32),
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'READY')),
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, idempotency_key),
    CHECK (width = height),
    CHECK ((state = 'PENDING' AND completed_at IS NULL) OR (state = 'READY' AND completed_at IS NOT NULL))
);

ALTER TABLE mineg.users
    ADD COLUMN avatar_upload_id uuid REFERENCES mineg.avatar_uploads(id),
    ADD COLUMN profile_version bigint NOT NULL DEFAULT 1 CHECK (profile_version > 0);

CREATE OR REPLACE FUNCTION mineg.enforce_user_status_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'APPROVED' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'approved user status is immutable';
    END IF;
    IF NEW.status = 'APPROVED' AND (
        NOT EXISTS (
            SELECT 1 FROM mineg.key_grant_tasks task
            WHERE task.user_id = NEW.id AND task.state = 'READY'
        ) OR NOT EXISTS (
            SELECT 1 FROM mineg.family_key_envelopes envelope
            WHERE envelope.user_id = NEW.id
        )
    ) THEN
        RAISE EXCEPTION 'user cannot be approved before the family key envelope is ready';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
CREATE OR REPLACE FUNCTION mineg.enforce_user_status_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'APPROVED' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'approved user status is immutable';
    END IF;
    IF NEW.status = 'APPROVED' AND NOT EXISTS (
        SELECT 1 FROM mineg.key_grant_tasks task
        WHERE task.user_id = NEW.id AND task.state = 'READY'
    ) THEN
        RAISE EXCEPTION 'user cannot be approved before key grant is ready';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

ALTER TABLE mineg.users
    DROP COLUMN profile_version,
    DROP COLUMN avatar_upload_id;
DROP TABLE mineg.avatar_uploads;
DROP TABLE mineg.family_key_envelopes;
ALTER TABLE mineg.key_grant_tasks
    DROP CONSTRAINT key_grant_tasks_state_check,
    DROP COLUMN updated_at,
    DROP COLUMN attempt_count,
    DROP COLUMN completed_by,
    DROP COLUMN family_id,
    ADD CONSTRAINT key_grant_tasks_state_check CHECK (state IN ('PENDING', 'READY'));
DROP TABLE mineg.families;
-- +goose StatementEnd
