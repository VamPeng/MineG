-- Documentation mirror of the compiled SQLite v3 migration.
-- Decrypted keys and media bytes are intentionally absent.
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS backup_settings(
  user_id TEXT NOT NULL,
  device_installation_id TEXT NOT NULL,
  auto_backup_enabled INTEGER NOT NULL DEFAULT 1 CHECK(auto_backup_enabled IN (0,1)),
  allow_cellular_backup INTEGER NOT NULL DEFAULT 0 CHECK(allow_cellular_backup IN (0,1)),
  updated_at TEXT NOT NULL,
  PRIMARY KEY(user_id, device_installation_id)
);
CREATE TABLE IF NOT EXISTS local_albums(
  user_id TEXT NOT NULL,
  platform_album_ref TEXT NOT NULL,
  name TEXT NOT NULL,
  is_available INTEGER NOT NULL DEFAULT 1 CHECK(is_available IN (0,1)),
  modified_at TEXT NOT NULL,
  PRIMARY KEY(user_id, platform_album_ref)
);
CREATE TABLE IF NOT EXISTS local_media(
  user_id TEXT NOT NULL,
  platform_asset_ref TEXT NOT NULL,
  media_type TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  duration_ms INTEGER,
  captured_at TEXT NOT NULL,
  modified_at TEXT NOT NULL,
  modified_version INTEGER NOT NULL,
  content_version TEXT NOT NULL,
  availability TEXT NOT NULL,
  thumbnail_uri TEXT,
  scan_generation TEXT NOT NULL,
  PRIMARY KEY(user_id, platform_asset_ref)
);
CREATE TABLE IF NOT EXISTS local_media_albums(
  user_id TEXT NOT NULL,
  platform_asset_ref TEXT NOT NULL,
  platform_album_ref TEXT NOT NULL,
  PRIMARY KEY(user_id, platform_asset_ref, platform_album_ref),
  FOREIGN KEY(user_id, platform_asset_ref) REFERENCES local_media(user_id, platform_asset_ref) ON DELETE CASCADE,
  FOREIGN KEY(user_id, platform_album_ref) REFERENCES local_albums(user_id, platform_album_ref) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS local_scan_state(
  user_id TEXT PRIMARY KEY,
  cursor_modified_version INTEGER NOT NULL DEFAULT 0,
  cursor_asset_ref TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL,
  indexed_count INTEGER NOT NULL DEFAULT 0,
  scan_generation TEXT NOT NULL DEFAULT '',
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS download_receipts(
  user_id TEXT NOT NULL,
  cloud_media_id TEXT NOT NULL,
  platform_asset_ref TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY(user_id, cloud_media_id)
);
INSERT OR IGNORE INTO schema_migrations(version) VALUES(3);
PRAGMA user_version=3;
COMMIT;
