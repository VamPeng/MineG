package objectstore

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/aliyun/alibabacloud-oss-go-sdk-v2/oss"
	osscredentials "github.com/aliyun/alibabacloud-oss-go-sdk-v2/oss/credentials"
	openapicredentials "github.com/aliyun/credentials-go/credentials"
)

type OSSProfileConfig struct {
	Region                string
	Bucket                string
	PublicEndpoint        string
	InternalEndpoint      string
	ECSRAMRole            string
	AccessKeyID           string
	AccessKeySecret       string
	SecurityToken         string
	CredentialsExpiration time.Time
}

type OSSProfileObjects struct {
	bucket                string
	presignClient         *oss.Client
	headClient            *oss.Client
	credentialsExpiration time.Time
}

func NewOSSProfileObjects(config OSSProfileConfig) (*OSSProfileObjects, error) {
	config.Region = strings.TrimSpace(config.Region)
	config.Bucket = strings.TrimSpace(config.Bucket)
	config.PublicEndpoint = strings.TrimSpace(config.PublicEndpoint)
	config.InternalEndpoint = strings.TrimSpace(config.InternalEndpoint)
	config.ECSRAMRole = strings.TrimSpace(config.ECSRAMRole)
	config.AccessKeyID = strings.TrimSpace(config.AccessKeyID)
	config.AccessKeySecret = strings.TrimSpace(config.AccessKeySecret)
	config.SecurityToken = strings.TrimSpace(config.SecurityToken)
	if config.Region == "" || config.Bucket == "" || strings.ContainsAny(config.Bucket, "/?#") {
		return nil, errors.New("OSS profile region and bucket are required")
	}
	provider, err := newOSSCredentialsProvider(config)
	if err != nil {
		return nil, err
	}
	publicConfig := oss.LoadDefaultConfig().
		WithCredentialsProvider(provider).
		WithRegion(config.Region).
		WithAdditionalHeaders([]string{"content-length"}).
		WithConnectTimeout(5 * time.Second).
		WithReadWriteTimeout(15 * time.Second)
	if config.PublicEndpoint != "" {
		endpoint, err := normalizeOSSEndpoint(config.PublicEndpoint)
		if err != nil {
			return nil, err
		}
		publicConfig.WithEndpoint(endpoint)
	}
	internalConfig := publicConfig.Copy()
	if config.InternalEndpoint != "" {
		endpoint, err := normalizeOSSEndpoint(config.InternalEndpoint)
		if err != nil {
			return nil, err
		}
		internalConfig.WithEndpoint(endpoint)
	} else if config.PublicEndpoint == "" {
		internalConfig.WithUseInternalEndpoint(true)
	}
	return &OSSProfileObjects{
		bucket: config.Bucket, presignClient: oss.NewClient(publicConfig), headClient: oss.NewClient(&internalConfig),
		credentialsExpiration: config.CredentialsExpiration,
	}, nil
}

func newOSSCredentialsProvider(config OSSProfileConfig) (osscredentials.CredentialsProvider, error) {
	localSTSConfigured := config.AccessKeyID != "" || config.AccessKeySecret != "" || config.SecurityToken != "" || !config.CredentialsExpiration.IsZero()
	if localSTSConfigured {
		if config.PublicEndpoint == "" || config.InternalEndpoint != "" || config.ECSRAMRole != "" || config.AccessKeyID == "" ||
			config.AccessKeySecret == "" || config.SecurityToken == "" || config.CredentialsExpiration.IsZero() {
			return nil, errors.New("local OSS requires a public endpoint and complete temporary STS credentials")
		}
		if !config.CredentialsExpiration.After(time.Now().UTC()) {
			return nil, errors.New("local OSS temporary STS credentials are expired")
		}
		expiration := config.CredentialsExpiration.UTC()
		return osscredentials.CredentialsProviderFunc(func(context.Context) (osscredentials.Credentials, error) {
			if !expiration.After(time.Now().UTC()) {
				return osscredentials.Credentials{}, errors.New("local OSS temporary STS credentials are expired")
			}
			return osscredentials.Credentials{
				AccessKeyID: config.AccessKeyID, AccessKeySecret: config.AccessKeySecret,
				SecurityToken: config.SecurityToken, Expires: &expiration,
			}, nil
		}), nil
	}
	if config.PublicEndpoint != "" || config.InternalEndpoint == "" || config.ECSRAMRole == "" {
		return nil, errors.New("deployment OSS requires an internal endpoint and ECS RAM role credentials")
	}
	credentialConfig := new(openapicredentials.Config).
		SetType("ecs_ram_role").
		SetDisableIMDSv1(true).
		SetConnectTimeout(1000).
		SetTimeout(1000)
	if config.ECSRAMRole != "" {
		credentialConfig.SetRoleName(config.ECSRAMRole)
	}
	credential, err := openapicredentials.NewCredential(credentialConfig)
	if err != nil {
		return nil, fmt.Errorf("configure ECS RAM role credentials: %w", err)
	}
	return osscredentials.CredentialsProviderFunc(func(context.Context) (osscredentials.Credentials, error) {
		value, err := credential.GetCredential()
		if err != nil {
			return osscredentials.Credentials{}, err
		}
		if value.AccessKeyId == nil || value.AccessKeySecret == nil || value.SecurityToken == nil {
			return osscredentials.Credentials{}, errors.New("ECS RAM role returned incomplete temporary credentials")
		}
		return osscredentials.Credentials{
			AccessKeyID: *value.AccessKeyId, AccessKeySecret: *value.AccessKeySecret,
			SecurityToken: *value.SecurityToken,
		}, nil
	}), nil
}

