-- Documentation mirror of the compiled SQLite v2 migration.
-- Only non-sensitive account display and routing state is persisted here.
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS account_state(
  singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
  user_id TEXT NOT NULL,
  masked_phone TEXT NOT NULL,
  approval_status TEXT NOT NULL CHECK(approval_status IN ('PENDING', 'APPROVED')),
  updated_at TEXT NOT NULL
);
INSERT OR IGNORE INTO schema_migrations(version) VALUES(2);
PRAGMA user_version=2;
COMMIT;
