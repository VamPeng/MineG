package objectstore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"github.com/aliyun/alibabacloud-oss-go-sdk-v2/oss"
)

// CheckLocalOSSAccess verifies the intended local role boundary without
// completing an object. The temporary multipart upload is always aborted.
func CheckLocalOSSAccess(ctx context.Context, config OSSProfileConfig) error {
	objects, err := NewOSSProfileObjects(config)
	if err != nil {
		return err
	}
	if config.CredentialsExpiration.IsZero() {
		return errors.New("local OSS check requires temporary STS credentials")
	}
	// The application uses OSS IMG's x-oss-process query for photo previews.
	// Check that the same temporary credentials can sign that exact read before
	// starting the API, without fetching or persisting any media object.
	preview, err := objects.IssueMediaImagePreview(ctx, "media/diagnostics/preview-check.jpg", 5*time.Minute)
	if err != nil {
		return fmt.Errorf("presign diagnostic image preview: %w", err)
	}
	previewURL, err := url.Parse(preview.URL)
	if err != nil || preview.Method != http.MethodGet || previewURL.Query().Get("x-oss-process") != "image/resize,m_lfit,l_512" {
		return errors.New("diagnostic image preview grant was not a signed OSS IMG GET")
	}

	key := fmt.Sprintf("media/diagnostics/permission-check-%d.bin", time.Now().UTC().UnixNano())
	initiated, err := objects.headClient.InitiateMultipartUpload(ctx, &oss.InitiateMultipartUploadRequest{
		Bucket: oss.Ptr(objects.bucket), Key: oss.Ptr(key), ContentType: oss.Ptr("application/octet-stream"),
		ForbidOverwrite: oss.Ptr("true"),
	})
	if err != nil || initiated.UploadId == nil {
		if err == nil {
			err = errors.New("OSS returned no multipart upload ID")
		}
		return fmt.Errorf("initiate diagnostic multipart upload: %w", err)
	}
	uploadID := *initiated.UploadId
	aborted := false
	defer func() {
		if !aborted {
			_, _ = objects.headClient.AbortMultipartUpload(context.Background(), &oss.AbortMultipartUploadRequest{
				Bucket: oss.Ptr(objects.bucket), Key: oss.Ptr(key), UploadId: oss.Ptr(uploadID),
			})
		}
	}()

	part := bytes.Repeat([]byte{0x4d}, 128*1024)
	presigned, err := objects.presignMediaUploadPart(
		ctx, key, uploadID, 1, int64(len(part)), 5*time.Minute,
	)
	if err != nil {
		return fmt.Errorf("presign diagnostic multipart part: %w", err)
	}
	request, err := http.NewRequestWithContext(ctx, presigned.Method, presigned.URL, bytes.NewReader(part))
	if err != nil {
		return fmt.Errorf("build diagnostic presigned request: %w", err)
	}
	for name, value := range presigned.Headers {
		request.Header.Set(name, value)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return fmt.Errorf("upload diagnostic presigned multipart part: %w", err)
	}
	_ = response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("upload diagnostic presigned multipart part: OSS returned %s", response.Status)
	}
	listed, err := objects.headClient.ListParts(ctx, &oss.ListPartsRequest{
		Bucket: oss.Ptr(objects.bucket), Key: oss.Ptr(key), UploadId: oss.Ptr(uploadID), MaxParts: 10,
	})
	if err != nil {
		return fmt.Errorf("list diagnostic multipart parts: %w", err)
	}
	if len(listed.Parts) != 1 || listed.Parts[0].PartNumber != 1 {
		return errors.New("diagnostic multipart part was not listed exactly once")
	}

	if err := requireAccessDenied("list bucket", func() error {
		_, callErr := objects.headClient.ListObjectsV2(ctx, &oss.ListObjectsV2Request{Bucket: oss.Ptr(objects.bucket), MaxKeys: 1})
		return callErr
	}); err != nil {
		return err
	}
	if err := requireAccessDenied("read outside allowed prefixes", func() error {
		_, callErr := objects.headClient.HeadObject(ctx, &oss.HeadObjectRequest{
			Bucket: oss.Ptr(objects.bucket), Key: oss.Ptr("forbidden/permission-check.bin"),
		})
		return callErr
	}); err != nil {
		return err
	}
	if err := requireAccessDenied("delete object", func() error {
		_, callErr := objects.headClient.DeleteObject(ctx, &oss.DeleteObjectRequest{Bucket: oss.Ptr(objects.bucket), Key: oss.Ptr(key)})
		return callErr
	}); err != nil {
		return err
	}

	if _, err := objects.headClient.AbortMultipartUpload(ctx, &oss.AbortMultipartUploadRequest{
		Bucket: oss.Ptr(objects.bucket), Key: oss.Ptr(key), UploadId: oss.Ptr(uploadID),
	}); err != nil {
		return fmt.Errorf("abort diagnostic multipart upload: %w", err)
	}
	aborted = true
	return nil
}

func requireAccessDenied(action string, call func() error) error {
	err := call()
	if err == nil {
		return fmt.Errorf("%s unexpectedly succeeded", action)
	}
	var serviceError interface{ ErrorCode() string }
	if !errors.As(err, &serviceError) || serviceError.ErrorCode() != "AccessDenied" {
		return fmt.Errorf("%s returned %w instead of AccessDenied", action, err)
	}
	return nil
}
