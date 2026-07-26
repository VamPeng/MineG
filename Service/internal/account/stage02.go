package account

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/vampeng/mineg/service/internal/platform/database/dbgen"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

var nicknamePattern = regexp.MustCompile(`^[\p{Han}\p{L}\p{N} _-]+$`)

type KeyMaterial struct {
	PublicKey               string          `json:"public_key"`
	EncryptedKeyBundle      string          `json:"encrypted_key_bundle"`
	KDFParameters           json.RawMessage `json:"kdf_parameters"`
	BundleVersion           int32           `json:"bundle_version"`
	FamilyEnvelope          string          `json:"family_envelope,omitempty"`
	FamilyEnvelopeAlgorithm string          `json:"family_envelope_algorithm,omitempty"`
	FamilyEnvelopeVersion   int32           `json:"family_envelope_version,omitempty"`
	UpdatedAt               time.Time       `json:"updated_at"`
}

func (s *Service) GetKeyMaterial(ctx context.Context, session UserSession) (KeyMaterial, error) {
	row, err := dbgen.New(s.pool).GetUserKeyMaterial(ctx, session.RawUserID)
	if errors.Is(err, pgx.ErrNoRows) {
		return KeyMaterial{}, &Error{Code: "KEY_BUNDLE_NOT_FOUND", Status: 404, Title: "Key bundle not found", Detail: "The current user key bundle does not exist."}
	}
	if err != nil {
		return KeyMaterial{}, internalError()
	}
	result := KeyMaterial{
		PublicKey:          base64.RawStdEncoding.EncodeToString(row.PublicKey),
		EncryptedKeyBundle: base64.RawStdEncoding.EncodeToString(row.EncryptedKeyBundle),
		KDFParameters:      json.RawMessage(row.KdfParameters), BundleVersion: row.BundleVersion,
		UpdatedAt: row.UpdatedAt.Time,
	}
	if len(row.FamilyEnvelope) > 0 {
		result.FamilyEnvelope = base64.RawStdEncoding.EncodeToString(row.FamilyEnvelope)
		result.FamilyEnvelopeAlgorithm = row.FamilyEnvelopeAlgorithm.String
		result.FamilyEnvelopeVersion = row.FamilyEnvelopeVersion.Int32
	}
	return result, nil
}

type UpdateKeyBundleInput struct {
	PublicKey       []byte
	EncryptedBundle []byte
	KDFParameters   json.RawMessage
	BundleVersion   int32
	RequestID       string
}

func (s *Service) UpdateKeyBundle(ctx context.Context, session UserSession, input UpdateKeyBundleInput) (KeyMaterial, error) {
	if len(input.PublicKey) != 32 || len(input.EncryptedBundle) < 48 || len(input.EncryptedBundle) > 1024*1024 ||
		!validJSONObject(input.KDFParameters) || input.BundleVersion <= 0 {
		return KeyMaterial{}, validationError("KEY_BUNDLE_INVALID", "Invalid key bundle", "The key bundle fields are invalid.")
	}
	current, err := dbgen.New(s.pool).GetUserKeyMaterial(ctx, session.RawUserID)
	if err != nil {
		return KeyMaterial{}, internalError()
	}
	if !bytes.Equal(current.PublicKey, input.PublicKey) {
		return KeyMaterial{}, conflictError("PUBLIC_KEY_IMMUTABLE", "Public key is immutable", "The public key cannot change after registration.")
	}
	if input.BundleVersion < current.BundleVersion {
		return KeyMaterial{}, conflictError("KEY_BUNDLE_VERSION_STALE", "Key bundle version is stale", "The key bundle version cannot move backwards.")
	}
	if input.BundleVersion == current.BundleVersion {
		if bytes.Equal(current.EncryptedKeyBundle, input.EncryptedBundle) && equalJSON(current.KdfParameters, input.KDFParameters) {
			return s.GetKeyMaterial(ctx, session)
		}
		return KeyMaterial{}, conflictError("KEY_BUNDLE_VERSION_STALE", "Key bundle version is stale", "Changing an encrypted key bundle requires a newer bundle version.")
	}
	updated, err := dbgen.New(s.pool).UpdateUserKeyBundle(ctx, dbgen.UpdateUserKeyBundleParams{
		UserID: session.RawUserID, EncryptedKeyBundle: input.EncryptedBundle,
		KdfParameters: input.KDFParameters, BundleVersion: input.BundleVersion,
		UpdatedAt: pgTime(s.now()), PublicKey: input.PublicKey,
	})
	if err != nil {
		return KeyMaterial{}, internalError()
	}
	if updated == 0 {
		return KeyMaterial{}, conflictError("KEY_BUNDLE_VERSION_STALE", "Key bundle version is stale", "The key bundle could not be updated.")
	}
	_ = recordAudit(ctx, dbgen.New(s.pool), "USER", session.RawUserID, "KEY_BUNDLE_UPDATE", "USER_KEY_BUNDLE", session.RawUserID, "SUCCESS", input.RequestID)
	return s.GetKeyMaterial(ctx, session)
}

