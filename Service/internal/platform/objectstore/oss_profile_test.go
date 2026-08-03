package objectstore

import (
	"context"
	"encoding/json"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/aliyun/alibabacloud-oss-go-sdk-v2/oss"
	osscredentials "github.com/aliyun/alibabacloud-oss-go-sdk-v2/oss/credentials"
)

func TestNormalizeOSSEndpointRequiresCredentialFreeHTTPSOrigin(t *testing.T) {
	value, err := normalizeOSSEndpoint("https://oss-cn-hangzhou-internal.aliyuncs.com/")
	if err != nil || value != "oss-cn-hangzhou-internal.aliyuncs.com" {
		t.Fatalf("normalized endpoint = %q, %v", value, err)
	}
	for _, invalid := range []string{
		"http://oss-cn-hangzhou-internal.aliyuncs.com",
		"https://user:secret@oss-cn-hangzhou-internal.aliyuncs.com",
		"https://oss-cn-hangzhou-internal.aliyuncs.com/path",
		"https://oss-cn-hangzhou-internal.aliyuncs.com?secret=value",
	} {
		if _, err := normalizeOSSEndpoint(invalid); err == nil {
			t.Fatalf("insecure endpoint accepted: %s", invalid)
		}
	}
}

func TestLocalTemporarySTSCredentialsIncludeTokenAndExpiration(t *testing.T) {
	expiration := time.Now().UTC().Add(time.Hour).Truncate(time.Second)
	provider, err := newOSSCredentialsProvider(OSSProfileConfig{
		PublicEndpoint: "https://oss-cn-hangzhou.aliyuncs.com",
		AccessKeyID:    "temporary-ak", AccessKeySecret: "temporary-sk", SecurityToken: "temporary-token",
		CredentialsExpiration: expiration,
	})
	if err != nil {
		t.Fatal(err)
	}
	credentials, err := provider.GetCredentials(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if credentials.AccessKeyID != "temporary-ak" || credentials.AccessKeySecret != "temporary-sk" ||
		credentials.SecurityToken != "temporary-token" || credentials.Expires == nil || !credentials.Expires.Equal(expiration) {
		t.Fatalf("unexpected temporary credentials: token=%t expiration=%v", credentials.SecurityToken != "", credentials.Expires)
	}
}

func TestLocalTemporarySTSConstructorPresignsWithSecurityToken(t *testing.T) {
	expiration := time.Now().UTC().Add(time.Hour)
	objects, err := NewOSSProfileObjects(OSSProfileConfig{
		Region: "cn-hangzhou", Bucket: "mineg-private", PublicEndpoint: "https://oss-cn-hangzhou.aliyuncs.com",
		AccessKeyID: "temporary-ak", AccessKeySecret: "temporary-sk", SecurityToken: "temporary-token",
		CredentialsExpiration: expiration,
	})
	if err != nil {
		t.Fatal(err)
	}
	grant, err := objects.IssueAvatarUpload(context.Background(), ProfileObjectMetadata{
		Key: "avatars/user-001/upload-001.webp", Size: 1024, ContentType: "image/webp", SHA256: make([]byte, 32),
	}, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(grant.URL)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Query().Get("x-oss-security-token") != "temporary-token" {
		t.Fatalf("temporary security token missing from presigned URL: %s", grant.URL)
	}
}

func TestMediaUploadPartPresignBindsStableHTTPHeaders(t *testing.T) {
	client := oss.NewClient(oss.LoadDefaultConfig().
		WithCredentialsProvider(osscredentials.NewStaticCredentialsProvider("temporary-id", "temporary-secret", "temporary-token")).
		WithRegion("cn-hangzhou").
		WithEndpoint("https://oss-cn-hangzhou.aliyuncs.com").
		WithAdditionalHeaders([]string{"content-length"}))
	objects := &OSSProfileObjects{bucket: "mineg-private", presignClient: client}

	grant, err := objects.presignMediaUploadPart(
		context.Background(), "media/owner/upload/resource.original", "multipart-upload-id", 1, 1024, 5*time.Minute,
	)
	if err != nil {
		t.Fatal(err)
	}
	if grant.Headers["Content-Length"] != "1024" || grant.Headers["Content-Type"] != "application/octet-stream" {
		t.Fatalf("multipart grant omitted signed framing headers: %#v", grant.Headers)
	}
}

func TestMediaImagePreviewPresignBindsOSSProcessToExactRead(t *testing.T) {
	client := oss.NewClient(oss.LoadDefaultConfig().
		WithCredentialsProvider(osscredentials.NewStaticCredentialsProvider("temporary-id", "temporary-secret", "temporary-token")).
		WithRegion("cn-hangzhou").
		WithEndpoint("https://oss-cn-hangzhou.aliyuncs.com"))
	objects := &OSSProfileObjects{bucket: "mineg-private", presignClient: client}

	grant, err := objects.IssueMediaImagePreview(context.Background(), "media/owner/upload/resource.original", 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(grant.URL)
	if err != nil {
		t.Fatal(err)
	}
	if grant.Method != "GET" || parsed.Query().Get("x-oss-process") != "image/resize,m_lfit,l_512" ||
		parsed.Query().Get("x-oss-signature") == "" {
		t.Fatalf("image preview was not a signed processed GET: %#v", grant)
	}
	encoded, err := json.Marshal(grant)
	if err != nil || !strings.Contains(string(encoded), `"headers":{}`) {
		t.Fatalf("signed image preview did not serialize empty headers as an object: %s, %v", encoded, err)
	}
}

func TestLocalTemporarySTSRejectsIncompleteExpiredAndMixedConfiguration(t *testing.T) {
	valid := OSSProfileConfig{
		PublicEndpoint: "https://oss-cn-hangzhou.aliyuncs.com",
		AccessKeyID:    "temporary-ak", AccessKeySecret: "temporary-sk", SecurityToken: "temporary-token",
		CredentialsExpiration: time.Now().UTC().Add(time.Hour),
	}
	for name, mutate := range map[string]func(*OSSProfileConfig){
		"missing token": func(config *OSSProfileConfig) { config.SecurityToken = "" },
		"expired":       func(config *OSSProfileConfig) { config.CredentialsExpiration = time.Now().UTC().Add(-time.Minute) },
		"mixed role":    func(config *OSSProfileConfig) { config.ECSRAMRole = "mineg-api" },
	} {
		t.Run(name, func(t *testing.T) {
			config := valid
			mutate(&config)
			if _, err := newOSSCredentialsProvider(config); err == nil {
				t.Fatal("expected invalid local STS configuration to be rejected")
			}
		})
	}
}

func TestLocalTemporarySTSDoesNotIssueGrantPastCredentialExpiration(t *testing.T) {
	objects := &OSSProfileObjects{credentialsExpiration: time.Now().UTC().Add(5 * time.Minute)}
	if err := objects.ensureCredentialLifetime(10 * time.Minute); err == nil {
		t.Fatal("expected grant beyond STS expiration to be rejected")
	}
	if err := objects.ensureCredentialLifetime(time.Minute); err != nil {
		t.Fatalf("short grant rejected: %v", err)
	}
}

type permissionCheckError struct{ code string }

func (e permissionCheckError) Error() string     { return e.code }
func (e permissionCheckError) ErrorCode() string { return e.code }

func TestRequireAccessDenied(t *testing.T) {
	if err := requireAccessDenied("negative check", func() error { return permissionCheckError{code: "AccessDenied"} }); err != nil {
		t.Fatalf("AccessDenied rejected: %v", err)
	}
	if err := requireAccessDenied("negative check", func() error { return nil }); err == nil {
		t.Fatal("unexpected success accepted")
	}
	if err := requireAccessDenied("negative check", func() error { return permissionCheckError{code: "NoSuchKey"} }); err == nil {
		t.Fatal("wrong error code accepted")
	}
}

func TestAvatarPresignIsExactShortLivedPutWithoutBroadActions(t *testing.T) {
	config := oss.LoadDefaultConfig().
		WithCredentialsProvider(osscredentials.NewStaticCredentialsProvider("temporary-ak", "temporary-sk")).
		WithRegion("cn-hangzhou").
		WithAdditionalHeaders([]string{"content-length"}).
		WithSignatureVersion(oss.SignatureVersionV4)
	client := oss.NewClient(config)
	objects := &OSSProfileObjects{bucket: "mineg-private", presignClient: client, headClient: client}
	started := time.Now()
	grant, err := objects.IssueAvatarUpload(context.Background(), ProfileObjectMetadata{
		Key: "avatars/user-001/upload-001.webp", Size: 1024, ContentType: "image/webp",
		SHA256: make([]byte, 32),
	}, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if grant.Method != "PUT" || grant.ExpiresAt.Before(started.Add(4*time.Minute+50*time.Second)) ||
		grant.ExpiresAt.After(started.Add(5*time.Minute+10*time.Second)) {
		t.Fatalf("unexpected grant method or lifetime: %#v", grant)
	}
	parsed, err := url.Parse(grant.URL)
	if err != nil || !strings.HasSuffix(parsed.Host, ".oss-cn-hangzhou.aliyuncs.com") ||
		parsed.Path != "/avatars/user-001/upload-001.webp" {
		t.Fatalf("grant is not scoped to the exact avatar key: %s, %v", grant.URL, err)
	}
	serialized := strings.ToLower(grant.URL + " " + strings.Join(mapKeys(grant.Headers), " "))
	for _, forbidden := range []string{"deleteobject", "listobjects", "bucket-acl"} {
		if strings.Contains(serialized, forbidden) {
			t.Fatalf("grant contains broad action %q: %#v", forbidden, grant)
		}
	}
	if grant.Headers["Content-Type"] != "image/webp" || grant.Headers["Content-Length"] != "1024" {
		t.Fatalf("content constraints are not signed: %#v", grant.Headers)
	}
	metadataFound := false
	for name := range grant.Headers {
		if strings.EqualFold(name, "x-oss-meta-mineg-sha256") {
			metadataFound = true
		}
	}
	if !metadataFound {
		t.Fatalf("digest metadata is not signed: %#v", grant.Headers)
	}
}

func mapKeys(values map[string]string) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	return keys
}
