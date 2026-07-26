-- name: CountAdminUsers :one
SELECT count(*) FROM mineg.admin_users;

-- name: CreateAdminUser :one
INSERT INTO mineg.admin_users (username, password_hash)
VALUES ($1, $2)
RETURNING id, username, password_hash, created_at, disabled_at;

-- name: FindAdminByUsername :one
SELECT id, username, password_hash, created_at, disabled_at
FROM mineg.admin_users
WHERE username = $1;

-- name: CreateAdminSession :one
INSERT INTO mineg.admin_sessions (
    admin_user_id, session_token_hash, csrf_token_hash, absolute_expires_at
) VALUES ($1, $2, $3, $4)
RETURNING id, admin_user_id, created_at, last_active_at, absolute_expires_at;

-- name: FindAdminSessionForUpdate :one
SELECT sessions.id, sessions.admin_user_id, sessions.csrf_token_hash,
       sessions.created_at, sessions.last_active_at, sessions.absolute_expires_at,
       sessions.revoked_at, admins.username, admins.disabled_at
FROM mineg.admin_sessions sessions
JOIN mineg.admin_users admins ON admins.id = sessions.admin_user_id
WHERE sessions.session_token_hash = $1
FOR UPDATE OF sessions;

-- name: TouchAdminSession :exec
UPDATE mineg.admin_sessions SET last_active_at = $2 WHERE id = $1;

-- name: RotateAdminCSRF :exec
UPDATE mineg.admin_sessions SET csrf_token_hash = $2, last_active_at = $3 WHERE id = $1;

-- name: RevokeAdminSession :exec
UPDATE mineg.admin_sessions SET revoked_at = COALESCE(revoked_at, $2) WHERE id = $1;

-- name: CreateUser :one
INSERT INTO mineg.users (phone_e164, password_hash, nickname)
VALUES ($1, $2, $3)
RETURNING id, phone_e164, password_hash, status, nickname, avatar_url,
          reviewed_at, reviewed_by, created_at, updated_at;

-- name: CreateUserKeyBundle :exec
INSERT INTO mineg.user_key_bundles (
    user_id, public_key, encrypted_key_bundle, kdf_parameters, bundle_version
) VALUES ($1, $2, $3, $4, $5);

-- name: FindUserByPhone :one
SELECT id, phone_e164, password_hash, status, nickname, avatar_url,
       reviewed_at, reviewed_by, created_at, updated_at
FROM mineg.users
WHERE phone_e164 = $1;

-- name: FindRegistrationRequest :one
SELECT device_installation_id, idempotency_key, request_hash, user_id, rotation_family_id, created_at
FROM mineg.registration_requests
WHERE device_installation_id = $1 AND idempotency_key = $2;

-- name: AcquireRegistrationLock :exec
SELECT pg_advisory_xact_lock(
    hashtextextended(sqlc.arg(device_installation_id)::text || ':' || sqlc.arg(idempotency_key)::text, 0)
);

-- name: CreateRegistrationRequest :exec
INSERT INTO mineg.registration_requests (
    device_installation_id, idempotency_key, request_hash, user_id, rotation_family_id
) VALUES ($1, $2, $3, $4, $5);

-- name: UpdateRegistrationFamily :exec
UPDATE mineg.registration_requests
SET rotation_family_id = $3
WHERE device_installation_id = $1 AND idempotency_key = $2;

-- name: FindUserByID :one
SELECT id, phone_e164, password_hash, status, nickname, avatar_url,
       reviewed_at, reviewed_by, created_at, updated_at, avatar_upload_id, profile_version
FROM mineg.users
WHERE id = $1;

-- name: UpsertDevice :one
INSERT INTO mineg.devices (user_id, installation_id, platform)
VALUES ($1, $2, $3)
ON CONFLICT (user_id, installation_id) DO UPDATE
SET platform = EXCLUDED.platform, last_active_at = CURRENT_TIMESTAMP
RETURNING id, user_id, installation_id, platform, last_active_at, created_at;

-- name: RecordAgreement :exec
INSERT INTO mineg.user_agreements (
    user_id, terms_version, privacy_version, device_installation_id
) VALUES ($1, $2, $3, $4);

-- name: CreateUserSession :one
INSERT INTO mineg.user_sessions (
    user_id, device_id, rotation_family_id, access_token_hash, refresh_token_hash,
    access_expires_at, refresh_expires_at
) VALUES ($1, $2, $3, $4, $5, $6, $7)
RETURNING id, user_id, device_id, rotation_family_id, access_expires_at,
          refresh_expires_at, rotated_at, revoked_at, created_at, last_active_at;