func (s *OSSProfileObjects) IssueAvatarUpload(ctx context.Context, object ProfileObjectMetadata, lifetime time.Duration) (ObjectGrant, error) {
	if err := validateAvatarObject(object, lifetime); err != nil || !strings.HasPrefix(object.Key, "avatars/") {
		return ObjectGrant{}, errors.New("invalid avatar object grant")
	}
	if err := s.ensureCredentialLifetime(lifetime); err != nil {
		return ObjectGrant{}, err
	}
	digest := base64.RawStdEncoding.EncodeToString(object.SHA256)
	result, err := s.presignClient.Presign(ctx, &oss.PutObjectRequest{
		Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(object.Key),
		ContentType: oss.Ptr(object.ContentType), CacheControl: oss.Ptr("private, no-store"),
		ForbidOverwrite: oss.Ptr("true"), Metadata: map[string]string{"mineg-sha256": digest},
		RequestCommon: oss.RequestCommon{Headers: map[string]string{"content-length": fmt.Sprintf("%d", object.Size)}},
	}, oss.PresignExpires(lifetime))
	if err != nil {
		return ObjectGrant{}, fmt.Errorf("presign avatar upload: %w", err)
	}
	return ObjectGrant{URL: result.URL, Method: result.Method, ExpiresAt: result.Expiration, Headers: normalizedObjectGrantHeaders(result.SignedHeaders)}, nil
}

func (s *OSSProfileObjects) VerifyAvatar(ctx context.Context, expected ProfileObjectMetadata) error {
	if err := validateAvatarObject(expected, 5*time.Minute); err != nil || !strings.HasPrefix(expected.Key, "avatars/") {
		return ErrObjectNotReady
	}
	result, err := s.headClient.HeadObject(ctx, &oss.HeadObjectRequest{Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(expected.Key)})
	if err != nil {
		return fmt.Errorf("head avatar object: %w", err)
	}
	contentType := ""
	if result.ContentType != nil {
		contentType = *result.ContentType
	}
	digest := ""
	for name, value := range result.Metadata {
		if strings.EqualFold(name, "mineg-sha256") {
			digest = value
			break
		}
	}
	decodedDigest, decodeErr := base64.RawStdEncoding.DecodeString(digest)
	if result.ContentLength != expected.Size || contentType != expected.ContentType || decodeErr != nil ||
		!bytes.Equal(decodedDigest, expected.SHA256) {
		return ErrObjectNotReady
	}
	return nil
}

func (s *OSSProfileObjects) IssueAvatarRead(ctx context.Context, key string, lifetime time.Duration) (ObjectGrant, error) {
	if !strings.HasPrefix(key, "avatars/") || lifetime <= 0 || lifetime > 15*time.Minute {
		return ObjectGrant{}, errors.New("invalid avatar read grant")
	}
	if err := s.ensureCredentialLifetime(lifetime); err != nil {
		return ObjectGrant{}, err
	}
	result, err := s.presignClient.Presign(ctx, &oss.GetObjectRequest{
		Bucket: oss.Ptr(s.bucket), Key: oss.Ptr(key),
	}, oss.PresignExpires(lifetime))
	if err != nil {
		return ObjectGrant{}, fmt.Errorf("presign avatar read: %w", err)
	}
	return ObjectGrant{URL: result.URL, Method: result.Method, ExpiresAt: result.Expiration, Headers: normalizedObjectGrantHeaders(result.SignedHeaders)}, nil
}

func normalizeOSSEndpoint(value string) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(value))
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil ||
		(parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", errors.New("OSS endpoint must be a credential-free HTTPS origin")
	}
	return parsed.Host, nil
}

func (s *OSSProfileObjects) ensureCredentialLifetime(lifetime time.Duration) error {
	if !s.credentialsExpiration.IsZero() && !time.Now().UTC().Add(lifetime).Before(s.credentialsExpiration) {
		return errors.New("local OSS temporary STS credentials expire before the requested object grant")
	}
	return nil
}
