package media

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/vampeng/mineg/service/internal/platform/database/dbgen"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

const (
	familyCursorScope = "family-media-v1"
	trashCursorScope  = "trash-media-v1"
)

type ShareResult struct {
	MediaID     string    `json:"media_id"`
	State       string    `json:"state"`
	Outcome     string    `json:"outcome"`
	EffectiveAt time.Time `json:"effective_at"`
}

type FamilyOwner struct {
	ID       string `json:"id"`
	Nickname string `json:"nickname"`
}

type FamilySummary struct {
	ID                string      `json:"id"`
	Owner             FamilyOwner `json:"owner"`
	MediaType         string      `json:"media_type"`
	CapturedAt        time.Time   `json:"captured_at"`
	CreatedAt         time.Time   `json:"created_at"`
	DurationMS        *int64      `json:"duration_ms,omitempty"`
	OriginalTotalSize int64       `json:"original_total_size"`
}

type FamilyPage struct {
	Items      []FamilySummary `json:"items"`
	NextCursor *string         `json:"next_cursor"`
}

type FamilyDetail struct {
	ID                string      `json:"id"`
	Owner             FamilyOwner `json:"owner"`
	MediaType         string      `json:"media_type"`
	CapturedAt        time.Time   `json:"captured_at"`
	CreatedAt         time.Time   `json:"created_at"`
	Width             *int32      `json:"width,omitempty"`
	Height            *int32      `json:"height,omitempty"`
	DurationMS        *int64      `json:"duration_ms,omitempty"`
	OriginalTotalSize int64       `json:"original_total_size"`
	Resources         []Resource  `json:"resources"`
}

type TrashSummary struct {
	ID                string    `json:"id"`
	MediaType         string    `json:"media_type"`
	CapturedAt        time.Time `json:"captured_at"`
	CreatedAt         time.Time `json:"created_at"`
	DurationMS        *int64    `json:"duration_ms,omitempty"`
	OriginalTotalSize int64     `json:"original_total_size"`
	TrashedAt         time.Time `json:"trashed_at"`
}

type TrashPage struct {
	Items      []TrashSummary `json:"items"`
	NextCursor *string        `json:"next_cursor"`
}

type RestoreResult struct {
	MediaID    string    `json:"media_id"`
	Outcome    string    `json:"outcome"`
	RestoredAt time.Time `json:"restored_at"`
}

type FeedbackInput struct {
	Category             string `json:"category"`
	Description          string `json:"description"`
	Contact              string `json:"contact"`
	AppVersion           string `json:"app_version"`
	Platform             string `json:"platform"`
	OSVersion            string `json:"os_version"`
	DeviceInstallationID string `json:"device_installation_id"`
}

type FeedbackResult struct {
	FeedbackID string    `json:"feedback_id"`
	Outcome    string    `json:"outcome"`
	CreatedAt  time.Time `json:"created_at"`
}

type familyCursorPayload struct {
	ViewerID   string    `json:"viewer_id"`
	Filter     string    `json:"filter"`
	CapturedAt time.Time `json:"captured_at"`
	MediaID    string    `json:"media_id"`
	Scope      string    `json:"scope"`
}

type trashCursorPayload struct {
	OwnerID   string    `json:"owner_id"`
	TrashedAt time.Time `json:"trashed_at"`
	MediaID   string    `json:"media_id"`
	Scope     string    `json:"scope"`
}

