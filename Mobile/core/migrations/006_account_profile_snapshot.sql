BEGIN IMMEDIATE;

CREATE TABLE IF NOT EXISTS current_profile_snapshots (
  user_id TEXT PRIMARY KEY,
  nickname TEXT NOT NULL,
  masked_phone TEXT NOT NULL,
  avatar_url TEXT,
  profile_version INTEGER NOT NULL CHECK (profile_version >= 1),
  updated_at TEXT NOT NULL
);

INSERT OR IGNORE INTO schema_migrations(version) VALUES (6);
PRAGMA user_version = 6;

COMMIT;
