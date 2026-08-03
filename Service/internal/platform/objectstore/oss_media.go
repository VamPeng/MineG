package objectstore

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/aliyun/alibabacloud-oss-go-sdk-v2/oss"
)

func (s *OSSProfileObjects) BeginMediaUpload(ctx context.Context, prefix string, resources []MediaResourcePlan, lifetime time.Duration) (MediaUploadGrant, error) {
	if err := validateMediaUpload(prefix, resources, lifetime); err != nil {
		return MediaUploadGrant{}, err
	}
	if err := s.ensureCredentialLifetime(lifetime); err != nil {
		return MediaUploadGrant{}, err
	}
	grant := MediaUploadGrant{Purpose: resources[0].Purpose, ScopePrefix: prefix, ExpiresAt: time.Now().UTC().Add(lifetime), Resources: make([]MediaResourceGrant, 0, len(resources))}
	for _, resource := range resources {
		initiated, err := s.headClient.InitiateMultipartUpload(ctx, &oss.InitiateMultipartUploadRequest{
			Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(resource.ObjectKey), ContentType: oss.Ptr("application/octet-stream"),
			CacheControl: oss.Ptr("private, no-store"), ForbidOverwrite: oss.Ptr("true"),
			Metadata: map[string]string{"mineg-content-sha256": base64.RawStdEncoding.EncodeToString(resource.SHA256)},
		})
		if err != nil || initiated.UploadId == nil {
			_ = s.AbortMediaUpload(ctx, grant.Resources)
			if err == nil {
				err = errors.New("OSS returned no multipart upload ID")
			}
			return MediaUploadGrant{}, fmt.Errorf("initiate media multipart upload: %w", err)
		}
		item := MediaResourceGrant{ResourceID: resource.ID, ObjectKey: resource.ObjectKey, UploadID: *initiated.UploadId, Parts: make([]MediaPartGrant, 0, len(resource.Parts))}
		for _, part := range resource.Parts {
			partGrant, err := s.presignMediaUploadPart(ctx, resource.ObjectKey, *initiated.UploadId, part.Number, part.Size, lifetime)
			if err != nil {
				grant.Resources = append(grant.Resources, item)
				_ = s.AbortMediaUpload(ctx, grant.Resources)
				return MediaUploadGrant{}, fmt.Errorf("presign media upload part: %w", err)
			}
			item.Parts = append(item.Parts, MediaPartGrant{Number: part.Number, Grant: partGrant})
			if partGrant.ExpiresAt.Before(grant.ExpiresAt) {
				grant.ExpiresAt = partGrant.ExpiresAt
			}
		}
		grant.Resources = append(grant.Resources, item)
	}
	return grant, nil
}

func (s *OSSProfileObjects) ResumeMediaUpload(ctx context.Context, prefix string, existing []MediaResourceGrant, resources []MediaResourcePlan, lifetime time.Duration) (MediaUploadGrant, error) {
	if err := validateMediaUpload(prefix, resources, lifetime); err != nil || len(existing) != len(resources) {
		return MediaUploadGrant{}, errors.New("invalid media upload resume")
	}
	if err := s.ensureCredentialLifetime(lifetime); err != nil {
		return MediaUploadGrant{}, err
	}
	grant := MediaUploadGrant{Purpose: resources[0].Purpose, ScopePrefix: prefix, ExpiresAt: time.Now().UTC().Add(lifetime), Resources: make([]MediaResourceGrant, 0, len(resources))}
	for index, resource := range resources {
		prior := existing[index]
		if prior.ResourceID != resource.ID || prior.ObjectKey != resource.ObjectKey || prior.UploadID == "" {
			return MediaUploadGrant{}, errors.New("media upload resume scope mismatch")
		}
		item := MediaResourceGrant{ResourceID: resource.ID, ObjectKey: resource.ObjectKey, UploadID: prior.UploadID, Parts: make([]MediaPartGrant, 0, len(resource.Parts))}
		for _, part := range resource.Parts {
			partGrant, err := s.presignMediaUploadPart(ctx, resource.ObjectKey, prior.UploadID, part.Number, part.Size, lifetime)
			if err != nil {
				return MediaUploadGrant{}, fmt.Errorf("presign resumed media upload part: %w", err)
			}
			item.Parts = append(item.Parts, MediaPartGrant{Number: part.Number, Grant: partGrant})
			if partGrant.ExpiresAt.Before(grant.ExpiresAt) {
				grant.ExpiresAt = partGrant.ExpiresAt
			}
		}
		grant.Resources = append(grant.Resources, item)
	}
	return grant, nil
}