func (s *Service) Share(ctx context.Context, actor Actor, mediaID, targetState, idempotencyKey, requestID string) (ShareResult, error) {
	if err := validateActor(actor); err != nil {
		return ShareResult{}, err
	}
	mediaUUID, err := parseMediaID(mediaID)
	if err != nil {
		return ShareResult{}, err
	}
	if targetState != "ACTIVE" && targetState != "INACTIVE" {
		return ShareResult{}, validation("SHARE_STATE_INVALID", "Invalid share state", "The requested share state is invalid.")
	}
	if !idempotencyPattern.MatchString(idempotencyKey) {
		return ShareResult{}, validation("IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "The Idempotency-Key header is invalid.")
	}
	requestHash := sha256.Sum256([]byte(mediaID + ":" + targetState))
	var result ShareResult
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		queries := dbgen.New(tx)
		if lockErr := queries.AcquireShareIdempotencyLock(ctx, dbgen.AcquireShareIdempotencyLockParams{OwnerID: actor.UserID, IdempotencyKey: idempotencyKey}); lockErr != nil {
			return lockErr
		}
		prior, priorErr := queries.FindShareRequest(ctx, dbgen.FindShareRequestParams{OwnerID: actor.RawUserID, IdempotencyKey: idempotencyKey})
		if priorErr == nil {
			if !equalBytes(prior.RequestHash, requestHash[:]) {
				return conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for another share request.")
			}
			result = ShareResult{MediaID: uuidString(prior.MediaID), State: prior.RequestedState, Outcome: prior.Outcome, EffectiveAt: prior.EffectiveAt.Time}
			return nil
		}
		if !errors.Is(priorErr, pgx.ErrNoRows) {
			return priorErr
		}
		member, memberErr := queries.IsFixedFamilyMember(ctx, actor.RawUserID)
		if memberErr != nil {
			return memberErr
		}
		if !member {
			return &Error{Code: "FAMILY_MEMBERSHIP_REQUIRED", Status: 403, Title: "Family membership required", Detail: "The account is not enrolled in the fixed household."}
		}
		if _, lockErr := queries.LockPrivateMediaForShare(ctx, dbgen.LockPrivateMediaForShareParams{ID: mediaUUID, OwnerID: actor.RawUserID}); errors.Is(lockErr, pgx.ErrNoRows) {
			return notFound()
		} else if lockErr != nil {
			return lockErr
		}
		now := s.now().UTC()
		share, shareErr := queries.FindShare(ctx, dbgen.FindShareParams{MediaID: mediaUUID, OwnerID: actor.RawUserID})
		outcome := ""
		effectiveAt := now
		if targetState == "ACTIVE" {
			if shareErr == nil && share.State == "ACTIVE" {
				outcome = "ALREADY_SHARED"
				effectiveAt = share.SharedAt.Time
			} else if shareErr == nil || errors.Is(shareErr, pgx.ErrNoRows) {
				if activateErr := queries.ActivateShare(ctx, dbgen.ActivateShareParams{MediaID: mediaUUID, OwnerID: actor.RawUserID, SharedAt: pgTime(now)}); activateErr != nil {
					return activateErr
				}
				if versionErr := queries.BumpPrivateMediaAccessVersion(ctx, mediaUUID); versionErr != nil {
					return versionErr
				}
				outcome = "SHARED"
			} else {
				return shareErr
			}
		} else {
			if errors.Is(shareErr, pgx.ErrNoRows) || (shareErr == nil && share.State == "INACTIVE") {
				outcome = "ALREADY_UNSHARED"
				if shareErr == nil && share.UnsharedAt.Valid {
					effectiveAt = share.UnsharedAt.Time
				}
			} else if shareErr == nil {
				if _, inactivateErr := queries.InactivateShare(ctx, dbgen.InactivateShareParams{MediaID: mediaUUID, OwnerID: actor.RawUserID, UnsharedAt: pgTime(now)}); inactivateErr != nil {
					return inactivateErr
				}
				if versionErr := queries.BumpPrivateMediaAccessVersion(ctx, mediaUUID); versionErr != nil {
					return versionErr
				}
				outcome = "UNSHARED"
			} else {
				return shareErr
			}
		}
		if createErr := queries.CreateShareRequest(ctx, dbgen.CreateShareRequestParams{
			OwnerID: actor.RawUserID, IdempotencyKey: idempotencyKey, MediaID: mediaUUID,
			RequestedState: targetState, RequestHash: requestHash[:], Outcome: outcome, EffectiveAt: pgTime(effectiveAt),
		}); createErr != nil {
			return createErr
		}
		action := "PRIVATE_MEDIA_SHARE"
		if targetState == "INACTIVE" {
			action = "PRIVATE_MEDIA_UNSHARE"
		}
		if auditErr := queries.RecordAuditEvent(ctx, dbgen.RecordAuditEventParams{
			ActorType: "USER", ActorID: actor.RawUserID, Action: action, TargetType: "MEDIA", TargetID: mediaUUID,
			Result: "SUCCESS", RequestID: requestID, Metadata: []byte(`{"scope":"FIXED_FAMILY"}`),
		}); auditErr != nil {
			return auditErr
		}
		result = ShareResult{MediaID: mediaID, State: targetState, Outcome: outcome, EffectiveAt: effectiveAt}
		return nil
	})
	if err != nil {
		var mediaErr *Error
		if errors.As(err, &mediaErr) {
			return ShareResult{}, mediaErr
		}
		return ShareResult{}, internal()
	}
	return result, nil
}

