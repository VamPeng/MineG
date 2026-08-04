-- Stage 05 supersedes the summary-only private-media cache with an account-isolated
-- page cache, resource manifest, and recoverable save-operation truth. Temporary
-- grants and object URLs are intentionally not represented in any table.
CREATE TABLE private_media_items_v2(
    user_id TEXT NOT NULL,
    media_id TEXT NOT NULL,
    media_type TEXT NOT NULL,
    captured_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    duration_ms INTEGER,
    original_total_size INTEGER NOT NULL CHECK(original_total_size>=0),
    preview_resource_id TEXT,
    content_revision INTEGER NOT NULL CHECK(content_revision>=1),
    updated_at TEXT NOT NULL,
    PRIMARY KEY(user_id,media_id)
);
CREATE INDEX private_media_items_v2_order_idx ON private_media_items_v2(
    user_id,captured_at DESC,media_id DESC
);

CREATE TABLE private_media_page_state_v2(
    user_id TEXT PRIMARY KEY,
    next_cursor TEXT,
    fully_loaded INTEGER NOT NULL DEFAULT 0 CHECK(fully_loaded IN (0,1)),
    refreshed_at TEXT NOT NULL
);

CREATE TABLE private_media_resources(
    user_id TEXT NOT NULL,
    media_id TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    content_size INTEGER NOT NULL CHECK(content_size>0),
    content_sha256_base64 TEXT NOT NULL,
    PRIMARY KEY(user_id,resource_id),
    UNIQUE(user_id,media_id,resource_type),
    FOREIGN KEY(user_id,media_id) REFERENCES private_media_items_v2(user_id,media_id) ON DELETE CASCADE
);

-- Historical compatibility only: versions already migrated through v13 may contain these two
-- retired save-state tables. Runtime code does not read or write them; a future compatible
-- migration may remove them after deployed databases no longer need rollback compatibility.
CREATE TABLE private_media_save_operations(
    operation_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    media_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN (
        'IDLE','REQUESTING_ACCESS','DOWNLOADING','VERIFYING','WRITING_SYSTEM_ALBUM',
        'COMPLETED','CANCELLED','RETRYABLE_FAILED','PERMANENT_FAILED'
    )),
    failure_code TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count>=0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(user_id,media_id),
    FOREIGN KEY(user_id,media_id) REFERENCES private_media_items_v2(user_id,media_id) ON DELETE CASCADE
);

CREATE TABLE private_media_save_resources(
    operation_id TEXT NOT NULL REFERENCES private_media_save_operations(operation_id) ON DELETE CASCADE,
    resource_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PENDING','DOWNLOADING','VERIFIED','WRITTEN','FAILED')),
    verified_path TEXT,
    PRIMARY KEY(operation_id,resource_id)
);

-- Preserve the former list cache while moving it to the authoritative Stage 05 shape.
INSERT OR IGNORE INTO private_media_items_v2(
    user_id,media_id,media_type,captured_at,created_at,original_total_size,content_revision,updated_at
)
SELECT user_id,media_id,media_type,captured_at,created_at,0,content_revision,created_at
FROM private_media_snapshots;
INSERT OR IGNORE INTO private_media_page_state_v2(user_id,fully_loaded,refreshed_at)
SELECT user_id,0,refreshed_at FROM private_media_cache_state;

ALTER TABLE download_receipts ADD COLUMN content_revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE download_receipts ADD COLUMN resource_set_digest TEXT;
ALTER TABLE download_receipts ADD COLUMN updated_at TEXT NOT NULL DEFAULT '';

INSERT INTO schema_migrations(version) VALUES(13);
PRAGMA user_version=13;
