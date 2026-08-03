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

-- name: IsFixedFamilyMember :one
SELECT EXISTS(
    SELECT 1 FROM mineg.family_memberships
    WHERE user_id = $1
) AS is_member;

-- name: AcquireShareIdempotencyLock :exec
SELECT pg_advisory_xact_lock(hashtextextended(
    sqlc.arg(owner_id)::text || ':' || sqlc.arg(idempotency_key)::text, 1
));

-- name: FindShareRequest :one
SELECT * FROM mineg.share_requests
WHERE owner_id = $1 AND idempotency_key = $2;

-- name: LockPrivateMediaForShare :one
SELECT media.id
FROM mineg.media AS media
JOIN mineg.family_memberships AS member ON member.user_id = media.owner_id
LEFT JOIN mineg.trash_records AS trash
    ON trash.media_id = media.id
   AND trash.restored_at IS NULL
   AND trash.purged_at IS NULL
WHERE media.id = $1
  AND media.owner_id = $2
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL
FOR UPDATE OF media;

-- name: FindShare :one
SELECT * FROM mineg.shares
WHERE media_id = $1 AND owner_id = $2;

-- name: ActivateShare :exec
INSERT INTO mineg.shares(media_id, owner_id, state, shared_at, unshared_at, updated_at)
VALUES ($1, $2, 'ACTIVE', $3, NULL, $3)
ON CONFLICT (media_id) DO UPDATE
SET state = 'ACTIVE',
    version = mineg.shares.version + 1,
    shared_at = EXCLUDED.shared_at,
    unshared_at = NULL,
    updated_at = EXCLUDED.updated_at;

-- name: InactivateShare :execrows
UPDATE mineg.shares
SET state = 'INACTIVE', version = version + 1, unshared_at = $3, updated_at = $3
WHERE media_id = $1 AND owner_id = $2 AND state = 'ACTIVE';

-- name: CreateShareRequest :exec
INSERT INTO mineg.share_requests(
    owner_id, idempotency_key, media_id, requested_state, request_hash, outcome, effective_at
) VALUES ($1, $2, $3, $4, $5, $6, $7);

-- name: ListFamilyMedia :many
SELECT media.id,
       media.owner_id,
       owner.nickname AS owner_nickname,
       media.media_type,
       media.captured_at,
       media.created_at,
       media.duration_ms,
       COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.family_memberships AS viewer
JOIN mineg.family_memberships AS household
  ON household.family_id = viewer.family_id
JOIN mineg.shares AS share
  ON share.owner_id = household.user_id AND share.state = 'ACTIVE'
JOIN mineg.media AS media
  ON media.id = share.media_id AND media.owner_id = share.owner_id
JOIN mineg.users AS owner ON owner.id = media.owner_id AND owner.status = 'APPROVED'
LEFT JOIN mineg.trash_records AS trash
  ON trash.media_id = media.id AND trash.restored_at IS NULL AND trash.purged_at IS NULL
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id
      AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE viewer.user_id = sqlc.arg(viewer_id)
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL
  AND (sqlc.arg(owner_only)::boolean = false OR media.owner_id = sqlc.arg(viewer_id))
ORDER BY media.captured_at DESC, media.id DESC
LIMIT sqlc.arg(page_limit);

-- name: ListFamilyMediaAfter :many
SELECT media.id,
       media.owner_id,
       owner.nickname AS owner_nickname,
       media.media_type,
       media.captured_at,
       media.created_at,
       media.duration_ms,
       COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.family_memberships AS viewer
JOIN mineg.family_memberships AS household
  ON household.family_id = viewer.family_id
JOIN mineg.shares AS share
  ON share.owner_id = household.user_id AND share.state = 'ACTIVE'
JOIN mineg.media AS media
  ON media.id = share.media_id AND media.owner_id = share.owner_id
