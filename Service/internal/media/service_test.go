package media

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"reflect"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/vampeng/mineg/service/internal/platform/database/dbgen"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

func TestPrivateMediaResponsesPreserveAuthoritativeContentRevision(t *testing.T) {
	for name, target := range map[string]any{
		"summary": &Summary{},
		"detail":  &Detail{},
	} {
		t.Run(name, func(t *testing.T) {
			if err := json.Unmarshal([]byte(`{"content_revision":7}`), target); err != nil {
				t.Fatal(err)
			}
			encoded, err := json.Marshal(target)
			if err != nil {
				t.Fatal(err)
			}
			var response map[string]any
			if err := json.Unmarshal(encoded, &response); err != nil {
				t.Fatal(err)
			}
			if response["content_revision"] != float64(7) {
				t.Fatalf("content_revision = %#v in %s", response["content_revision"], encoded)
			}
		})
	}
	summary := summaryFromValues(pgtype.UUID{}, "PHOTO", 7, pgtype.Timestamptz{}, pgtype.Timestamptz{}, pgtype.Int8{}, 0)
	if summary.ContentRevision != 7 {
		t.Fatalf("summary content revision = %d, want 7", summary.ContentRevision)
	}
}

func TestPrivateMediaCursorIsOwnerBoundAndTamperEvident(t *testing.T) {
	service := New(nil, Config{CursorKey: bytes.Repeat([]byte{0x6b}, 32)})
	value := service.encodeCursor(cursorPayload{
		OwnerID: "member-1", CapturedAt: time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC),
		MediaID: uuid.NewString(), Scope: cursorScope,
	})
	payload, err := service.decodeCursor(value)
	if err != nil || payload.OwnerID != "member-1" || payload.Scope != cursorScope {
		t.Fatalf("cursor did not round trip: %#v, %v", payload, err)
	}
	tamperIndex := len(value) / 2
	tamperedCharacter := byte('A')
	if value[tamperIndex] == tamperedCharacter {
		tamperedCharacter = 'B'
	}
	tampered := value[:tamperIndex] + string(tamperedCharacter) + value[tamperIndex+1:]
	if _, err := service.decodeCursor(tampered); err == nil {
		t.Fatal("tampered cursor was accepted")
	}
}

func TestAccessSelectionUsesOriginalOnlyForEligibleImageThumbnail(t *testing.T) {
	resources := []dbgen.ListPrivateMediaAccessResourcesRow{
		{ID: toPGUUID(uuid.New()), ResourceType: "ORIGINAL", ObjectKey: "media/member/item.original", MimeType: "image/jpeg", ContentSize: 4, ContentSha256: bytes.Repeat([]byte{1}, 32)},
		{ID: toPGUUID(uuid.New()), ResourceType: "PREVIEW", ObjectKey: "media/member/item.preview", MimeType: "image/jpeg", ContentSize: 4, ContentSha256: bytes.Repeat([]byte{2}, 32)},
	}
	selected, err := selectAccessResources("PHOTO", resources, AccessInput{Purpose: "VIEW", Variant: "DETAIL"})
	if err != nil || len(selected) != 1 || selected[0].ResourceType != "ORIGINAL" {
		t.Fatalf("photo detail did not select its original: %#v, %v", selected, err)
	}
	selected, err = selectAccessResources("PHOTO", resources, AccessInput{Purpose: "STREAM"})
	if err != nil || len(selected) != 1 || selected[0].ResourceType != "PREVIEW" {
		t.Fatalf("photo stream did not select its preview: %#v, %v", selected, err)
	}
	selected, err = selectAccessResources("VIDEO", resources, AccessInput{Purpose: "VIEW", Variant: "DETAIL"})
	if err != nil || len(selected) != 1 || selected[0].ResourceType != "PREVIEW" {
		t.Fatalf("video detail did not retain its preview path: %#v, %v", selected, err)
	}
	selected, err = selectAccessResources("PHOTO", resources, AccessInput{Purpose: "VIEW", Variant: "THUMBNAIL"})
	if err != nil || len(selected) != 1 || selected[0].ResourceType != "ORIGINAL" {
		t.Fatalf("photo thumbnail did not select image source: %#v, %v", selected, err)
	}
	svg := append([]dbgen.ListPrivateMediaAccessResourcesRow(nil), resources...)
	svg[0].MimeType = "image/svg+xml"
	selected, err = selectAccessResources("PHOTO", svg, AccessInput{Purpose: "VIEW", Variant: "THUMBNAIL"})
	if err != nil || len(selected) != 1 || selected[0].ResourceType != "ORIGINAL" ||
		isOSSImagePreviewCandidate("PHOTO", selected[0]) {
		t.Fatalf("SVG thumbnail was not selected for direct delivery: %#v, %v", selected, err)
	}
	svg[0].ContentSize = ossImagePreviewMaximumBytes + 1
	if _, err := selectAccessResources("PHOTO", svg, AccessInput{Purpose: "VIEW", Variant: "THUMBNAIL"}); err == nil {
		t.Fatal("oversized SVG unexpectedly selected the original for thumbnail delivery")
	}
	if _, err := selectAccessResources("VIDEO", resources, AccessInput{Purpose: "VIEW", Variant: "THUMBNAIL"}); err == nil {
		t.Fatal("video thumbnail unexpectedly selected original")
	}
	unsupported := append([]dbgen.ListPrivateMediaAccessResourcesRow(nil), resources...)
	unsupported[0].MimeType = "image/heic"
	if _, err := selectAccessResources("PHOTO", unsupported, AccessInput{Purpose: "VIEW", Variant: "THUMBNAIL"}); err == nil {
		t.Fatal("unsupported image format unexpectedly selected original")
	}
}

