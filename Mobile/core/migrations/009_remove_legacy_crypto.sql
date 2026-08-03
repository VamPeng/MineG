-- Destructive forward migration: retire the Stage 03 v1 ciphertext preparation cache.
DROP TABLE IF EXISTS backup_parts;
DROP TABLE IF EXISTS backup_resources;
DROP TABLE IF EXISTS backup_tasks;
