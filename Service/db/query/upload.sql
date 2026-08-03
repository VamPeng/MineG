-- name: AcquireUploadIdempotencyLock :exec
SELECT pg_advisory_xact_lock(hashtextextended(sqlc.arg(owner_id)::text || ':' || sqlc.arg(idempotency_key)::text, 0));

-- name: FindUploadByIdempotency :one
SELECT * FROM mineg.upload_sessions
WHERE owner_id = $1 AND idempotency_key = $2;

-- name: FindCompletedMediaByFingerprint :one
SELECT * FROM mineg.media
WHERE owner_id = $1 AND dedupe_fingerprint = $2 AND content_revision = $3
  AND upload_status = 'COMPLETED';

-- name: CreateOriginalUploadSession :one
INSERT INTO mineg.upload_sessions(
    id, owner_id, idempotency_key, request_hash, purpose, dedupe_fingerprint,
    content_revision, client_media_id, media_type, captured_at, content_sha256, mime_type,
    width, height, duration_ms, expires_at
) VALUES ($1, $2, $3, $4, 'MEDIA_ORIGINAL', $5, $6, $7, $8, $9, $5, $10, $11, $12, $13, $14)
RETURNING *;

-- name: CreateDeduplicatedOriginalUploadSession :one
INSERT INTO mineg.upload_sessions(
    id, owner_id, idempotency_key, request_hash, purpose, state, dedupe_fingerprint,
    content_revision, client_media_id, media_type, captured_at, content_sha256, mime_type,
    width, height, duration_ms, media_id, expires_at, completed_at
) VALUES ($1, $2, $3, $4, 'MEDIA_ORIGINAL', 'COMPLETED', $5, $6, $7, $8, $9, $5, $10,
          $11, $12, $13, $14, $15, $16)
RETURNING *;

-- name: CreateOriginalUploadResource :exec
INSERT INTO mineg.media_resources(
    id, upload_session_id, resource_type, object_key, multipart_upload_id,
    content_size, content_sha256, mime_type, part_count
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9);

-- name: CreateExpectedUploadPart :exec
INSERT INTO mineg.upload_parts(
    upload_session_id, resource_id, part_number, expected_size, expected_sha256
) VALUES ($1, $2, $3, $4, $5);

-- name: FindUploadForOwner :one
SELECT * FROM mineg.upload_sessions WHERE id = $1 AND owner_id = $2;

-- name: ListUploadResources :many
SELECT * FROM mineg.media_resources WHERE upload_session_id = $1 ORDER BY resource_type, id;

-- name: ListUploadParts :many
SELECT * FROM mineg.upload_parts WHERE upload_session_id = $1 ORDER BY resource_id, part_number;

-- name: FindUploadPartForUpdate :one
SELECT part.*, resource.object_key, resource.multipart_upload_id, resource.resource_type
FROM mineg.upload_parts part
JOIN mineg.media_resources resource ON resource.id = part.resource_id
WHERE part.upload_session_id = $1 AND part.resource_id = $2 AND part.part_number = $3
FOR UPDATE OF part;

-- name: ReportUploadPart :execrows
UPDATE mineg.upload_parts
SET etag = $4, reported_size = $5, reported_sha256 = $6, state = 'UPLOADED', reported_at = $7
WHERE upload_session_id = $1 AND resource_id = $2 AND part_number = $3
  AND expected_size = $5 AND expected_sha256 = $6
  AND (state = 'PENDING' OR (etag = $4 AND reported_size = $5 AND reported_sha256 = $6));

-- name: LockUploadForCompletion :one
SELECT * FROM mineg.upload_sessions
WHERE id = $1 AND owner_id = $2
FOR UPDATE;

-- name: MarkUploadVerifying :execrows
UPDATE mineg.upload_sessions
SET state = 'VERIFYING', updated_at = $3
WHERE id = $1 AND owner_id = $2 AND state = 'PENDING' AND expires_at > $3;

-- name: MarkUploadInvalid :exec
UPDATE mineg.upload_sessions
SET state = 'INVALID', updated_at = $2
WHERE id = $1 AND state IN ('PENDING', 'VERIFYING');

-- name: CountUnreportedParts :one
SELECT count(*) FROM mineg.upload_parts WHERE upload_session_id = $1 AND state <> 'UPLOADED';

-- name: MarkUploadPartsVerified :exec
UPDATE mineg.upload_parts SET state = 'VERIFIED'
WHERE upload_session_id = $1 AND state = 'UPLOADED';

