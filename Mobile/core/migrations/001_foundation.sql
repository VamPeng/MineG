-- Documentation mirror of the compiled SQLite v1 migration.
-- Published mobile migrations are append-only; core/src/core.cpp executes this transaction.
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY NOT NULL);
CREATE TABLE IF NOT EXISTS foundation_probe(
  singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
  value TEXT NOT NULL
);
INSERT OR IGNORE INTO schema_migrations(version) VALUES(1);
INSERT OR IGNORE INTO foundation_probe(singleton, value) VALUES(1, 'initialized');
PRAGMA user_version=1;
COMMIT;
