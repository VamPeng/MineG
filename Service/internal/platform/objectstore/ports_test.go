package objectstore

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestAvatarObjectMustExistWithExactMetadataBeforeRead(t *testing.T) {
	now := time.Unix(100, 0).UTC()
	objects := NewMemoryProfileObjects(func() time.Time { return now })
	metadata := ProfileObjectMetadata{
		Key: "avatars/user/upload/avatar.webp", Size: 128, ContentType: "image/webp", SHA256: make([]byte, 32),
	}
	grant, err := objects.IssueAvatarUpload(context.Background(), metadata, 5*time.Minute)
	if err != nil || grant.Method != "PUT" || len(grant.Headers) == 0 {
		t.Fatalf("upload grant = %#v, %v", grant, err)
	}
	if err := objects.VerifyAvatar(context.Background(), metadata); !errors.Is(err, ErrObjectNotReady) {
		t.Fatalf("unuploaded object verification = %v", err)
	}
	if err := objects.PutVerifiedAvatar(metadata); err != nil {
		t.Fatal(err)
	}
	if err := objects.VerifyAvatar(context.Background(), metadata); err != nil {
		t.Fatal(err)
	}
	read, err := objects.IssueAvatarRead(context.Background(), metadata.Key, 2*time.Minute)
	if err != nil || read.Method != "GET" || !read.ExpiresAt.Equal(now.Add(2*time.Minute)) {
		t.Fatalf("read grant = %#v, %v", read, err)
	}
}