func TestPrivateMediaAccessRejectsDownload(t *testing.T) {
	service := New(nil, Config{})
	approvedActor := Actor{
		UserID: "member-1", RawUserID: toPGUUID(uuid.New()), Status: "APPROVED",
	}
	mediaID := uuid.NewString()

	var result AccessResult
	var err error
	func() {
		defer func() {
			if panicValue := recover(); panicValue != nil {
				t.Fatalf("DOWNLOAD advanced beyond input validation: %v", panicValue)
			}
		}()
		result, err = service.Access(context.Background(), approvedActor, mediaID, AccessInput{Purpose: "DOWNLOAD"})
	}()
	if !reflect.DeepEqual(result, AccessResult{}) {
		t.Fatalf("unexpected result: %#v", result)
	}
	assertMediaError(t, err, "PRIVATE_MEDIA_ACCESS_INVALID", http.StatusUnprocessableEntity)
}

func TestPrivateMediaAccessEnforcesPurposeVariantMatrix(t *testing.T) {
	for _, input := range []AccessInput{
		{Purpose: "VIEW"},
		{Purpose: "VIEW", Variant: "FULL"},
		{Purpose: "STREAM", Variant: "THUMBNAIL"},
		{Purpose: "STREAM", Variant: "DETAIL"},
	} {
		t.Run(input.Purpose+"/"+input.Variant, func(t *testing.T) {
			assertMediaError(t, validateAccessInput(input), "PRIVATE_MEDIA_ACCESS_INVALID", http.StatusUnprocessableEntity)
		})
	}
	for _, input := range []AccessInput{
		{Purpose: "VIEW", Variant: "THUMBNAIL"},
		{Purpose: "VIEW", Variant: "DETAIL"},
		{Purpose: "STREAM"},
	} {
		t.Run("valid/"+input.Purpose+"/"+input.Variant, func(t *testing.T) {
			if err := validateAccessInput(input); err != nil {
				t.Fatalf("valid input rejected: %#v: %v", input, err)
			}
		})
	}
}

func TestFamilyMediaAccessRetainsVariantValidation(t *testing.T) {
	service := New(nil, Config{})
	approvedActor := Actor{
		UserID: "member-1", RawUserID: toPGUUID(uuid.New()), Status: "APPROVED",
	}
	mediaID := uuid.NewString()

	for _, input := range []AccessInput{
		{Purpose: "VIEW"},
		{Purpose: "VIEW", Variant: "FULL"},
		{Purpose: "STREAM", Variant: "THUMBNAIL"},
	} {
		t.Run(input.Purpose+"/"+input.Variant, func(t *testing.T) {
			var result AccessResult
			var err error
			func() {
				defer func() {
					if panicValue := recover(); panicValue != nil {
						t.Fatalf("invalid family access advanced beyond input validation: %v", panicValue)
					}
				}()
				result, err = service.FamilyAccess(context.Background(), approvedActor, mediaID, input)
			}()
			if !reflect.DeepEqual(result, AccessResult{}) {
				t.Fatalf("unexpected result: %#v", result)
			}
			assertMediaError(t, err, "FAMILY_MEDIA_ACCESS_INVALID", http.StatusUnprocessableEntity)
		})
	}
}

