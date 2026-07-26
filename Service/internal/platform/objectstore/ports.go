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
var ErrUnavailable = errors.New("object store integration is unavailable")
var ErrObjectNotReady = errors.New("object is missing or its metadata does not match")

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

type ObjectGrant struct {
	URL       string            `json:"url"`
	Method    string            `json:"method"`
	ExpiresAt time.Time         `json:"expires_at"`
	Headers   map[string]string `json:"headers"`
}

type ProfileObjectMetadata struct {
	Key         string
	Size        int64
	ContentType string
	SHA256      []byte
}

// ProfileObjects is deliberately separate from media ciphertext storage. Avatar
// grants are restricted to one exact object key and never include list/delete.
type ProfileObjects interface {
	IssueAvatarUpload(context.Context, ProfileObjectMetadata, time.Duration) (ObjectGrant, error)
	VerifyAvatar(context.Context, ProfileObjectMetadata) error
	IssueAvatarRead(context.Context, string, time.Duration) (ObjectGrant, error)
}

type DisabledProfileObjects struct{}

func (DisabledProfileObjects) IssueAvatarUpload(context.Context, ProfileObjectMetadata, time.Duration) (ObjectGrant, error) {
	return ObjectGrant{}, ErrUnavailable
}

func (DisabledProfileObjects) VerifyAvatar(context.Context, ProfileObjectMetadata) error {
	return ErrUnavailable
}

func (DisabledProfileObjects) IssueAvatarRead(context.Context, string, time.Duration) (ObjectGrant, error) {
	return ObjectGrant{}, ErrUnavailable
}

type MemoryProfileObjects struct {
	mu      sync.RWMutex
	now     func() time.Time
	objects map[string]ProfileObjectMetadata
}

func NewMemoryProfileObjects(now func() time.Time) *MemoryProfileObjects {
	if now == nil {
		now = time.Now
	}
	return &MemoryProfileObjects{now: now, objects: make(map[string]ProfileObjectMetadata)}
}

func (s *MemoryProfileObjects) IssueAvatarUpload(_ context.Context, object ProfileObjectMetadata, lifetime time.Duration) (ObjectGrant, error) {
	if err := validateAvatarObject(object, lifetime); err != nil {
		return ObjectGrant{}, err
	}
	return ObjectGrant{
		URL: "https://objects.invalid/upload/" + object.Key, Method: "PUT",
		ExpiresAt: s.now().UTC().Add(lifetime),
		Headers:   map[string]string{"Content-Type": object.ContentType, "X-MineG-Content-SHA256": fmt.Sprintf("%x", object.SHA256)},
	}, nil
}

// PutVerifiedAvatar is a test/local adapter hook. It never accepts arbitrary
// bytes and therefore cannot accidentally become a plaintext media proxy.
func (s *MemoryProfileObjects) PutVerifiedAvatar(object ProfileObjectMetadata) error {
	if err := validateAvatarObject(object, 5*time.Minute); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	object.SHA256 = bytes.Clone(object.SHA256)
	s.objects[object.Key] = object
	return nil
}

func (s *MemoryProfileObjects) VerifyAvatar(_ context.Context, expected ProfileObjectMetadata) error {
	s.mu.RLock()
	actual, ok := s.objects[expected.Key]
	s.mu.RUnlock()
	if !ok || actual.Size != expected.Size || actual.ContentType != expected.ContentType || !bytes.Equal(actual.SHA256, expected.SHA256) {
		return ErrObjectNotReady
	}
	return nil
}

func (s *MemoryProfileObjects) IssueAvatarRead(_ context.Context, key string, lifetime time.Duration) (ObjectGrant, error) {
	s.mu.RLock()
	_, ok := s.objects[key]
	s.mu.RUnlock()
	if !ok {
		return ObjectGrant{}, ErrObjectNotReady
	}
	if lifetime <= 0 || lifetime > 15*time.Minute {
		return ObjectGrant{}, errors.New("invalid read grant lifetime")
	}
	return ObjectGrant{URL: "https://objects.invalid/read/" + key, Method: "GET", ExpiresAt: s.now().UTC().Add(lifetime), Headers: map[string]string{}}, nil
}

func validateAvatarObject(object ProfileObjectMetadata, lifetime time.Duration) error {
	if object.Key == "" || object.Size <= 0 || len(object.SHA256) != 32 || lifetime <= 0 || lifetime > 15*time.Minute {
		return errors.New("invalid avatar object grant")
	}
	switch object.ContentType {
	case "image/jpeg", "image/png", "image/heic", "image/heif", "image/webp":
		return nil
	default:
		return errors.New("invalid avatar content type")
	}
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
