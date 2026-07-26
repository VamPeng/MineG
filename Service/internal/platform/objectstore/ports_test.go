package objectstore

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"
)

func TestMemoryStoreRejectsPlainMedia(t *testing.T) {
	store := NewMemoryObjectStore()
	err := store.PutCiphertext(context.Background(), CiphertextObject{
		Key: "probe/photo.jpg", Body: bytes.NewReader([]byte("plain image")), Size: 11, ContentType: "image/jpeg",
	})
	if !errors.Is(err, ErrCiphertextRequired) {
		t.Fatalf("expected ciphertext rejection, got %v", err)
	}
}

func TestUploadGrantNeverIncludesDelete(t *testing.T) {
	issuer := MemorySTSIssuer{Now: func() time.Time { return time.Unix(0, 0) }}
	grant, err := issuer.IssueCiphertextUpload(context.Background(), "ciphertext/probe/", 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	for _, action := range grant.Actions {
		if action == "DeleteObject" || action == "DeleteObjectVersion" {
			t.Fatalf("online grant contains forbidden action %q", action)
		}
	}
}
