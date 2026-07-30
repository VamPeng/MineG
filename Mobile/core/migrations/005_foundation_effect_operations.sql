BEGIN IMMEDIATE;

CREATE TABLE IF NOT EXISTS core_operations (
  operation_id INTEGER PRIMARY KEY CHECK (operation_id > 0),
  contract_version TEXT NOT NULL CHECK (contract_version = 'foundation-v2'),
  command_type TEXT NOT NULL,
  command_json TEXT NOT NULL CHECK (json_valid(command_json)),
  sequence INTEGER NOT NULL CHECK (sequence > 0),
  status TEXT NOT NULL CHECK (
    status IN ('WAITING_FOR_EFFECT', 'COMPLETED', 'FAILED', 'CANCELLED')
  ),
  effect_type TEXT NOT NULL,
  effect_payload TEXT NOT NULL CHECK (json_valid(effect_payload)),
  effect_result_json TEXT,
  terminal_payload TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS core_operations_recovery_idx
  ON core_operations(status, updated_at, operation_id);

INSERT OR IGNORE INTO schema_migrations(version) VALUES (5);
PRAGMA user_version = 5;

COMMIT;
