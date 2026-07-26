-- +goose Up
-- +goose StatementBegin
CREATE TABLE mineg.users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_e164 text NOT NULL UNIQUE CHECK (phone_e164 ~ '^\+861[3-9][0-9]{9}$'),
    password_hash text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED')),
    nickname text NOT NULL CHECK (char_length(nickname) BETWEEN 2 AND 20),
    avatar_url text,
    reviewed_at timestamptz,
    reviewed_by uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status <> 'APPROVED' OR reviewed_at IS NOT NULL)
);

CREATE TABLE mineg.user_agreements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    terms_version text NOT NULL,
    privacy_version text NOT NULL,
    device_installation_id text NOT NULL CHECK (char_length(device_installation_id) BETWEEN 8 AND 128),
    accepted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX user_agreements_user_accepted_idx
    ON mineg.user_agreements(user_id, accepted_at DESC);

CREATE TABLE mineg.devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    installation_id text NOT NULL,
    platform text NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'HARMONYOS')),
    last_active_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, installation_id)
);

CREATE TABLE mineg.user_key_bundles (
    user_id uuid PRIMARY KEY REFERENCES mineg.users(id) ON DELETE CASCADE,
    public_key bytea NOT NULL CHECK (octet_length(public_key) = 32),
    encrypted_key_bundle bytea NOT NULL CHECK (octet_length(encrypted_key_bundle) BETWEEN 48 AND 1048576),
    kdf_parameters jsonb NOT NULL,
    bundle_version integer NOT NULL DEFAULT 1 CHECK (bundle_version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (jsonb_typeof(kdf_parameters) = 'object')
);

CREATE TABLE mineg.registration_requests (
    device_installation_id text NOT NULL,
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    user_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    rotation_family_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_installation_id, idempotency_key)
);

CREATE TABLE mineg.user_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES mineg.users(id) ON DELETE CASCADE,
    device_id uuid NOT NULL REFERENCES mineg.devices(id) ON DELETE CASCADE,
    rotation_family_id uuid NOT NULL,
    access_token_hash bytea NOT NULL UNIQUE CHECK (octet_length(access_token_hash) = 32),
    refresh_token_hash bytea NOT NULL UNIQUE CHECK (octet_length(refresh_token_hash) = 32),
    access_expires_at timestamptz NOT NULL,
    refresh_expires_at timestamptz NOT NULL,
    rotated_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (access_expires_at < refresh_expires_at)
);
CREATE INDEX user_sessions_family_idx ON mineg.user_sessions(rotation_family_id);
CREATE INDEX user_sessions_user_active_idx ON mineg.user_sessions(user_id, revoked_at);

CREATE TABLE mineg.admin_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username text NOT NULL UNIQUE CHECK (username = lower(username)),
    password_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at timestamptz
);

ALTER TABLE mineg.users
    ADD CONSTRAINT users_reviewed_by_fkey
    FOREIGN KEY (reviewed_by) REFERENCES mineg.admin_users(id);

CREATE TABLE mineg.admin_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id uuid NOT NULL REFERENCES mineg.admin_users(id) ON DELETE CASCADE,
    session_token_hash bytea NOT NULL UNIQUE CHECK (octet_length(session_token_hash) = 32),
    csrf_token_hash bytea NOT NULL CHECK (octet_length(csrf_token_hash) = 32),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX admin_sessions_active_idx ON mineg.admin_sessions(session_token_hash, revoked_at);

CREATE TABLE mineg.key_grant_tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL UNIQUE REFERENCES mineg.users(id) ON DELETE CASCADE,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'READY')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz
);

CREATE TABLE mineg.approval_requests (
    admin_user_id uuid NOT NULL REFERENCES mineg.admin_users(id),
    idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    user_id uuid NOT NULL REFERENCES mineg.users(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (admin_user_id, idempotency_key)
);

CREATE TABLE mineg.audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type text NOT NULL CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'ANONYMOUS')),
    actor_id uuid,
    action text NOT NULL,
    target_type text NOT NULL,
    target_id uuid,
    result text NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE', 'REPLAY')),
    request_id text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX audit_events_created_idx ON mineg.audit_events(created_at DESC);
CREATE INDEX audit_events_target_idx ON mineg.audit_events(target_type, target_id, created_at DESC);

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

CREATE TRIGGER users_status_transition
BEFORE UPDATE OF status ON mineg.users
FOR EACH ROW EXECUTE FUNCTION mineg.enforce_user_status_transition();
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP TRIGGER IF EXISTS users_status_transition ON mineg.users;
DROP FUNCTION IF EXISTS mineg.enforce_user_status_transition();
DROP TABLE IF EXISTS mineg.audit_events;
DROP TABLE IF EXISTS mineg.approval_requests;
DROP TABLE IF EXISTS mineg.key_grant_tasks;
DROP TABLE IF EXISTS mineg.admin_sessions;
ALTER TABLE mineg.users DROP CONSTRAINT IF EXISTS users_reviewed_by_fkey;
DROP TABLE IF EXISTS mineg.admin_users;
DROP TABLE IF EXISTS mineg.user_sessions;
DROP TABLE IF EXISTS mineg.registration_requests;
DROP TABLE IF EXISTS mineg.user_key_bundles;
DROP TABLE IF EXISTS mineg.devices;
DROP TABLE IF EXISTS mineg.user_agreements;
DROP TABLE IF EXISTS mineg.users;
-- +goose StatementEnd
