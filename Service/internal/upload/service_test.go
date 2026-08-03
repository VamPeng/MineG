package upload

import (
	"bytes"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestMediaCursorIsOwnerBoundAndTamperEvident(t *testing.T) {
	service := New(nil, Config{CursorKey: bytes.Repeat([]byte{0x7a}, 32)})
	value := service.encodeMediaCursor(mediaCursorPayload{
		OwnerID: "member-1", CapturedAt: time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC),
		MediaID: uuid.NewString(), Scope: "private-media-v1",
	})
	payload, err := service.decodeMediaCursor(value)
	if err != nil || payload.OwnerID != "member-1" || payload.Scope != "private-media-v1" {
		t.Fatalf("cursor did not round trip: %#v, %v", payload, err)
	}
	tamperedSuffix := "A"
	if value[len(value)-1] == tamperedSuffix[0] {
		tamperedSuffix = "B"
	}
	if _, err := service.decodeMediaCursor(value[:len(value)-1] + tamperedSuffix); err == nil {
		t.Fatal("tampered cursor was accepted")
	}
}

func TestValidateStage04CreateRequiresStableDeviceAndNormalizesAlbums(t *testing.T) {
	digest := bytes.Repeat([]byte{0x44}, 32)
	input := CreateInput{
		ProtocolVersion: "stage04-v1", IdempotencyKey: "stage04-upload-key", ClientMediaID: uuid.NewString(),
		Dedupe: digest, ContentRevision: 1, MediaType: "PHOTO", CapturedAt: time.Now().UTC(),
		ContentSHA256: digest, MimeType: "image/jpeg", DeviceInstallationID: "install-001",
		ClientAlbums: []ClientAlbumInput{{ID: "camera", Name: "Camera"}, {ID: "screens", Name: "Screenshots"}},
		Resources: []ResourceInput{{
			ID: uuid.NewString(), Type: "ORIGINAL", ContentSize: 4, SHA256: digest,
			Parts: []PartInput{{Number: 1, Size: 4, SHA256: digest}},
		}},
	}
	_, normalized, err := validateCreate(input, "owner-1")
	if err != nil {
		t.Fatalf("validate stage04 upload: %v", err)
	}
	if got := normalized.ClientAlbums[0].ID; got != "camera" {
		t.Fatalf("albums were not normalized deterministically: %q", got)
	}
	input.DeviceInstallationID = ""
	if _, _, err := validateCreate(input, "owner-1"); err == nil {
		t.Fatal("stage04 upload without device installation ID was accepted")
	}
}

func TestValidateStage05CreateRequiresOriginalAndResourceMIMEs(t *testing.T) {
	digest := bytes.Repeat([]byte{0x55}, 32)
	thumbnailDigest := bytes.Repeat([]byte{0x66}, 32)
	input := CreateInput{
		ProtocolVersion: "stage05-v1", IdempotencyKey: "stage05-upload-key", ClientMediaID: uuid.NewString(),
		Dedupe: digest, ContentRevision: 1, MediaType: "PHOTO", CapturedAt: time.Now().UTC(),
		ContentSHA256: digest, MimeType: "image/jpeg",
		Resources: []ResourceInput{
			{ID: uuid.NewString(), Type: "ORIGINAL", ContentSize: 4, SHA256: digest, MimeType: "image/jpeg", Parts: []PartInput{{Number: 1, Size: 4, SHA256: digest}}},
			{ID: uuid.NewString(), Type: "THUMBNAIL", ContentSize: 4, SHA256: thumbnailDigest, MimeType: "image/jpeg", Parts: []PartInput{{Number: 1, Size: 4, SHA256: thumbnailDigest}}},
		},
	}
	plans, _, err := validateCreate(input, "owner-1")
	if err != nil || len(plans) != 2 || plans[0].ObjectKey == plans[1].ObjectKey {
		t.Fatalf("validate stage05 upload: plans=%#v err=%v", plans, err)
	}
	input.Resources[0].MimeType = ""
	if _, _, err := validateCreate(input, "owner-1"); err == nil {
		t.Fatal("stage05 upload without a resource MIME was accepted")
	}
	input.Resources = input.Resources[1:]
	if _, _, err := validateCreate(input, "owner-1"); err == nil {
		t.Fatal("stage05 upload without an original resource was accepted")
	}
}
