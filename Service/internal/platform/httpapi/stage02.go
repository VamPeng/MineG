package httpapi

import (
	"encoding/base64"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/vampeng/mineg/service/internal/account"
)

func requireUser(w http.ResponseWriter, r *http.Request, service *account.Service) (account.UserSession, bool) {
	session, err := service.AuthenticateUser(r.Context(), r.Header.Get("Authorization"))
	if err != nil {
		writeAccountError(w, r, err)
		return account.UserSession{}, false
	}
	return session, true
}

type updateProfileRequest struct {
	Nickname string `json:"nickname"`
}

func handleUpdateProfile(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := requireUser(w, r, service)
		if !ok {
			return
		}
		var request updateProfileRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		profile, err := service.UpdateProfile(r.Context(), session, request.Nickname, RequestIDFromContext(r.Context()))
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, profile)
	}
}

type createAvatarUploadRequest struct {
	ContentType   string `json:"content_type"`
	SourceSize    int64  `json:"source_size"`
	DisplaySize   int64  `json:"display_size"`
	Width         int32  `json:"width"`
	Height        int32  `json:"height"`
	ContentSHA256 string `json:"content_sha256"`
}

func handleCreateAvatarUpload(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := requireUser(w, r, service)
		if !ok {
			return
		}
		var request createAvatarUploadRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		digest, err := base64.RawStdEncoding.DecodeString(request.ContentSHA256)
		if err != nil {
			writeAccountError(w, r, &account.Error{Code: "AVATAR_INVALID", Status: 422, Title: "Invalid avatar", Detail: "The avatar digest must use unpadded base64."})
			return
		}
		result, err := service.CreateAvatarUpload(r.Context(), session, account.CreateAvatarUploadInput{
			IdempotencyKey: r.Header.Get("Idempotency-Key"), ContentType: request.ContentType,
			SourceSize: request.SourceSize, DisplaySize: request.DisplaySize, Width: request.Width,
			Height: request.Height, ContentSHA256: digest,
		})
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusCreated, result)
	}
}

func handleCompleteAvatarUpload(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := requireUser(w, r, service)
		if !ok {
			return
		}
		var request struct{}
		if !decodeJSON(w, r, &request) {
			return
		}
		profile, err := service.CompleteAvatarUpload(r.Context(), session, chi.URLParam(r, "uploadID"), RequestIDFromContext(r.Context()))
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, profile)
	}
}

func handleGetAvatar(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := requireUser(w, r, service)
		if !ok {
			return
		}
		grant, err := service.GetAvatarGrant(r.Context(), session)
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, grant)
	}
}