func (s *Service) ListFamily(ctx context.Context, actor Actor, filter, cursor string, limit int32) (FamilyPage, error) {
	if err := validateActor(actor); err != nil {
		return FamilyPage{}, err
	}
	if filter == "" {
		filter = "all"
	}
	if filter != "all" && filter != "mine" {
		return FamilyPage{}, validation("FAMILY_FILTER_INVALID", "Invalid family filter", "The family filter must be all or mine.")
	}
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	queries := dbgen.New(s.pool)
	member, err := queries.IsFixedFamilyMember(ctx, actor.RawUserID)
	if err != nil {
		return FamilyPage{}, internal()
	}
	if !member {
		return FamilyPage{}, &Error{Code: "FAMILY_MEMBERSHIP_REQUIRED", Status: 403, Title: "Family membership required", Detail: "The account is not enrolled in the fixed household."}
	}
	queryLimit := limit + 1
	page := FamilyPage{Items: make([]FamilySummary, 0, queryLimit)}
	if cursor == "" {
		rows, listErr := queries.ListFamilyMedia(ctx, dbgen.ListFamilyMediaParams{ViewerID: actor.RawUserID, OwnerOnly: filter == "mine", PageLimit: queryLimit})
		if listErr != nil {
			return FamilyPage{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, familySummary(row.ID, row.OwnerID, row.OwnerNickname, row.MediaType, row.CapturedAt, row.CreatedAt, row.DurationMs, row.OriginalTotalSize))
		}
	} else {
		var payload familyCursorPayload
		if decodeErr := s.decodeStage06Cursor(cursor, &payload); decodeErr != nil || payload.Scope != familyCursorScope || payload.ViewerID != actor.UserID || payload.Filter != filter || uuid.Validate(payload.MediaID) != nil {
			return FamilyPage{}, validation("CURSOR_INVALID", "Invalid cursor", "The family pagination cursor is invalid.")
		}
		rows, listErr := queries.ListFamilyMediaAfter(ctx, dbgen.ListFamilyMediaAfterParams{
			ViewerID: actor.RawUserID, OwnerOnly: filter == "mine", AfterCapturedAt: pgTime(payload.CapturedAt),
			AfterMediaID: toPGUUID(uuid.MustParse(payload.MediaID)), PageLimit: queryLimit,
		})
		if listErr != nil {
			return FamilyPage{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, familySummary(row.ID, row.OwnerID, row.OwnerNickname, row.MediaType, row.CapturedAt, row.CreatedAt, row.DurationMs, row.OriginalTotalSize))
		}
	}
	if len(page.Items) > int(limit) {
		page.Items = page.Items[:limit]
		last := page.Items[len(page.Items)-1]
		next := s.encodeStage06Cursor(familyCursorPayload{ViewerID: actor.UserID, Filter: filter, CapturedAt: last.CapturedAt, MediaID: last.ID, Scope: familyCursorScope})
		page.NextCursor = &next
	}
	return page, nil
}

