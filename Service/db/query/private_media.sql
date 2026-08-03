-- name: ListPrivateMedia :many
SELECT media.id,
       media.media_type,
       media.captured_at,
       media.created_at,
       media.duration_ms,
       COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.media AS media
LEFT JOIN mineg.trash_records AS trash
    ON trash.media_id = media.id
   AND trash.restored_at IS NULL
   AND trash.purged_at IS NULL
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id
      AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE media.owner_id = $1
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL
ORDER BY media.captured_at DESC, media.id DESC
LIMIT $2;

-- name: ListPrivateMediaAfter :many
SELECT media.id,
       media.media_type,
       media.captured_at,
       media.created_at,
       media.duration_ms,
       COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.media AS media
LEFT JOIN mineg.trash_records AS trash
    ON trash.media_id = media.id
   AND trash.restored_at IS NULL
   AND trash.purged_at IS NULL
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id
      AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE media.owner_id = $1
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL
  AND (media.captured_at < $2 OR (media.captured_at = $2 AND media.id < $3))
ORDER BY media.captured_at DESC, media.id DESC
LIMIT $4;

-- name: FindPrivateMedia :one
SELECT media.id,
       media.media_type,
       media.captured_at,
       media.created_at,
       media.width,
       media.height,
       media.duration_ms,
       COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.media AS media
LEFT JOIN mineg.trash_records AS trash
    ON trash.media_id = media.id
   AND trash.restored_at IS NULL
   AND trash.purged_at IS NULL
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id
      AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE media.id = $1
  AND media.owner_id = $2
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL;

-- name: ListPrivateMediaResources :many
SELECT resource.id, resource.resource_type, resource.mime_type, resource.content_size, resource.content_sha256
FROM mineg.media_resources AS resource
JOIN mineg.media AS media ON media.id = resource.media_id
LEFT JOIN mineg.trash_records AS trash
    ON trash.media_id = media.id
   AND trash.restored_at IS NULL
   AND trash.purged_at IS NULL
WHERE resource.media_id = $1
  AND media.owner_id = $2
  AND media.upload_status = 'COMPLETED'
  AND resource.state = 'READY'
  AND trash.media_id IS NULL
ORDER BY CASE resource.resource_type
    WHEN 'ORIGINAL' THEN 1
    WHEN 'LIVE_PHOTO_VIDEO' THEN 2
    WHEN 'THUMBNAIL' THEN 3
    WHEN 'VIDEO_COVER' THEN 4
    WHEN 'PREVIEW' THEN 5
    ELSE 6
END, resource.id;

-- name: LockPrivateMediaForAccess :one
SELECT media.id, media.media_type, media.access_version
FROM mineg.media AS media
LEFT JOIN mineg.trash_records AS trash
    ON trash.media_id = media.id
   AND trash.restored_at IS NULL
   AND trash.purged_at IS NULL
WHERE media.id = $1
  AND media.owner_id = $2
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL
FOR UPDATE OF media;

-- name: ListPrivateMediaAccessResources :many
SELECT resource.id, resource.resource_type, resource.object_key, resource.mime_type, resource.content_size, resource.content_sha256
FROM mineg.media_resources AS resource
WHERE resource.media_id = $1
  AND resource.state = 'READY'
ORDER BY CASE resource.resource_type
    WHEN 'ORIGINAL' THEN 1
    WHEN 'LIVE_PHOTO_VIDEO' THEN 2
    WHEN 'THUMBNAIL' THEN 3
    WHEN 'VIDEO_COVER' THEN 4
    WHEN 'PREVIEW' THEN 5
    ELSE 6
END, resource.id;

-- name: AcquireTrashIdempotencyLock :exec
SELECT pg_advisory_xact_lock(hashtextextended(sqlc.arg(owner_id)::text || ':' || sqlc.arg(idempotency_key)::text, 0));

-- name: FindTrashRequest :one
SELECT * FROM mineg.trash_requests
WHERE owner_id = $1 AND idempotency_key = $2;

-- name: LockPrivateMediaForTrash :one
SELECT id
FROM mineg.media
WHERE id = $1
  AND owner_id = $2
  AND upload_status = 'COMPLETED'
FOR UPDATE;

-- name: FindActiveTrashRecord :one
SELECT * FROM mineg.trash_records
WHERE media_id = $1
  AND owner_id = $2
  AND restored_at IS NULL
  AND purged_at IS NULL;

-- name: CreateTrashRecord :exec
INSERT INTO mineg.trash_records(media_id, owner_id, trashed_at)
VALUES ($1, $2, $3);

-- name: DeactivateActiveShare :execrows
UPDATE mineg.shares
SET state = 'INACTIVE', version = version + 1, unshared_at = $2, updated_at = $2
WHERE media_id = $1 AND state = 'ACTIVE';

-- name: BumpPrivateMediaAccessVersion :exec
UPDATE mineg.media
SET access_version = access_version + 1
WHERE id = $1;

-- name: CreateTrashRequest :exec
INSERT INTO mineg.trash_requests(
    owner_id, idempotency_key, media_id, request_hash, outcome, trashed_at
) VALUES ($1, $2, $3, $4, $5, $6);
