package upload

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
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
	Objects   objectstore.MediaObjects
	Now       func() time.Time
	CursorKey []byte
}

type Service struct {
	pool      *database.Pool
	objects   objectstore.MediaObjects
	now       func() time.Time
	cursorKey []byte
}

func New(pool *database.Pool, config Config) *Service {
	if config.Objects == nil {
		config.Objects = objectstore.DisabledMediaObjects{}
	}
	if config.Now == nil {
		config.Now = time.Now
	}
	if len(config.CursorKey) < 32 {
		config.CursorKey = []byte("mineg-local-upload-cursor-key-change-before-deployment")
	}
	return &Service{pool: pool, objects: config.Objects, now: config.Now, cursorKey: config.CursorKey}
}

type PartInput struct {
	Number int32
	Size   int64
	SHA256 []byte
}

type ResourceInput struct {
	ID          string
	Type        string
	ContentSize int64
	SHA256      []byte
	MimeType    string
	Parts       []PartInput
}

type ClientAlbumInput struct {
	ID   string
	Name string
}

type CreateInput struct {
	ProtocolVersion      string
	IdempotencyKey       string
	ClientMediaID        string
	Dedupe               []byte
	ContentRevision      int32
	MediaType            string
	CapturedAt           time.Time
	ContentSHA256        []byte
	MimeType             string
	Width                *int32
	Height               *int32
	DurationMS           *int64
	Resources            []ResourceInput
	DeviceInstallationID string
	ClientAlbums         []ClientAlbumInput
	RequestID            string
}

type ResourceStatus struct {
	ID                   string                      `json:"resource_id"`
	Type                 string                      `json:"resource_type"`
	ObjectKey            string                      `json:"object_key,omitempty"`
	ContentSize          int64                       `json:"content_size,omitempty"`
	SHA256               []byte                      `json:"-"`
	PartCount            int32                       `json:"part_count"`
	UploadedParts        int32                       `json:"uploaded_parts"`
	ConfirmedPartNumbers []int32                     `json:"confirmed_part_numbers"`
	UploadID             string                      `json:"-"`
	PartPlans            []objectstore.MediaPartPlan `json:"-"`
}

type SessionResult struct {
	ID              string                        `json:"id"`
	ClientMediaID   string                        `json:"client_media_id"`
	State           string                        `json:"state"`
	Purpose         string                        `json:"purpose"`
	MediaID         string                        `json:"media_id,omitempty"`
	Deduplicated    bool                          `json:"deduplicated"`
	GrantGeneration int32                         `json:"grant_generation"`
	ExpiresAt       time.Time                     `json:"expires_at"`
	Grant           *objectstore.MediaUploadGrant `json:"grant,omitempty"`
	Resources       []ResourceStatus              `json:"resources"`
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
	Items      []MediaSummary `json:"items"`
	NextCursor *string        `json:"next_cursor"`
}