func (s *Service) FamilyDetail(ctx context.Context, actor Actor, mediaID string) (FamilyDetail, error) {
	if err := validateActor(actor); err != nil {
		return FamilyDetail{}, err
	}
	mediaUUID, err := parseMediaID(mediaID)
	if err != nil {
		return FamilyDetail{}, err
	}
	queries := dbgen.New(s.pool)
	row, err := queries.FindFamilyMedia(ctx, dbgen.FindFamilyMediaParams{UserID: actor.RawUserID, ID: mediaUUID})
	if errors.Is(err, pgx.ErrNoRows) {
		return FamilyDetail{}, familyNotFound()
	}
	if err != nil {
		return FamilyDetail{}, internal()
	}
	resources, err := queries.ListFamilyMediaResources(ctx, dbgen.ListFamilyMediaResourcesParams{UserID: actor.RawUserID, ID: mediaUUID})
	if err != nil {
		return FamilyDetail{}, internal()
	}
	result := FamilyDetail{
		ID: uuidString(row.ID), Owner: FamilyOwner{ID: uuidString(row.OwnerID), Nickname: row.OwnerNickname}, MediaType: row.MediaType,
		CapturedAt: row.CapturedAt.Time, CreatedAt: row.CreatedAt.Time, Width: optionalInt32(row.Width), Height: optionalInt32(row.Height),
		DurationMS: optionalInt64(row.DurationMs), OriginalTotalSize: row.OriginalTotalSize, Resources: make([]Resource, 0, len(resources)),
	}
	for _, resource := range resources {
		result.Resources = append(result.Resources, Resource{ID: uuidString(resource.ID), Type: resource.ResourceType, MimeType: resource.MimeType, ContentSize: resource.ContentSize, SHA256: base64.RawStdEncoding.EncodeToString(resource.ContentSha256)})
	}
	return result, nil
}

