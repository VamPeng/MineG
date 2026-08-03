-- +goose Up
-- +goose StatementBegin
-- Grant generations let clients distinguish a refreshed authorization from the
-- exact multipart confirmation set it was issued for.
ALTER TABLE mineg.upload_sessions
    ADD COLUMN grant_generation integer NOT NULL DEFAULT 1 CHECK (grant_generation > 0);

-- Client album IDs originate on a device and are not necessarily UUIDs.  Keep
-- the owner and installation scope in the durable server mapping.
ALTER TABLE mineg.albums
    ALTER COLUMN client_album_id TYPE text USING client_album_id::text,
    ADD COLUMN device_installation_id text,
    ADD COLUMN display_name text;

ALTER TABLE mineg.albums
    DROP CONSTRAINT albums_owner_id_kind_client_album_id_key;
CREATE UNIQUE INDEX albums_owner_client_album_unique
    ON mineg.albums(owner_id, device_installation_id, client_album_id)
    WHERE kind = 'CLIENT';

CREATE TABLE mineg.upload_session_client_albums (
    upload_session_id uuid NOT NULL REFERENCES mineg.upload_sessions(id) ON DELETE CASCADE,
    album_id uuid NOT NULL REFERENCES mineg.albums(id) ON DELETE RESTRICT,
    PRIMARY KEY (upload_session_id, album_id)
);

CREATE INDEX media_owner_completed_seek_idx
    ON mineg.media(owner_id, captured_at DESC, id DESC)
    WHERE upload_status = 'COMPLETED';
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DO $$ BEGIN RAISE EXCEPTION '00009_backup_queue_upload_recovery is intentionally irreversible'; END $$;
-- +goose StatementEnd
