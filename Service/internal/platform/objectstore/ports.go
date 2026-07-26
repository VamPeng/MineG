package objectstore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"
)

var ErrCiphertextRequired = errors.New("object store accepts ciphertext only")

type CiphertextObject struct {
	Key         string
	Body        io.Reader
	Size        int64
	ContentType string
	Cipher      string
}

type ObjectStore interface {
	PutCiphertext(context.Context, CiphertextObject) error
}

type UploadGrant struct {
	ObjectPrefix string
	ExpiresAt    time.Time
	Actions      []string
}

type STSIssuer interface {
	IssueCiphertextUpload(context.Context, string, time.Duration) (UploadGrant, error)
}

type MemoryObjectStore struct {
	mu      sync.RWMutex
	objects map[string][]byte
}

func NewMemoryObjectStore() *MemoryObjectStore {
	return &MemoryObjectStore{objects: make(map[string][]byte)}
}

func (s *MemoryObjectStore) PutCiphertext(_ context.Context, object CiphertextObject) error {
	if object.Body == nil || object.ContentType != "application/octet-stream" || object.Cipher != "XCHACHA20_POLY1305" {
		return ErrCiphertextRequired
	}
	data, err := io.ReadAll(io.LimitReader(object.Body, object.Size+1))
	if err != nil {
		return fmt.Errorf("read ciphertext: %w", err)
	}
	if int64(len(data)) != object.Size {
		return fmt.Errorf("ciphertext size mismatch")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.objects[object.Key] = bytes.Clone(data)
	return nil
}

type MemorySTSIssuer struct{ Now func() time.Time }

func (s MemorySTSIssuer) IssueCiphertextUpload(_ context.Context, prefix string, lifetime time.Duration) (UploadGrant, error) {
	if prefix == "" || lifetime <= 0 || lifetime > 15*time.Minute {
		return UploadGrant{}, errors.New("invalid upload grant request")
	}
	now := time.Now
	if s.Now != nil {
		now = s.Now
	}
	return UploadGrant{
		ObjectPrefix: prefix,
		ExpiresAt:    now().UTC().Add(lifetime),
		Actions:      []string{"PutObject", "UploadPart", "CompleteMultipartUpload"},
	}, nil
}
