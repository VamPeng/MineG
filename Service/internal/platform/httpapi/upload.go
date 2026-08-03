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
	PartNumber    int32  `json:"part_number"`
	ContentSize   int64  `json:"content_size"`
	ContentSHA256 string `json:"content_sha256"`
}

type createUploadResourceRequest struct {
	ResourceID    string                    `json:"resource_id"`
	ResourceType  string                    `json:"resource_type"`
	ContentSize   int64                     `json:"content_size"`
	ContentSHA256 string                    `json:"content_sha256"`
	MimeType      string                    `json:"mime_type"`
	Parts         []createUploadPartRequest `json:"parts"`
}

type createUploadClientAlbumRequest struct {
	ClientAlbumID string `json:"client_album_id"`
	Name          string `json:"name"`
}

type createUploadRequest struct {
	ProtocolVersion      string                           `json:"protocol_version"`
	ClientMediaID        string                           `json:"client_media_id"`
	ContentRevision      int32                            `json:"content_revision"`
	MediaType            string                           `json:"media_type"`
	CapturedAt           string                           `json:"captured_at"`
	ContentSHA256        string                           `json:"content_sha256"`
	MimeType             string                           `json:"mime_type"`
	Width                *int32                           `json:"width"`
	Height               *int32                           `json:"height"`
	DurationMS           *int64                           `json:"duration_ms"`
	Resources            []createUploadResourceRequest    `json:"resources"`
	DeviceInstallationID string                           `json:"device_installation_id"`
	ClientAlbums         []createUploadClientAlbumRequest `json:"client_albums"`
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
		contentDigest, digestErr := decodeRawBase64(request.ContentSHA256)
		if timeErr != nil || digestErr != nil {
			writeUploadError(w, r, &upload.Error{Code: "UPLOAD_INVALID", Status: 422, Title: "Invalid media upload", Detail: "Time and content digest must use RFC 3339 and unpadded standard base64."})
			return
		}
		resources := make([]upload.ResourceInput, 0, len(request.Resources))
		for _, resource := range request.Resources {
			digest, err := decodeRawBase64(resource.ContentSHA256)
			if err != nil {
				writeUploadError(w, r, &upload.Error{Code: "UPLOAD_RESOURCE_INVALID", Status: 422, Title: "Invalid media resource", Detail: "Resource digests must use unpadded standard base64."})
				return
			}
			parts := make([]upload.PartInput, 0, len(resource.Parts))
			for _, part := range resource.Parts {
				partDigest, err := decodeRawBase64(part.ContentSHA256)
				if err != nil {
					writeUploadError(w, r, &upload.Error{Code: "UPLOAD_PART_INVALID", Status: 422, Title: "Invalid upload part", Detail: "Part digests must use unpadded standard base64."})
					return
				}
				parts = append(parts, upload.PartInput{Number: part.PartNumber, Size: part.ContentSize, SHA256: partDigest})
			}
			resources = append(resources, upload.ResourceInput{ID: resource.ResourceID, Type: resource.ResourceType, ContentSize: resource.ContentSize, SHA256: digest, MimeType: resource.MimeType, Parts: parts})
		}
		albums := make([]upload.ClientAlbumInput, 0, len(request.ClientAlbums))
		for _, album := range request.ClientAlbums {
			albums = append(albums, upload.ClientAlbumInput{ID: album.ClientAlbumID, Name: album.Name})
		}
		result, err := uploads.Create(r.Context(), actor, upload.CreateInput{
			ProtocolVersion: request.ProtocolVersion, IdempotencyKey: r.Header.Get("Idempotency-Key"),
			ClientMediaID: request.ClientMediaID, Dedupe: contentDigest, ContentRevision: request.ContentRevision,
			MediaType: request.MediaType, CapturedAt: capturedAt, ContentSHA256: contentDigest,
			MimeType: request.MimeType, Width: request.Width, Height: request.Height, DurationMS: request.DurationMS, Resources: resources, DeviceInstallationID: request.DeviceInstallationID,
			ClientAlbums: albums, RequestID: RequestIDFromContext(r.Context()),
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
	ResourceID    string `json:"resource_id"`
	PartNumber    int32  `json:"part_number"`
	ContentSize   int64  `json:"content_size"`
	ContentSHA256 string `json:"content_sha256"`
	ETag          string `json:"etag"`
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
		digest, err := decodeRawBase64(request.ContentSHA256)
		if err != nil {
			writeUploadError(w, r, &upload.Error{Code: "UPLOAD_PART_INVALID", Status: 422, Title: "Invalid upload part", Detail: "Part digests must use unpadded standard base64."})
			return
		}
		result, err := uploads.ReportPart(r.Context(), actor, chi.URLParam(r, "uploadID"), upload.PartReportInput{
			IdempotencyKey: r.Header.Get("Idempotency-Key"), ResourceID: request.ResourceID,
			Number: request.PartNumber, Size: request.ContentSize, SHA256: digest, ETag: request.ETag,
		})
		if err != nil {
			writeUploadError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleCompleteUpload(accounts *account.Service, uploads *upload.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requireUploadActor(w, r, accounts)
		if !ok {
			return
		}
		var request struct{}
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := uploads.Complete(r.Context(), actor, chi.URLParam(r, "uploadID"), upload.CompleteInput{
			IdempotencyKey: r.Header.Get("Idempotency-Key"), RequestID: RequestIDFromContext(r.Context()),
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
		result, err := uploads.ListMedia(r.Context(), actor, r.URL.Query().Get("cursor"), int32(limit64))
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
