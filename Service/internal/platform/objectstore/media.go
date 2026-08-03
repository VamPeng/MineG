package objectstore

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

const OriginalMediaPartMaximum = 4 * 1024 * 1024

type MediaPartPlan struct {
	Number int32
	Size   int64
	SHA256 []byte
}

type MediaResourcePlan struct {
	ID          string
	ObjectKey   string
	Purpose     string
	ContentSize int64
	SHA256      []byte
	Parts       []MediaPartPlan
}

type MediaPartGrant struct {
	Number int32       `json:"part_number"`
	Grant  ObjectGrant `json:"grant"`
}

type MediaResourceGrant struct {
	ResourceID string           `json:"resource_id"`
	ObjectKey  string           `json:"object_key"`
	UploadID   string           `json:"upload_id"`
	Parts      []MediaPartGrant `json:"parts"`
}

type MediaUploadGrant struct {
	Purpose     string               `json:"purpose"`
	ScopePrefix string               `json:"scope_prefix"`
	ExpiresAt   time.Time            `json:"expires_at"`
	Resources   []MediaResourceGrant `json:"resources"`
}

type ReportedMediaPart struct {
	Number int32
	Size   int64
	SHA256 []byte
	ETag   string
}

type MediaResourceVerification struct {
	ResourceID string
	ObjectKey  string
	UploadID   string
	Purpose    string
	SHA256     []byte
	Parts      []ReportedMediaPart
}

// MediaObjects issues exact multipart grants and verifies server-side object
// state. It deliberately has no list-bucket, read, overwrite, or delete API.
type MediaObjects interface {
	BeginMediaUpload(context.Context, string, []MediaResourcePlan, time.Duration) (MediaUploadGrant, error)
	ResumeMediaUpload(context.Context, string, []MediaResourceGrant, []MediaResourcePlan, time.Duration) (MediaUploadGrant, error)
	VerifyAndCompleteMediaUpload(context.Context, []MediaResourceVerification) error
	AbortMediaUpload(context.Context, []MediaResourceGrant) error
}

// MediaReadObjects can issue a GET grant for one already-registered media
// object. It intentionally exposes neither bucket enumeration nor deletion.
type MediaReadObjects interface {
	IssueMediaRead(context.Context, string, time.Duration) (ObjectGrant, error)
	// IssueMediaImagePreview signs one exact original-image GET together with
	// the OSS image-processing query. The process must be part of the signed
	// request; callers must never append it to an already-signed URL.
	IssueMediaImagePreview(context.Context, string, time.Duration) (ObjectGrant, error)
}

type DisabledMediaReadObjects struct{}

func (DisabledMediaReadObjects) IssueMediaRead(context.Context, string, time.Duration) (ObjectGrant, error) {
	return ObjectGrant{}, ErrUnavailable
}

func (DisabledMediaReadObjects) IssueMediaImagePreview(context.Context, string, time.Duration) (ObjectGrant, error) {
	return ObjectGrant{}, ErrUnavailable
}

type DisabledMediaObjects struct{}

func (DisabledMediaObjects) BeginMediaUpload(context.Context, string, []MediaResourcePlan, time.Duration) (MediaUploadGrant, error) {
	return MediaUploadGrant{}, ErrUnavailable
}
func (DisabledMediaObjects) ResumeMediaUpload(context.Context, string, []MediaResourceGrant, []MediaResourcePlan, time.Duration) (MediaUploadGrant, error) {
	return MediaUploadGrant{}, ErrUnavailable
}
func (DisabledMediaObjects) VerifyAndCompleteMediaUpload(context.Context, []MediaResourceVerification) error {
	return ErrUnavailable
}
func (DisabledMediaObjects) AbortMediaUpload(context.Context, []MediaResourceGrant) error { return nil }

type memoryMultipart struct {
	key       string
	expiresAt time.Time
	parts     map[int32]memoryPart
	complete  bool
}

type memoryPart struct {
	size   int64
	sha256 []byte
	etag   string
}

type MemoryMediaObjects struct {
	mu      sync.RWMutex
	now     func() time.Time
	uploads map[string]*memoryMultipart
	next    uint64
}

func NewMemoryMediaObjects(now func() time.Time) *MemoryMediaObjects {
	if now == nil {
		now = time.Now
	}
	return &MemoryMediaObjects{now: now, uploads: make(map[string]*memoryMultipart)}
}

