-- Batch D: completed local-library generations and backup preferences.
-- Foreground scan cursors and execution state deliberately remain out of SQLite.
BEGIN IMMEDIATE;

ALTER TABLE local_albums RENAME TO local_albums_v3_legacy;
ALTER TABLE local_media RENAME TO local_media_v3_legacy;
ALTER TABLE local_media_albums RENAME TO local_media_albums_v3_legacy;
ALTER TABLE local_scan_state RENAME TO local_scan_state_v3_legacy;
DROP INDEX local_media_capture_idx;
DROP INDEX local_media_albums_album_idx;

CREATE TABLE local_library_active(
  user_id TEXT PRIMARY KEY,
  generation_id TEXT NOT NULL,
  indexed_count INTEGER NOT NULL CHECK(indexed_count >= 0),
  completed_at TEXT NOT NULL
);

CREATE TABLE local_albums(
  user_id TEXT NOT NULL,
  generation_id TEXT NOT NULL,
  platform_album_ref TEXT NOT NULL,
  name TEXT NOT NULL,
  PRIMARY KEY(user_id, generation_id, platform_album_ref)
);

CREATE TABLE local_media(
  user_id TEXT NOT NULL,
  generation_id TEXT NOT NULL,
  platform_asset_ref TEXT NOT NULL,
  media_type TEXT NOT NULL CHECK(media_type IN ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC')),
  mime_type TEXT NOT NULL,
  width INTEGER NOT NULL CHECK(width >= 0),
  height INTEGER NOT NULL CHECK(height >= 0),
  duration_ms INTEGER,
  captured_at TEXT NOT NULL,
  modified_at TEXT NOT NULL,
  modified_version INTEGER NOT NULL,
  content_version TEXT NOT NULL,
  availability TEXT NOT NULL CHECK(availability IN ('AVAILABLE','WAITING_LOCAL_RESOURCE','LOCAL_MISSING')),
  thumbnail_uri TEXT,
  PRIMARY KEY(user_id, generation_id, platform_asset_ref)
);
CREATE INDEX local_media_capture_idx
  ON local_media(user_id, generation_id, captured_at DESC, platform_asset_ref DESC);

CREATE TABLE local_media_albums(
  user_id TEXT NOT NULL,
  generation_id TEXT NOT NULL,
  platform_asset_ref TEXT NOT NULL,
  platform_album_ref TEXT NOT NULL,
  PRIMARY KEY(user_id, generation_id, platform_asset_ref, platform_album_ref),
  FOREIGN KEY(user_id, generation_id, platform_asset_ref)
    REFERENCES local_media(user_id, generation_id, platform_asset_ref) ON DELETE CASCADE,
  FOREIGN KEY(user_id, generation_id, platform_album_ref)
    REFERENCES local_albums(user_id, generation_id, platform_album_ref) ON DELETE CASCADE
);
CREATE INDEX local_media_albums_album_idx
  ON local_media_albums(user_id, generation_id, platform_album_ref, platform_asset_ref);

-- Only a previously COMPLETE generation is eligible to become visible.
INSERT INTO local_library_active(user_id,generation_id,indexed_count,completed_at)
SELECT user_id,scan_generation,indexed_count,updated_at
FROM local_scan_state_v3_legacy
WHERE status='COMPLETE' AND scan_generation<>'';

INSERT INTO local_albums(user_id,generation_id,platform_album_ref,name)
SELECT album.user_id,state.scan_generation,album.platform_album_ref,album.name
FROM local_albums_v3_legacy album
JOIN local_scan_state_v3_legacy state ON state.user_id=album.user_id
WHERE state.status='COMPLETE' AND state.scan_generation<>'' AND album.is_available=1;

INSERT INTO local_media(user_id,generation_id,platform_asset_ref,media_type,mime_type,width,height,
                        duration_ms,captured_at,modified_at,modified_version,content_version,
                        availability,thumbnail_uri)
SELECT media.user_id,state.scan_generation,media.platform_asset_ref,media.media_type,media.mime_type,
       media.width,media.height,media.duration_ms,media.captured_at,media.modified_at,
       media.modified_version,media.content_version,media.availability,media.thumbnail_uri
FROM local_media_v3_legacy media
JOIN local_scan_state_v3_legacy state ON state.user_id=media.user_id
WHERE state.status='COMPLETE' AND state.scan_generation<>'' AND media.availability<>'LOCAL_MISSING';

INSERT OR IGNORE INTO local_media_albums(user_id,generation_id,platform_asset_ref,platform_album_ref)
SELECT relation.user_id,state.scan_generation,relation.platform_asset_ref,relation.platform_album_ref
FROM local_media_albums_v3_legacy relation
JOIN local_scan_state_v3_legacy state ON state.user_id=relation.user_id
JOIN local_media media ON media.user_id=relation.user_id
  AND media.generation_id=state.scan_generation
  AND media.platform_asset_ref=relation.platform_asset_ref
JOIN local_albums album ON album.user_id=relation.user_id
  AND album.generation_id=state.scan_generation
  AND album.platform_album_ref=relation.platform_album_ref
WHERE state.status='COMPLETE' AND state.scan_generation<>'';

DROP TABLE local_media_albums_v3_legacy;
DROP TABLE local_media_v3_legacy;
DROP TABLE local_albums_v3_legacy;
DROP TABLE local_scan_state_v3_legacy;

INSERT OR IGNORE INTO schema_migrations(version) VALUES(8);
PRAGMA user_version=8;
COMMIT;
