package objectstore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

var ErrUnavailable = errors.New("object store integration is unavailable")
var ErrObjectNotReady = errors.New("object is missing or its metadata does not match")

type ObjectGrant struct {
	URL       string            `json:"url"`
	Method    string            `json:"method"`
	ExpiresAt time.Time         `json:"expires_at"`
	Headers   map[string]string `json:"headers"`
}

// normalizedObjectGrantHeaders keeps the wire contract stable for signed GETs
// that require no extra headers. A nil Go map serializes as JSON null, whereas
// clients require a JSON object before applying only safe request headers.
func normalizedObjectGrantHeaders(headers map[string]string) map[string]string {
	if len(headers) == 0 {
		return map[string]string{}
	}
	return headers
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
