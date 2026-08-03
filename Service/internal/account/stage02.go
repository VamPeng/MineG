package account

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/vampeng/mineg/service/internal/platform/database/dbgen"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

var nicknamePattern = regexp.MustCompile(`^[\p{Han}\p{L}\p{N} _-]+$`)

func (s *Service) UpdateProfile(ctx context.Context, session UserSession, nickname, requestID string) (Profile, error) {
	if session.Status != "APPROVED" {
		return Profile{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting administrator approval."}
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
		return AvatarUploadResult{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting administrator approval."}
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
		return Profile{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting administrator approval."}
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
		return objectstore.ObjectGrant{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting administrator approval."}
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