func (s *Service) FamilyAccess(ctx context.Context, actor Actor, mediaID string, input AccessInput) (AccessResult, error) {
	if err := validateActor(actor); err != nil {
		return AccessResult{}, err
	}
	mediaUUID, err := parseMediaID(mediaID)
	if err != nil {
		return AccessResult{}, err
	}
	if input.Purpose == "DOWNLOAD" || (input.Purpose != "VIEW" && input.Purpose != "STREAM") {
		return AccessResult{}, validation("FAMILY_MEDIA_ACCESS_INVALID", "Invalid family access", "Family media only supports view or stream access.")
	}
	if !validFamilyAccessInput(input) {
		return AccessResult{}, validation("FAMILY_MEDIA_ACCESS_INVALID", "Invalid family access", "The family media purpose and variant combination is invalid.")
	}
	result := AccessResult{MediaID: mediaID, Purpose: input.Purpose, Resources: make([]AccessResource, 0)}
	if input.Variant != "" {
		variant := input.Variant
		result.Variant = &variant
	}
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		queries := dbgen.New(tx)
		locked, lockErr := queries.LockFamilyMediaForAccess(ctx, dbgen.LockFamilyMediaForAccessParams{UserID: actor.RawUserID, ID: mediaUUID})
		if errors.Is(lockErr, pgx.ErrNoRows) {
			return familyNotFound()
		}
		if lockErr != nil {
			return lockErr
		}
		rows, listErr := queries.ListFamilyMediaAccessResources(ctx, mediaUUID)
		if listErr != nil {
			return listErr
		}
		privateRows := make([]dbgen.ListPrivateMediaAccessResourcesRow, 0, len(rows))
		for _, row := range rows {
			privateRows = append(privateRows, dbgen.ListPrivateMediaAccessResourcesRow(row))
		}
		selected, selectErr := selectAccessResources(locked.MediaType, privateRows, input)
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
			if input.Purpose == "VIEW" && input.Variant == "THUMBNAIL" && isOSSImagePreviewCandidate(locked.MediaType, resource) {
				deliveryMode = deliveryOSSImageThumbnail
				maximum := int64(ossImagePreviewMaximumBytes)
				maximumOutputSize = &maximum
				grant, grantErr = s.objects.IssueMediaImagePreview(ctx, resource.ObjectKey, readGrantLifetime)
			} else {
				grant, grantErr = s.objects.IssueMediaRead(ctx, resource.ObjectKey, readGrantLifetime)
			}
			if grantErr != nil || !validMediaReadGrant(grant, issuedAt) {
				return objectError(grantErr)
			}
			result.ExpiresAt = earliestMediaReadGrantExpiry(result.ExpiresAt, grant.ExpiresAt)
			result.Resources = append(result.Resources, AccessResource{
				Resource:      Resource{ID: uuidString(resource.ID), Type: resource.ResourceType, MimeType: resource.MimeType, ContentSize: resource.ContentSize, SHA256: base64.RawStdEncoding.EncodeToString(resource.ContentSha256)},
				SupportsRange: input.Purpose == "STREAM", DeliveryMode: deliveryMode, MaximumOutputSize: maximumOutputSize, Grant: grant,
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

func validFamilyAccessInput(input AccessInput) bool {
	switch input.Purpose {
	case "VIEW":
		return input.Variant == "THUMBNAIL" || input.Variant == "DETAIL"
	case "STREAM":
		return input.Variant == ""
	default:
		return false
	}
}

func (s *Service) ListTrash(ctx context.Context, actor Actor, cursor string, limit int32) (TrashPage, error) {
	if err := validateActor(actor); err != nil {
		return TrashPage{}, err
	}
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	queryLimit := limit + 1
	queries := dbgen.New(s.pool)
	page := TrashPage{Items: make([]TrashSummary, 0, queryLimit)}
	if cursor == "" {
		rows, err := queries.ListTrashMedia(ctx, dbgen.ListTrashMediaParams{OwnerID: actor.RawUserID, Limit: queryLimit})
		if err != nil {
			return TrashPage{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, trashSummary(row.ID, row.MediaType, row.CapturedAt, row.CreatedAt, row.DurationMs, row.OriginalTotalSize, row.TrashedAt))
		}
	} else {
		var payload trashCursorPayload
		if err := s.decodeStage06Cursor(cursor, &payload); err != nil || payload.Scope != trashCursorScope || payload.OwnerID != actor.UserID || uuid.Validate(payload.MediaID) != nil {
			return TrashPage{}, validation("CURSOR_INVALID", "Invalid cursor", "The trash pagination cursor is invalid.")
		}
		rows, err := queries.ListTrashMediaAfter(ctx, dbgen.ListTrashMediaAfterParams{OwnerID: actor.RawUserID, TrashedAt: pgTime(payload.TrashedAt), ID: toPGUUID(uuid.MustParse(payload.MediaID)), Limit: queryLimit})
		if err != nil {
			return TrashPage{}, internal()
		}
		for _, row := range rows {
			page.Items = append(page.Items, trashSummary(row.ID, row.MediaType, row.CapturedAt, row.CreatedAt, row.DurationMs, row.OriginalTotalSize, row.TrashedAt))
		}
	}
	if len(page.Items) > int(limit) {
		page.Items = page.Items[:limit]
		last := page.Items[len(page.Items)-1]
		next := s.encodeStage06Cursor(trashCursorPayload{OwnerID: actor.UserID, TrashedAt: last.TrashedAt, MediaID: last.ID, Scope: trashCursorScope})
		page.NextCursor = &next
	}
	return page, nil
}

func (s *Service) Restore(ctx context.Context, actor Actor, mediaID, idempotencyKey, requestID string) (RestoreResult, error) {
	if err := validateActor(actor); err != nil {
		return RestoreResult{}, err
	}
	mediaUUID, err := parseMediaID(mediaID)
	if err != nil {
		return RestoreResult{}, err
	}
	if !idempotencyPattern.MatchString(idempotencyKey) {
		return RestoreResult{}, validation("IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "The Idempotency-Key header is invalid.")
	}
	requestHash := sha256.Sum256([]byte(mediaID))
	var result RestoreResult
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		queries := dbgen.New(tx)
		if lockErr := queries.AcquireRestoreIdempotencyLock(ctx, dbgen.AcquireRestoreIdempotencyLockParams{OwnerID: actor.UserID, IdempotencyKey: idempotencyKey}); lockErr != nil {
			return lockErr
		}
		prior, priorErr := queries.FindRestoreRequest(ctx, dbgen.FindRestoreRequestParams{OwnerID: actor.RawUserID, IdempotencyKey: idempotencyKey})
		if priorErr == nil {
			if !equalBytes(prior.RequestHash, requestHash[:]) {
				return conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for another restore request.")
			}
			result = RestoreResult{MediaID: uuidString(prior.MediaID), Outcome: prior.Outcome, RestoredAt: prior.RestoredAt.Time}
			return nil
		}
		if !errors.Is(priorErr, pgx.ErrNoRows) {
			return priorErr
		}
		record, recordErr := queries.LockTrashRecordForRestore(ctx, dbgen.LockTrashRecordForRestoreParams{MediaID: mediaUUID, OwnerID: actor.RawUserID})
		if errors.Is(recordErr, pgx.ErrNoRows) {
			return trashNotFound()
		}
		if recordErr != nil {
			return recordErr
		}
		now := s.now().UTC()
		outcome := "RESTORED"
		restoredAt := now
		if record.RestoredAt.Valid {
			outcome = "ALREADY_RESTORED"
			restoredAt = record.RestoredAt.Time
		} else {
			updated, updateErr := queries.RestoreTrashRecord(ctx, dbgen.RestoreTrashRecordParams{MediaID: mediaUUID, OwnerID: actor.RawUserID, RestoredAt: pgTime(now)})
			if updateErr != nil {
				return updateErr
			}
			if updated == 0 {
				return conflict("TRASH_RESTORE_CONFLICT", "Restore conflict", "The media trash state changed concurrently.")
			}
			if _, shareErr := queries.InactivateShare(ctx, dbgen.InactivateShareParams{MediaID: mediaUUID, OwnerID: actor.RawUserID, UnsharedAt: pgTime(now)}); shareErr != nil {
				return shareErr
			}
			if versionErr := queries.BumpPrivateMediaAccessVersion(ctx, mediaUUID); versionErr != nil {
				return versionErr
			}
			if auditErr := queries.RecordAuditEvent(ctx, dbgen.RecordAuditEventParams{
				ActorType: "USER", ActorID: actor.RawUserID, Action: "PRIVATE_MEDIA_RESTORE", TargetType: "MEDIA", TargetID: mediaUUID,
				Result: "SUCCESS", RequestID: requestID, Metadata: []byte(`{"share_state":"INACTIVE"}`),
			}); auditErr != nil {
				return auditErr
			}
		}
		if createErr := queries.CreateRestoreRequest(ctx, dbgen.CreateRestoreRequestParams{OwnerID: actor.RawUserID, IdempotencyKey: idempotencyKey, MediaID: mediaUUID, RequestHash: requestHash[:], Outcome: outcome, RestoredAt: pgTime(restoredAt)}); createErr != nil {
			return createErr
		}
		result = RestoreResult{MediaID: mediaID, Outcome: outcome, RestoredAt: restoredAt}
		return nil
	})
	if err != nil {
		var mediaErr *Error
		if errors.As(err, &mediaErr) {
			return RestoreResult{}, mediaErr
		}
		return RestoreResult{}, internal()
	}
	return result, nil
}

func (s *Service) SubmitFeedback(ctx context.Context, actor Actor, input FeedbackInput, idempotencyKey, requestID string) (FeedbackResult, error) {
	if err := validateActor(actor); err != nil {
		return FeedbackResult{}, err
	}
	if !idempotencyPattern.MatchString(idempotencyKey) {
		return FeedbackResult{}, validation("IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "The Idempotency-Key header is invalid.")
	}
	input.Category = strings.TrimSpace(input.Category)
	input.Description = strings.TrimSpace(input.Description)
	input.Contact = strings.TrimSpace(input.Contact)
	input.AppVersion = strings.TrimSpace(input.AppVersion)
	input.Platform = strings.TrimSpace(input.Platform)
	input.OSVersion = strings.TrimSpace(input.OSVersion)
	input.DeviceInstallationID = strings.TrimSpace(input.DeviceInstallationID)
	if err := validateFeedback(input); err != nil {
		return FeedbackResult{}, err
	}
	body, _ := json.Marshal(input)
	requestHash := sha256.Sum256(body)
	var result FeedbackResult
	err := s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		queries := dbgen.New(tx)
		if lockErr := queries.AcquireFeedbackIdempotencyLock(ctx, dbgen.AcquireFeedbackIdempotencyLockParams{UserID: actor.UserID, IdempotencyKey: idempotencyKey}); lockErr != nil {
			return lockErr
		}
		prior, priorErr := queries.FindFeedbackRequest(ctx, dbgen.FindFeedbackRequestParams{UserID: actor.RawUserID, IdempotencyKey: idempotencyKey})
		if priorErr == nil {
			if !equalBytes(prior.RequestHash, requestHash[:]) {
				return conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The key was already used for different feedback.")
			}
			result = FeedbackResult{FeedbackID: uuidString(prior.FeedbackID), Outcome: "ALREADY_SUBMITTED", CreatedAt: prior.CreatedAt.Time}
			return nil
		}
		if !errors.Is(priorErr, pgx.ErrNoRows) {
			return priorErr
		}
		now := s.now().UTC()
		contact := pgtype.Text{}
		if input.Contact != "" {
			contact = pgtype.Text{String: input.Contact, Valid: true}
		}
		created, createErr := queries.CreateFeedback(ctx, dbgen.CreateFeedbackParams{
			UserID: actor.RawUserID, Category: input.Category, Description: input.Description, Contact: contact,
			AppVersion: input.AppVersion, Platform: input.Platform, OsVersion: input.OSVersion,
			DeviceInstallationID: input.DeviceInstallationID, CreatedAt: pgTime(now),
		})
		if createErr != nil {
			return createErr
		}
		if createRequestErr := queries.CreateFeedbackRequest(ctx, dbgen.CreateFeedbackRequestParams{UserID: actor.RawUserID, IdempotencyKey: idempotencyKey, RequestHash: requestHash[:], FeedbackID: created.ID}); createRequestErr != nil {
			return createRequestErr
		}
		if auditErr := queries.RecordAuditEvent(ctx, dbgen.RecordAuditEventParams{
			ActorType: "USER", ActorID: actor.RawUserID, Action: "FEEDBACK_SUBMIT", TargetType: "FEEDBACK", TargetID: created.ID,
			Result: "SUCCESS", RequestID: requestID, Metadata: []byte(`{"attachments":false}`),
		}); auditErr != nil {
			return auditErr
		}
		result = FeedbackResult{FeedbackID: uuidString(created.ID), Outcome: "SUBMITTED", CreatedAt: created.CreatedAt.Time}
		return nil
	})
	if err != nil {
		var mediaErr *Error
		if errors.As(err, &mediaErr) {
			return FeedbackResult{}, mediaErr
		}
		return FeedbackResult{}, internal()
	}
	return result, nil
}

func familySummary(id, ownerID pgtype.UUID, nickname, mediaType string, capturedAt, createdAt pgtype.Timestamptz, duration pgtype.Int8, size int64) FamilySummary {
	return FamilySummary{ID: uuidString(id), Owner: FamilyOwner{ID: uuidString(ownerID), Nickname: nickname}, MediaType: mediaType, CapturedAt: capturedAt.Time, CreatedAt: createdAt.Time, DurationMS: optionalInt64(duration), OriginalTotalSize: size}
}

func trashSummary(id pgtype.UUID, mediaType string, capturedAt, createdAt pgtype.Timestamptz, duration pgtype.Int8, size int64, trashedAt pgtype.Timestamptz) TrashSummary {
	return TrashSummary{ID: uuidString(id), MediaType: mediaType, CapturedAt: capturedAt.Time, CreatedAt: createdAt.Time, DurationMS: optionalInt64(duration), OriginalTotalSize: size, TrashedAt: trashedAt.Time}
}

func (s *Service) encodeStage06Cursor(payload any) string {
	body, _ := json.Marshal(payload)
	mac := hmac.New(sha256.New, s.cursorKey)
	_, _ = mac.Write(body)
	return base64.RawURLEncoding.EncodeToString(append(body, mac.Sum(nil)...))
}

func (s *Service) decodeStage06Cursor(value string, destination any) error {
	packet, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(packet) <= sha256.Size {
		return errors.New("invalid cursor")
	}
	body, signature := packet[:len(packet)-sha256.Size], packet[len(packet)-sha256.Size:]
	mac := hmac.New(sha256.New, s.cursorKey)
	_, _ = mac.Write(body)
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return errors.New("invalid cursor signature")
	}
	return json.Unmarshal(body, destination)
}

