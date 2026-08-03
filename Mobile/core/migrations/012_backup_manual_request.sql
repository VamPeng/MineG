-- A user-initiated upload shares the durable Stage 04 queue but is not suppressed by the
-- automatic-backup switch. The bit is local scheduling intent, never server-facing metadata.
ALTER TABLE backup_tasks
    ADD COLUMN requested_manually INTEGER NOT NULL DEFAULT 0 CHECK(requested_manually IN (0,1));
