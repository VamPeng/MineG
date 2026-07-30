package upload

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/vampeng/mineg/service/internal/platform/database"
	"github.com/vampeng/mineg/service/internal/platform/database/dbgen"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

const (
	sessionLifetime = 24 * time.Hour
	grantLifetime   = 10 * time.Minute
	verifyingLease  = 2 * time.Minute
)

var (
	idempotencyPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,128}$`)
	etagPattern        = regexp.MustCompile(`^[A-Za-z0-9+/=_:.-]{1,256}$`)
)

type Error struct {
	Code      string
	Status    int
	Title     string
	Detail    string
	Retryable bool
}

func (e *Error) Error() string { return e.Code }

type Actor struct {
	UserID    string
	RawUserID pgtype.UUID
	Status    string
}

type Config struct {
	Objects objectstore.MediaObjects
	Now     func() time.Time
}

type Service struct {
	pool    *database.Pool
	objects objectstore.MediaObjects
	now     func() time.Time
}

func New(pool *database.Pool, config Config) *Service {
	if config.Objects == nil {
		config.Objects = objectstore.DisabledMediaObjects{}
	}
	if config.Now == nil {
		config.Now = time.Now
	}
	return &Service{pool: pool, objects: config.Objects, now: config.Now}
}

type PartInput struct {
	Number int32
	Size   int64
	SHA256 []byte
}

type ResourceInput struct {
	ID             string
	Type           string
	CiphertextSize int64
	ContentSize    int64
	SHA256         []byte
	Parts          []PartInput
}

type CreateInput struct {
	ProtocolVersion   string
	IdempotencyKey    string
	ClientMediaID     string
	Dedupe            []byte
	ContentRevision   int32
	MediaType         string
	CapturedAt        time.Time
	ManifestDigest    []byte
	EncryptedManifest []byte
	EncryptedMediaKey []byte
	ContentSHA256     []byte
	MimeType          string
	Resources         []ResourceInput
	RequestID         string
}

type ResourceStatus struct {
	ID             string                      `json:"resource_id"`
	Type           string                      `json:"resource_type"`
	ObjectKey      string                      `json:"object_key,omitempty"`
	CiphertextSize int64                       `json:"ciphertext_size,omitempty"`
	ContentSize    int64                       `json:"content_size,omitempty"`
	SHA256         []byte                      `json:"-"`
	PartCount      int32                       `json:"part_count"`
	UploadedParts  int32                       `json:"uploaded_parts"`
	UploadID       string                      `json:"-"`
	PartPlans      []objectstore.MediaPartPlan `json:"-"`
}

type SessionResult struct {
	ID            string                        `json:"id"`
	ClientMediaID string                        `json:"client_media_id"`
	State         string                        `json:"state"`
	Purpose       string                        `json:"purpose"`
	MediaID       string                        `json:"media_id,omitempty"`
	Deduplicated  bool                          `json:"deduplicated"`
	ExpiresAt     time.Time                     `json:"expires_at"`
	Grant         *objectstore.MediaUploadGrant `json:"grant,omitempty"`
	Resources     []ResourceStatus              `json:"resources"`
}

type PartReportInput struct {
	IdempotencyKey string
	ResourceID     string
	Number         int32
	Size           int64
	SHA256         []byte
	ETag           string
}

type PartReportResult struct {
	UploadID   string `json:"upload_id"`
	ResourceID string `json:"resource_id"`
	PartNumber int32  `json:"part_number"`
	State      string `json:"state"`
}

type CompleteInput struct {
	IdempotencyKey string
	ManifestDigest []byte
	RequestID      string
}

type CompleteResult struct {
	UploadID     string `json:"upload_id"`
	MediaID      string `json:"media_id"`
	State        string `json:"state"`
	Outcome      string `json:"outcome"`
	Deduplicated bool   `json:"deduplicated"`
}

type MediaSummary struct {
	ID              string    `json:"id"`
	MediaType       string    `json:"media_type"`
	ContentRevision int32     `json:"content_revision"`
	CapturedAt      time.Time `json:"captured_at"`
	CreatedAt       time.Time `json:"created_at"`
}

type MediaPage struct {
	Items []MediaSummary `json:"items"`
}

func (s *Service) Create(ctx context.Context, actor Actor, input CreateInput) (SessionResult, error) {
	if err := validateActor(actor); err != nil {
		return SessionResult{}, err
	}
	resources, normalized, err := validateCreate(input, actor.UserID)
	if err != nil {
		return SessionResult{}, err
	}
	requestHash := hashCreate(normalized)
	queries := dbgen.New(s.pool)
	existing, findErr := queries.FindUploadByIdempotency(ctx, dbgen.FindUploadByIdempotencyParams{OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey})
	if findErr == nil {
		if !bytes.Equal(existing.RequestHash, requestHash[:]) {
			return SessionResult{}, conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for another media upload.")
		}
		return s.sessionResult(ctx, existing, true)
	}
	if !errors.Is(findErr, pgx.ErrNoRows) {
		return SessionResult{}, internal()
	}
	if media, dedupeErr := queries.FindCompletedMediaByFingerprint(ctx, dbgen.FindCompletedMediaByFingerprintParams{
		OwnerID: actor.RawUserID, DedupeFingerprint: input.Dedupe, ContentRevision: input.ContentRevision,
	}); dedupeErr == nil {
		now := s.now().UTC()
		var row dbgen.MinegUploadSession
		var createErr error
		if input.ProtocolVersion == "stage03-v2" {
			row, createErr = queries.CreateDeduplicatedOriginalUploadSession(ctx, dbgen.CreateDeduplicatedOriginalUploadSessionParams{
				ID: toPGUUID(uuid.New()), OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey,
				RequestHash: requestHash[:], DedupeFingerprint: input.Dedupe, ContentRevision: input.ContentRevision,
				ClientMediaID: toPGUUID(uuid.MustParse(input.ClientMediaID)), MediaType: input.MediaType,
				CapturedAt: pgTime(input.CapturedAt), MimeType: pgtype.Text{String: input.MimeType, Valid: true},
				MediaID: media.ID, ExpiresAt: pgTime(now.Add(sessionLifetime)), CompletedAt: pgTime(now),
			})
		} else {
			row, createErr = queries.CreateDeduplicatedUploadSession(ctx, dbgen.CreateDeduplicatedUploadSessionParams{
				ID: toPGUUID(uuid.New()), OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey,
				RequestHash: requestHash[:], DedupeFingerprint: input.Dedupe, ContentRevision: input.ContentRevision,
				ClientMediaID: toPGUUID(uuid.MustParse(input.ClientMediaID)), MediaType: input.MediaType, CapturedAt: pgTime(input.CapturedAt),
				ManifestDigest: input.ManifestDigest, EncryptedManifest: input.EncryptedManifest,
				EncryptedMediaKey: input.EncryptedMediaKey, MediaID: media.ID,
				ExpiresAt: pgTime(now.Add(sessionLifetime)), CompletedAt: pgTime(now),
			})
		}
		if createErr != nil {
			if isUnique(createErr) {
				return s.recoverCreateRace(ctx, actor, input.IdempotencyKey, requestHash)
			}
			return SessionResult{}, internal()
		}
		return s.sessionResult(ctx, row, false)
	} else if !errors.Is(dedupeErr, pgx.ErrNoRows) {
		return SessionResult{}, internal()
	}

	sessionID := uuid.New()
	prefix := fmt.Sprintf("media/%s/%s/", actor.UserID, sessionID.String())
	for index := range resources {
		extension := ".original"
		if input.ProtocolVersion != "stage03-v2" {
			extension = ".cipher"
		}
		resources[index].ObjectKey = prefix + resources[index].ID + extension
	}
	grant, err := s.objects.BeginMediaUpload(ctx, prefix, resources, grantLifetime)
	if err != nil {
		return SessionResult{}, objectError(err)
	}
	created := false
	defer func() {
		if !created {
			_ = s.objects.AbortMediaUpload(context.WithoutCancel(ctx), grant.Resources)
		}
	}()
	now := s.now().UTC()
	var row dbgen.MinegUploadSession
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		if err := q.AcquireUploadIdempotencyLock(ctx, dbgen.AcquireUploadIdempotencyLockParams{OwnerID: actor.UserID, IdempotencyKey: input.IdempotencyKey}); err != nil {
			return err
		}
		prior, err := q.FindUploadByIdempotency(ctx, dbgen.FindUploadByIdempotencyParams{OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey})
		if err == nil {
			if !bytes.Equal(prior.RequestHash, requestHash[:]) {
				return conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for another media upload.")
			}
			row = prior
			return nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if input.ProtocolVersion == "stage03-v2" {
			row, err = q.CreateOriginalUploadSession(ctx, dbgen.CreateOriginalUploadSessionParams{
				ID: toPGUUID(sessionID), OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey,
				RequestHash: requestHash[:], DedupeFingerprint: input.Dedupe, ContentRevision: input.ContentRevision,
				ClientMediaID: toPGUUID(uuid.MustParse(input.ClientMediaID)), MediaType: input.MediaType,
				CapturedAt: pgTime(input.CapturedAt), MimeType: pgtype.Text{String: input.MimeType, Valid: true},
				ExpiresAt: pgTime(now.Add(sessionLifetime)),
			})
		} else {
			row, err = q.CreateUploadSession(ctx, dbgen.CreateUploadSessionParams{
				ID: toPGUUID(sessionID), OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey,
				RequestHash: requestHash[:], DedupeFingerprint: input.Dedupe, ContentRevision: input.ContentRevision,
				ClientMediaID: toPGUUID(uuid.MustParse(input.ClientMediaID)), MediaType: input.MediaType, CapturedAt: pgTime(input.CapturedAt),
				ManifestDigest: input.ManifestDigest, EncryptedManifest: input.EncryptedManifest,
				EncryptedMediaKey: input.EncryptedMediaKey, ExpiresAt: pgTime(now.Add(sessionLifetime)),
			})
		}
		if err != nil {
			return err
		}
		for index, resource := range normalized.Resources {
			resourceGrant := grant.Resources[index]
			resourceID := toPGUUID(uuid.MustParse(resource.ID))
			if input.ProtocolVersion == "stage03-v2" {
				if err := q.CreateOriginalUploadResource(ctx, dbgen.CreateOriginalUploadResourceParams{
					ID: resourceID, UploadSessionID: row.ID, ResourceType: resource.Type,
					ObjectKey: resourceGrant.ObjectKey, MultipartUploadID: resourceGrant.UploadID,
					ContentSize: pgtype.Int8{Int64: resource.ContentSize, Valid: true}, ContentSha256: resource.SHA256,
					PartCount: int32(len(resource.Parts)),
				}); err != nil {
					return err
				}
			} else if err := q.CreateUploadResource(ctx, dbgen.CreateUploadResourceParams{
				ID: resourceID, UploadSessionID: row.ID, ResourceType: resource.Type,
				ObjectKey: resourceGrant.ObjectKey, MultipartUploadID: resourceGrant.UploadID,
				CiphertextSize: pgtype.Int8{Int64: resource.CiphertextSize, Valid: true}, CiphertextSha256: resource.SHA256, PartCount: int32(len(resource.Parts)),
			}); err != nil {
				return err
			}
			for _, part := range resource.Parts {
				if err := q.CreateExpectedUploadPart(ctx, dbgen.CreateExpectedUploadPartParams{
					UploadSessionID: row.ID, ResourceID: resourceID, PartNumber: part.Number,
					ExpectedSize: part.Size, ExpectedSha256: part.SHA256,
				}); err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		var uploadErr *Error
		if errors.As(err, &uploadErr) {
			return SessionResult{}, uploadErr
		}
		return SessionResult{}, internal()
	}
	if uuidString(row.ID) != sessionID.String() {
		return s.sessionResult(ctx, row, true)
	}
	created = true
	result, err := s.sessionResult(ctx, row, false)
	if err == nil {
		result.Grant = &grant
	}
	return result, err
}

func (s *Service) Get(ctx context.Context, actor Actor, uploadID string) (SessionResult, error) {
	if err := validateActor(actor); err != nil {
		return SessionResult{}, err
	}
	id, err := parseUUID(uploadID, "UPLOAD_ID_INVALID")
	if err != nil {
		return SessionResult{}, err
	}
	row, err := dbgen.New(s.pool).FindUploadForOwner(ctx, dbgen.FindUploadForOwnerParams{ID: id, OwnerID: actor.RawUserID})
	if errors.Is(err, pgx.ErrNoRows) {
		return SessionResult{}, notFound("UPLOAD_NOT_FOUND", "Upload not found", "The upload session does not exist.")
	}
	if err != nil {
		return SessionResult{}, internal()
	}
	return s.sessionResult(ctx, row, true)
}

func (s *Service) ReportPart(ctx context.Context, actor Actor, uploadID string, input PartReportInput) (PartReportResult, error) {
	if err := validateActor(actor); err != nil {
		return PartReportResult{}, err
	}
	if !idempotencyPattern.MatchString(input.IdempotencyKey) || input.Number < 1 || input.Size < 1 || input.Size > objectstore.MediaPartMaximum || len(input.SHA256) != sha256.Size || !etagPattern.MatchString(strings.Trim(input.ETag, "\"")) {
		return PartReportResult{}, validation("UPLOAD_PART_INVALID", "Invalid upload part", "The part report fields are invalid.")
	}
	sessionID, err := parseUUID(uploadID, "UPLOAD_ID_INVALID")
	if err != nil {
		return PartReportResult{}, err
	}
	resourceID, err := parseUUID(input.ResourceID, "RESOURCE_ID_INVALID")
	if err != nil {
		return PartReportResult{}, err
	}
	now := s.now().UTC()
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		session, err := q.LockUploadForCompletion(ctx, dbgen.LockUploadForCompletionParams{ID: sessionID, OwnerID: actor.RawUserID})
		if errors.Is(err, pgx.ErrNoRows) {
			return notFound("UPLOAD_NOT_FOUND", "Upload not found", "The upload session does not exist.")
		}
		if err != nil {
			return err
		}
		if session.State == "COMPLETED" {
			return conflict("UPLOAD_ALREADY_COMPLETED", "Upload already completed", "No more parts can be reported.")
		}
		if session.State != "PENDING" || !now.Before(session.ExpiresAt.Time) {
			return conflict("UPLOAD_NOT_ACTIVE", "Upload not active", "The upload session is not accepting parts.")
		}
		part, err := q.FindUploadPartForUpdate(ctx, dbgen.FindUploadPartForUpdateParams{UploadSessionID: sessionID, ResourceID: resourceID, PartNumber: input.Number})
		if errors.Is(err, pgx.ErrNoRows) {
			return notFound("UPLOAD_PART_NOT_FOUND", "Upload part not found", "The resource part is outside this session.")
		}
		if err != nil {
			return err
		}
		if part.ExpectedSize != input.Size || !bytes.Equal(part.ExpectedSha256, input.SHA256) {
			return conflict("UPLOAD_PART_MISMATCH", "Upload part mismatch", "The reported size or digest does not match the authenticated resource plan.")
		}
		if part.State != "PENDING" {
			if part.ReportedSize.Int64 == input.Size && bytes.Equal(part.ReportedSha256, input.SHA256) && normalizeETag(part.Etag.String) == normalizeETag(input.ETag) {
				return nil
			}
			return conflict("UPLOAD_PART_ALREADY_REPORTED", "Upload part already reported", "The part was already reported with different metadata.")
		}
		updated, err := q.ReportUploadPart(ctx, dbgen.ReportUploadPartParams{
			UploadSessionID: sessionID, ResourceID: resourceID, PartNumber: input.Number,
			Etag: pgtype.Text{String: normalizeETag(input.ETag), Valid: true}, ReportedSize: pgtype.Int8{Int64: input.Size, Valid: true},
			ReportedSha256: input.SHA256, ReportedAt: pgTime(now),
		})
		if err != nil || updated != 1 {
			if err != nil {
				return err
			}
			return conflict("UPLOAD_PART_MISMATCH", "Upload part mismatch", "The part report was rejected.")
		}
		return nil
	})
	if err != nil {
		return PartReportResult{}, normalize(err)
	}
	return PartReportResult{UploadID: uploadID, ResourceID: input.ResourceID, PartNumber: input.Number, State: "UPLOADED"}, nil
}

func (s *Service) Complete(ctx context.Context, actor Actor, uploadID string, input CompleteInput) (CompleteResult, error) {
	if err := validateActor(actor); err != nil {
		return CompleteResult{}, err
	}
	if !idempotencyPattern.MatchString(input.IdempotencyKey) {
		return CompleteResult{}, validation("UPLOAD_COMPLETE_INVALID", "Invalid upload completion", "The completion idempotency key or manifest digest is invalid.")
	}
	sessionID, err := parseUUID(uploadID, "UPLOAD_ID_INVALID")
	if err != nil {
		return CompleteResult{}, err
	}
	now := s.now().UTC()
	var session dbgen.MinegUploadSession
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		var err error
		session, err = q.LockUploadForCompletion(ctx, dbgen.LockUploadForCompletionParams{ID: sessionID, OwnerID: actor.RawUserID})
		if errors.Is(err, pgx.ErrNoRows) {
			return notFound("UPLOAD_NOT_FOUND", "Upload not found", "The upload session does not exist.")
		}
		if err != nil {
			return err
		}
		if session.Purpose == "MEDIA_CIPHERTEXT" && (len(input.ManifestDigest) != sha256.Size || !bytes.Equal(session.ManifestDigest, input.ManifestDigest)) {
			return conflict("UPLOAD_MANIFEST_MISMATCH", "Upload manifest mismatch", "The completion manifest does not match the session.")
		}
		if session.Purpose == "MEDIA_ORIGINAL" && len(input.ManifestDigest) != 0 {
			return validation("UPLOAD_COMPLETE_INVALID", "Invalid upload completion", "Original media completion does not accept an encrypted manifest digest.")
		}
		if session.State == "COMPLETED" {
			return nil
		}
		if session.State == "VERIFYING" {
			if now.Sub(session.UpdatedAt.Time) < verifyingLease {
				return &Error{Code: "UPLOAD_VERIFYING", Status: 409, Title: "Upload verification in progress", Detail: "Another request is verifying this upload.", Retryable: true}
			}
			updated, resetErr := q.ResetUploadPending(ctx, dbgen.ResetUploadPendingParams{ID: session.ID, UpdatedAt: pgTime(now)})
			if resetErr != nil || updated != 1 {
				if resetErr != nil {
					return resetErr
				}
				return conflict("UPLOAD_STATE_CONFLICT", "Upload state conflict", "The verification lease changed concurrently.")
			}
			session.State = "PENDING"
		}
		if session.State != "PENDING" || !now.Before(session.ExpiresAt.Time) {
			return conflict("UPLOAD_NOT_ACTIVE", "Upload not active", "The upload session is expired or invalid.")
		}
		missing, err := q.CountUnreportedParts(ctx, session.ID)
		if err != nil {
			return err
		}
		if missing != 0 {
			return conflict("UPLOAD_PARTS_INCOMPLETE", "Upload parts incomplete", "Every planned part must be uploaded and reported before completion.")
		}
		updated, err := q.MarkUploadVerifying(ctx, dbgen.MarkUploadVerifyingParams{ID: session.ID, OwnerID: actor.RawUserID, UpdatedAt: pgTime(now)})
		if err != nil || updated != 1 {
			if err != nil {
				return err
			}
			return conflict("UPLOAD_STATE_CONFLICT", "Upload state conflict", "The upload state changed concurrently.")
		}
		session.State = "VERIFYING"
		return nil
	})
	if err != nil {
		return CompleteResult{}, normalize(err)
	}
	if session.State == "COMPLETED" {
		status, statusErr := s.sessionResult(ctx, session, false)
		if statusErr != nil {
			return CompleteResult{}, statusErr
		}
		return CompleteResult{UploadID: uploadID, MediaID: uuidString(session.MediaID), State: "COMPLETED", Outcome: "ALREADY_COMPLETED", Deduplicated: status.Deduplicated}, nil
	}
	verification, err := s.verification(ctx, session.ID)
	if err != nil {
		_, _ = dbgen.New(s.pool).ResetUploadPending(context.WithoutCancel(ctx), dbgen.ResetUploadPendingParams{ID: session.ID, UpdatedAt: pgTime(s.now().UTC())})
		return CompleteResult{}, err
	}
	if err := s.objects.VerifyAndCompleteMediaUpload(ctx, verification); err != nil {
		_, _ = dbgen.New(s.pool).ResetUploadPending(context.WithoutCancel(ctx), dbgen.ResetUploadPendingParams{ID: session.ID, UpdatedAt: pgTime(s.now().UTC())})
		return CompleteResult{}, objectNotReady(err)
	}
	result := CompleteResult{UploadID: uploadID, State: "COMPLETED", Outcome: "COMPLETED"}
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		locked, err := q.LockUploadForCompletion(ctx, dbgen.LockUploadForCompletionParams{ID: session.ID, OwnerID: actor.RawUserID})
		if err != nil {
			return err
		}
		if locked.State == "COMPLETED" {
			result.MediaID = uuidString(locked.MediaID)
			result.Outcome = "ALREADY_COMPLETED"
			return nil
		}
		if locked.State != "VERIFYING" {
			return conflict("UPLOAD_STATE_CONFLICT", "Upload state conflict", "The upload left verification unexpectedly.")
		}
		var media dbgen.MinegMedium
		if locked.Purpose == "MEDIA_ORIGINAL" {
			media, err = q.CreateCompletedOriginalMedia(ctx, dbgen.CreateCompletedOriginalMediaParams{
				ID: locked.ClientMediaID, OwnerID: actor.RawUserID, SourceUploadID: locked.ID,
				MediaType: locked.MediaType, DedupeFingerprint: locked.DedupeFingerprint,
				ContentRevision: locked.ContentRevision, CapturedAt: locked.CapturedAt,
				MimeType: locked.MimeType,
			})
		} else {
			media, err = q.CreateCompletedMedia(ctx, dbgen.CreateCompletedMediaParams{
				ID: locked.ClientMediaID, OwnerID: actor.RawUserID, SourceUploadID: locked.ID,
				MediaType: locked.MediaType, DedupeFingerprint: locked.DedupeFingerprint,
				ContentRevision: locked.ContentRevision, CapturedAt: locked.CapturedAt,
				ManifestDigest: locked.ManifestDigest, EncryptedManifest: locked.EncryptedManifest,
			})
		}
		created := err == nil
		if errors.Is(err, pgx.ErrNoRows) {
			media, err = q.FindCompletedMediaByFingerprint(ctx, dbgen.FindCompletedMediaByFingerprintParams{
				OwnerID: actor.RawUserID, DedupeFingerprint: locked.DedupeFingerprint, ContentRevision: locked.ContentRevision,
			})
			result.Deduplicated = true
			result.Outcome = "DEDUPLICATED"
		}
		if err != nil {
			return err
		}
		if created {
			if err := q.MarkUploadPartsVerified(ctx, locked.ID); err != nil {
				return err
			}
			resources, err := q.AttachResourcesToMedia(ctx, dbgen.AttachResourcesToMediaParams{UploadSessionID: locked.ID, MediaID: media.ID})
			if err != nil || resources < 1 {
				if err != nil {
					return err
				}
				return errors.New("no media resources attached")
			}
			album, err := q.EnsureLibraryAlbum(ctx, actor.RawUserID)
			if err != nil {
				return err
			}
			if err := q.LinkMediaToAlbum(ctx, dbgen.LinkMediaToAlbumParams{MediaID: media.ID, AlbumID: album.ID}); err != nil {
				return err
			}
			if locked.Purpose == "MEDIA_CIPHERTEXT" {
				if err := q.CreateOwnerMediaKeyEnvelope(ctx, dbgen.CreateOwnerMediaKeyEnvelopeParams{
					MediaID: media.ID, OwnerID: actor.RawUserID, EncryptedMediaKey: locked.EncryptedMediaKey,
				}); err != nil {
					return err
				}
			}
		} else {
			if err := q.MarkUploadResourcesInvalid(ctx, locked.ID); err != nil {
				return err
			}
		}
		updated, err := q.CompleteUploadSession(ctx, dbgen.CompleteUploadSessionParams{ID: locked.ID, MediaID: media.ID, CompletedAt: pgTime(s.now().UTC())})
		if err != nil || updated != 1 {
			if err != nil {
				return err
			}
			return errors.New("upload completion did not commit")
		}
		if err := q.RecordAuditEvent(ctx, dbgen.RecordAuditEventParams{
			ActorType: "USER", ActorID: actor.RawUserID, Action: "MEDIA_UPLOAD_COMPLETE",
			TargetType: "MEDIA", TargetID: media.ID, Result: map[bool]string{true: "SUCCESS", false: "REPLAY"}[created], RequestID: input.RequestID,
			Metadata: []byte(fmt.Sprintf(`{"purpose":%q,"client_encryption":%t,"outcome":%q}`,
				locked.Purpose, locked.Purpose == "MEDIA_CIPHERTEXT", result.Outcome)),
		}); err != nil {
			return err
		}
		result.MediaID = uuidString(media.ID)
		return nil
	})
	if err != nil {
		// OSS completion is idempotent. Returning the session to PENDING lets a
		// later request retry the database commit after an already assembled object.
		_, _ = dbgen.New(s.pool).ResetUploadPending(context.WithoutCancel(ctx), dbgen.ResetUploadPendingParams{ID: session.ID, UpdatedAt: pgTime(s.now().UTC())})
		return CompleteResult{}, normalize(err)
	}
	return result, nil
}

func (s *Service) ListMedia(ctx context.Context, actor Actor, limit int32) (MediaPage, error) {
	if err := validateActor(actor); err != nil {
		return MediaPage{}, err
	}
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	rows, err := dbgen.New(s.pool).ListOwnerMedia(ctx, dbgen.ListOwnerMediaParams{OwnerID: actor.RawUserID, Limit: limit})
	if err != nil {
		return MediaPage{}, internal()
	}
	page := MediaPage{Items: make([]MediaSummary, 0, len(rows))}
	for _, row := range rows {
		page.Items = append(page.Items, MediaSummary{ID: uuidString(row.ID), MediaType: row.MediaType, ContentRevision: row.ContentRevision, CapturedAt: row.CapturedAt.Time, CreatedAt: row.CreatedAt.Time})
	}
	return page, nil
}

func (s *Service) verification(ctx context.Context, sessionID pgtype.UUID) ([]objectstore.MediaResourceVerification, error) {
	q := dbgen.New(s.pool)
	resources, err := q.ListUploadResources(ctx, sessionID)
	if err != nil {
		return nil, internal()
	}
	parts, err := q.ListUploadParts(ctx, sessionID)
	if err != nil {
		return nil, internal()
	}
	byResource := make(map[string][]objectstore.ReportedMediaPart)
	for _, part := range parts {
		if part.State != "UPLOADED" || !part.Etag.Valid || !part.ReportedSize.Valid || len(part.ReportedSha256) != 32 {
			return nil, conflict("UPLOAD_PARTS_INCOMPLETE", "Upload parts incomplete", "Every planned part must be reported.")
		}
		key := uuidString(part.ResourceID)
		byResource[key] = append(byResource[key], objectstore.ReportedMediaPart{Number: part.PartNumber, Size: part.ReportedSize.Int64, SHA256: part.ReportedSha256, ETag: part.Etag.String})
	}
	result := make([]objectstore.MediaResourceVerification, 0, len(resources))
	for _, resource := range resources {
		purpose := "MEDIA_CIPHERTEXT"
		digest := resource.CiphertextSha256
		if resource.ContentSize.Valid {
			purpose = "MEDIA_ORIGINAL"
			digest = resource.ContentSha256
		}
		result = append(result, objectstore.MediaResourceVerification{
			ResourceID: uuidString(resource.ID), ObjectKey: resource.ObjectKey,
			UploadID: resource.MultipartUploadID, Purpose: purpose, SHA256: digest,
			Parts: byResource[uuidString(resource.ID)],
		})
	}
	return result, nil
}

func (s *Service) sessionResult(ctx context.Context, row dbgen.MinegUploadSession, reissue bool) (SessionResult, error) {
	q := dbgen.New(s.pool)
	if (row.State == "PENDING" || row.State == "VERIFYING") && !s.now().Before(row.ExpiresAt.Time) {
		updated, err := q.ExpireUploadSession(ctx, dbgen.ExpireUploadSessionParams{
			ID: row.ID, OwnerID: row.OwnerID, UpdatedAt: pgTime(s.now().UTC()),
		})
		if err != nil {
			return SessionResult{}, internal()
		}
		if updated == 1 {
			row.State = "EXPIRED"
		} else {
			row, err = q.FindUploadForOwner(ctx, dbgen.FindUploadForOwnerParams{ID: row.ID, OwnerID: row.OwnerID})
			if err != nil {
				return SessionResult{}, internal()
			}
		}
	}
	resources, err := q.ListUploadResources(ctx, row.ID)
	if err != nil {
		return SessionResult{}, internal()
	}
	parts, err := q.ListUploadParts(ctx, row.ID)
	if err != nil {
		return SessionResult{}, internal()
	}
	statuses := make([]ResourceStatus, 0, len(resources))
	plans := make([]objectstore.MediaResourcePlan, 0, len(resources))
	existing := make([]objectstore.MediaResourceGrant, 0, len(resources))
	for _, resource := range resources {
		status := ResourceStatus{ID: uuidString(resource.ID), Type: resource.ResourceType, ObjectKey: resource.ObjectKey, PartCount: resource.PartCount, UploadID: resource.MultipartUploadID}
		if row.Purpose == "MEDIA_ORIGINAL" {
			status.ContentSize = resource.ContentSize.Int64
			status.SHA256 = resource.ContentSha256
		} else {
			status.CiphertextSize = resource.CiphertextSize.Int64
			status.SHA256 = resource.CiphertextSha256
		}
		for _, part := range parts {
			if part.ResourceID == resource.ID {
				status.PartPlans = append(status.PartPlans, objectstore.MediaPartPlan{Number: part.PartNumber, Size: part.ExpectedSize, SHA256: part.ExpectedSha256})
				if part.State != "PENDING" {
					status.UploadedParts++
				}
			}
		}
		statuses = append(statuses, status)
		contentSize := status.ContentSize
		if contentSize == 0 {
			contentSize = status.CiphertextSize
		}
		plans = append(plans, objectstore.MediaResourcePlan{ID: status.ID, ObjectKey: status.ObjectKey, Purpose: row.Purpose, ContentSize: contentSize, SHA256: status.SHA256, Parts: status.PartPlans})
		existing = append(existing, objectstore.MediaResourceGrant{ResourceID: status.ID, ObjectKey: status.ObjectKey, UploadID: status.UploadID})
	}
	result := SessionResult{ID: uuidString(row.ID), ClientMediaID: uuidString(row.ClientMediaID), State: row.State, Purpose: row.Purpose, Deduplicated: row.State == "COMPLETED" && len(resources) == 0, ExpiresAt: row.ExpiresAt.Time, Resources: statuses}
	if row.MediaID.Valid {
		result.MediaID = uuidString(row.MediaID)
	}
	if row.State == "EXPIRED" && len(existing) > 0 {
		_ = q.MarkUploadResourcesInvalid(ctx, row.ID)
		_ = s.objects.AbortMediaUpload(context.WithoutCancel(ctx), existing)
	}
	if reissue && row.State == "EXPIRED" && len(resources) > 0 {
		prefix := fmt.Sprintf("media/%s/%s/", uuidString(row.OwnerID), uuidString(row.ID))
		grant, err := s.objects.BeginMediaUpload(ctx, prefix, plans, grantLifetime)
		if err != nil {
			return SessionResult{}, objectError(err)
		}
		keepGrant := false
		defer func() {
			if !keepGrant {
				_ = s.objects.AbortMediaUpload(context.WithoutCancel(ctx), grant.Resources)
			}
		}()
		now := s.now().UTC()
		var locked dbgen.MinegUploadSession
		revived := false
		err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
			txQueries := dbgen.New(tx)
			var lockErr error
			locked, lockErr = txQueries.LockUploadForCompletion(ctx, dbgen.LockUploadForCompletionParams{ID: row.ID, OwnerID: row.OwnerID})
			if lockErr != nil {
				return lockErr
			}
			if locked.State != "EXPIRED" {
				return nil
			}
			updated, updateErr := txQueries.ReviveExpiredUploadSession(ctx, dbgen.ReviveExpiredUploadSessionParams{
				ID: row.ID, OwnerID: row.OwnerID, ExpiresAt: pgTime(now.Add(sessionLifetime)), UpdatedAt: pgTime(now),
			})
			if updateErr != nil || updated != 1 {
				if updateErr != nil {
					return updateErr
				}
				return errors.New("expired upload was not revived")
			}
			for index, resource := range resources {
				updated, updateErr = txQueries.ReviveUploadResource(ctx, dbgen.ReviveUploadResourceParams{
					ID: resource.ID, UploadSessionID: row.ID, MultipartUploadID: grant.Resources[index].UploadID,
				})
				if updateErr != nil || updated != 1 {
					if updateErr != nil {
						return updateErr
					}
					return errors.New("expired upload resource was not revived")
				}
			}
			if updateErr := txQueries.ResetUploadParts(ctx, row.ID); updateErr != nil {
				return updateErr
			}
			revived = true
			return nil
		})
		if err != nil {
			return SessionResult{}, internal()
		}
		if !revived {
			return s.sessionResult(ctx, locked, true)
		}
		keepGrant = true
		result.State = "PENDING"
		result.ExpiresAt = now.Add(sessionLifetime)
		for index := range result.Resources {
			result.Resources[index].UploadedParts = 0
		}
		result.Grant = &grant
		return result, nil
	}
	if reissue && row.State == "PENDING" && s.now().Before(row.ExpiresAt.Time) && len(resources) > 0 {
		prefix := fmt.Sprintf("media/%s/%s/", uuidString(row.OwnerID), uuidString(row.ID))
		grant, err := s.objects.ResumeMediaUpload(ctx, prefix, existing, plans, grantLifetime)
		if err != nil {
			return SessionResult{}, objectError(err)
		}
		result.Grant = &grant
	}
	return result, nil
}

func (s *Service) recoverCreateRace(ctx context.Context, actor Actor, key string, hash [32]byte) (SessionResult, error) {
	row, err := dbgen.New(s.pool).FindUploadByIdempotency(ctx, dbgen.FindUploadByIdempotencyParams{OwnerID: actor.RawUserID, IdempotencyKey: key})
	if err != nil {
		return SessionResult{}, internal()
	}
	if !bytes.Equal(row.RequestHash, hash[:]) {
		return SessionResult{}, conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for another media upload.")
	}
	return s.sessionResult(ctx, row, true)
}

func validateCreate(input CreateInput, ownerID string) ([]objectstore.MediaResourcePlan, CreateInput, error) {
	original := input.ProtocolVersion == "stage03-v2"
	validPayload := original && len(input.ContentSHA256) == 32 && bytes.Equal(input.Dedupe, input.ContentSHA256) && input.MimeType != "" && len(input.MimeType) <= 127 &&
		len(input.ManifestDigest) == 0 && len(input.EncryptedManifest) == 0 && len(input.EncryptedMediaKey) == 0
	if !original {
		validPayload = input.ProtocolVersion == "" && len(input.ManifestDigest) == 32 && len(input.EncryptedManifest) >= 48 && len(input.EncryptedManifest) <= 1024*1024 &&
			len(input.EncryptedMediaKey) >= 64 && len(input.EncryptedMediaKey) <= 1024
	}
	if !idempotencyPattern.MatchString(input.IdempotencyKey) || uuid.Validate(input.ClientMediaID) != nil || len(input.Dedupe) != 32 || input.ContentRevision <= 0 ||
		!validMediaType(input.MediaType) || input.CapturedAt.IsZero() || !validPayload || len(input.Resources) < 1 || len(input.Resources) > 8 {
		return nil, CreateInput{}, validation("UPLOAD_INVALID", "Invalid media upload", "The upload session metadata is invalid.")
	}
	normalized := input
	normalized.RequestID = ""
	normalized.Resources = append([]ResourceInput(nil), input.Resources...)
	sort.Slice(normalized.Resources, func(i, j int) bool { return normalized.Resources[i].ID < normalized.Resources[j].ID })
	plans := make([]objectstore.MediaResourcePlan, 0, len(normalized.Resources))
	resourceTypes := make(map[string]struct{}, len(normalized.Resources))
	for index := range normalized.Resources {
		resource := &normalized.Resources[index]
		resourceSize := resource.CiphertextSize
		if original {
			resourceSize = resource.ContentSize
		}
		if uuid.Validate(resource.ID) != nil || !validResourceType(resource.Type) || len(resource.SHA256) != 32 || resourceSize < 1 || len(resource.Parts) < 1 || len(resource.Parts) > 10000 ||
			(original && (resource.Type != "ORIGINAL" || resource.CiphertextSize != 0)) || (!original && resource.ContentSize != 0) {
			return nil, CreateInput{}, validation("UPLOAD_RESOURCE_INVALID", "Invalid media resource", "A resource plan is invalid.")
		}
		if _, exists := resourceTypes[resource.Type]; exists {
			return nil, CreateInput{}, validation("UPLOAD_RESOURCE_DUPLICATE", "Duplicate media resource", "Resource types must be unique within one media.")
		}
		resourceTypes[resource.Type] = struct{}{}
		if original && !bytes.Equal(resource.SHA256, input.ContentSHA256) {
			return nil, CreateInput{}, validation("UPLOAD_RESOURCE_INVALID", "Invalid media resource", "The original resource digest must match the media content digest.")
		}
		sort.Slice(resource.Parts, func(i, j int) bool { return resource.Parts[i].Number < resource.Parts[j].Number })
		var total int64
		partPlans := make([]objectstore.MediaPartPlan, 0, len(resource.Parts))
		partMaximum := int64(objectstore.MediaPartMaximum)
		if original {
			partMaximum = objectstore.OriginalMediaPartMaximum
		}
		for partIndex, part := range resource.Parts {
			if part.Number != int32(partIndex+1) || part.Size < 1 || part.Size > partMaximum || len(part.SHA256) != 32 || (partIndex < len(resource.Parts)-1 && part.Size != partMaximum) {
				return nil, CreateInput{}, validation("UPLOAD_PART_INVALID", "Invalid upload part", "Logical blocks must map one-to-one to ordered multipart parts.")
			}
			total += part.Size
			partPlans = append(partPlans, objectstore.MediaPartPlan{Number: part.Number, Size: part.Size, SHA256: part.SHA256})
		}
		if total != resourceSize {
			return nil, CreateInput{}, validation("UPLOAD_RESOURCE_SIZE_MISMATCH", "Media resource size mismatch", "The resource size does not equal its parts.")
		}
		purpose := "MEDIA_CIPHERTEXT"
		extension := ".cipher"
		if original {
			purpose, extension = "MEDIA_ORIGINAL", ".original"
		}
		plans = append(plans, objectstore.MediaResourcePlan{ID: resource.ID, ObjectKey: fmt.Sprintf("media/%s/pending/%s%s", ownerID, resource.ID, extension), Purpose: purpose, ContentSize: resourceSize, SHA256: resource.SHA256, Parts: partPlans})
	}
	return plans, normalized, nil
}

func hashCreate(input CreateInput) [32]byte {
	if input.ProtocolVersion == "" {
		type legacyResourceInput struct {
			ID             string
			Type           string
			CiphertextSize int64
			SHA256         []byte
			Parts          []PartInput
		}
		type legacyCreateInput struct {
			IdempotencyKey    string
			ClientMediaID     string
			Dedupe            []byte
			ContentRevision   int32
			MediaType         string
			CapturedAt        time.Time
			ManifestDigest    []byte
			EncryptedManifest []byte
			EncryptedMediaKey []byte
			Resources         []legacyResourceInput
			RequestID         string
		}
		legacyResources := make([]legacyResourceInput, 0, len(input.Resources))
		for _, resource := range input.Resources {
			legacyResources = append(legacyResources, legacyResourceInput{ID: resource.ID, Type: resource.Type,
				CiphertextSize: resource.CiphertextSize, SHA256: resource.SHA256, Parts: resource.Parts})
		}
		encoded, _ := json.Marshal(legacyCreateInput{
			IdempotencyKey: input.IdempotencyKey, ClientMediaID: input.ClientMediaID,
			Dedupe: input.Dedupe, ContentRevision: input.ContentRevision, MediaType: input.MediaType,
			CapturedAt: input.CapturedAt, ManifestDigest: input.ManifestDigest,
			EncryptedManifest: input.EncryptedManifest, EncryptedMediaKey: input.EncryptedMediaKey,
			Resources: legacyResources, RequestID: input.RequestID,
		})
		return sha256.Sum256(encoded)
	}
	encoded, _ := json.Marshal(input)
	return sha256.Sum256(encoded)
}

func validateActor(actor Actor) error {
	if actor.Status != "APPROVED" || !actor.RawUserID.Valid || actor.UserID == "" {
		return &Error{Code: "ACCOUNT_NOT_APPROVED", Status: 403, Title: "Account not approved", Detail: "An approved member session is required."}
	}
	return nil
}

func validMediaType(value string) bool {
	switch value {
	case "PHOTO", "VIDEO", "GIF", "LIVE_PHOTO", "DYNAMIC":
		return true
	default:
		return false
	}
}

func validResourceType(value string) bool {
	switch value {
	case "ORIGINAL", "THUMBNAIL", "VIDEO_COVER", "PREVIEW", "LIVE_PHOTO_VIDEO", "DYNAMIC_PREVIEW":
		return true
	default:
		return false
	}
}

func parseUUID(value, code string) (pgtype.UUID, error) {
	parsed, err := uuid.Parse(value)
	if err != nil {
		return pgtype.UUID{}, validation(code, "Invalid identifier", "The identifier is not a UUID.")
	}
	return toPGUUID(parsed), nil
}

func toPGUUID(value uuid.UUID) pgtype.UUID { return pgtype.UUID{Bytes: [16]byte(value), Valid: true} }
func uuidString(value pgtype.UUID) string {
	if !value.Valid {
		return ""
	}
	return uuid.UUID(value.Bytes).String()
}
func pgTime(value time.Time) pgtype.Timestamptz {
	return pgtype.Timestamptz{Time: value.UTC(), Valid: true}
}
func normalizeETag(value string) string { return strings.Trim(strings.TrimSpace(value), "\"") }

func validation(code, title, detail string) *Error {
	return &Error{Code: code, Status: 422, Title: title, Detail: detail}
}
func conflict(code, title, detail string) *Error {
	return &Error{Code: code, Status: 409, Title: title, Detail: detail}
}
func notFound(code, title, detail string) *Error {
	return &Error{Code: code, Status: 404, Title: title, Detail: detail}
}
func internal() *Error {
	return &Error{Code: "INTERNAL_ERROR", Status: 500, Title: "Internal error", Detail: "The upload request could not be completed.", Retryable: true}
}
func objectError(err error) *Error {
	if errors.Is(err, objectstore.ErrUnavailable) {
		return &Error{Code: "OBJECT_STORAGE_UNAVAILABLE", Status: 503, Title: "Object storage unavailable", Detail: "Media upload authorization is unavailable.", Retryable: true}
	}
	return &Error{Code: "OBJECT_STORAGE_ERROR", Status: 503, Title: "Object storage error", Detail: "Media storage could not prepare the upload.", Retryable: true}
}
func objectNotReady(error) *Error {
	return &Error{Code: "UPLOAD_OBJECT_NOT_READY", Status: 409, Title: "Upload object not ready", Detail: "OSS parts do not match the reported media plan.", Retryable: true}
}
func normalize(err error) error {
	var uploadErr *Error
	if errors.As(err, &uploadErr) {
		return uploadErr
	}
	return internal()
}

func isUnique(err error) bool {
	var pgErr interface{ SQLState() string }
	return errors.As(err, &pgErr) && pgErr.SQLState() == "23505"
}
