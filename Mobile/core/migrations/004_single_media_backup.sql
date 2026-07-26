CREATE TABLE backup_tasks (
  task_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  platform_asset_ref TEXT NOT NULL,
  content_version TEXT NOT NULL,
  media_type TEXT NOT NULL CHECK(media_type IN ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC')),
  state TEXT NOT NULL CHECK(state IN (
    'PREPARING','PREPARED','UPLOADING','SERVER_VERIFYING','COMPLETED',
    'RETRYABLE_FAILED','PERMANENT_FAILED')),
  dedupe_fingerprint TEXT,
  encrypted_media_key TEXT,
  encrypted_manifest TEXT,
  manifest_digest TEXT,
  server_upload_id TEXT,
  server_media_id TEXT,
  error_code TEXT,
  retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count >= 0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(user_id, platform_asset_ref, content_version)
);

CREATE INDEX backup_tasks_user_state_idx ON backup_tasks(user_id, state, updated_at);

CREATE TABLE backup_resources (
  resource_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES backup_tasks(task_id) ON DELETE CASCADE,
  resource_type TEXT NOT NULL CHECK(resource_type IN (
    'ORIGINAL','THUMBNAIL','VIDEO_COVER','PREVIEW','LIVE_PHOTO_VIDEO','DYNAMIC_PREVIEW')),
  ciphertext_path TEXT NOT NULL,
  ciphertext_size INTEGER NOT NULL CHECK(ciphertext_size > 0),
  ciphertext_sha256 TEXT NOT NULL,
  manifest_json TEXT NOT NULL CHECK(json_valid(manifest_json)),
  UNIQUE(task_id, resource_type)
);

CREATE TABLE backup_parts (
  resource_id TEXT NOT NULL REFERENCES backup_resources(resource_id) ON DELETE CASCADE,
  part_number INTEGER NOT NULL CHECK(part_number BETWEEN 1 AND 10000),
  ciphertext_offset INTEGER NOT NULL CHECK(ciphertext_offset >= 0),
  ciphertext_size INTEGER NOT NULL CHECK(ciphertext_size BETWEEN 1 AND 4194320),
  ciphertext_sha256 TEXT NOT NULL,
  etag TEXT,
  state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','UPLOADED')),
  PRIMARY KEY(resource_id, part_number)
);