type mediaCursorPayload struct {
	OwnerID    string    `json:"owner_id"`
	CapturedAt time.Time `json:"captured_at"`
	MediaID    string    `json:"media_id"`
	Scope      string    `json:"scope"`
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
		if err := s.ensureUploadAlbums(ctx, actor, existing, normalized); err != nil {
			return SessionResult{}, err
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
		row, createErr = queries.CreateDeduplicatedOriginalUploadSession(ctx, dbgen.CreateDeduplicatedOriginalUploadSessionParams{
			ID: toPGUUID(uuid.New()), OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey,
			RequestHash: requestHash[:], DedupeFingerprint: input.Dedupe, ContentRevision: input.ContentRevision,
			ClientMediaID: toPGUUID(uuid.MustParse(input.ClientMediaID)), MediaType: input.MediaType,
			CapturedAt: pgTime(input.CapturedAt), MimeType: input.MimeType, Width: pgInt4(input.Width), Height: pgInt4(input.Height), DurationMs: pgInt8(input.DurationMS),
			MediaID: media.ID, ExpiresAt: pgTime(now.Add(sessionLifetime)), CompletedAt: pgTime(now),
		})
		if createErr != nil {
			if isUnique(createErr) {
				return s.recoverCreateRace(ctx, actor, normalized, requestHash)
			}
			return SessionResult{}, internal()
		}
		if err := s.ensureUploadAlbums(ctx, actor, row, normalized); err != nil {
			return SessionResult{}, err
		}
		return s.sessionResult(ctx, row, false)
	} else if !errors.Is(dedupeErr, pgx.ErrNoRows) {
		return SessionResult{}, internal()
	}

	sessionID := uuid.New()
	prefix := fmt.Sprintf("media/%s/%s/", actor.UserID, sessionID.String())
	for index := range resources {
		resources[index].ObjectKey = prefix + resources[index].ID + "." + resourceObjectSuffix(normalized.Resources[index].Type)
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
		row, err = q.CreateOriginalUploadSession(ctx, dbgen.CreateOriginalUploadSessionParams{
			ID: toPGUUID(sessionID), OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey,
			RequestHash: requestHash[:], DedupeFingerprint: input.Dedupe, ContentRevision: input.ContentRevision,
			ClientMediaID: toPGUUID(uuid.MustParse(input.ClientMediaID)), MediaType: input.MediaType,
			CapturedAt: pgTime(input.CapturedAt), MimeType: input.MimeType, Width: pgInt4(input.Width), Height: pgInt4(input.Height), DurationMs: pgInt8(input.DurationMS),
			ExpiresAt: pgTime(now.Add(sessionLifetime)),
		})
		if err != nil {
			return err
		}
		for index, resource := range normalized.Resources {
			resourceGrant := grant.Resources[index]
			resourceID := toPGUUID(uuid.MustParse(resource.ID))
			if err := q.CreateOriginalUploadResource(ctx, dbgen.CreateOriginalUploadResourceParams{
				ID: resourceID, UploadSessionID: row.ID, ResourceType: resource.Type,
				ObjectKey: resourceGrant.ObjectKey, MultipartUploadID: resourceGrant.UploadID,
				ContentSize: resource.ContentSize, ContentSha256: resource.SHA256, MimeType: resource.MimeType,
				PartCount: int32(len(resource.Parts)),
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
	if err := s.ensureUploadAlbums(ctx, actor, row, normalized); err != nil {
		return SessionResult{}, err
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
	if !idempotencyPattern.MatchString(input.IdempotencyKey) || input.Number < 1 || input.Size < 1 || input.Size > objectstore.OriginalMediaPartMaximum || len(input.SHA256) != sha256.Size || !etagPattern.MatchString(strings.Trim(input.ETag, "\"")) {
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
		media, err := q.CreateCompletedOriginalMedia(ctx, dbgen.CreateCompletedOriginalMediaParams{
			ID: locked.ClientMediaID, OwnerID: actor.RawUserID, SourceUploadID: locked.ID,
			MediaType: locked.MediaType, DedupeFingerprint: locked.DedupeFingerprint,
			ContentRevision: locked.ContentRevision, CapturedAt: locked.CapturedAt,
			MimeType: locked.MimeType, Width: locked.Width, Height: locked.Height, DurationMs: locked.DurationMs,
		})
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
		} else {
			if err := q.MarkUploadResourcesInvalid(ctx, locked.ID); err != nil {
				return err
			}
		}
		if err := q.LinkUploadSessionAlbumsToMedia(ctx, dbgen.LinkUploadSessionAlbumsToMediaParams{
			UploadSessionID: locked.ID, MediaID: media.ID,
		}); err != nil {
			return err
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
			Metadata: []byte(fmt.Sprintf(`{"purpose":"MEDIA_ORIGINAL","outcome":%q}`, result.Outcome)),
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

func (s *Service) ListMedia(ctx context.Context, actor Actor, cursor string, limit int32) (MediaPage, error) {
	if err := validateActor(actor); err != nil {
		return MediaPage{}, err
	}
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	const cursorScope = "private-media-v1"
	queryLimit := limit + 1
	page := MediaPage{Items: make([]MediaSummary, 0, queryLimit)}
	if cursor == "" {
		rows, err := dbgen.New(s.pool).ListOwnerMedia(ctx, dbgen.ListOwnerMediaParams{OwnerID: actor.RawUserID, Limit: queryLimit})
		if err != nil {
			return MediaPage{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, MediaSummary{ID: uuidString(row.ID), MediaType: row.MediaType, ContentRevision: row.ContentRevision, CapturedAt: row.CapturedAt.Time, CreatedAt: row.CreatedAt.Time})
		}
	} else {
		payload, decodeErr := s.decodeMediaCursor(cursor)
		if decodeErr != nil || payload.Scope != cursorScope || payload.OwnerID != actor.UserID || uuid.Validate(payload.MediaID) != nil {
			return MediaPage{}, validation("CURSOR_INVALID", "Invalid cursor", "The media pagination cursor is invalid.")
		}
		rows, err := dbgen.New(s.pool).ListOwnerMediaAfter(ctx, dbgen.ListOwnerMediaAfterParams{
			OwnerID: actor.RawUserID, CapturedAt: pgTime(payload.CapturedAt), ID: toPGUUID(uuid.MustParse(payload.MediaID)), Limit: queryLimit,
		})
		if err != nil {
			return MediaPage{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, MediaSummary{ID: uuidString(row.ID), MediaType: row.MediaType, ContentRevision: row.ContentRevision, CapturedAt: row.CapturedAt.Time, CreatedAt: row.CreatedAt.Time})
		}
	}
	if len(page.Items) > int(limit) {
		page.Items = page.Items[:limit]
		last := page.Items[len(page.Items)-1]
		next := s.encodeMediaCursor(mediaCursorPayload{OwnerID: actor.UserID, CapturedAt: last.CapturedAt, MediaID: last.ID, Scope: cursorScope})
		page.NextCursor = &next
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
		result = append(result, objectstore.MediaResourceVerification{
			ResourceID: uuidString(resource.ID), ObjectKey: resource.ObjectKey,
			UploadID: resource.MultipartUploadID, Purpose: "MEDIA_ORIGINAL", SHA256: resource.ContentSha256,
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
		status := ResourceStatus{ID: uuidString(resource.ID), Type: resource.ResourceType, ObjectKey: resource.ObjectKey, PartCount: resource.PartCount, UploadID: resource.MultipartUploadID, ConfirmedPartNumbers: make([]int32, 0, resource.PartCount)}
		status.ContentSize = resource.ContentSize
		status.SHA256 = resource.ContentSha256
		for _, part := range parts {
			if part.ResourceID == resource.ID {
				status.PartPlans = append(status.PartPlans, objectstore.MediaPartPlan{Number: part.PartNumber, Size: part.ExpectedSize, SHA256: part.ExpectedSha256})
				if part.State != "PENDING" {
					status.UploadedParts++
					status.ConfirmedPartNumbers = append(status.ConfirmedPartNumbers, part.PartNumber)
				}
			}
		}
		statuses = append(statuses, status)
		plans = append(plans, objectstore.MediaResourcePlan{ID: status.ID, ObjectKey: status.ObjectKey, Purpose: "MEDIA_ORIGINAL", ContentSize: status.ContentSize, SHA256: status.SHA256, Parts: status.PartPlans})
		existing = append(existing, objectstore.MediaResourceGrant{ResourceID: status.ID, ObjectKey: status.ObjectKey, UploadID: status.UploadID})
	}
	result := SessionResult{ID: uuidString(row.ID), ClientMediaID: uuidString(row.ClientMediaID), State: row.State, Purpose: row.Purpose, Deduplicated: row.State == "COMPLETED" && len(resources) == 0, GrantGeneration: row.GrantGeneration, ExpiresAt: row.ExpiresAt.Time, Resources: statuses}
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
		generation, generationErr := s.bumpGrantGeneration(ctx, row)
		if generationErr != nil {
			return SessionResult{}, generationErr
		}
		result.GrantGeneration = generation
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
		generation, generationErr := s.bumpGrantGeneration(ctx, row)
		if generationErr != nil {
			return SessionResult{}, generationErr
		}
		result.GrantGeneration = generation
	}
	return result, nil
}

func (s *Service) bumpGrantGeneration(ctx context.Context, row dbgen.MinegUploadSession) (int32, error) {
	generation, err := dbgen.New(s.pool).BumpUploadGrantGeneration(ctx, dbgen.BumpUploadGrantGenerationParams{
		ID: row.ID, OwnerID: row.OwnerID, UpdatedAt: pgTime(s.now().UTC()),
	})
	if err != nil {
		return 0, internal()
	}
	return generation, nil
}

func (s *Service) encodeMediaCursor(payload mediaCursorPayload) string {
	body, _ := json.Marshal(payload)
	mac := hmac.New(sha256.New, s.cursorKey)
	_, _ = mac.Write(body)
	packet := append(body, mac.Sum(nil)...)
	return base64.RawURLEncoding.EncodeToString(packet)
}

func (s *Service) decodeMediaCursor(value string) (mediaCursorPayload, error) {
	packet, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(packet) <= sha256.Size {
		return mediaCursorPayload{}, errors.New("invalid cursor")
	}
	body, signature := packet[:len(packet)-sha256.Size], packet[len(packet)-sha256.Size:]
	mac := hmac.New(sha256.New, s.cursorKey)
	_, _ = mac.Write(body)
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return mediaCursorPayload{}, errors.New("invalid cursor signature")
	}
	var payload mediaCursorPayload
	if err := json.Unmarshal(body, &payload); err != nil || payload.OwnerID == "" || payload.CapturedAt.IsZero() || payload.MediaID == "" {
		return mediaCursorPayload{}, errors.New("invalid cursor payload")
	}
	return payload, nil
}

func (s *Service) ensureUploadAlbums(ctx context.Context, actor Actor, row dbgen.MinegUploadSession, input CreateInput) error {
	if len(input.ClientAlbums) == 0 {
		return nil
	}
	return s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		for _, clientAlbum := range input.ClientAlbums {
			album, err := q.EnsureClientAlbum(ctx, dbgen.EnsureClientAlbumParams{
				OwnerID:              actor.RawUserID,
				ClientAlbumID:        pgtype.Text{String: clientAlbum.ID, Valid: true},
				DeviceInstallationID: pgtype.Text{String: input.DeviceInstallationID, Valid: true},
				DisplayName:          pgtype.Text{String: clientAlbum.Name, Valid: true},
			})
			if err != nil {
				return err
			}
			if err := q.LinkUploadSessionToClientAlbum(ctx, dbgen.LinkUploadSessionToClientAlbumParams{
				UploadSessionID: row.ID, AlbumID: album.ID,
			}); err != nil {
				return err
			}
		}
		if row.MediaID.Valid {
			return q.LinkUploadSessionAlbumsToMedia(ctx, dbgen.LinkUploadSessionAlbumsToMediaParams{
				UploadSessionID: row.ID, MediaID: row.MediaID,
			})
		}
		return nil
	})
}

func (s *Service) recoverCreateRace(ctx context.Context, actor Actor, input CreateInput, hash [32]byte) (SessionResult, error) {
	row, err := dbgen.New(s.pool).FindUploadByIdempotency(ctx, dbgen.FindUploadByIdempotencyParams{OwnerID: actor.RawUserID, IdempotencyKey: input.IdempotencyKey})
	if err != nil {
		return SessionResult{}, internal()
	}
	if !bytes.Equal(row.RequestHash, hash[:]) {
		return SessionResult{}, conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for another media upload.")
	}
	if err := s.ensureUploadAlbums(ctx, actor, row, input); err != nil {
		return SessionResult{}, err
	}
	return s.sessionResult(ctx, row, true)
}

func validateCreate(input CreateInput, ownerID string) ([]objectstore.MediaResourcePlan, CreateInput, error) {
	validPayload := (input.ProtocolVersion == "stage03-v2" || input.ProtocolVersion == "stage04-v1" || input.ProtocolVersion == "stage05-v1") && len(input.ContentSHA256) == 32 &&
		bytes.Equal(input.Dedupe, input.ContentSHA256) && input.MimeType != "" && len(input.MimeType) <= 127
	if !idempotencyPattern.MatchString(input.IdempotencyKey) || uuid.Validate(input.ClientMediaID) != nil || len(input.Dedupe) != 32 || input.ContentRevision <= 0 ||
		!validMediaType(input.MediaType) || input.CapturedAt.IsZero() || !validPayload || len(input.Resources) < 1 || len(input.Resources) > 8 {
		return nil, CreateInput{}, validation("UPLOAD_INVALID", "Invalid media upload", "The upload session metadata is invalid.")
	}
	normalized := input
	normalized.RequestID = ""
	normalized.Resources = append([]ResourceInput(nil), input.Resources...)
	normalized.ClientAlbums = make([]ClientAlbumInput, 0, len(input.ClientAlbums))
	if input.ProtocolVersion != "stage05-v1" && (input.Width != nil || input.Height != nil || input.DurationMS != nil) {
		return nil, CreateInput{}, validation("UPLOAD_INVALID", "Invalid media upload", "Display metadata is supported by Stage 05 uploads only.")
	}
	if (input.Width != nil && *input.Width <= 0) || (input.Height != nil && *input.Height <= 0) || (input.DurationMS != nil && *input.DurationMS < 0) {
		return nil, CreateInput{}, validation("UPLOAD_INVALID", "Invalid media upload", "Display metadata must use positive dimensions and a non-negative duration.")
	}
	if input.ProtocolVersion == "stage04-v1" && (len(input.DeviceInstallationID) < 8 || len(input.DeviceInstallationID) > 128) {
		return nil, CreateInput{}, validation("UPLOAD_INVALID", "Invalid media upload", "A Stage 04 upload requires a stable device installation ID.")
	}
	if len(input.ClientAlbums) > 200 {
		return nil, CreateInput{}, validation("UPLOAD_ALBUM_INVALID", "Invalid client albums", "Too many client albums were supplied for one media item.")
	}
	albums := make(map[string]struct{}, len(input.ClientAlbums))
	for _, album := range input.ClientAlbums {
		album.ID = strings.TrimSpace(album.ID)
		album.Name = strings.TrimSpace(album.Name)
		if input.DeviceInstallationID == "" || album.ID == "" || len(album.ID) > 256 || album.Name == "" || len(album.Name) > 256 {
			return nil, CreateInput{}, validation("UPLOAD_ALBUM_INVALID", "Invalid client albums", "Client album IDs and names must be bounded non-empty strings.")
		}
		if _, duplicate := albums[album.ID]; duplicate {
			return nil, CreateInput{}, validation("UPLOAD_ALBUM_INVALID", "Invalid client albums", "Client album IDs must be unique for one media item.")
		}
		albums[album.ID] = struct{}{}
		normalized.ClientAlbums = append(normalized.ClientAlbums, album)
	}
	sort.Slice(normalized.ClientAlbums, func(i, j int) bool { return normalized.ClientAlbums[i].ID < normalized.ClientAlbums[j].ID })
	sort.Slice(normalized.Resources, func(i, j int) bool { return normalized.Resources[i].ID < normalized.Resources[j].ID })
	plans := make([]objectstore.MediaResourcePlan, 0, len(normalized.Resources))
	resourceTypes := make(map[string]struct{}, len(normalized.Resources))
	for index := range normalized.Resources {
		resource := &normalized.Resources[index]
		resource.MimeType = strings.TrimSpace(resource.MimeType)
		if resource.MimeType == "" && input.ProtocolVersion != "stage05-v1" {
			// Stage 03/04 send the top-level original MIME only. Stage 05 sends
			// it per resource; preserving this fallback keeps resumable old tasks valid.
			resource.MimeType = normalized.MimeType
		}
		resourceSize := resource.ContentSize
		if uuid.Validate(resource.ID) != nil || !validResourceType(resource.Type) || len(resource.SHA256) != 32 || resourceSize < 1 || len(resource.Parts) < 1 || len(resource.Parts) > 10000 ||
			len(resource.MimeType) < 3 || len(resource.MimeType) > 127 ||
			(input.ProtocolVersion != "stage05-v1" && resource.Type != "ORIGINAL") {
			return nil, CreateInput{}, validation("UPLOAD_RESOURCE_INVALID", "Invalid media resource", "A resource plan is invalid.")
		}
		if _, exists := resourceTypes[resource.Type]; exists {
			return nil, CreateInput{}, validation("UPLOAD_RESOURCE_DUPLICATE", "Duplicate media resource", "Resource types must be unique within one media.")
		}
		resourceTypes[resource.Type] = struct{}{}
		if resource.Type == "ORIGINAL" && !bytes.Equal(resource.SHA256, input.ContentSHA256) {
			return nil, CreateInput{}, validation("UPLOAD_RESOURCE_INVALID", "Invalid media resource", "The original resource digest must match the media content digest.")
		}
		sort.Slice(resource.Parts, func(i, j int) bool { return resource.Parts[i].Number < resource.Parts[j].Number })
		var total int64
		partPlans := make([]objectstore.MediaPartPlan, 0, len(resource.Parts))
		partMaximum := int64(objectstore.OriginalMediaPartMaximum)
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
		plans = append(plans, objectstore.MediaResourcePlan{ID: resource.ID, ObjectKey: fmt.Sprintf("media/%s/pending/%s.%s", ownerID, resource.ID, resourceObjectSuffix(resource.Type)), Purpose: "MEDIA_ORIGINAL", ContentSize: resourceSize, SHA256: resource.SHA256, Parts: partPlans})
	}
	if _, exists := resourceTypes["ORIGINAL"]; !exists {
		return nil, CreateInput{}, validation("UPLOAD_RESOURCE_INVALID", "Invalid media resource", "Every media upload requires an original resource.")
	}
	return plans, normalized, nil
}

func resourceObjectSuffix(resourceType string) string {
	return strings.ToLower(resourceType)
}

func hashCreate(input CreateInput) [32]byte {
	if input.ProtocolVersion == "stage03-v2" || input.ProtocolVersion == "stage04-v1" {
		// Keep the exact historical preimage for a persisted legacy idempotency
		// key. Resource MIME is a Stage 05 field and must not make an old retry
		// look like a distinct request after an upgrade.
		type legacyResourceInput struct {
			ID          string
			Type        string
			ContentSize int64
			SHA256      []byte
			Parts       []PartInput
		}
		type legacyCreateInput struct {
			ProtocolVersion      string
			IdempotencyKey       string
			ClientMediaID        string
			Dedupe               []byte
			ContentRevision      int32
			MediaType            string
			CapturedAt           time.Time
			ContentSHA256        []byte
			MimeType             string
			Resources            []legacyResourceInput
			DeviceInstallationID string
			ClientAlbums         []ClientAlbumInput
			RequestID            string
		}
		resources := make([]legacyResourceInput, 0, len(input.Resources))
		for _, resource := range input.Resources {
			resources = append(resources, legacyResourceInput{
				ID: resource.ID, Type: resource.Type, ContentSize: resource.ContentSize,
				SHA256: resource.SHA256, Parts: resource.Parts,
			})
		}
		encoded, _ := json.Marshal(legacyCreateInput{
			ProtocolVersion: input.ProtocolVersion, IdempotencyKey: input.IdempotencyKey,
			ClientMediaID: input.ClientMediaID, Dedupe: input.Dedupe, ContentRevision: input.ContentRevision,
			MediaType: input.MediaType, CapturedAt: input.CapturedAt, ContentSHA256: input.ContentSHA256,
			MimeType: input.MimeType, Resources: resources, DeviceInstallationID: input.DeviceInstallationID,
			ClientAlbums: input.ClientAlbums, RequestID: input.RequestID,
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
func pgInt4(value *int32) pgtype.Int4 {
	if value == nil {
		return pgtype.Int4{}
	}
	return pgtype.Int4{Int32: *value, Valid: true}
}
func pgInt8(value *int64) pgtype.Int8 {
	if value == nil {
		return pgtype.Int8{}
	}
	return pgtype.Int8{Int64: *value, Valid: true}
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