func (s *MemoryMediaObjects) BeginMediaUpload(_ context.Context, prefix string, resources []MediaResourcePlan, lifetime time.Duration) (MediaUploadGrant, error) {
	if err := validateMediaUpload(prefix, resources, lifetime); err != nil {
		return MediaUploadGrant{}, err
	}
	expiresAt := s.now().UTC().Add(lifetime)
	grant := MediaUploadGrant{Purpose: resources[0].Purpose, ScopePrefix: prefix, ExpiresAt: expiresAt, Resources: make([]MediaResourceGrant, 0, len(resources))}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, resource := range resources {
		s.next++
		uploadID := fmt.Sprintf("memory-%s-%d", resource.ID, s.next)
		if _, exists := s.uploads[uploadID]; exists {
			return MediaUploadGrant{}, errors.New("multipart upload already exists")
		}
		s.uploads[uploadID] = &memoryMultipart{key: resource.ObjectKey, expiresAt: expiresAt, parts: make(map[int32]memoryPart)}
		item := MediaResourceGrant{ResourceID: resource.ID, ObjectKey: resource.ObjectKey, UploadID: uploadID, Parts: make([]MediaPartGrant, 0, len(resource.Parts))}
		for _, part := range resource.Parts {
			item.Parts = append(item.Parts, MediaPartGrant{Number: part.Number, Grant: ObjectGrant{
				URL: fmt.Sprintf("https://objects.invalid/media/%s/parts/%d", uploadID, part.Number), Method: "PUT", ExpiresAt: expiresAt,
				Headers: map[string]string{"Content-Type": "application/octet-stream", "Content-Length": fmt.Sprintf("%d", part.Size)},
			}})
		}
		grant.Resources = append(grant.Resources, item)
	}
	return grant, nil
}

func (s *MemoryMediaObjects) ResumeMediaUpload(_ context.Context, prefix string, existing []MediaResourceGrant, resources []MediaResourcePlan, lifetime time.Duration) (MediaUploadGrant, error) {
	if err := validateMediaUpload(prefix, resources, lifetime); err != nil || len(existing) != len(resources) {
		return MediaUploadGrant{}, errors.New("invalid media upload resume")
	}
	expiresAt := s.now().UTC().Add(lifetime)
	grant := MediaUploadGrant{Purpose: resources[0].Purpose, ScopePrefix: prefix, ExpiresAt: expiresAt, Resources: make([]MediaResourceGrant, 0, len(resources))}
	s.mu.Lock()
	defer s.mu.Unlock()
	for index, resource := range resources {
		prior := existing[index]
		upload, ok := s.uploads[prior.UploadID]
		if !ok || upload.complete || upload.key != resource.ObjectKey || prior.ResourceID != resource.ID {
			return MediaUploadGrant{}, ErrObjectNotReady
		}
		upload.expiresAt = expiresAt
		item := MediaResourceGrant{ResourceID: resource.ID, ObjectKey: resource.ObjectKey, UploadID: prior.UploadID, Parts: make([]MediaPartGrant, 0, len(resource.Parts))}
		for _, part := range resource.Parts {
			item.Parts = append(item.Parts, MediaPartGrant{Number: part.Number, Grant: ObjectGrant{
				URL: fmt.Sprintf("https://objects.invalid/media/%s/parts/%d", prior.UploadID, part.Number), Method: "PUT", ExpiresAt: expiresAt,
				Headers: map[string]string{"Content-Type": "application/octet-stream", "Content-Length": fmt.Sprintf("%d", part.Size)},
			}})
		}
		grant.Resources = append(grant.Resources, item)
	}
	return grant, nil
}

// PutPart is a test/local transport hook. Production clients upload directly
// to a signed OSS URL and never proxy media bytes through the API process.
func (s *MemoryMediaObjects) PutPart(uploadID string, number int32, content []byte) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	upload, ok := s.uploads[uploadID]
	if !ok || upload.complete || !s.now().Before(upload.expiresAt) || number < 1 || len(content) == 0 || len(content) > OriginalMediaPartMaximum {
		return "", ErrObjectNotReady
	}
	digest := sha256.Sum256(content)
	md5Digest := md5.Sum(content) // OSS multipart ETags are opaque; this only models the local adapter.
	etag := hex.EncodeToString(md5Digest[:])
	upload.parts[number] = memoryPart{size: int64(len(content)), sha256: digest[:], etag: etag}
	return etag, nil
}

