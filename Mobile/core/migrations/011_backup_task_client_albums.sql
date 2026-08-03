-- Keep the first Stage 04 upload request byte-for-byte stable across retries.
-- The client album snapshot is business metadata, not a platform object or media body.
ALTER TABLE backup_tasks ADD COLUMN client_albums_json TEXT NOT NULL DEFAULT '[]';