type PendingKeyGrant struct {
	ID                 string    `json:"id"`
	UserID             string    `json:"user_id"`
	FamilyID           string    `json:"family_id"`
	Kind               string    `json:"kind"`
	RecipientPublicKey string    `json:"recipient_public_key"`
	BundleVersion      int32     `json:"bundle_version"`
	CreatedAt          time.Time `json:"created_at"`
}

type PendingKeyGrantPage struct {
	Items []PendingKeyGrant `json:"items"`
}

func (s *Service) ListPendingKeyGrants(ctx context.Context, session UserSession, limit int) (PendingKeyGrantPage, error) {
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	queries := dbgen.New(s.pool)
	rows, err := queries.ListPendingKeyGrants(ctx, dbgen.ListPendingKeyGrantsParams{UserID: session.RawUserID, Limit: int32(limit)})
	if err != nil {
		return PendingKeyGrantPage{}, internalError()
	}
	if backlog, countErr := queries.CountEligiblePendingKeyGrants(ctx, session.RawUserID); countErr == nil {
		recordKeyGrantBacklog(ctx, backlog)
	}
	page := PendingKeyGrantPage{Items: make([]PendingKeyGrant, 0, len(rows))}
	for _, row := range rows {
		kind := "MEMBER_GRANT"
		if row.Bootstrap {
			kind = "FAMILY_BOOTSTRAP"
		}
		page.Items = append(page.Items, PendingKeyGrant{
			ID: uuidString(row.ID), UserID: uuidString(row.UserID), FamilyID: uuidString(row.FamilyID),
			Kind: kind, RecipientPublicKey: base64.RawStdEncoding.EncodeToString(row.PublicKey),
			BundleVersion: row.BundleVersion, CreatedAt: row.CreatedAt.Time,
		})
	}
	return page, nil
}

type CompleteKeyGrantInput struct {
	RecipientPublicKey []byte
	EncryptedEnvelope  []byte
	Algorithm          string
	EnvelopeVersion    int32
	RequestID          string
}

type CompleteKeyGrantResult struct {
	GrantID string `json:"grant_id"`
	UserID  string `json:"user_id"`
	Outcome string `json:"outcome"`
	Status  string `json:"status"`
}