-- name: FindUserSessionByAccess :one
SELECT sessions.id, sessions.user_id, sessions.device_id, sessions.rotation_family_id,
       sessions.access_expires_at, sessions.refresh_expires_at, sessions.rotated_at,
       sessions.revoked_at, sessions.created_at, sessions.last_active_at, users.status
FROM mineg.user_sessions sessions
JOIN mineg.users users ON users.id = sessions.user_id
WHERE sessions.access_token_hash = $1;

-- name: FindUserSessionByRefreshForUpdate :one
SELECT sessions.id, sessions.user_id, sessions.device_id, sessions.rotation_family_id,
       sessions.access_expires_at, sessions.refresh_expires_at, sessions.rotated_at,
       sessions.revoked_at, sessions.created_at, sessions.last_active_at, users.status
FROM mineg.user_sessions sessions
JOIN mineg.users users ON users.id = sessions.user_id
WHERE sessions.refresh_token_hash = $1
FOR UPDATE OF sessions;

-- name: MarkUserSessionRotated :exec
UPDATE mineg.user_sessions SET rotated_at = $2, last_active_at = $2 WHERE id = $1;

-- name: RevokeUserSession :exec
UPDATE mineg.user_sessions SET revoked_at = COALESCE(revoked_at, $2) WHERE id = $1;

-- name: RevokeUserSessionFamily :exec
UPDATE mineg.user_sessions
SET revoked_at = COALESCE(revoked_at, $2)
WHERE rotation_family_id = $1;

-- name: ListPendingApprovals :many
SELECT id, phone_e164, created_at
FROM mineg.users
WHERE reviewed_at IS NULL
  AND ($1::timestamptz IS NULL OR (created_at, id) > ($1::timestamptz, $2::uuid))
ORDER BY created_at ASC, id ASC
LIMIT $3;

-- name: FindApproval :one
SELECT id, phone_e164, status, reviewed_at, created_at
FROM mineg.users
WHERE id = $1;

-- name: FindApprovalRequest :one
SELECT admin_user_id, idempotency_key, user_id, created_at
FROM mineg.approval_requests
WHERE admin_user_id = $1 AND idempotency_key = $2;

-- name: CreateApprovalRequest :execrows
INSERT INTO mineg.approval_requests (admin_user_id, idempotency_key, user_id)
VALUES ($1, $2, $3)
ON CONFLICT (admin_user_id, idempotency_key) DO NOTHING;

-- name: MarkUserReviewed :execrows
UPDATE mineg.users
SET reviewed_at = COALESCE(reviewed_at, $2), reviewed_by = COALESCE(reviewed_by, $3), updated_at = $2
WHERE id = $1 AND reviewed_at IS NULL;

-- name: CreateKeyGrantTask :one
INSERT INTO mineg.key_grant_tasks (user_id)
VALUES ($1)
ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id
RETURNING id, user_id, state, created_at, completed_at;

-- name: GetFixedFamily :one
SELECT id, singleton, created_at
FROM mineg.families
WHERE singleton = true;

-- name: LockFixedFamily :one
SELECT id
FROM mineg.families
WHERE singleton = true
FOR UPDATE;

-- name: ListPendingKeyGrants :many
SELECT task.id, task.user_id, task.family_id, task.state, task.created_at,
       bundle.public_key, bundle.bundle_version,
       NOT EXISTS (SELECT 1 FROM mineg.family_key_envelopes any_envelope) AS bootstrap
FROM mineg.key_grant_tasks task
JOIN mineg.users target ON target.id = task.user_id
JOIN mineg.user_key_bundles bundle ON bundle.user_id = task.user_id
WHERE task.state = 'PENDING'
  AND target.reviewed_at IS NOT NULL
  AND (
      (task.user_id = $1 AND NOT EXISTS (SELECT 1 FROM mineg.family_key_envelopes))
      OR
      (task.user_id <> $1 AND EXISTS (
          SELECT 1 FROM mineg.family_key_envelopes actor_envelope
          WHERE actor_envelope.user_id = $1
      ))
  )
ORDER BY task.created_at, task.id
LIMIT $2;

-- name: CountEligiblePendingKeyGrants :one
SELECT count(*)
FROM mineg.key_grant_tasks task
JOIN mineg.users target ON target.id = task.user_id
WHERE task.state = 'PENDING'
  AND target.reviewed_at IS NOT NULL
  AND (
      (task.user_id = $1 AND NOT EXISTS (SELECT 1 FROM mineg.family_key_envelopes))
      OR
      (task.user_id <> $1 AND EXISTS (
          SELECT 1 FROM mineg.family_key_envelopes actor_envelope
          WHERE actor_envelope.user_id = $1
      ))
  );

