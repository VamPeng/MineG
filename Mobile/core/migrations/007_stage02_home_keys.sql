BEGIN IMMEDIATE;

CREATE TABLE IF NOT EXISTS private_media_snapshots (
  user_id TEXT NOT NULL,
  media_id TEXT NOT NULL,
  media_type TEXT NOT NULL,
  content_revision INTEGER NOT NULL CHECK (content_revision >= 1),
  captured_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, media_id)
);

CREATE INDEX IF NOT EXISTS private_media_snapshot_order_idx
  ON private_media_snapshots(user_id, captured_at DESC, created_at DESC, media_id DESC);

CREATE TABLE IF NOT EXISTS private_media_cache_state (
  user_id TEXT PRIMARY KEY,
  refreshed_at TEXT NOT NULL
);

INSERT OR IGNORE INTO schema_migrations(version) VALUES (7);
PRAGMA user_version = 7;

COMMIT;
