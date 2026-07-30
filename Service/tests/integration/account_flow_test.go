//go:build integration

package integration_test

import (
	"context"
	"crypto/sha256"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/pressly/goose/v3"
	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/platform/database"
	"github.com/vampeng/mineg/service/internal/platform/httpapi"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
	"github.com/vampeng/mineg/service/internal/upload"
)

func TestStage01AccountReviewSessionFlow(t *testing.T) {
	databaseURL := os.Getenv("MINEG_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MINEG_TEST_DATABASE_URL is not configured")
	}
	ctx := context.Background()
	resetAndMigrate(t, ctx, databaseURL)
	pool, err := database.Open(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	now := time.Now().UTC().Truncate(time.Millisecond)
	profileObjects := objectstore.NewMemoryProfileObjects(func() time.Time { return now })
	service := account.New(pool, account.Config{
		Now: func() time.Time { return now }, CursorKey: []byte("stage-01-integration-cursor-key-0001"),
		ProfileObjects: profileObjects,
	})

	signUp := account.SignUpInput{
		Phone: "13800138000", Password: "family-photo-2026",
		DeviceInstallationID: "android-installation-0001",
		Platform:             "ANDROID",
		IdempotencyKey:       "signup-request-0001", RequestID: "integration-signup-001",
	}
	created, err := service.SignUp(ctx, signUp)
	if err != nil {
		t.Fatal(err)
	}
	var legacyBundleCount int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM mineg.user_key_bundles WHERE user_id=$1", created.UserID).Scan(&legacyBundleCount); err != nil {
		t.Fatal(err)
	}
	if legacyBundleCount != 0 {
		t.Fatalf("new registration unexpectedly created %d legacy key bundles", legacyBundleCount)
	}
	repeated, err := service.SignUp(ctx, signUp)
	if err != nil || repeated.UserID != created.UserID {
		t.Fatalf("idempotent registration = %#v, %v", repeated, err)
	}
	duplicate := signUp
	duplicate.IdempotencyKey = "signup-request-0002"
	if _, err := service.SignUp(ctx, duplicate); errorCode(err) != "PHONE_ALREADY_REGISTERED" {
		t.Fatalf("duplicate phone error = %v", err)
	}

	if _, err := service.SignIn(ctx, account.SignInInput{
		Phone: signUp.Phone, Password: "wrong-password-1", DeviceInstallationID: signUp.DeviceInstallationID,
		Platform: "ANDROID", AgreementAccepted: true, TermsVersion: "1.0", PrivacyVersion: "1.0",
	}); errorCode(err) != "CREDENTIALS_INVALID" {
		t.Fatalf("credential error = %v", err)
	}
	pendingSession, err := service.SignIn(ctx, account.SignInInput{
		Phone: signUp.Phone, Password: signUp.Password, DeviceInstallationID: signUp.DeviceInstallationID,
		Platform: "ANDROID", AgreementAccepted: true, TermsVersion: "1.0", PrivacyVersion: "1.0",
		RequestID: "integration-login-001",
	})
	if err != nil || pendingSession.ApprovalStatus != "PENDING" || pendingSession.NextStep != "REVIEW_PENDING" {
		t.Fatalf("pending session = %#v, %v", pendingSession, err)
	}
	userSession, err := service.AuthenticateUser(ctx, "Bearer "+pendingSession.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.GetProfile(ctx, userSession); errorCode(err) != "ACCOUNT_PENDING" {
		t.Fatalf("pending profile error = %v", err)
	}

	adminID, err := account.BootstrapAdmin(ctx, pool, "reviewer", "admin-family-2026")
	if err != nil || adminID == "" {
		t.Fatalf("bootstrap = %q, %v", adminID, err)
	}
	if _, err := account.BootstrapAdmin(ctx, pool, "second", "admin-family-2026"); err == nil {
		t.Fatal("second bootstrap unexpectedly succeeded")
	}
	adminLogin, err := service.AdminLogin(ctx, "reviewer", "admin-family-2026", "integration-admin-login")
	if err != nil {
		t.Fatal(err)
	}
	adminSession, err := service.AuthenticateAdmin(ctx, adminLogin.SessionToken)
	if err != nil || service.CheckCSRF(adminSession, adminLogin.CSRFToken) != nil {
		t.Fatalf("admin session = %#v, %v", adminSession, err)
	}
	handler := httpapi.New(httpapi.Dependencies{
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Readiness: pool,
		Account: service, AdminOrigin: "https://admin.example.test", RequestTimeout: 5 * time.Second,
	})
	for _, path := range []string{"/api/v1/me/key-bundle", "/api/v1/me/avatar", "/api/v1/key-grants/pending"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.AddCookie(&http.Cookie{Name: account.AdminCookieName, Value: adminLogin.SessionToken})
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusUnauthorized {
			t.Fatalf("admin cookie crossed into mobile route %s with status %d", path, recorder.Code)
		}
	}
	request := httptest.NewRequest(http.MethodGet, "/api/v1/admin/approvals", nil)
	request.Header.Set("Authorization", "Bearer "+pendingSession.AccessToken)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("mobile bearer crossed into admin route with status %d", recorder.Code)
	}
	page, err := service.ListApprovals(ctx, "", 20)
	if err != nil || len(page.Items) != 1 || page.Items[0].MaskedPhone != "138****8000" {
		t.Fatalf("approval page = %#v, %v", page, err)
	}

	results := make(chan account.ApproveResult, 2)
	errorsChannel := make(chan error, 2)
	var wait sync.WaitGroup
	for _, key := range []string{"approve-request-0001", "approve-request-0002"} {
		wait.Add(1)
		go func(idempotencyKey string) {
			defer wait.Done()
			result, err := service.Approve(ctx, adminSession, created.UserID, idempotencyKey, "integration-approve")
			if err != nil {
				errorsChannel <- err
				return
			}
			results <- result
		}(key)
	}
	wait.Wait()
	close(errorsChannel)
	close(results)
	for err := range errorsChannel {
		t.Fatal(err)
	}
	outcomes := map[string]int{}
	for result := range results {
		outcomes[result.Outcome]++
	}
	if outcomes["APPROVED"] != 1 || outcomes["ALREADY_PROCESSED"] != 1 {
		t.Fatalf("concurrent outcomes = %#v", outcomes)
	}
	page, err = service.ListApprovals(ctx, "", 20)
	if err != nil || len(page.Items) != 0 {
		t.Fatalf("processed application remained in queue: %#v, %v", page, err)
	}
	userSession, err = service.AuthenticateUser(ctx, "Bearer "+pendingSession.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	status := service.GetApprovalStatus(ctx, userSession)
	if status.Status != "APPROVED" {
		t.Fatalf("reviewed account did not become approved directly: %#v", status)
	}
	var keyGrantTaskCount int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM mineg.key_grant_tasks WHERE user_id=$1", created.UserID).Scan(&keyGrantTaskCount); err != nil {
		t.Fatal(err)
	}
	if keyGrantTaskCount != 0 {
		t.Fatalf("direct approval unexpectedly created %d key grant tasks", keyGrantTaskCount)
	}

	rotated, err := service.Refresh(ctx, pendingSession.RefreshToken, "integration-refresh")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Refresh(ctx, pendingSession.RefreshToken, "integration-replay"); errorCode(err) != "SESSION_REPLAYED" {
		t.Fatalf("replay error = %v", err)
	}
	if _, err := service.Refresh(ctx, rotated.RefreshToken, "integration-after-replay"); errorCode(err) != "SESSION_REPLAYED" {
		t.Fatalf("rotation family was not revoked: %v", err)
	}

	logoutSession, err := service.SignIn(ctx, account.SignInInput{
		Phone: signUp.Phone, Password: signUp.Password, DeviceInstallationID: signUp.DeviceInstallationID,
		Platform: "ANDROID", AgreementAccepted: true, TermsVersion: "1.0", PrivacyVersion: "1.0",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := service.SignOut(ctx, logoutSession.RefreshToken, "integration-logout"); err != nil {
		t.Fatal(err)
	}
	if _, err := service.AuthenticateUser(ctx, "Bearer "+logoutSession.AccessToken); errorCode(err) != "SESSION_EXPIRED" {
		t.Fatalf("revoked access remained valid: %v", err)
	}
	replayedAfterApproval, err := service.SignUp(ctx, signUp)
	if err != nil || replayedAfterApproval.ApprovalStatus != "APPROVED" || replayedAfterApproval.NextStep != "APP_HOME" {
		t.Fatalf("approved registration replay = %#v, %v", replayedAfterApproval, err)
	}

	approvedSession, err := service.SignIn(ctx, account.SignInInput{
		Phone: signUp.Phone, Password: signUp.Password, DeviceInstallationID: signUp.DeviceInstallationID,
		Platform: "ANDROID", AgreementAccepted: true, TermsVersion: "1.0", PrivacyVersion: "1.0",
	})
	if err != nil || approvedSession.NextStep != "APP_HOME" {
		t.Fatalf("approved session = %#v, %v", approvedSession, err)
	}
	approvedAuth, err := service.AuthenticateUser(ctx, "Bearer "+approvedSession.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	profile, err := service.GetProfile(ctx, approvedAuth)
	if err != nil || profile.Nickname != "8000" || profile.MaskedPhone != "138****8000" {
		t.Fatalf("profile = %#v, %v", profile, err)
	}
	profile, err = service.UpdateProfile(ctx, approvedAuth, " 家庭相册_01 ", "integration-profile")
	if err != nil || profile.Nickname != "家庭相册_01" || profile.Version != 2 {
		t.Fatalf("updated profile = %#v, %v", profile, err)
	}
	if _, err := service.UpdateProfile(ctx, approvedAuth, "invalid!", "integration-profile-invalid"); errorCode(err) != "NICKNAME_INVALID" {
		t.Fatalf("invalid nickname error = %v", err)
	}
	digest := bytesOf(9, 32)
	avatar, err := service.CreateAvatarUpload(ctx, approvedAuth, account.CreateAvatarUploadInput{
		IdempotencyKey: "avatar-request-0001", ContentType: "image/webp", SourceSize: 512,
		DisplaySize: 256, Width: 256, Height: 256, ContentSHA256: digest,
	})
	if err != nil {
		t.Fatal(err)
	}
	replayedAvatar, err := service.CreateAvatarUpload(ctx, approvedAuth, account.CreateAvatarUploadInput{
		IdempotencyKey: "avatar-request-0001", ContentType: "image/webp", SourceSize: 512,
		DisplaySize: 256, Width: 256, Height: 256, ContentSHA256: digest,
	})
	if err != nil || replayedAvatar.UploadID != avatar.UploadID || !replayedAvatar.Grant.ExpiresAt.Equal(avatar.Grant.ExpiresAt) {
		t.Fatalf("idempotent avatar upload = %#v, %v", replayedAvatar, err)
	}
	objectKey := strings.TrimPrefix(avatar.Grant.URL, "https://objects.invalid/upload/")
	if _, err := service.CompleteAvatarUpload(ctx, approvedAuth, avatar.UploadID, "avatar-before-upload"); errorCode(err) != "AVATAR_UPLOAD_NOT_READY" {
		t.Fatalf("unverified avatar completion error = %v", err)
	}
	if err := profileObjects.PutVerifiedAvatar(objectstore.ProfileObjectMetadata{
		Key: objectKey, Size: 256, ContentType: "image/webp", SHA256: digest,
	}); err != nil {
		t.Fatal(err)
	}
	profile, err = service.CompleteAvatarUpload(ctx, approvedAuth, avatar.UploadID, "avatar-complete")
	if err != nil || profile.AvatarURL == "" || profile.Version != 3 {
		t.Fatalf("completed avatar profile = %#v, %v", profile, err)
	}
	if _, err := service.CreateAvatarUpload(ctx, approvedAuth, account.CreateAvatarUploadInput{
		IdempotencyKey: "avatar-request-0001", ContentType: "image/webp", SourceSize: 512,
		DisplaySize: 256, Width: 256, Height: 256, ContentSHA256: digest,
	}); errorCode(err) != "AVATAR_UPLOAD_ALREADY_COMPLETED" {
		t.Fatalf("completed avatar idempotency error = %v", err)
	}

	uploadNow := now
	mediaObjects := objectstore.NewMemoryMediaObjects(func() time.Time { return uploadNow })
	uploads := upload.New(pool, upload.Config{Objects: mediaObjects, Now: func() time.Time { return uploadNow }})
	uploadActor := upload.Actor{UserID: approvedAuth.UserID, RawUserID: approvedAuth.RawUserID, Status: approvedAuth.Status}
	ciphertext := []byte("single encrypted media block with authentication tag")
	ciphertextDigest := sha256.Sum256(ciphertext)
	fingerprint := sha256.Sum256([]byte("private-account-dedupe-fingerprint"))
	manifestDigest := sha256.Sum256([]byte("encrypted-manifest"))
	uploadInput := upload.CreateInput{
		IdempotencyKey: "media-request-0001", ClientMediaID: "10000000-0000-4000-8000-000000000003",
		Dedupe: fingerprint[:], ContentRevision: 1, MediaType: "PHOTO", CapturedAt: now,
		ManifestDigest: manifestDigest[:], EncryptedManifest: bytesOf(4, 64), EncryptedMediaKey: bytesOf(5, 80),
		Resources: []upload.ResourceInput{{
			ID: "20000000-0000-4000-8000-000000000003", Type: "ORIGINAL",
			CiphertextSize: int64(len(ciphertext)), SHA256: ciphertextDigest[:],
			Parts: []upload.PartInput{{Number: 1, Size: int64(len(ciphertext)), SHA256: ciphertextDigest[:]}},
		}},
		RequestID: "integration-media-create-001",
	}
	createdUpload, err := uploads.Create(ctx, uploadActor, uploadInput)
	if err != nil || createdUpload.State != "PENDING" || createdUpload.Grant == nil {
		t.Fatalf("created media upload = %#v, %v", createdUpload, err)
	}
	replayedInput := uploadInput
	replayedInput.RequestID = "integration-media-create-replay"
	replayedUpload, err := uploads.Create(ctx, uploadActor, replayedInput)
	if err != nil || replayedUpload.ID != createdUpload.ID || replayedUpload.Grant == nil {
		t.Fatalf("idempotent media create = %#v, %v", replayedUpload, err)
	}
	changedInput := uploadInput
	changedInput.MediaType = "VIDEO"
	if _, err := uploads.Create(ctx, uploadActor, changedInput); uploadErrorCode(err) != "IDEMPOTENCY_KEY_REUSED" {
		t.Fatalf("changed media idempotency error = %v", err)
	}
	resourceGrant := createdUpload.Grant.Resources[0]
	etag, err := mediaObjects.PutPart(resourceGrant.UploadID, 1, ciphertext)
	if err != nil {
		t.Fatal(err)
	}
	partReport := upload.PartReportInput{
		IdempotencyKey: "media-part-request-0001", ResourceID: uploadInput.Resources[0].ID,
		Number: 1, Size: int64(len(ciphertext)), SHA256: ciphertextDigest[:], ETag: etag,
	}
	wrongReport := partReport
	wrongReport.SHA256 = bytesOf(0xff, 32)
	if _, err := uploads.ReportPart(ctx, uploadActor, createdUpload.ID, wrongReport); uploadErrorCode(err) != "UPLOAD_PART_MISMATCH" {
		t.Fatalf("tampered part report error = %v", err)
	}
	if _, err := uploads.ReportPart(ctx, uploadActor, createdUpload.ID, partReport); err != nil {
		t.Fatal(err)
	}
	if _, err := uploads.ReportPart(ctx, uploadActor, createdUpload.ID, partReport); err != nil {
		t.Fatalf("idempotent part report: %v", err)
	}
	completedMedia, err := uploads.Complete(ctx, uploadActor, createdUpload.ID, upload.CompleteInput{
		IdempotencyKey: "media-complete-request-0001", ManifestDigest: manifestDigest[:], RequestID: "integration-media-complete",
	})
	if err != nil || completedMedia.Outcome != "COMPLETED" || completedMedia.Deduplicated {
		t.Fatalf("completed media = %#v, %v", completedMedia, err)
	}
	replayedCompletion, err := uploads.Complete(ctx, uploadActor, createdUpload.ID, upload.CompleteInput{
		IdempotencyKey: "media-complete-request-0001", ManifestDigest: manifestDigest[:],
	})
	if err != nil || replayedCompletion.Outcome != "ALREADY_COMPLETED" || replayedCompletion.Deduplicated {
		t.Fatalf("idempotent media completion = %#v, %v", replayedCompletion, err)
	}
	deduplicatedInput := uploadInput
	deduplicatedInput.IdempotencyKey = "media-request-deduplicated-0002"
	deduplicatedInput.ClientMediaID = "10000000-0000-4000-8000-000000000004"
	deduplicated, err := uploads.Create(ctx, uploadActor, deduplicatedInput)
	if err != nil || !deduplicated.Deduplicated || deduplicated.MediaID != completedMedia.MediaID || deduplicated.Grant != nil {
		t.Fatalf("owner-scoped media dedupe = %#v, %v", deduplicated, err)
	}
	mediaPage, err := uploads.ListMedia(ctx, uploadActor, 20)
	if err != nil || len(mediaPage.Items) != 1 || mediaPage.Items[0].ID != completedMedia.MediaID {
		t.Fatalf("media list = %#v, %v", mediaPage, err)
	}

	original := []byte("original media bytes are uploaded without application encryption")
	originalDigest := sha256.Sum256(original)
	originalInput := upload.CreateInput{
		ProtocolVersion: "stage03-v2", IdempotencyKey: "original-media-request-0001",
		ClientMediaID: "10000000-0000-4000-8000-000000000013",
		Dedupe:        originalDigest[:], ContentSHA256: originalDigest[:], ContentRevision: 1,
		MediaType: "PHOTO", MimeType: "image/jpeg", CapturedAt: now,
		Resources: []upload.ResourceInput{{
			ID: "20000000-0000-4000-8000-000000000013", Type: "ORIGINAL",
			ContentSize: int64(len(original)), SHA256: originalDigest[:],
			Parts: []upload.PartInput{{Number: 1, Size: int64(len(original)), SHA256: originalDigest[:]}},
		}},
		RequestID: "integration-original-media-create",
	}
	originalUpload, err := uploads.Create(ctx, uploadActor, originalInput)
	if err != nil || originalUpload.Purpose != "MEDIA_ORIGINAL" || originalUpload.Grant == nil ||
		originalUpload.Grant.Purpose != "MEDIA_ORIGINAL" || originalUpload.Resources[0].ContentSize != int64(len(original)) ||
		originalUpload.Resources[0].CiphertextSize != 0 || !strings.HasSuffix(originalUpload.Resources[0].ObjectKey, ".original") {
		t.Fatalf("created original media upload = %#v, %v", originalUpload, err)
	}
	originalGrant := originalUpload.Grant.Resources[0]
	originalETag, err := mediaObjects.PutPart(originalGrant.UploadID, 1, original)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := uploads.ReportPart(ctx, uploadActor, originalUpload.ID, upload.PartReportInput{
		IdempotencyKey: "original-media-part-0001", ResourceID: originalInput.Resources[0].ID,
		Number: 1, Size: int64(len(original)), SHA256: originalDigest[:], ETag: originalETag,
	}); err != nil {
		t.Fatal(err)
	}
	originalCompleted, err := uploads.Complete(ctx, uploadActor, originalUpload.ID, upload.CompleteInput{
		IdempotencyKey: "original-media-complete-0001", RequestID: "integration-original-media-complete",
	})
	if err != nil || originalCompleted.Outcome != "COMPLETED" {
		t.Fatalf("completed original media = %#v, %v", originalCompleted, err)
	}
	var envelopeCount int
	var storedContentSize int64
	var storedCiphertextSize *int64
	if err := pool.QueryRow(ctx, `
		SELECT count(envelope.media_id), resource.content_size, resource.ciphertext_size
		FROM mineg.media resource_media
		JOIN mineg.media_resources resource ON resource.media_id=resource_media.id
		LEFT JOIN mineg.media_key_envelopes envelope ON envelope.media_id=resource_media.id
		WHERE resource_media.id=$1
		GROUP BY resource.content_size, resource.ciphertext_size`, originalCompleted.MediaID).
		Scan(&envelopeCount, &storedContentSize, &storedCiphertextSize); err != nil {
		t.Fatal(err)
	}
	if envelopeCount != 0 || storedContentSize != int64(len(original)) || storedCiphertextSize != nil {
		t.Fatalf("original media persistence leaked encryption contract: envelopes=%d content=%d ciphertext=%v", envelopeCount, storedContentSize, storedCiphertextSize)
	}
	expiringInput := uploadInput
	expiringInput.IdempotencyKey = "media-request-expiry-0003"
	expiringInput.ClientMediaID = "10000000-0000-4000-8000-000000000005"
	expiringFingerprint := sha256.Sum256([]byte("expiring-private-fingerprint"))
	expiringInput.Dedupe = expiringFingerprint[:]
	expiringInput.Resources = []upload.ResourceInput{{
		ID: "20000000-0000-4000-8000-000000000005", Type: "ORIGINAL",
		CiphertextSize: int64(len(ciphertext)), SHA256: ciphertextDigest[:],
		Parts: []upload.PartInput{{Number: 1, Size: int64(len(ciphertext)), SHA256: ciphertextDigest[:]}},
	}}
	expiring, err := uploads.Create(ctx, uploadActor, expiringInput)
	if err != nil || expiring.Grant == nil {
		t.Fatalf("expiring media upload = %#v, %v", expiring, err)
	}
	priorMultipartID := expiring.Grant.Resources[0].UploadID
	uploadNow = uploadNow.Add(25 * time.Hour)
	revived, err := uploads.Get(ctx, uploadActor, expiring.ID)
	if err != nil || revived.State != "PENDING" || revived.Grant == nil || revived.Grant.Resources[0].UploadID == priorMultipartID {
		t.Fatalf("revived expired upload = %#v, %v", revived, err)
	}
	staleInput := uploadInput
	staleInput.IdempotencyKey = "media-request-stale-verify-0004"
	staleInput.ClientMediaID = "10000000-0000-4000-8000-000000000006"
	staleFingerprint := sha256.Sum256([]byte("stale-verification-fingerprint"))
	staleInput.Dedupe = staleFingerprint[:]
	staleInput.Resources = []upload.ResourceInput{{
		ID: "20000000-0000-4000-8000-000000000006", Type: "ORIGINAL",
		CiphertextSize: int64(len(ciphertext)), SHA256: ciphertextDigest[:],
		Parts: []upload.PartInput{{Number: 1, Size: int64(len(ciphertext)), SHA256: ciphertextDigest[:]}},
	}}
	stale, err := uploads.Create(ctx, uploadActor, staleInput)
	if err != nil || stale.Grant == nil {
		t.Fatalf("stale verification upload = %#v, %v", stale, err)
	}
	staleETag, err := mediaObjects.PutPart(stale.Grant.Resources[0].UploadID, 1, ciphertext)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := uploads.ReportPart(ctx, uploadActor, stale.ID, upload.PartReportInput{
		IdempotencyKey: "media-part-stale-verify-0004", ResourceID: staleInput.Resources[0].ID,
		Number: 1, Size: int64(len(ciphertext)), SHA256: ciphertextDigest[:], ETag: staleETag,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx,
		"UPDATE mineg.upload_sessions SET state='VERIFYING', updated_at=$1 WHERE id=$2",
		uploadNow.Add(-3*time.Minute), stale.ID,
	); err != nil {
		t.Fatal(err)
	}
	staleCompleted, err := uploads.Complete(ctx, uploadActor, stale.ID, upload.CompleteInput{
		IdempotencyKey: "media-complete-stale-verify-0004", ManifestDigest: manifestDigest[:], RequestID: "integration-stale-verify",
	})
	if err != nil || staleCompleted.Outcome != "COMPLETED" {
		t.Fatalf("stale verification lease takeover = %#v, %v", staleCompleted, err)
	}

	secondInput := signUp
	secondInput.Phone = "13900139000"
	secondInput.DeviceInstallationID = "android-installation-0002"
	secondInput.IdempotencyKey = "signup-request-member-0002"
	secondInput.RequestID = "integration-signup-002"
	second, err := service.SignUp(ctx, secondInput)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Approve(ctx, adminSession, second.UserID, "approve-request-member-0002", "integration-approve-002"); err != nil {
		t.Fatal(err)
	}
	secondApproved, err := service.SignIn(ctx, account.SignInInput{
		Phone: secondInput.Phone, Password: secondInput.Password, DeviceInstallationID: secondInput.DeviceInstallationID,
		Platform: "ANDROID", AgreementAccepted: true, TermsVersion: "1.0", PrivacyVersion: "1.0",
	})
	if err != nil || secondApproved.NextStep != "APP_HOME" {
		t.Fatalf("second member approved session = %#v, %v", secondApproved, err)
	}
}

func bytesOf(value byte, size int) []byte {
	result := make([]byte, size)
	for index := range result {
		result[index] = value
	}
	return result
}

func resetAndMigrate(t *testing.T, ctx context.Context, databaseURL string) {
	t.Helper()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if _, err := pool.Exec(ctx, `DROP SCHEMA IF EXISTS mineg CASCADE; DROP TABLE IF EXISTS goose_db_version`); err != nil {
		t.Fatal(err)
	}
	_, source, _, _ := runtime.Caller(0)
	migrationDir := filepath.Join(filepath.Dir(source), "..", "..", "migrations")
	db, err := goose.OpenDBWithDriver("pgx", databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := goose.UpContext(ctx, db, migrationDir); err != nil {
		t.Fatal(err)
	}
}

func errorCode(err error) string {
	var serviceError *account.Error
	if errors.As(err, &serviceError) {
		return serviceError.Code
	}
	return ""
}

func uploadErrorCode(err error) string {
	var serviceError *upload.Error
	if errors.As(err, &serviceError) {
		return serviceError.Code
	}
	return ""
}