func (s *MemoryMediaObjects) VerifyAndCompleteMediaUpload(_ context.Context, resources []MediaResourceVerification) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, expected := range resources {
		upload, ok := s.uploads[expected.UploadID]
		if !ok || upload.key != expected.ObjectKey || len(expected.SHA256) != 32 || len(expected.Parts) == 0 {
			return ErrObjectNotReady
		}
		for _, part := range expected.Parts {
			actual, ok := upload.parts[part.Number]
			if !ok || actual.size != part.Size || !bytes.Equal(actual.sha256, part.SHA256) || normalizeETag(actual.etag) != normalizeETag(part.ETag) {
				return ErrObjectNotReady
			}
		}
	}
	for _, expected := range resources {
		s.uploads[expected.UploadID].complete = true
	}
	return nil
}

func (s *MemoryMediaObjects) AbortMediaUpload(_ context.Context, resources []MediaResourceGrant) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, resource := range resources {
		delete(s.uploads, resource.UploadID)
	}
	return nil
}

func (s *MemoryMediaObjects) IssueMediaRead(_ context.Context, key string, lifetime time.Duration) (ObjectGrant, error) {
	if !strings.HasPrefix(key, "media/") || lifetime <= 0 || lifetime > 15*time.Minute {
		return ObjectGrant{}, errors.New("invalid media read grant")
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, upload := range s.uploads {
		if upload.complete && upload.key == key {
			return ObjectGrant{
				URL: "https://objects.invalid/read/" + key, Method: "GET", ExpiresAt: s.now().UTC().Add(lifetime), Headers: map[string]string{},
			}, nil
		}
	}
	return ObjectGrant{}, ErrObjectNotReady
}

func (s *MemoryMediaObjects) IssueMediaImagePreview(_ context.Context, key string, lifetime time.Duration) (ObjectGrant, error) {
	if !strings.HasPrefix(key, "media/") || lifetime <= 0 || lifetime > 15*time.Minute {
		return ObjectGrant{}, errors.New("invalid media image preview grant")
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, upload := range s.uploads {
		if upload.complete && upload.key == key {
			return ObjectGrant{
				URL:    "https://objects.invalid/read/" + key + "?x-oss-process=image%2Fresize%2Cm_lfit%2Cl_512",
				Method: "GET", ExpiresAt: s.now().UTC().Add(lifetime), Headers: map[string]string{},
			}, nil
		}
	}
	return ObjectGrant{}, ErrObjectNotReady
}

func validateMediaUpload(prefix string, resources []MediaResourcePlan, lifetime time.Duration) error {
	if !strings.HasPrefix(prefix, "media/") || !strings.HasSuffix(prefix, "/") || strings.Contains(prefix, "..") || lifetime <= 0 || lifetime > 15*time.Minute || len(resources) < 1 || len(resources) > 8 {
		return errors.New("invalid media upload scope")
	}
	resourceIDs := make(map[string]struct{}, len(resources))
	objectKeys := make(map[string]struct{}, len(resources))
	purpose := resources[0].Purpose
	if purpose != "MEDIA_ORIGINAL" {
		return errors.New("invalid media upload purpose")
	}
	for _, resource := range resources {
		if resource.ID == "" || resource.Purpose != purpose || !strings.HasPrefix(resource.ObjectKey, prefix) || len(resource.SHA256) != sha256.Size || resource.ContentSize < 1 || len(resource.Parts) < 1 || len(resource.Parts) > 10000 {
			return errors.New("invalid media resource plan")
		}
		if _, exists := resourceIDs[resource.ID]; exists {
			return errors.New("duplicate media resource")
		}
		if _, exists := objectKeys[resource.ObjectKey]; exists {
			return errors.New("duplicate media object key")
		}
		resourceIDs[resource.ID] = struct{}{}
		objectKeys[resource.ObjectKey] = struct{}{}
		var total int64
		partMaximum := int64(OriginalMediaPartMaximum)
		for index, part := range resource.Parts {
			if part.Number != int32(index+1) || part.Size < 1 || part.Size > partMaximum || len(part.SHA256) != sha256.Size {
				return errors.New("invalid media part plan")
			}
			if index < len(resource.Parts)-1 && part.Size != partMaximum {
				return errors.New("non-final media part is not one full logical block")
			}
			total += part.Size
		}
		if total != resource.ContentSize {
			return errors.New("media resource size does not equal its parts")
		}
	}
	return nil
}

func normalizeETag(value string) string { return strings.Trim(strings.TrimSpace(value), "\"") }

func sortedReportedParts(parts []ReportedMediaPart) []ReportedMediaPart {
	result := append([]ReportedMediaPart(nil), parts...)
	sort.Slice(result, func(i, j int) bool { return result[i].Number < result[j].Number })
	return result
}
