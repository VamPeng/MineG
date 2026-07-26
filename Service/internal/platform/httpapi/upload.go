package httpapi

import (
	"encoding/base64"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/upload"
)

func mountUploadRoutes(api chi.Router, accounts *account.Service, uploads *upload.Service) {
	api.Post("/uploads", handleCreateUpload(accounts, uploads))
	api.Get("/uploads/{uploadID}", handleGetUpload(accounts, uploads))
	api.Post("/uploads/{uploadID}/parts", handleReportUploadPart(accounts, uploads))
	api.Post("/uploads/{uploadID}/complete", handleCompleteUpload(accounts, uploads))
	api.Get("/media", handleListMedia(accounts, uploads))
}

type createUploadPartRequest struct {
	PartNumber       int32  `json:"part_number"`
	CiphertextSize   int64  `json:"ciphertext_size"`
	CiphertextSHA256 string `json:"ciphertext_sha256"`
}

type createUploadResourceRequest struct {
	ResourceID       string                    `json:"resource_id"`
	ResourceType     string                    `json:"resource_type"`
	CiphertextSize   int64                     `json:"ciphertext_size"`
	CiphertextSHA256 string                    `json:"ciphertext_sha256"`
	Parts            []createUploadPartRequest `json:"parts"`
}

type createUploadRequest struct {
	ClientMediaID     string                        `json:"client_media_id"`
	DedupeFingerprint string                        `json:"dedupe_fingerprint"`
	ContentRevision   int32                         `json:"content_revision"`
	MediaType         string                        `json:"media_type"`
	CapturedAt        string                        `json:"captured_at"`
	ManifestDigest    string                        `json:"manifest_digest"`
	EncryptedManifest string                        `json:"encrypted_manifest"`
	EncryptedMediaKey string                        `json:"encrypted_media_key"`
	Resources         []createUploadResourceRequest `json:"resources"`
}

func handleCreateUpload(accounts *account.Service, uploads *upload.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requireUploadActor(w, r, accounts)
		if !ok {
			return
		}
		var request createUploadRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		capturedAt, timeErr := time.Parse(time.RFC3339Nano, request.CapturedAt)
		dedupe, dedupeErr := decodeRawBase64(request.DedupeFingerprint)
		manifestDigest, manifestErr := decodeRawBase64(request.ManifestDigest)
		encryptedManifest, encryptedManifestErr := decodeRawBase64(request.EncryptedManifest)
		encryptedMediaKey, encryptedKeyErr := decodeRawBase64(request.EncryptedMediaKey)
		if timeErr != nil || dedupeErr != nil || manifestErr != nil || encryptedManifestErr != nil || encryptedKeyErr != nil {
			writeUploadError(w, r, &upload.Error{Code: "UPLOAD_INVALID", Status: 422, Title: "Invalid media upload", Detail: "Time and binary fields must use RFC 3339 and unpadded standard base64."})
			return
		}
		resources := make([]upload.ResourceInput, 0, len(request.Resources))
		for _, resource := range request.Resources {
			digest, err := decodeRawBase64(resource.CiphertextSHA256)
			if err != nil {
				writeUploadError(w, r, &upload.Error{Code: "UPLOAD_RESOURCE_INVALID", Status: 422, Title: "Invalid media resource", Detail: "Resource digests must use unpadded standard base64."})
				return
			}
			parts := make([]upload.PartInput, 0, len(resource.Parts))
			for _, part := range resource.Parts {
				partDigest, err := decodeRawBase64(part.CiphertextSHA256)
				if err != nil {
					writeUploadError(w, r, &upload.Error{Code: "UPLOAD_PART_INVALID", Status: 422, Title: "Invalid upload part", Detail: "Part digests must use unpadded standard base64."})
					return
				}
				parts = append(parts, upload.PartInput{Number: part.PartNumber, Size: part.CiphertextSize, SHA256: partDigest})
			}
			resources = append(resources, upload.ResourceInput{ID: resource.ResourceID, Type: resource.ResourceType, CiphertextSize: resource.CiphertextSize, SHA256: digest, Parts: parts})
		}
		result, err := uploads.Create(r.Context(), actor, upload.CreateInput{
			IdempotencyKey: r.Header.Get("Idempotency-Key"), ClientMediaID: request.ClientMediaID,
			Dedupe: dedupe, ContentRevision: request.ContentRevision, MediaType: request.MediaType,
			CapturedAt: capturedAt, ManifestDigest: manifestDigest, EncryptedManifest: encryptedManifest,
			EncryptedMediaKey: encryptedMediaKey, Resources: resources, RequestID: RequestIDFromContext(r.Context()),
		})
		if err != nil {
			writeUploadError(w, r, err)
			return
		}
		writeJSON(w, http.StatusCreated, result)
	}
}