JOIN mineg.users AS owner ON owner.id = media.owner_id AND owner.status = 'APPROVED'
LEFT JOIN mineg.trash_records AS trash
  ON trash.media_id = media.id AND trash.restored_at IS NULL AND trash.purged_at IS NULL
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id
      AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE viewer.user_id = sqlc.arg(viewer_id)
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL
  AND (sqlc.arg(owner_only)::boolean = false OR media.owner_id = sqlc.arg(viewer_id))
  AND (media.captured_at < sqlc.arg(after_captured_at)
       OR (media.captured_at = sqlc.arg(after_captured_at) AND media.id < sqlc.arg(after_media_id)))
ORDER BY media.captured_at DESC, media.id DESC
LIMIT sqlc.arg(page_limit);

-- name: FindFamilyMedia :one
SELECT media.id,
       media.owner_id,
       owner.nickname AS owner_nickname,
       media.media_type,
       media.captured_at,
       media.created_at,
       media.width,
       media.height,
       media.duration_ms,
       COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.family_memberships AS viewer
JOIN mineg.family_memberships AS household ON household.family_id = viewer.family_id
JOIN mineg.shares AS share ON share.owner_id = household.user_id AND share.state = 'ACTIVE'
JOIN mineg.media AS media ON media.id = share.media_id AND media.owner_id = share.owner_id
JOIN mineg.users AS owner ON owner.id = media.owner_id AND owner.status = 'APPROVED'
LEFT JOIN mineg.trash_records AS trash
  ON trash.media_id = media.id AND trash.restored_at IS NULL AND trash.purged_at IS NULL
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id
      AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE viewer.user_id = $1
  AND media.id = $2
  AND media.upload_status = 'COMPLETED'
  AND trash.media_id IS NULL;

-- name: ListFamilyMediaResources :many
SELECT resource.id, resource.resource_type, resource.mime_type, resource.content_size, resource.content_sha256
FROM mineg.family_memberships AS viewer
JOIN mineg.family_memberships AS household ON household.family_id = viewer.family_id
JOIN mineg.shares AS share ON share.owner_id = household.user_id AND share.state = 'ACTIVE'
JOIN mineg.media AS media ON media.id = share.media_id AND media.owner_id = share.owner_id
JOIN mineg.media_resources AS resource ON resource.media_id = media.id AND resource.state = 'READY'
LEFT JOIN mineg.trash_records AS trash
  ON trash.media_id = media.id AND trash.restored_at IS NULL AND trash.purged_at IS NULL
WHERE viewer.user_id = $1 AND media.id = $2
  AND media.upload_status = 'COMPLETED' AND trash.media_id IS NULL
ORDER BY CASE resource.resource_type
    WHEN 'ORIGINAL' THEN 1 WHEN 'LIVE_PHOTO_VIDEO' THEN 2 WHEN 'THUMBNAIL' THEN 3
    WHEN 'VIDEO_COVER' THEN 4 WHEN 'PREVIEW' THEN 5 ELSE 6 END, resource.id;

-- name: LockFamilyMediaForAccess :one
SELECT media.id, media.media_type, media.access_version
FROM mineg.family_memberships AS viewer
JOIN mineg.family_memberships AS household ON household.family_id = viewer.family_id
JOIN mineg.shares AS share ON share.owner_id = household.user_id AND share.state = 'ACTIVE'
JOIN mineg.media AS media ON media.id = share.media_id AND media.owner_id = share.owner_id
LEFT JOIN mineg.trash_records AS trash
  ON trash.media_id = media.id AND trash.restored_at IS NULL AND trash.purged_at IS NULL
WHERE viewer.user_id = $1 AND media.id = $2
  AND media.upload_status = 'COMPLETED' AND trash.media_id IS NULL
FOR UPDATE OF media, share;

-- name: ListFamilyMediaAccessResources :many
SELECT resource.id, resource.resource_type, resource.object_key, resource.mime_type,
       resource.content_size, resource.content_sha256