-- name: EnsureLibraryAlbum :one
INSERT INTO mineg.albums(owner_id, kind)
VALUES ($1, 'LIBRARY')
ON CONFLICT (owner_id) WHERE kind = 'LIBRARY' DO UPDATE SET owner_id = EXCLUDED.owner_id
RETURNING *;

-- name: CreateCompletedOriginalMedia :one
INSERT INTO mineg.media(
    id, owner_id, source_upload_id, media_type, dedupe_fingerprint,
    content_revision, captured_at, content_sha256, mime_type, width, height, duration_ms, upload_status
) VALUES ($1, $2, $3, $4, $5, $6, $7, $5, $8, $9, $10, $11, 'COMPLETED')
ON CONFLICT (owner_id, dedupe_fingerprint, content_revision) DO NOTHING
RETURNING *;

-- name: AttachResourcesToMedia :execrows
UPDATE mineg.media_resources
SET media_id = $2, state = 'READY'
WHERE upload_session_id = $1 AND state = 'PENDING';

-- name: MarkUploadResourcesInvalid :exec
UPDATE mineg.media_resources SET state = 'INVALID'
WHERE upload_session_id = $1 AND state = 'PENDING';

-- name: LinkMediaToAlbum :exec
INSERT INTO mineg.media_album_links(media_id, album_id)
VALUES ($1, $2) ON CONFLICT DO NOTHING;

-- name: EnsureClientAlbum :one
INSERT INTO mineg.albums(owner_id, kind, client_album_id, device_installation_id, display_name)
VALUES ($1, 'CLIENT', $2, $3, $4)
ON CONFLICT (owner_id, device_installation_id, client_album_id) WHERE kind = 'CLIENT'
DO UPDATE SET display_name = EXCLUDED.display_name
RETURNING *;

-- name: LinkUploadSessionToClientAlbum :exec
INSERT INTO mineg.upload_session_client_albums(upload_session_id, album_id)
VALUES ($1, $2) ON CONFLICT DO NOTHING;

-- name: LinkUploadSessionAlbumsToMedia :exec
INSERT INTO mineg.media_album_links(media_id, album_id)
SELECT $2, album_id FROM mineg.upload_session_client_albums
WHERE upload_session_id = $1
ON CONFLICT DO NOTHING;

-- name: CompleteUploadSession :execrows
UPDATE mineg.upload_sessions
SET state = 'COMPLETED', media_id = $2, completed_at = $3, updated_at = $3
WHERE id = $1 AND state = 'VERIFYING';

-- name: ResetUploadPending :execrows
UPDATE mineg.upload_sessions
SET state = 'PENDING', updated_at = $2
WHERE id = $1 AND state = 'VERIFYING';

-- name: ListOwnerMedia :many
SELECT id, media_type, content_revision, captured_at, created_at
FROM mineg.media
WHERE owner_id = $1 AND upload_status = 'COMPLETED'
ORDER BY captured_at DESC, id DESC
LIMIT $2;

-- name: ListOwnerMediaAfter :many
SELECT id, media_type, content_revision, captured_at, created_at
FROM mineg.media
WHERE owner_id = $1
  AND upload_status = 'COMPLETED'
  AND (captured_at < $2 OR (captured_at = $2 AND id < $3))
ORDER BY captured_at DESC, id DESC
LIMIT $4;

-- name: BumpUploadGrantGeneration :one
UPDATE mineg.upload_sessions
SET grant_generation = grant_generation + 1, updated_at = $3
WHERE id = $1 AND owner_id = $2
RETURNING grant_generation;

-- name: ExpireUploadSessions :execrows
UPDATE mineg.upload_sessions
SET state = 'EXPIRED', updated_at = $1
WHERE state IN ('PENDING', 'VERIFYING') AND expires_at <= $1;

-- name: ExpireUploadSession :execrows
UPDATE mineg.upload_sessions
SET state = 'EXPIRED', updated_at = $3
WHERE id = $1 AND owner_id = $2 AND state IN ('PENDING', 'VERIFYING') AND expires_at <= $3;

-- name: ReviveExpiredUploadSession :execrows
UPDATE mineg.upload_sessions
SET state = 'PENDING', expires_at = $3, updated_at = $4
WHERE id = $1 AND owner_id = $2 AND state = 'EXPIRED';

-- name: ReviveUploadResource :execrows
UPDATE mineg.media_resources
SET multipart_upload_id = $3, media_id = NULL, state = 'PENDING'
WHERE id = $1 AND upload_session_id = $2 AND state = 'INVALID';

-- name: ResetUploadParts :exec
UPDATE mineg.upload_parts
SET etag = NULL, reported_size = NULL, reported_sha256 = NULL, state = 'PENDING', reported_at = NULL
WHERE upload_session_id = $1;
