package media

import (
	"bytes"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/vampeng/mineg/service/internal/platform/database/dbgen"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

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

func TestAccessSelectionUsesOriginalOnlyForEligibleOSSImageThumbnail(t *testing.T) {
	resources := []dbgen.ListPrivateMediaAccessResourcesRow{
		{ID: toPGUUID(uuid.New()), ResourceType: "ORIGINAL", ObjectKey: "media/member/item.original", MimeType: "image/jpeg", ContentSize: 4, ContentSha256: bytes.Repeat([]byte{1}, 32)},
		{ID: toPGUUID(uuid.New()), ResourceType: "PREVIEW", ObjectKey: "media/member/item.preview", MimeType: "image/jpeg", ContentSize: 4, ContentSha256: bytes.Repeat([]byte{2}, 32)},
	}
	for _, input := range []AccessInput{{Purpose: "VIEW", Variant: "DETAIL"}, {Purpose: "STREAM"}} {
		selected, err := selectAccessResources("PHOTO", resources, input)
		if err != nil || len(selected) != 1 || selected[0].ResourceType != "PREVIEW" {
			t.Fatalf("input=%#v selected=%#v err=%v", input, selected, err)
		}
	}
	selected, err := selectAccessResources("PHOTO", resources, AccessInput{Purpose: "VIEW", Variant: "THUMBNAIL"})
	if err != nil || len(selected) != 1 || selected[0].ResourceType != "ORIGINAL" {
		t.Fatalf("photo thumbnail did not select image source: %#v, %v", selected, err)
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

func TestDownloadOfLivePhotoRequiresTheCompanionOriginalResource(t *testing.T) {
	resources := []dbgen.ListPrivateMediaAccessResourcesRow{{
		ID: pgtype.UUID{Bytes: [16]byte(uuid.New()), Valid: true}, ResourceType: "ORIGINAL",
		ObjectKey: "media/member/item.original", MimeType: "image/jpeg", ContentSize: 4, ContentSha256: bytes.Repeat([]byte{1}, 32),
	}}
	if _, err := selectAccessResources("LIVE_PHOTO", resources, AccessInput{Purpose: "DOWNLOAD"}); err == nil {
		t.Fatal("live photo download without LIVE_PHOTO_VIDEO was accepted")
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