func validateFeedback(input FeedbackInput) error {
	validCategory := map[string]bool{"ACCOUNT": true, "PERMISSION": true, "BACKUP": true, "BROWSE_PLAYBACK": true, "SHARING": true, "TRASH": true, "OTHER": true}
	if !validCategory[input.Category] {
		return validation("FEEDBACK_CATEGORY_INVALID", "Invalid feedback category", "The feedback category is invalid.")
	}
	if count := utf8.RuneCountInString(input.Description); count < 1 || count > 1000 {
		return validation("FEEDBACK_DESCRIPTION_INVALID", "Invalid feedback description", "Feedback must contain 1 to 1000 characters.")
	}
	if count := utf8.RuneCountInString(input.Contact); count > 200 {
		return validation("FEEDBACK_CONTACT_INVALID", "Invalid feedback contact", "Feedback contact must contain at most 200 characters.")
	}
	if input.AppVersion == "" || len(input.AppVersion) > 64 || input.OSVersion == "" || len(input.OSVersion) > 128 {
		return validation("FEEDBACK_ENVIRONMENT_INVALID", "Invalid feedback environment", "App and OS versions are required.")
	}
	if input.Platform != "ANDROID" && input.Platform != "IOS" && input.Platform != "HARMONYOS" {
		return validation("FEEDBACK_ENVIRONMENT_INVALID", "Invalid feedback environment", "The platform is invalid.")
	}
	if !idempotencyPattern.MatchString(input.DeviceInstallationID) {
		return validation("FEEDBACK_ENVIRONMENT_INVALID", "Invalid feedback environment", "The anonymous installation identifier is invalid.")
	}
	combined := strings.ToLower(input.Description + "\n" + input.Contact)
	for _, forbidden := range []string{"http://", "https://", "bearer ", "securitytoken", "accesskey", "x-amz-", "x-oss-signature"} {
		if strings.Contains(combined, forbidden) {
			return validation("FEEDBACK_SENSITIVE_DATA_REJECTED", "Sensitive feedback rejected", "Feedback cannot contain object addresses or credentials.")
		}
	}
	return nil
}

func familyNotFound() *Error {
	return &Error{Code: "FAMILY_MEDIA_NOT_FOUND", Status: 404, Title: "Family media not found", Detail: "The requested shared media is unavailable."}
}

func trashNotFound() *Error {
	return &Error{Code: "TRASH_MEDIA_NOT_FOUND", Status: 404, Title: "Trash media not found", Detail: "The requested trash item is unavailable."}
}
