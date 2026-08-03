-- Stage 04 owns its plaintext upload queue separately from the retired Stage 03 v1
-- ciphertext preparation cache.  All rows are scoped to one account + install.
CREATE TABLE backup_scan_state(
    user_id TEXT NOT NULL,
    device_installation_id TEXT NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('HISTORICAL','INCREMENTAL','FULL_RECONCILE')),
    state TEXT NOT NULL CHECK(state IN ('IDLE','SCANNING','WAITING_PERMISSION','FAILED')),
    generation_id TEXT NOT NULL,
    cursor_json TEXT,
    upper_bound_json TEXT,
    reconcile_requested INTEGER NOT NULL DEFAULT 0 CHECK(reconcile_requested IN (0,1)),
    discovered_count INTEGER NOT NULL DEFAULT 0 CHECK(discovered_count>=0),
    started_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY(user_id, device_installation_id)
);

CREATE TABLE backup_tasks(
    task_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    device_installation_id TEXT NOT NULL,
    platform_asset_ref TEXT NOT NULL,
    content_version TEXT NOT NULL,
    client_media_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK(media_type IN ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC')),
    mime_type TEXT NOT NULL,
    captured_at TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN (
        'DISCOVERED','WAITING_PERMISSION','WAITING_RESOURCE','WAITING_NETWORK','PREPARING',
        'CREATING_SESSION','UPLOADING','SERVER_VERIFYING','RETRYABLE_FAILED',
        'PERMANENT_FAILED','PAUSED_BY_SETTING','COMPLETED'
    )),
    resume_state TEXT,
    server_upload_id TEXT,
    server_media_id TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count>=0),
    next_retry_at TEXT,
    failure_code TEXT,
    failure_scope TEXT CHECK(failure_scope IS NULL OR failure_scope IN ('LOCAL','NETWORK','SERVICE','OSS','AUTH')),
    lease_token TEXT,
    lease_expires_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(user_id, device_installation_id, platform_asset_ref, content_version),
    UNIQUE(user_id, device_installation_id, idempotency_key)
);
CREATE INDEX backup_tasks_runnable_idx ON backup_tasks(
    user_id, device_installation_id, state, next_retry_at, captured_at DESC, task_id DESC
);

CREATE TABLE backup_resources(
    resource_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES backup_tasks(task_id) ON DELETE CASCADE,
    resource_type TEXT NOT NULL,
    byte_length INTEGER NOT NULL CHECK(byte_length>0),
    sha256_base64 TEXT NOT NULL,
    preparation_state TEXT NOT NULL CHECK(preparation_state IN ('PENDING','READY','UNAVAILABLE','FAILED')),
    server_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(server_confirmed IN (0,1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(task_id, resource_type)
);

CREATE TABLE backup_parts(
    resource_id TEXT NOT NULL REFERENCES backup_resources(resource_id) ON DELETE CASCADE,
    part_number INTEGER NOT NULL CHECK(part_number>0),
    byte_offset INTEGER NOT NULL CHECK(byte_offset>=0),
    byte_length INTEGER NOT NULL CHECK(byte_length>0),
    sha256_base64 TEXT NOT NULL,
    etag TEXT,
    state TEXT NOT NULL CHECK(state IN ('PENDING','TRANSFERRED','CONFIRMED')),
    confirmed_at TEXT,
    PRIMARY KEY(resource_id, part_number)
);
CREATE INDEX backup_parts_pending_idx ON backup_parts(resource_id, state, part_number);