func assertMediaError(t *testing.T, err error, wantCode string, wantStatus int) {
	t.Helper()
	mediaErr, ok := err.(*Error)
	if !ok {
		t.Fatalf("error = %T (%v), want *Error", err, err)
	}
	if mediaErr.Code != wantCode || mediaErr.Status != wantStatus {
		t.Fatalf("error = %#v, want code %q and status %d", mediaErr, wantCode, wantStatus)
	}
}

func TestMediaReadGrantUsesTheEarliestActualSigningExpiry(t *testing.T) {
	issuedAt := time.Date(2026, 8, 3, 0, 30, 0, 0, time.UTC)
	firstExpiry := issuedAt.Add(readGrantLifetime).Add(4 * time.Millisecond)
	secondExpiry := issuedAt.Add(readGrantLifetime).Add(2 * time.Millisecond)
	if !validMediaReadGrant(objectstore.ObjectGrant{Method: "GET", ExpiresAt: firstExpiry}, issuedAt) {
		t.Fatal("a grant signed just after the recorded issue time was rejected")
	}
	if validMediaReadGrant(objectstore.ObjectGrant{Method: "PUT", ExpiresAt: firstExpiry}, issuedAt) {
		t.Fatal("non-GET media read grant was accepted")
	}
	if expiry := earliestMediaReadGrantExpiry(time.Time{}, firstExpiry); !expiry.Equal(firstExpiry) {
		t.Fatalf("first expiry = %v, want %v", expiry, firstExpiry)
	}
	if expiry := earliestMediaReadGrantExpiry(firstExpiry, secondExpiry); !expiry.Equal(secondExpiry) {
		t.Fatalf("earliest expiry = %v, want %v", expiry, secondExpiry)
	}
}

func TestStage06CursorsBindViewerAndFilter(t *testing.T) {
	service := New(nil, Config{CursorKey: bytes.Repeat([]byte{0x3c}, 32)})
	mediaID := uuid.NewString()
	capturedAt := time.Date(2026, 8, 3, 14, 0, 0, 0, time.UTC)
	encoded := service.encodeStage06Cursor(familyCursorPayload{
		ViewerID: "member-1", Filter: "mine", CapturedAt: capturedAt,
		MediaID: mediaID, Scope: familyCursorScope,
	})
	var decoded familyCursorPayload
	if err := service.decodeStage06Cursor(encoded, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.ViewerID != "member-1" || decoded.Filter != "mine" || decoded.MediaID != mediaID || !decoded.CapturedAt.Equal(capturedAt) {
		t.Fatalf("cursor did not preserve its scope: %#v", decoded)
	}
	tampered := encoded[:len(encoded)-1] + "A"
	if tampered == encoded {
		tampered = encoded[:len(encoded)-1] + "B"
	}
	if err := service.decodeStage06Cursor(tampered, &decoded); err == nil {
		t.Fatal("tampered stage06 cursor was accepted")
	}
}

func TestFeedbackValidationRejectsCredentialsAndAcceptsProductCategories(t *testing.T) {
	valid := FeedbackInput{
		Category: "SHARING", Description: "取消共享后页面没有及时刷新", Contact: "13800000000",
		AppVersion: "0.1.0", Platform: "ANDROID", OSVersion: "16", DeviceInstallationID: "device-installation-0001",
	}
	if err := validateFeedback(valid); err != nil {
		t.Fatalf("valid feedback was rejected: %v", err)
	}
	for _, description := range []string{
		"对象地址 https://bucket.example.test/file",
		"Bearer secret-token",
		"SecurityToken=temporary-secret",
	} {
		candidate := valid
		candidate.Description = description
		if err := validateFeedback(candidate); err == nil {
			t.Fatalf("sensitive feedback was accepted: %q", description)
		}
	}
	invalidCategory := valid
	invalidCategory.Category = "PRODUCT_SUGGESTION"
	if err := validateFeedback(invalidCategory); err == nil {
		t.Fatal("an unregistered feedback category was accepted")
	}
}