func handleGetUpload(accounts *account.Service, uploads *upload.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requireUploadActor(w, r, accounts)
		if !ok {
			return
		}
		result, err := uploads.Get(r.Context(), actor, chi.URLParam(r, "uploadID"))
		if err != nil {
			writeUploadError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

type reportUploadPartRequest struct {
	ResourceID       string `json:"resource_id"`
	PartNumber       int32  `json:"part_number"`
	CiphertextSize   int64  `json:"ciphertext_size"`
	CiphertextSHA256 string `json:"ciphertext_sha256"`
	ETag             string `json:"etag"`
}

func handleReportUploadPart(accounts *account.Service, uploads *upload.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requireUploadActor(w, r, accounts)
		if !ok {
			return
		}
		var request reportUploadPartRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		digest, err := decodeRawBase64(request.CiphertextSHA256)
		if err != nil {
			writeUploadError(w, r, &upload.Error{Code: "UPLOAD_PART_INVALID", Status: 422, Title: "Invalid upload part", Detail: "Part digests must use unpadded standard base64."})
			return
		}
		result, err := uploads.ReportPart(r.Context(), actor, chi.URLParam(r, "uploadID"), upload.PartReportInput{
			IdempotencyKey: r.Header.Get("Idempotency-Key"), ResourceID: request.ResourceID,
			Number: request.PartNumber, Size: request.CiphertextSize, SHA256: digest, ETag: request.ETag,
		})
		if err != nil {
			writeUploadError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

type completeUploadRequest struct {
	ManifestDigest string `json:"manifest_digest"`
}

func handleCompleteUpload(accounts *account.Service, uploads *upload.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requireUploadActor(w, r, accounts)
		if !ok {
			return
		}
		var request completeUploadRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		digest, err := decodeRawBase64(request.ManifestDigest)
		if err != nil {
			writeUploadError(w, r, &upload.Error{Code: "UPLOAD_COMPLETE_INVALID", Status: 422, Title: "Invalid upload completion", Detail: "The manifest digest must use unpadded standard base64."})
			return
		}
		result, err := uploads.Complete(r.Context(), actor, chi.URLParam(r, "uploadID"), upload.CompleteInput{
			IdempotencyKey: r.Header.Get("Idempotency-Key"), ManifestDigest: digest, RequestID: RequestIDFromContext(r.Context()),
		})
		if err != nil {
			writeUploadError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleListMedia(accounts *account.Service, uploads *upload.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requireUploadActor(w, r, accounts)
		if !ok {
			return
		}
		limit64, _ := strconv.ParseInt(r.URL.Query().Get("limit"), 10, 32)
		result, err := uploads.ListMedia(r.Context(), actor, int32(limit64))
		if err != nil {
			writeUploadError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func requireUploadActor(w http.ResponseWriter, r *http.Request, accounts *account.Service) (upload.Actor, bool) {
	session, ok := requireUser(w, r, accounts)
	if !ok {
		return upload.Actor{}, false
	}
	return upload.Actor{UserID: session.UserID, RawUserID: session.RawUserID, Status: session.Status}, true
}

func decodeRawBase64(value string) ([]byte, error) { return base64.RawStdEncoding.DecodeString(value) }

func writeUploadError(w http.ResponseWriter, r *http.Request, err error) {
	var value *upload.Error
	if !errors.As(err, &value) {
		value = &upload.Error{Code: "INTERNAL_ERROR", Status: 500, Title: "Internal error", Detail: "The upload request could not be completed.", Retryable: true}
	}
	writeProblem(w, r, value.Status, value.Code, value.Title, value.Detail, value.Retryable)
}
