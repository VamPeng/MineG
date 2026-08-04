package media

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"regexp"
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
	cursorScope                 = "private-media-v2"
	readGrantLifetime           = 5 * time.Minute
	ossImagePreviewMaximumBytes = 5 * 1024 * 1024
	deliveryOriginalResource    = "ORIGINAL_RESOURCE"
	deliveryOSSImageThumbnail   = "OSS_IMAGE_THUMBNAIL"
)

var idempotencyPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,128}$`)

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
	CursorKey []byte
	Objects   objectstore.MediaReadObjects
	Now       func() time.Time
}

type Service struct {
	pool      *database.Pool
	cursorKey []byte
	objects   objectstore.MediaReadObjects
	now       func() time.Time
}

func New(pool *database.Pool, config Config) *Service {
	if len(config.CursorKey) < 32 {
		config.CursorKey = []byte("mineg-local-private-media-cursor-key-change-before-deployment")
	}
	if config.Objects == nil {
		config.Objects = objectstore.DisabledMediaReadObjects{}
	}
	if config.Now == nil {
		config.Now = time.Now
	}
	return &Service{pool: pool, cursorKey: config.CursorKey, objects: config.Objects, now: config.Now}
}

type Resource struct {
	ID          string `json:"resource_id"`
	Type        string `json:"resource_type"`
	MimeType    string `json:"mime_type"`
	ContentSize int64  `json:"content_size"`
	SHA256      string `json:"content_sha256"`
}

type Summary struct {
	ID                string    `json:"id"`
	MediaType         string    `json:"media_type"`
	ContentRevision   int32     `json:"content_revision"`
	CapturedAt        time.Time `json:"captured_at"`
	CreatedAt         time.Time `json:"created_at"`
	DurationMS        *int64    `json:"duration_ms,omitempty"`
	OriginalTotalSize int64     `json:"original_total_size"`
	PreviewResource   *Resource `json:"preview_resource,omitempty"`
}

type Page struct {
	Items      []Summary `json:"items"`
	NextCursor *string   `json:"next_cursor"`
}

type Detail struct {
	ID                string     `json:"id"`
	MediaType         string     `json:"media_type"`
	ContentRevision   int32      `json:"content_revision"`
	CapturedAt        time.Time  `json:"captured_at"`
	CreatedAt         time.Time  `json:"created_at"`
	Width             *int32     `json:"width,omitempty"`
	Height            *int32     `json:"height,omitempty"`
	DurationMS        *int64     `json:"duration_ms,omitempty"`
	OriginalTotalSize int64      `json:"original_total_size"`
	Resources         []Resource `json:"resources"`
}

type AccessInput struct {
	Purpose string
	Variant string
}

type AccessResource struct {
	Resource
	SupportsRange     bool                    `json:"supports_range"`
	DeliveryMode      string                  `json:"delivery_mode"`
	MaximumOutputSize *int64                  `json:"maximum_output_size,omitempty"`
	Grant             objectstore.ObjectGrant `json:"grant"`
}

type AccessResult struct {
	MediaID   string           `json:"media_id"`
	Purpose   string           `json:"purpose"`
	Variant   *string          `json:"variant,omitempty"`
	IssuedAt  time.Time        `json:"issued_at"`
	ExpiresAt time.Time        `json:"expires_at"`
	Resources []AccessResource `json:"resources"`
}

type TrashResult struct {
	MediaID   string    `json:"media_id"`
	Outcome   string    `json:"outcome"`
	TrashedAt time.Time `json:"trashed_at"`
}

type cursorPayload struct {
	OwnerID    string    `json:"owner_id"`
	CapturedAt time.Time `json:"captured_at"`
	MediaID    string    `json:"media_id"`
	Scope      string    `json:"scope"`
}

func (s *Service) List(ctx context.Context, actor Actor, cursor string, limit int32) (Page, error) {
	if err := validateActor(actor); err != nil {
		return Page{}, err
	}
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	queries := dbgen.New(s.pool)
	queryLimit := limit + 1
	var page Page
	page.Items = make([]Summary, 0, queryLimit)
	if cursor == "" {
		rows, err := queries.ListPrivateMedia(ctx, dbgen.ListPrivateMediaParams{OwnerID: actor.RawUserID, Limit: queryLimit})
		if err != nil {
			return Page{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, summaryFromValues(row.ID, row.MediaType, row.ContentRevision, row.CapturedAt, row.CreatedAt, row.DurationMs, row.OriginalTotalSize))
		}
	} else {
		payload, decodeErr := s.decodeCursor(cursor)
		if decodeErr != nil || payload.Scope != cursorScope || payload.OwnerID != actor.UserID || uuid.Validate(payload.MediaID) != nil {
			return Page{}, validation("CURSOR_INVALID", "Invalid cursor", "The media pagination cursor is invalid.")
		}
		rows, err := queries.ListPrivateMediaAfter(ctx, dbgen.ListPrivateMediaAfterParams{
			OwnerID: actor.RawUserID, CapturedAt: pgTime(payload.CapturedAt), ID: toPGUUID(uuid.MustParse(payload.MediaID)), Limit: queryLimit,
		})
		if err != nil {
			return Page{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, summaryFromValues(row.ID, row.MediaType, row.ContentRevision, row.CapturedAt, row.CreatedAt, row.DurationMs, row.OriginalTotalSize))
		}
	}
	if len(page.Items) > int(limit) {
		page.Items = page.Items[:limit]
		last := page.Items[len(page.Items)-1]
		next := s.encodeCursor(cursorPayload{OwnerID: actor.UserID, CapturedAt: last.CapturedAt, MediaID: last.ID, Scope: cursorScope})
		page.NextCursor = &next
	}
	return page, nil
}

func (s *Service) Detail(ctx context.Context, actor Actor, mediaID string) (Detail, error) {
	if err := validateActor(actor); err != nil {
		return Detail{}, err
	}
	if uuid.Validate(mediaID) != nil {
		return Detail{}, validation("PRIVATE_MEDIA_INVALID", "Invalid media", "The media ID must be a UUID.")
	}
	queries := dbgen.New(s.pool)
	row, err := queries.FindPrivateMedia(ctx, dbgen.FindPrivateMediaParams{ID: toPGUUID(uuid.MustParse(mediaID)), OwnerID: actor.RawUserID})
	if errors.Is(err, pgx.ErrNoRows) {
		return Detail{}, notFound()
	}
	if err != nil {
		return Detail{}, internal()
	}
	resources, err := queries.ListPrivateMediaResources(ctx, dbgen.ListPrivateMediaResourcesParams{MediaID: toPGUUID(uuid.MustParse(mediaID)), OwnerID: actor.RawUserID})
	if err != nil {
		return Detail{}, internal()
	}
	result := Detail{
		ID: uuidString(row.ID), MediaType: row.MediaType, ContentRevision: row.ContentRevision,
		CapturedAt: row.CapturedAt.Time, CreatedAt: row.CreatedAt.Time,
		Width: optionalInt32(row.Width), Height: optionalInt32(row.Height), DurationMS: optionalInt64(row.DurationMs),
		OriginalTotalSize: row.OriginalTotalSize, Resources: make([]Resource, 0, len(resources)),
	}
	for _, resource := range resources {
		result.Resources = append(result.Resources, Resource{
			ID: uuidString(resource.ID), Type: resource.ResourceType, MimeType: resource.MimeType,
			ContentSize: resource.ContentSize, SHA256: base64.RawStdEncoding.EncodeToString(resource.ContentSha256),
		})
	}
	return result, nil
}

func (s *Service) Access(ctx context.Context, actor Actor, mediaID string, input AccessInput) (AccessResult, error) {
	if err := validateActor(actor); err != nil {
		return AccessResult{}, err
	}
	mediaUUID, err := parseMediaID(mediaID)
	if err != nil {
		return AccessResult{}, err
	}
	if err := validateAccessInput(input); err != nil {
		return AccessResult{}, err
	}
	result := AccessResult{MediaID: mediaID, Purpose: input.Purpose, Resources: make([]AccessResource, 0)}
	if input.Variant != "" {
		variant := input.Variant
		result.Variant = &variant
	}
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		queries := dbgen.New(tx)
		locked, lockErr := queries.LockPrivateMediaForAccess(ctx, dbgen.LockPrivateMediaForAccessParams{ID: mediaUUID, OwnerID: actor.RawUserID})
		if errors.Is(lockErr, pgx.ErrNoRows) {
			return notFound()
		}
		if lockErr != nil {
			return lockErr
		}
		rows, listErr := queries.ListPrivateMediaAccessResources(ctx, mediaUUID)
		if listErr != nil {
			return listErr
		}
		selected, selectErr := selectAccessResources(locked.MediaType, rows, input)
		if selectErr != nil {
			return selectErr
		}
		issuedAt := s.now().UTC()
		result.IssuedAt = issuedAt
		for _, resource := range selected {
			deliveryMode := deliveryOriginalResource
			var maximumOutputSize *int64
			var grant objectstore.ObjectGrant
			var grantErr error
			if input.Purpose == "VIEW" && input.Variant == "THUMBNAIL" &&
				isOSSImagePreviewCandidate(locked.MediaType, resource) {
				deliveryMode = deliveryOSSImageThumbnail
				maximum := int64(ossImagePreviewMaximumBytes)
				maximumOutputSize = &maximum
				grant, grantErr = s.objects.IssueMediaImagePreview(ctx, resource.ObjectKey, readGrantLifetime)
			} else {
				grant, grantErr = s.objects.IssueMediaRead(ctx, resource.ObjectKey, readGrantLifetime)
			}
			if grantErr != nil {
				return objectError(grantErr)
			}
			if !validMediaReadGrant(grant, issuedAt) {
				return objectError(errors.New("invalid media read grant"))
			}
			// A presigning SDK timestamps each grant when it is issued, which can
			// be a few milliseconds after the API recorded issuedAt. Return the
			// earliest actual grant expiry rather than rejecting that valid grant
			// against a deadline calculated before signing began.
			result.ExpiresAt = earliestMediaReadGrantExpiry(result.ExpiresAt, grant.ExpiresAt)
			result.Resources = append(result.Resources, AccessResource{
				Resource:      Resource{ID: uuidString(resource.ID), Type: resource.ResourceType, MimeType: resource.MimeType, ContentSize: resource.ContentSize, SHA256: base64.RawStdEncoding.EncodeToString(resource.ContentSha256)},
				SupportsRange: input.Purpose == "STREAM", DeliveryMode: deliveryMode,
				MaximumOutputSize: maximumOutputSize, Grant: grant,
			})
		}
		return nil
	})
	if err != nil {
		var mediaErr *Error
		if errors.As(err, &mediaErr) {
			return AccessResult{}, mediaErr
		}
		return AccessResult{}, internal()
	}
	return result, nil
}

func (s *Service) Trash(ctx context.Context, actor Actor, mediaID, idempotencyKey, requestID string) (TrashResult, error) {
	if err := validateActor(actor); err != nil {
		return TrashResult{}, err
	}
	mediaUUID, err := parseMediaID(mediaID)
	if err != nil {
		return TrashResult{}, err
	}
	if !idempotencyPattern.MatchString(idempotencyKey) {
		return TrashResult{}, validation("IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "The Idempotency-Key header is invalid.")
	}
	requestHash := sha256.Sum256([]byte(mediaID))
	var result TrashResult
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		queries := dbgen.New(tx)
		if lockErr := queries.AcquireTrashIdempotencyLock(ctx, dbgen.AcquireTrashIdempotencyLockParams{OwnerID: actor.UserID, IdempotencyKey: idempotencyKey}); lockErr != nil {
			return lockErr
		}
		prior, priorErr := queries.FindTrashRequest(ctx, dbgen.FindTrashRequestParams{OwnerID: actor.RawUserID, IdempotencyKey: idempotencyKey})
		if priorErr == nil {
			if !equalBytes(prior.RequestHash, requestHash[:]) {
				return conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for another private-media delete request.")
			}
			result = TrashResult{MediaID: uuidString(prior.MediaID), Outcome: prior.Outcome, TrashedAt: prior.TrashedAt.Time}
			return nil
		}
		if !errors.Is(priorErr, pgx.ErrNoRows) {
			return priorErr
		}
		if _, lockErr := queries.LockPrivateMediaForTrash(ctx, dbgen.LockPrivateMediaForTrashParams{ID: mediaUUID, OwnerID: actor.RawUserID}); errors.Is(lockErr, pgx.ErrNoRows) {
			return notFound()
		} else if lockErr != nil {
			return lockErr
		}
		now := s.now().UTC()
		outcome := "TRASHED"
		trashedAt := now
		record, recordErr := queries.FindActiveTrashRecord(ctx, dbgen.FindActiveTrashRecordParams{MediaID: mediaUUID, OwnerID: actor.RawUserID})
		if recordErr == nil {
			outcome = "ALREADY_TRASHED"
			trashedAt = record.TrashedAt.Time
		} else if errors.Is(recordErr, pgx.ErrNoRows) {
			if createErr := queries.CreateTrashRecord(ctx, dbgen.CreateTrashRecordParams{MediaID: mediaUUID, OwnerID: actor.RawUserID, TrashedAt: pgTime(now)}); createErr != nil {
				return createErr
			}
			if versionErr := queries.BumpPrivateMediaAccessVersion(ctx, mediaUUID); versionErr != nil {
				return versionErr
			}
			if _, deactivateErr := queries.DeactivateActiveShare(ctx, dbgen.DeactivateActiveShareParams{MediaID: mediaUUID, UnsharedAt: pgTime(now)}); deactivateErr != nil {
				return deactivateErr
			}
			if auditErr := queries.RecordAuditEvent(ctx, dbgen.RecordAuditEventParams{
				ActorType: "USER", ActorID: actor.RawUserID, Action: "PRIVATE_MEDIA_TRASH", TargetType: "MEDIA", TargetID: mediaUUID,
				Result: "SUCCESS", RequestID: requestID, Metadata: []byte(`{"mode":"LOGICAL"}`),
			}); auditErr != nil {
				return auditErr
			}
		} else {
			return recordErr
		}
		if createErr := queries.CreateTrashRequest(ctx, dbgen.CreateTrashRequestParams{
			OwnerID: actor.RawUserID, IdempotencyKey: idempotencyKey, MediaID: mediaUUID, RequestHash: requestHash[:], Outcome: outcome, TrashedAt: pgTime(trashedAt),
		}); createErr != nil {
			return createErr
		}
		result = TrashResult{MediaID: mediaID, Outcome: outcome, TrashedAt: trashedAt}
		return nil
	})
	if err != nil {
		var mediaErr *Error
		if errors.As(err, &mediaErr) {
			return TrashResult{}, mediaErr
		}
		return TrashResult{}, internal()
	}
	return result, nil
}

func summaryFromValues(id pgtype.UUID, mediaType string, contentRevision int32, capturedAt, createdAt pgtype.Timestamptz, duration pgtype.Int8, originalTotalSize int64) Summary {
	return Summary{
		ID: uuidString(id), MediaType: mediaType, ContentRevision: contentRevision,
		CapturedAt: capturedAt.Time, CreatedAt: createdAt.Time,
		DurationMS: optionalInt64(duration), OriginalTotalSize: originalTotalSize,
	}
}

func (s *Service) encodeCursor(payload cursorPayload) string {
	body, _ := json.Marshal(payload)
	mac := hmac.New(sha256.New, s.cursorKey)
	_, _ = mac.Write(body)
	return base64.RawURLEncoding.EncodeToString(append(body, mac.Sum(nil)...))
}

func (s *Service) decodeCursor(value string) (cursorPayload, error) {
	packet, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(packet) <= sha256.Size {
		return cursorPayload{}, errors.New("invalid cursor")
	}
	body, signature := packet[:len(packet)-sha256.Size], packet[len(packet)-sha256.Size:]
	mac := hmac.New(sha256.New, s.cursorKey)
	_, _ = mac.Write(body)
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return cursorPayload{}, errors.New("invalid cursor signature")
	}
	var payload cursorPayload
	if err := json.Unmarshal(body, &payload); err != nil || payload.OwnerID == "" || payload.CapturedAt.IsZero() || payload.MediaID == "" {
		return cursorPayload{}, errors.New("invalid cursor payload")
	}
	return payload, nil
}

func validateActor(actor Actor) error {
	if actor.Status != "APPROVED" || !actor.RawUserID.Valid || actor.UserID == "" {
		return &Error{Code: "ACCOUNT_NOT_APPROVED", Status: 403, Title: "Account not approved", Detail: "An approved member session is required."}
	}
	return nil
}

func validation(code, title, detail string) *Error {
	return &Error{Code: code, Status: 422, Title: title, Detail: detail}
}

func notFound() *Error {
	return &Error{Code: "PRIVATE_MEDIA_NOT_FOUND", Status: 404, Title: "Media not found", Detail: "The requested private media is unavailable."}
}

func internal() *Error {
	return &Error{Code: "INTERNAL_ERROR", Status: 500, Title: "Internal error", Detail: "The private media request could not be completed.", Retryable: true}
}

func pgTime(value time.Time) pgtype.Timestamptz {
	return pgtype.Timestamptz{Time: value.UTC(), Valid: true}
}

func toPGUUID(value uuid.UUID) pgtype.UUID {
	return pgtype.UUID{Bytes: value, Valid: true}
}

func uuidString(value pgtype.UUID) string {
	if !value.Valid {
		return ""
	}
	return uuid.UUID(value.Bytes).String()
}

func optionalInt32(value pgtype.Int4) *int32 {
	if !value.Valid {
		return nil
	}
	result := value.Int32
	return &result
}

func optionalInt64(value pgtype.Int8) *int64 {
	if !value.Valid {
		return nil
	}
	result := value.Int64
	return &result
}

func parseMediaID(value string) (pgtype.UUID, error) {
	parsed, err := uuid.Parse(value)
	if err != nil {
		return pgtype.UUID{}, validation("PRIVATE_MEDIA_INVALID", "Invalid media", "The media ID must be a UUID.")
	}
	return toPGUUID(parsed), nil
}

func validateAccessInput(input AccessInput) error {
	valid := (input.Purpose == "VIEW" && (input.Variant == "THUMBNAIL" || input.Variant == "DETAIL")) ||
		(input.Purpose == "STREAM" && input.Variant == "")
	if !valid {
		return validation("PRIVATE_MEDIA_ACCESS_INVALID", "Invalid media access", "The purpose and variant combination is invalid.")
	}
	return nil
}

func selectAccessResources(mediaType string, resources []dbgen.ListPrivateMediaAccessResourcesRow, input AccessInput) ([]dbgen.ListPrivateMediaAccessResourcesRow, error) {
	byType := make(map[string]dbgen.ListPrivateMediaAccessResourcesRow, len(resources))
	for _, resource := range resources {
		byType[resource.ResourceType] = resource
	}
	first := func(types ...string) (dbgen.ListPrivateMediaAccessResourcesRow, bool) {
		for _, resourceType := range types {
			if resource, exists := byType[resourceType]; exists {
				return resource, true
			}
		}
		return dbgen.ListPrivateMediaAccessResourcesRow{}, false
	}
	switch input.Purpose {
	case "VIEW":
		if input.Variant == "THUMBNAIL" {
			if resource, exists := first("THUMBNAIL", "VIDEO_COVER"); exists {
				return []dbgen.ListPrivateMediaAccessResourcesRow{resource}, nil
			}
			if resource, exists := first("ORIGINAL"); exists && isImageThumbnailCandidate(mediaType, resource) {
				return []dbgen.ListPrivateMediaAccessResourcesRow{resource}, nil
			}
		} else {
			if resource, exists := first("ORIGINAL"); exists && isImageDetailOriginalCandidate(mediaType, resource) {
				return []dbgen.ListPrivateMediaAccessResourcesRow{resource}, nil
			}
			if resource, exists := first("PREVIEW", "DYNAMIC_PREVIEW", "THUMBNAIL", "VIDEO_COVER"); exists {
				return []dbgen.ListPrivateMediaAccessResourcesRow{resource}, nil
			}
		}
	case "STREAM":
		if resource, exists := first("PREVIEW", "DYNAMIC_PREVIEW"); exists {
			return []dbgen.ListPrivateMediaAccessResourcesRow{resource}, nil
		}
	}
	return nil, accessUnavailable()
}

func isImageThumbnailCandidate(mediaType string, resource dbgen.ListPrivateMediaAccessResourcesRow) bool {
	return isOSSImagePreviewCandidate(mediaType, resource) ||
		(resource.ResourceType == "ORIGINAL" && mediaType == "PHOTO" &&
			resource.MimeType == "image/svg+xml" && resource.ContentSize <= ossImagePreviewMaximumBytes)
}

func isImageDetailOriginalCandidate(mediaType string, resource dbgen.ListPrivateMediaAccessResourcesRow) bool {
	return resource.ResourceType == "ORIGINAL" && mediaType != "VIDEO" &&
		strings.HasPrefix(resource.MimeType, "image/")
}

func isOSSImagePreviewCandidate(mediaType string, resource dbgen.ListPrivateMediaAccessResourcesRow) bool {
	if resource.ResourceType != "ORIGINAL" || (mediaType != "PHOTO" && mediaType != "GIF") {
		return false
	}
	switch resource.MimeType {
	case "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp":
		return true
	default:
		return false
	}
}

func validMediaReadGrant(grant objectstore.ObjectGrant, issuedAt time.Time) bool {
	return grant.Method == "GET" && grant.ExpiresAt.After(issuedAt)
}

func earliestMediaReadGrantExpiry(current, candidate time.Time) time.Time {
	if current.IsZero() || candidate.Before(current) {
		return candidate
	}
	return current
}

func equalBytes(left, right []byte) bool { return hmac.Equal(left, right) }

func conflict(code, title, detail string) *Error {
	return &Error{Code: code, Status: 409, Title: title, Detail: detail}
}

func accessUnavailable() *Error {
	return &Error{Code: "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE", Status: 409, Title: "Media resource unavailable", Detail: "No compatible private-media resource is available for this purpose."}
}

func objectError(error) *Error {
	return &Error{Code: "PRIVATE_MEDIA_ACCESS_UNAVAILABLE", Status: 503, Title: "Media access unavailable", Detail: "A private-media read authorization could not be issued.", Retryable: true}
}