FROM mineg.media_resources AS resource
WHERE resource.media_id = $1 AND resource.state = 'READY'
ORDER BY CASE resource.resource_type
    WHEN 'ORIGINAL' THEN 1 WHEN 'LIVE_PHOTO_VIDEO' THEN 2 WHEN 'THUMBNAIL' THEN 3
    WHEN 'VIDEO_COVER' THEN 4 WHEN 'PREVIEW' THEN 5 ELSE 6 END, resource.id;

-- name: ListTrashMedia :many
SELECT media.id, media.media_type, media.captured_at, media.created_at, media.duration_ms,
       trash.trashed_at, COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.trash_records AS trash
JOIN mineg.media AS media ON media.id = trash.media_id AND media.owner_id = trash.owner_id
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE trash.owner_id = $1 AND trash.restored_at IS NULL AND trash.purged_at IS NULL
ORDER BY trash.trashed_at DESC, media.id DESC
LIMIT $2;

-- name: ListTrashMediaAfter :many
SELECT media.id, media.media_type, media.captured_at, media.created_at, media.duration_ms,
       trash.trashed_at, COALESCE(originals.original_total_size, 0)::bigint AS original_total_size
FROM mineg.trash_records AS trash
JOIN mineg.media AS media ON media.id = trash.media_id AND media.owner_id = trash.owner_id
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(resource.content_size), 0)::bigint AS original_total_size
    FROM mineg.media_resources AS resource
    WHERE resource.media_id = media.id AND resource.state = 'READY'
      AND resource.resource_type IN ('ORIGINAL', 'LIVE_PHOTO_VIDEO')
) AS originals ON TRUE
WHERE trash.owner_id = $1 AND trash.restored_at IS NULL AND trash.purged_at IS NULL
  AND (trash.trashed_at < $2 OR (trash.trashed_at = $2 AND media.id < $3))
ORDER BY trash.trashed_at DESC, media.id DESC
LIMIT $4;

-- name: AcquireRestoreIdempotencyLock :exec
SELECT pg_advisory_xact_lock(hashtextextended(
    sqlc.arg(owner_id)::text || ':' || sqlc.arg(idempotency_key)::text, 2
));

-- name: FindRestoreRequest :one
SELECT * FROM mineg.restore_requests
WHERE owner_id = $1 AND idempotency_key = $2;

-- name: LockTrashRecordForRestore :one
SELECT * FROM mineg.trash_records
WHERE media_id = $1 AND owner_id = $2 AND purged_at IS NULL
FOR UPDATE;

-- name: RestoreTrashRecord :execrows
UPDATE mineg.trash_records
SET restored_at = $3
WHERE media_id = $1 AND owner_id = $2 AND restored_at IS NULL AND purged_at IS NULL;

-- name: CreateRestoreRequest :exec
INSERT INTO mineg.restore_requests(
    owner_id, idempotency_key, media_id, request_hash, outcome, restored_at
) VALUES ($1, $2, $3, $4, $5, $6);

-- name: AcquireFeedbackIdempotencyLock :exec
SELECT pg_advisory_xact_lock(hashtextextended(
    sqlc.arg(user_id)::text || ':' || sqlc.arg(idempotency_key)::text, 3
));

-- name: FindFeedbackRequest :one
SELECT request.user_id, request.idempotency_key, request.request_hash,
       feedback.id AS feedback_id, feedback.created_at
FROM mineg.feedback_requests AS request
JOIN mineg.feedback AS feedback ON feedback.id = request.feedback_id
WHERE request.user_id = $1 AND request.idempotency_key = $2;

-- name: CreateFeedback :one
INSERT INTO mineg.feedback(
    user_id, category, description, contact, app_version, platform, os_version,
    device_installation_id, created_at
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
RETURNING id, created_at;

-- name: CreateFeedbackRequest :exec
INSERT INTO mineg.feedback_requests(user_id, idempotency_key, request_hash, feedback_id)
VALUES ($1, $2, $3, $4);