func (s *Service) CompleteKeyGrant(ctx context.Context, session UserSession, grantID string, input CompleteKeyGrantInput) (result CompleteKeyGrantResult, resultErr error) {
	started := time.Now()
	var reviewedAt time.Time
	defer func() {
		outcome := result.Outcome
		if resultErr != nil {
			outcome = "FAILED"
			var accountErr *Error
			if errors.As(resultErr, &accountErr) {
				outcome = accountErr.Code
			}
		}
		if outcome == "" {
			outcome = "FAILED"
		}
		recordKeyGrantCompletion(ctx, started, reviewedAt, outcome)
	}()
	parsedGrantID, err := parsePGUUID(grantID)
	if err != nil {
		return CompleteKeyGrantResult{}, validationError("KEY_GRANT_ID_INVALID", "Invalid key grant", "The key grant identifier is invalid.")
	}
	if len(input.RecipientPublicKey) != 32 || len(input.EncryptedEnvelope) != 80 ||
		input.Algorithm != "X25519_SEALED_BOX" || input.EnvelopeVersion <= 0 {
		return CompleteKeyGrantResult{}, validationError("KEY_ENVELOPE_INVALID", "Invalid key envelope", "The family key envelope metadata is invalid.")
	}
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		familyID, err := q.LockFixedFamily(ctx)
		if err != nil {
			return err
		}
		grant, err := q.FindKeyGrantForUpdate(ctx, parsedGrantID)
		if errors.Is(err, pgx.ErrNoRows) {
			return &Error{Code: "KEY_GRANT_NOT_FOUND", Status: 404, Title: "Key grant not found", Detail: "The key grant task does not exist."}
		}
		if err != nil {
			return err
		}
		if grant.ReviewedAt.Valid {
			reviewedAt = grant.ReviewedAt.Time
		}
		if !bytes.Equal(grant.PublicKey, input.RecipientPublicKey) {
			return conflictError("KEY_GRANT_RECIPIENT_MISMATCH", "Key grant recipient mismatch", "The recipient public key no longer matches the grant target.")
		}
		existing, existingErr := q.FindFamilyKeyEnvelope(ctx, grant.UserID)
		if grant.State == "READY" || existingErr == nil {
			if existingErr != nil {
				return conflictError("KEY_GRANT_STATE_INVALID", "Key grant state invalid", "The completed grant has no matching envelope.")
			}
			if !bytes.Equal(existing.EncryptedEnvelope, input.EncryptedEnvelope) ||
				!bytes.Equal(existing.RecipientPublicKeyHash, publicKeyHash(input.RecipientPublicKey)) ||
				existing.EnvelopeVersion != input.EnvelopeVersion {
				return conflictError("KEY_GRANT_ALREADY_COMPLETED", "Key grant already completed", "The grant was completed with different envelope material.")
			}
			result = CompleteKeyGrantResult{GrantID: grantID, UserID: uuidString(grant.UserID), Outcome: "ALREADY_COMPLETED", Status: "APPROVED"}
			return nil
		}
		if !errors.Is(existingErr, pgx.ErrNoRows) {
			return existingErr
		}
		envelopeCount, err := q.CountFamilyKeyEnvelopes(ctx)
		if err != nil {
			return err
		}
		if envelopeCount == 0 {
			if grant.UserID != session.RawUserID {
				return &Error{Code: "KEY_GRANT_FORBIDDEN", Status: 403, Title: "Key grant forbidden", Detail: "Only the first reviewed member can bootstrap the family key for itself."}
			}
		} else {
			if grant.UserID == session.RawUserID || session.Status != "APPROVED" {
				return &Error{Code: "KEY_GRANT_FORBIDDEN", Status: 403, Title: "Key grant forbidden", Detail: "An approved existing member must complete this key grant."}
			}
			if _, err := q.FindFamilyKeyEnvelope(ctx, session.RawUserID); err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					return &Error{Code: "KEY_GRANT_FORBIDDEN", Status: 403, Title: "Key grant forbidden", Detail: "The current member has no family key envelope."}
				}
				return err
			}
		}
		if !grant.ReviewedAt.Valid {
			return &Error{Code: "KEY_GRANT_FORBIDDEN", Status: 403, Title: "Key grant forbidden", Detail: "The target account has not been reviewed."}
		}
		if err := q.CreateFamilyKeyEnvelope(ctx, dbgen.CreateFamilyKeyEnvelopeParams{
			FamilyID: familyID, UserID: grant.UserID, CreatedBy: session.RawUserID,
			RecipientPublicKeyHash: publicKeyHash(input.RecipientPublicKey), EncryptedEnvelope: input.EncryptedEnvelope,
			EnvelopeVersion: input.EnvelopeVersion,
		}); err != nil {
			return err
		}
		now := pgTime(s.now())
		updated, err := q.CompleteKeyGrantTask(ctx, dbgen.CompleteKeyGrantTaskParams{ID: grant.ID, CompletedBy: session.RawUserID, CompletedAt: now})
		if err != nil || updated != 1 {
			if err != nil {
				return err
			}
			return conflictError("KEY_GRANT_STATE_INVALID", "Key grant state invalid", "The key grant changed concurrently.")
		}
		approved, err := q.ApproveEnvelopeReadyUser(ctx, dbgen.ApproveEnvelopeReadyUserParams{ID: grant.UserID, UpdatedAt: now})
		if err != nil || approved != 1 {
			if err != nil {
				return err
			}
			return conflictError("KEY_GRANT_STATE_INVALID", "Key grant state invalid", "The target account could not be approved.")
		}
		if err := recordAudit(ctx, q, "USER", session.RawUserID, "FAMILY_KEY_GRANT_COMPLETE", "USER", grant.UserID, "SUCCESS", input.RequestID); err != nil {
			return err
		}
		result = CompleteKeyGrantResult{GrantID: grantID, UserID: uuidString(grant.UserID), Outcome: "COMPLETED", Status: "APPROVED"}
		return nil
	})
	if err != nil {
		resultErr = normalizeDatabaseError(err)
		_ = recordAudit(ctx, dbgen.New(s.pool), "USER", session.RawUserID, "FAMILY_KEY_GRANT_COMPLETE", "KEY_GRANT_TASK", parsedGrantID, "FAILURE", input.RequestID)
		return CompleteKeyGrantResult{}, resultErr
	}
	return result, nil
}

