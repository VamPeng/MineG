-- +goose Up
-- +goose StatementBegin
-- MineG has one fixed household. Membership is explicit so an accidentally
-- approved account cannot read shared media without being enrolled.
CREATE TABLE mineg.family_memberships (
    user_id uuid PRIMARY KEY REFERENCES mineg.users(id) ON DELETE CASCADE,
    family_id uuid NOT NULL DEFAULT '00000000-0000-4000-8000-000000000001',
    member_slot smallint NOT NULL CHECK (member_slot IN (1, 2)),
    joined_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (family_id = '00000000-0000-4000-8000-000000000001'),
    UNIQUE (family_id, member_slot)
);

DO $$
BEGIN
    IF (SELECT count(*) FROM mineg.users WHERE status = 'APPROVED') > 2 THEN
        RAISE EXCEPTION 'fixed household cannot contain more than two approved users';
    END IF;
END $$;

INSERT INTO mineg.family_memberships(user_id, member_slot)
SELECT id, row_number() OVER (ORDER BY created_at, id)::smallint
FROM mineg.users
WHERE status = 'APPROVED'
ON CONFLICT (user_id) DO NOTHING;

CREATE INDEX family_memberships_family_idx
    ON mineg.family_memberships(family_id, joined_at, user_id);

CREATE TABLE mineg.share_requests (
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    media_id uuid NOT NULL REFERENCES mineg.media(id) ON DELETE RESTRICT,
    requested_state text NOT NULL CHECK (requested_state IN ('ACTIVE', 'INACTIVE')),
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    outcome text NOT NULL CHECK (outcome IN ('SHARED', 'ALREADY_SHARED', 'UNSHARED', 'ALREADY_UNSHARED')),
    effective_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);

CREATE TABLE mineg.restore_requests (
    owner_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    media_id uuid NOT NULL REFERENCES mineg.media(id) ON DELETE RESTRICT,
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    outcome text NOT NULL CHECK (outcome IN ('RESTORED', 'ALREADY_RESTORED')),
    restored_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);

CREATE TABLE mineg.feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    category text NOT NULL CHECK (category IN (
        'ACCOUNT', 'PERMISSION', 'BACKUP', 'BROWSE_PLAYBACK', 'SHARING', 'TRASH', 'OTHER'
    )),
    description text NOT NULL CHECK (char_length(description) BETWEEN 1 AND 1000),
    contact text CHECK (contact IS NULL OR char_length(contact) BETWEEN 1 AND 200),
    app_version text NOT NULL CHECK (char_length(app_version) BETWEEN 1 AND 64),
    platform text NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'HARMONYOS')),
    os_version text NOT NULL CHECK (char_length(os_version) BETWEEN 1 AND 128),
    device_installation_id text NOT NULL CHECK (char_length(device_installation_id) BETWEEN 8 AND 128),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX feedback_user_created_idx ON mineg.feedback(user_id, created_at DESC);

CREATE TABLE mineg.feedback_requests (
    user_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    feedback_id uuid NOT NULL REFERENCES mineg.feedback(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, idempotency_key)
);
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP TABLE IF EXISTS mineg.feedback_requests;
DROP TABLE IF EXISTS mineg.feedback;
DROP TABLE IF EXISTS mineg.restore_requests;
DROP TABLE IF EXISTS mineg.share_requests;
DROP TABLE IF EXISTS mineg.family_memberships;
-- +goose StatementEnd