func (s *OSSProfileObjects) presignMediaUploadPart(ctx context.Context, objectKey, uploadID string, partNumber int32, size int64, lifetime time.Duration) (ObjectGrant, error) {
	presigned, err := s.presignClient.Presign(ctx, &oss.UploadPartRequest{
		Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(objectKey), UploadId: oss.Ptr(uploadID),
		PartNumber: partNumber, ContentLength: oss.Ptr(size),
		RequestCommon: oss.RequestCommon{Headers: map[string]string{"content-type": "application/octet-stream"}},
	}, oss.PresignExpires(lifetime))
	if err != nil {
		return ObjectGrant{}, err
	}
	return ObjectGrant{
		URL: presigned.URL, Method: presigned.Method, ExpiresAt: presigned.Expiration, Headers: normalizedObjectGrantHeaders(presigned.SignedHeaders),
	}, nil
}

func (s *OSSProfileObjects) VerifyAndCompleteMediaUpload(ctx context.Context, resources []MediaResourceVerification) error {
	if len(resources) == 0 {
		return ErrObjectNotReady
	}
	type preparedResource struct {
		verification MediaResourceVerification
		parts        []oss.UploadPart
		completed    bool
	}
	prepared := make([]preparedResource, 0, len(resources))
	for _, resource := range resources {
		if !strings.HasPrefix(resource.ObjectKey, "media/") || resource.UploadID == "" || len(resource.Parts) == 0 {
			return ErrObjectNotReady
		}
		expected := sortedReportedParts(resource.Parts)
		actual := make([]oss.Part, 0, len(expected))
		paginator := s.headClient.NewListPartsPaginator(&oss.ListPartsRequest{
			Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(resource.ObjectKey), UploadId: oss.Ptr(resource.UploadID), MaxParts: 1000,
		})
		for paginator.HasNext() {
			page, err := paginator.NextPage(ctx)
			if err != nil {
				ready, headErr := s.completedMediaObjectMatches(ctx, resource)
				if headErr == nil && ready {
					prepared = append(prepared, preparedResource{verification: resource, completed: true})
					actual = nil
					break
				}
				return fmt.Errorf("list media upload parts: %w", err)
			}
			actual = append(actual, page.Parts...)
		}
		if len(prepared) > 0 && prepared[len(prepared)-1].verification.UploadID == resource.UploadID && prepared[len(prepared)-1].completed {
			continue
		}
		if len(actual) != len(expected) {
			return ErrObjectNotReady
		}
		completed := make([]oss.UploadPart, 0, len(expected))
		for index, part := range expected {
			stored := actual[index]
			if stored.ETag == nil || stored.PartNumber != part.Number || stored.Size != part.Size || normalizeETag(*stored.ETag) != normalizeETag(part.ETag) || len(part.SHA256) != 32 {
				return ErrObjectNotReady
			}
			completed = append(completed, oss.UploadPart{PartNumber: part.Number, ETag: stored.ETag})
		}
		prepared = append(prepared, preparedResource{verification: resource, parts: completed})
	}
	// Verify every resource before assembling any of them. If a later OSS call
	// fails, completed objects are recognized by HeadObject on the retry.
	for _, resource := range prepared {
		if resource.completed {
			continue
		}
		_, err := s.headClient.CompleteMultipartUpload(ctx, &oss.CompleteMultipartUploadRequest{
			Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(resource.verification.ObjectKey), UploadId: oss.Ptr(resource.verification.UploadID),
			ForbidOverwrite: oss.Ptr("true"), CompleteMultipartUpload: &oss.CompleteMultipartUpload{Parts: resource.parts},
		})
		if err != nil {
			ready, headErr := s.completedMediaObjectMatches(ctx, resource.verification)
			if headErr != nil || !ready {
				return fmt.Errorf("complete media multipart upload: %w", err)
			}
			continue
		}
		ready, err := s.completedMediaObjectMatches(ctx, resource.verification)
		if err != nil {
			return fmt.Errorf("head completed media object: %w", err)
		}
		if !ready {
			return ErrObjectNotReady
		}
	}
	return nil
}

