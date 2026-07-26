package objectstore

import (
	"context"
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