-- name: FindKeyGrantForUpdate :one
SELECT task.id, task.user_id, task.family_id, task.state, task.completed_by,
       task.created_at, task.completed_at, task.attempt_count, task.updated_at,
       target.reviewed_at, target.status, bundle.public_key, bundle.bundle_version
FROM mineg.key_grant_tasks task
JOIN mineg.users target ON target.id = task.user_id
JOIN mineg.user_key_bundles bundle ON bundle.user_id = task.user_id
WHERE task.id = $1
FOR UPDATE OF task, target;

-- name: CountFamilyKeyEnvelopes :one
SELECT count(*) FROM mineg.family_key_envelopes;

-- name: FindFamilyKeyEnvelope :one
SELECT family_id, user_id, created_by, recipient_public_key_hash,
       encrypted_envelope, algorithm, envelope_version, created_at
FROM mineg.family_key_envelopes
WHERE user_id = $1;

-- name: CreateFamilyKeyEnvelope :exec
INSERT INTO mineg.family_key_envelopes(
    family_id, user_id, created_by, recipient_public_key_hash,
    encrypted_envelope, algorithm, envelope_version
) VALUES ($1, $2, $3, $4, $5, 'X25519_SEALED_BOX', $6);

-- name: CompleteKeyGrantTask :execrows
UPDATE mineg.key_grant_tasks
SET state = 'READY', completed_by = $2, completed_at = $3,
    attempt_count = attempt_count + 1, updated_at = $3
WHERE id = $1 AND state = 'PENDING';

-- name: ApproveEnvelopeReadyUser :execrows
UPDATE mineg.users
SET status = 'APPROVED', updated_at = $2
WHERE id = $1 AND status = 'PENDING';

-- name: GetUserKeyMaterial :one
SELECT bundle.user_id, bundle.public_key, bundle.encrypted_key_bundle,
       bundle.kdf_parameters, bundle.bundle_version, bundle.updated_at,
       envelope.encrypted_envelope AS family_envelope,
       envelope.algorithm AS family_envelope_algorithm,
       envelope.envelope_version AS family_envelope_version
FROM mineg.user_key_bundles bundle
LEFT JOIN mineg.family_key_envelopes envelope ON envelope.user_id = bundle.user_id
WHERE bundle.user_id = $1;

-- name: UpdateUserKeyBundle :execrows
UPDATE mineg.user_key_bundles
SET encrypted_key_bundle = $2, kdf_parameters = $3,
    bundle_version = $4, updated_at = $5
WHERE user_id = $1 AND public_key = $6 AND bundle_version < $4;

-- name: UpdateUserNickname :one
UPDATE mineg.users
SET nickname = $2, profile_version = profile_version + 1, updated_at = $3
WHERE id = $1 AND status = 'APPROVED'
RETURNING id, phone_e164, password_hash, status, nickname, avatar_url,
          reviewed_at, reviewed_by, created_at, updated_at, avatar_upload_id, profile_version;

-- name: CreateAvatarUpload :one
INSERT INTO mineg.avatar_uploads(
    id, user_id, idempotency_key, object_key, content_type, source_size,
    display_size, width, height, content_sha256, expires_at
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
ON CONFLICT (user_id, idempotency_key) DO UPDATE SET user_id = EXCLUDED.user_id
RETURNING *;

-- name: FindAvatarUploadForUpdate :one
SELECT * FROM mineg.avatar_uploads
WHERE id = $1 AND user_id = $2
FOR UPDATE;

-- name: CompleteAvatarUpload :execrows
UPDATE mineg.avatar_uploads
SET state = 'READY', completed_at = $3
WHERE id = $1 AND user_id = $2 AND state = 'PENDING' AND expires_at > $3;

-- name: SetUserAvatar :execrows
UPDATE mineg.users
SET avatar_upload_id = $2, avatar_url = NULL,
    profile_version = profile_version + 1, updated_at = $3
WHERE id = $1 AND status = 'APPROVED';

-- name: GetReadyAvatar :one
SELECT upload.id, upload.user_id, upload.object_key, upload.content_type,
       upload.display_size, upload.content_sha256, upload.completed_at
FROM mineg.users user_account
JOIN mineg.avatar_uploads upload ON upload.id = user_account.avatar_upload_id
WHERE user_account.id = $1 AND upload.state = 'READY';

-- name: RecordAuditEvent :exec
INSERT INTO mineg.audit_events (
    actor_type, actor_id, action, target_type, target_id, result, request_id, metadata
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8);