func (s *OSSProfileObjects) completedMediaObjectMatches(ctx context.Context, resource MediaResourceVerification) (bool, error) {
	head, err := s.headClient.HeadObject(ctx, &oss.HeadObjectRequest{Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(resource.ObjectKey)})
	if err != nil {
		return false, err
	}
	var expectedSize int64
	for _, part := range resource.Parts {
		expectedSize += part.Size
	}
	metadataDigest := ""
	for name, value := range head.Metadata {
		if strings.EqualFold(name, "mineg-content-sha256") {
			metadataDigest = value
		}
	}
	decoded, decodeErr := base64.RawStdEncoding.DecodeString(metadataDigest)
	// The server-authenticated upload plan is the source of the whole-object
	// digest; OSS independently proves part order, ETag, and total length.
	return head.ContentLength == expectedSize && decodeErr == nil && len(resource.SHA256) == 32 && bytes.Equal(decoded, resource.SHA256), nil
}

func (s *OSSProfileObjects) AbortMediaUpload(ctx context.Context, resources []MediaResourceGrant) error {
	var first error
	for _, resource := range resources {
		if !strings.HasPrefix(resource.ObjectKey, "media/") || resource.UploadID == "" {
			continue
		}
		_, err := s.headClient.AbortMultipartUpload(ctx, &oss.AbortMultipartUploadRequest{
			Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(resource.ObjectKey), UploadId: oss.Ptr(resource.UploadID),
		})
		if err != nil && first == nil {
			first = err
		}
	}
	return first
}

func (s *OSSProfileObjects) IssueMediaRead(ctx context.Context, key string, lifetime time.Duration) (ObjectGrant, error) {
	if !strings.HasPrefix(key, "media/") || lifetime <= 0 || lifetime > 15*time.Minute {
		return ObjectGrant{}, errors.New("invalid media read grant")
	}
	if err := s.ensureCredentialLifetime(lifetime); err != nil {
		return ObjectGrant{}, err
	}
	presigned, err := s.presignClient.Presign(ctx, &oss.GetObjectRequest{
		Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(key),
	}, oss.PresignExpires(lifetime))
	if err != nil {
		return ObjectGrant{}, fmt.Errorf("presign media read: %w", err)
	}
	return ObjectGrant{URL: presigned.URL, Method: presigned.Method, ExpiresAt: presigned.Expiration, Headers: normalizedObjectGrantHeaders(presigned.SignedHeaders)}, nil
}

// IssueMediaImagePreview uses OSS IMG for a bounded, on-the-fly grid image.
// It does not persist a derivative object and it never reads the original body
// through the API service. The OSS process query is included before signing.
func (s *OSSProfileObjects) IssueMediaImagePreview(ctx context.Context, key string, lifetime time.Duration) (ObjectGrant, error) {
	if !strings.HasPrefix(key, "media/") || lifetime <= 0 || lifetime > 15*time.Minute {
		return ObjectGrant{}, errors.New("invalid media image preview grant")
	}
	if err := s.ensureCredentialLifetime(lifetime); err != nil {
		return ObjectGrant{}, err
	}
	presigned, err := s.presignClient.Presign(ctx, &oss.GetObjectRequest{
		Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(key),
		Process: oss.Ptr("image/resize,m_lfit,l_512"),
	}, oss.PresignExpires(lifetime))
	if err != nil {
		return ObjectGrant{}, fmt.Errorf("presign media image preview: %w", err)
	}
	return ObjectGrant{URL: presigned.URL, Method: presigned.Method, ExpiresAt: presigned.Expiration, Headers: normalizedObjectGrantHeaders(presigned.SignedHeaders)}, nil
}
