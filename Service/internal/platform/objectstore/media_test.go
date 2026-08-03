package objectstore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"testing"
	"time"
)

func TestMemoryMediaObjectsScopesPartsAndVerifiesCiphertext(t *testing.T) {
	now := time.Date(2026, 7, 26, 12, 0, 0, 0, time.UTC)
	objects := NewMemoryMediaObjects(func() time.Time { return now })
	first := bytes.Repeat([]byte{0x4d}, OriginalMediaPartMaximum)
	last := []byte("authenticated-final-block")
	firstDigest := sha256.Sum256(first)
	lastDigest := sha256.Sum256(last)
	wholeDigest := sha256.Sum256(append(append([]byte(nil), first...), last...))
	plan := MediaResourcePlan{
		ID: "resource-1", ObjectKey: "media/owner/session/resource-1.cipher",
		Purpose: "MEDIA_ORIGINAL", ContentSize: int64(len(first) + len(last)), SHA256: wholeDigest[:],
		Parts: []MediaPartPlan{
			{Number: 1, Size: int64(len(first)), SHA256: firstDigest[:]},
			{Number: 2, Size: int64(len(last)), SHA256: lastDigest[:]},
		},
	}
	grant, err := objects.BeginMediaUpload(context.Background(), "media/owner/session/", []MediaResourcePlan{plan}, 10*time.Minute)
	if err != nil {
		t.Fatalf("begin media upload: %v", err)
	}
	if grant.Purpose != "MEDIA_ORIGINAL" || grant.ScopePrefix != "media/owner/session/" || len(grant.Resources) != 1 {
		t.Fatalf("unexpected media grant: %#v", grant)
	}
	for _, part := range grant.Resources[0].Parts {
		if part.Grant.Method != "PUT" || part.Grant.URL == "" {
			t.Fatalf("part grant is not an exact PUT: %#v", part.Grant)
		}
		if _, exposed := part.Grant.Headers["Authorization"]; exposed {
			t.Fatal("media grant exposed a reusable authorization credential")
		}
	}
	uploadID := grant.Resources[0].UploadID
	etag1, err := objects.PutPart(uploadID, 1, first)
	if err != nil {
		t.Fatalf("put first part: %v", err)
	}
	etag2, err := objects.PutPart(uploadID, 2, last)
	if err != nil {
		t.Fatalf("put final part: %v", err)
	}
	verification := []MediaResourceVerification{{
		ResourceID: plan.ID, ObjectKey: plan.ObjectKey, UploadID: uploadID, SHA256: wholeDigest[:],
		Parts: []ReportedMediaPart{
			{Number: 1, Size: int64(len(first)), SHA256: firstDigest[:], ETag: etag1},
			{Number: 2, Size: int64(len(last)), SHA256: lastDigest[:], ETag: etag2},
		},
	}}
	tampered := append([]MediaResourceVerification(nil), verification...)
	tampered[0].Parts = append([]ReportedMediaPart(nil), verification[0].Parts...)
	tampered[0].Parts[1].SHA256 = bytes.Repeat([]byte{0xff}, sha256.Size)
	if !errors.Is(objects.VerifyAndCompleteMediaUpload(context.Background(), tampered), ErrObjectNotReady) {
		t.Fatal("tampered client part digest was accepted")
	}
	if err := objects.VerifyAndCompleteMediaUpload(context.Background(), verification); err != nil {
		t.Fatalf("verify complete media upload: %v", err)
	}
	// Completion is idempotent so a database commit retry cannot strand the
	// upload after OSS has already assembled the object.
	if err := objects.VerifyAndCompleteMediaUpload(context.Background(), verification); err != nil {
		t.Fatalf("repeat completion: %v", err)
	}
}

func TestMediaUploadRejectsBrokenFourMiBMapping(t *testing.T) {
	digest := bytes.Repeat([]byte{0x11}, sha256.Size)
	objects := NewMemoryMediaObjects(time.Now)
	_, err := objects.BeginMediaUpload(context.Background(), "media/owner/session/", []MediaResourcePlan{{
		ID: "resource-1", ObjectKey: "media/owner/session/resource-1.cipher",
		Purpose: "MEDIA_ORIGINAL", ContentSize: 34, SHA256: digest,
		Parts: []MediaPartPlan{{Number: 1, Size: 17, SHA256: digest}, {Number: 2, Size: 17, SHA256: digest}},
	}}, time.Minute)
	if err == nil {
		t.Fatal("non-final part that is not one encrypted 4 MiB block was accepted")
	}
}