func (s *Service) UpdateProfile(ctx context.Context, session UserSession, nickname, requestID string) (Profile, error) {
	if session.Status != "APPROVED" {
		return Profile{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting approval and key access."}
	}
	nickname = strings.TrimSpace(nickname)
	if count := utf8.RuneCountInString(nickname); count < 2 || count > 20 || !nicknamePattern.MatchString(nickname) {
		return Profile{}, validationError("NICKNAME_INVALID", "Invalid nickname", "Nickname must contain 2 to 20 supported characters.")
	}
	if _, err := dbgen.New(s.pool).UpdateUserNickname(ctx, dbgen.UpdateUserNicknameParams{ID: session.RawUserID, Nickname: nickname, UpdatedAt: pgTime(s.now())}); err != nil {
		return Profile{}, internalError()
	}
	_ = recordAudit(ctx, dbgen.New(s.pool), "USER", session.RawUserID, "PROFILE_UPDATE", "USER", session.RawUserID, "SUCCESS", requestID)
	return s.GetProfile(ctx, session)
}

type CreateAvatarUploadInput struct {
	IdempotencyKey string
	ContentType    string
	SourceSize     int64
	DisplaySize    int64
	Width          int32
	Height         int32
	ContentSHA256  []byte
}

type AvatarUploadResult struct {
	UploadID string                  `json:"upload_id"`
	Grant    objectstore.ObjectGrant `json:"grant"`
}

func (s *Service) CreateAvatarUpload(ctx context.Context, session UserSession, input CreateAvatarUploadInput) (AvatarUploadResult, error) {
	if session.Status != "APPROVED" {
		return AvatarUploadResult{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting approval and key access."}
	}
	if !idempotencyPattern.MatchString(input.IdempotencyKey) || input.SourceSize < 1 || input.SourceSize > 10*1024*1024 ||
		input.DisplaySize < 1 || input.DisplaySize > 10*1024*1024 || input.Width < 1 || input.Width > 1024 ||
		input.Height != input.Width || len(input.ContentSHA256) != sha256.Size || !validAvatarContentType(input.ContentType) {
		return AvatarUploadResult{}, validationError("AVATAR_INVALID", "Invalid avatar", "The avatar metadata, dimensions, size, or format is invalid.")
	}
	uploadID := newPGUUID()
	objectKey := fmt.Sprintf("avatars/%s/%s/avatar%s", session.UserID, uuidString(uploadID), avatarExtension(input.ContentType))
	metadata := objectstore.ProfileObjectMetadata{Key: objectKey, Size: input.DisplaySize, ContentType: input.ContentType, SHA256: input.ContentSHA256}
	grant, err := s.profileObjects.IssueAvatarUpload(ctx, metadata, 10*time.Minute)
	if err != nil {
		return AvatarUploadResult{}, objectStoreError(err)
	}
	row, err := dbgen.New(s.pool).CreateAvatarUpload(ctx, dbgen.CreateAvatarUploadParams{
		ID: uploadID, UserID: session.RawUserID, IdempotencyKey: input.IdempotencyKey,
		ObjectKey: objectKey, ContentType: input.ContentType, SourceSize: input.SourceSize,
		DisplaySize: input.DisplaySize, Width: input.Width, Height: input.Height,
		ContentSha256: input.ContentSHA256, ExpiresAt: pgTime(grant.ExpiresAt),
	})
	if err != nil {
		return AvatarUploadResult{}, internalError()
	}
	if row.ContentType != input.ContentType || row.SourceSize != input.SourceSize || row.DisplaySize != input.DisplaySize ||
		row.Width != input.Width || row.Height != input.Height || !bytes.Equal(row.ContentSha256, input.ContentSHA256) {
		return AvatarUploadResult{}, conflictError("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The idempotency key was already used for another avatar upload.")
	}
	if row.ObjectKey != objectKey {
		if row.State != "PENDING" {
			return AvatarUploadResult{}, conflictError("AVATAR_UPLOAD_ALREADY_COMPLETED", "Avatar upload already completed", "The idempotent avatar upload has already been completed.")
		}
		remaining := row.ExpiresAt.Time.Sub(s.now())
		if remaining <= 0 {
			return AvatarUploadResult{}, conflictError("AVATAR_UPLOAD_EXPIRED", "Avatar upload expired", "Use a new idempotency key to create another avatar upload.")
		}
		metadata.Key = row.ObjectKey
		grant, err = s.profileObjects.IssueAvatarUpload(ctx, metadata, remaining)
		if err != nil {
			return AvatarUploadResult{}, objectStoreError(err)
		}
	}
	return AvatarUploadResult{UploadID: uuidString(row.ID), Grant: grant}, nil
}

func (s *Service) CompleteAvatarUpload(ctx context.Context, session UserSession, uploadID, requestID string) (Profile, error) {
	if session.Status != "APPROVED" {
		return Profile{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting approval and key access."}
	}
	parsedID, err := parsePGUUID(uploadID)
	if err != nil {
		return Profile{}, validationError("AVATAR_UPLOAD_ID_INVALID", "Invalid avatar upload", "The avatar upload identifier is invalid.")
	}
	row, err := dbgen.New(s.pool).FindAvatarUploadForUpdate(ctx, dbgen.FindAvatarUploadForUpdateParams{ID: parsedID, UserID: session.RawUserID})
	if errors.Is(err, pgx.ErrNoRows) {
		return Profile{}, &Error{Code: "AVATAR_UPLOAD_NOT_FOUND", Status: 404, Title: "Avatar upload not found", Detail: "The avatar upload does not exist."}
	}
	if err != nil {
		return Profile{}, internalError()
	}
	metadata := objectstore.ProfileObjectMetadata{Key: row.ObjectKey, Size: row.DisplaySize, ContentType: row.ContentType, SHA256: row.ContentSha256}
	if err := s.profileObjects.VerifyAvatar(ctx, metadata); err != nil {
		return Profile{}, objectStoreError(err)
	}
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		current, err := q.FindAvatarUploadForUpdate(ctx, dbgen.FindAvatarUploadForUpdateParams{ID: parsedID, UserID: session.RawUserID})
		if err != nil {
			return err
		}
		now := pgTime(s.now())
		if current.State == "PENDING" {
			updated, err := q.CompleteAvatarUpload(ctx, dbgen.CompleteAvatarUploadParams{ID: parsedID, UserID: session.RawUserID, CompletedAt: now})
			if err != nil || updated != 1 {
				if err != nil {
					return err
				}
				return conflictError("AVATAR_UPLOAD_EXPIRED", "Avatar upload expired", "The avatar upload grant has expired.")
			}
		}
		updated, err := q.SetUserAvatar(ctx, dbgen.SetUserAvatarParams{ID: session.RawUserID, AvatarUploadID: parsedID, UpdatedAt: now})
		if err != nil || updated != 1 {
			return err
		}
		return recordAudit(ctx, q, "USER", session.RawUserID, "AVATAR_UPDATE", "AVATAR_UPLOAD", parsedID, "SUCCESS", requestID)
	})
	if err != nil {
		return Profile{}, normalizeDatabaseError(err)
	}
	return s.GetProfile(ctx, session)
}

func (s *Service) GetAvatarGrant(ctx context.Context, session UserSession) (objectstore.ObjectGrant, error) {
	if session.Status != "APPROVED" {
		return objectstore.ObjectGrant{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting approval and key access."}
	}
	avatar, err := dbgen.New(s.pool).GetReadyAvatar(ctx, session.RawUserID)
	if errors.Is(err, pgx.ErrNoRows) {
		return objectstore.ObjectGrant{}, &Error{Code: "AVATAR_NOT_FOUND", Status: 404, Title: "Avatar not found", Detail: "The current user has no avatar."}
	}
	if err != nil {
		return objectstore.ObjectGrant{}, internalError()
	}
	grant, err := s.profileObjects.IssueAvatarRead(ctx, avatar.ObjectKey, 5*time.Minute)
	if err != nil {
		return objectstore.ObjectGrant{}, objectStoreError(err)
	}
	return grant, nil
}

func publicKeyHash(value []byte) []byte {
	digest := sha256.Sum256(value)
	return digest[:]
}

func equalJSON(left, right []byte) bool {
	var leftValue any
	var rightValue any
	return json.Unmarshal(left, &leftValue) == nil && json.Unmarshal(right, &rightValue) == nil &&
		reflect.DeepEqual(leftValue, rightValue)
}

func validAvatarContentType(value string) bool {
	switch value {
	case "image/jpeg", "image/png", "image/heic", "image/heif", "image/webp":
		return true
	default:
		return false
	}
}

func avatarExtension(contentType string) string {
	switch contentType {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/heic", "image/heif":
		return ".heic"
	default:
		return ".webp"
	}
}

func objectStoreError(err error) error {
	if errors.Is(err, objectstore.ErrObjectNotReady) {
		return &Error{Code: "AVATAR_UPLOAD_NOT_READY", Status: 409, Title: "Avatar upload not ready", Detail: "The avatar object is missing or does not match the declared metadata.", Retryable: true}
	}
	return &Error{Code: "OBJECT_STORAGE_UNAVAILABLE", Status: 503, Title: "Object storage unavailable", Detail: "The profile object store is temporarily unavailable.", Retryable: true}
}
