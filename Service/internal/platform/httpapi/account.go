package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/vampeng/mineg/service/internal/account"
)

const maxJSONBody = 1024 * 1024

func mountAccountRoutes(api chi.Router, service *account.Service, adminOrigin string) {
	api.Route("/auth", func(auth chi.Router) {
		auth.Post("/register", handleSignUp(service))
		auth.Post("/login", handleSignIn(service))
		auth.Post("/refresh", handleRefresh(service))
		auth.Post("/logout", handleSignOut(service))
		auth.Get("/approval-status", handleApprovalStatus(service))
	})
	api.Get("/me", handleProfile(service))
	api.Patch("/me/profile", handleUpdateProfile(service))
	api.Get("/me/key-bundle", handleGetKeyBundle(service))
	api.Put("/me/key-bundle", handleUpdateKeyBundle(service))
	api.Post("/me/avatar/uploads", handleCreateAvatarUpload(service))
	api.Post("/me/avatar/uploads/{uploadID}/complete", handleCompleteAvatarUpload(service))
	api.Get("/me/avatar", handleGetAvatar(service))
	api.Get("/key-grants/pending", handlePendingKeyGrants(service))
	api.Post("/key-grants/{grantID}/complete", handleCompleteKeyGrant(service))

	api.Route("/admin", func(admin chi.Router) {
		admin.Use(adminCORSMiddleware(adminOrigin))
		admin.Post("/login", handleAdminLogin(service, adminOrigin))
		admin.Get("/session", handleAdminSession(service))
		admin.Post("/logout", handleAdminLogout(service, adminOrigin))
		admin.Get("/approvals", handleApprovals(service))
		admin.Get("/approvals/{approvalID}", handleApprovalDetail(service))
		admin.Post("/approvals/{approvalID}/approve", handleApprove(service, adminOrigin))
	})
}

type signUpRequest struct {
	Phone                string          `json:"phone"`
	Password             string          `json:"password"`
	PublicKey            string          `json:"public_key"`
	EncryptedKeyBundle   string          `json:"encrypted_key_bundle"`
	KDFParameters        json.RawMessage `json:"kdf_parameters"`
	BundleVersion        int32           `json:"bundle_version"`
	DeviceInstallationID string          `json:"device_installation_id"`
	Platform             string          `json:"platform"`
}

func handleSignUp(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var request signUpRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		var publicKey, bundle []byte
		var publicErr, bundleErr error
		if request.PublicKey != "" {
			publicKey, publicErr = base64.RawStdEncoding.DecodeString(request.PublicKey)
		}
		if request.EncryptedKeyBundle != "" {
			bundle, bundleErr = base64.RawStdEncoding.DecodeString(request.EncryptedKeyBundle)
		}
		if publicErr != nil || bundleErr != nil {
			writeAccountError(w, r, &account.Error{Code: "KEY_BUNDLE_INVALID", Status: 422, Title: "Invalid key bundle", Detail: "Key data must use unpadded base64."})
			return
		}
		result, err := service.SignUp(r.Context(), account.SignUpInput{
			Phone: request.Phone, Password: request.Password, PublicKey: publicKey,
			EncryptedKeyBundle: bundle, KDFParameters: request.KDFParameters,
			BundleVersion: request.BundleVersion, DeviceInstallationID: request.DeviceInstallationID,
			Platform:       request.Platform,
			IdempotencyKey: r.Header.Get("Idempotency-Key"), RequestID: RequestIDFromContext(r.Context()),
		})
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusCreated, result)
	}
}

type signInRequest struct {
	Phone                string `json:"phone"`
	Password             string `json:"password"`
	DeviceInstallationID string `json:"device_installation_id"`
	Platform             string `json:"platform"`
	AgreementAccepted    bool   `json:"agreement_accepted"`
	TermsVersion         string `json:"terms_version"`
	PrivacyVersion       string `json:"privacy_version"`
}

func handleSignIn(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var request signInRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := service.SignIn(r.Context(), account.SignInInput{
			Phone: request.Phone, Password: request.Password,
			DeviceInstallationID: request.DeviceInstallationID, Platform: request.Platform,
			AgreementAccepted: request.AgreementAccepted, TermsVersion: request.TermsVersion,
			PrivacyVersion: request.PrivacyVersion, RequestID: RequestIDFromContext(r.Context()),
		})
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

func handleRefresh(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var request refreshRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := service.Refresh(r.Context(), request.RefreshToken, RequestIDFromContext(r.Context()))
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleSignOut(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var request refreshRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		if err := service.SignOut(r.Context(), request.RefreshToken, RequestIDFromContext(r.Context())); err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "signed_out"})
	}
}

func handleApprovalStatus(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, err := service.AuthenticateUser(r.Context(), r.Header.Get("Authorization"))
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, service.GetApprovalStatus(r.Context(), session))
	}
}

func handleProfile(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, err := service.AuthenticateUser(r.Context(), r.Header.Get("Authorization"))
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		profile, err := service.GetProfile(r.Context(), session)
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, profile)
	}
}

type adminLoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func handleAdminLogin(service *account.Service, origin string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !requireOrigin(w, r, origin) {
			return
		}
		var request adminLoginRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := service.AdminLogin(r.Context(), request.Username, request.Password, RequestIDFromContext(r.Context()))
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		setAdminCookie(w, result.SessionToken)
		writeJSON(w, http.StatusOK, map[string]string{"csrf_token": result.CSRFToken, "username": result.Username})
	}
}

func handleAdminSession(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := requireAdmin(w, r, service)
		if !ok {
			return
		}
		csrfToken, err := service.RotateAdminCSRF(r.Context(), session)
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"csrf_token": csrfToken, "username": session.Username})
	}
}

func handleAdminLogout(service *account.Service, origin string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !requireOrigin(w, r, origin) {
			return
		}
		session, ok := requireAdmin(w, r, service)
		if !ok {
			return
		}
		if err := service.CheckCSRF(session, r.Header.Get("X-CSRF-Token")); err != nil {
			writeAccountError(w, r, err)
			return
		}
		if err := service.AdminLogout(r.Context(), session, RequestIDFromContext(r.Context())); err != nil {
			writeAccountError(w, r, err)
			return
		}
		clearAdminCookie(w)
		writeJSON(w, http.StatusOK, map[string]string{"status": "signed_out"})
	}
}

func handleApprovals(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := requireAdmin(w, r, service); !ok {
			return
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		page, err := service.ListApprovals(r.Context(), r.URL.Query().Get("cursor"), limit)
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, page)
	}
}

func handleApprovalDetail(service *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := requireAdmin(w, r, service); !ok {
			return
		}
		approval, err := service.GetApproval(r.Context(), chi.URLParam(r, "approvalID"))
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, approval)
	}
}

func handleApprove(service *account.Service, origin string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !requireOrigin(w, r, origin) {
			return
		}
		session, ok := requireAdmin(w, r, service)
		if !ok {
			return
		}
		if err := service.CheckCSRF(session, r.Header.Get("X-CSRF-Token")); err != nil {
			writeAccountError(w, r, err)
			return
		}
		result, err := service.Approve(
			r.Context(), session, chi.URLParam(r, "approvalID"), r.Header.Get("Idempotency-Key"),
			RequestIDFromContext(r.Context()),
		)
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		csrfToken, err := service.RotateAdminCSRF(r.Context(), session)
		if err != nil {
			writeAccountError(w, r, err)
			return
		}
		w.Header().Set("X-CSRF-Token", csrfToken)
		writeJSON(w, http.StatusOK, result)
	}
}

func requireAdmin(w http.ResponseWriter, r *http.Request, service *account.Service) (account.AdminSession, bool) {
	cookie, err := r.Cookie(account.AdminCookieName)
	if err != nil {
		writeAccountError(w, r, &account.Error{Code: "AUTH_REQUIRED", Status: 401, Title: "Authentication required", Detail: "An administrator session is required."})
		return account.AdminSession{}, false
	}
	session, err := service.AuthenticateAdmin(r.Context(), cookie.Value)
	if err != nil {
		clearAdminCookie(w)
		writeAccountError(w, r, err)
		return account.AdminSession{}, false
	}
	return session, true
}

func decodeJSON(w http.ResponseWriter, r *http.Request, destination any) bool {
	if contentType := r.Header.Get("Content-Type"); !strings.HasPrefix(contentType, "application/json") {
		writeProblem(w, r, http.StatusUnsupportedMediaType, "CONTENT_TYPE_INVALID", "Invalid content type", "Content-Type must be application/json.", false)
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxJSONBody)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		writeProblem(w, r, http.StatusBadRequest, "REQUEST_INVALID", "Invalid request", "The JSON request body is invalid.", false)
		return false
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeProblem(w, r, http.StatusBadRequest, "REQUEST_INVALID", "Invalid request", "The request must contain exactly one JSON value.", false)
		return false
	}
	return true
}

func writeAccountError(w http.ResponseWriter, r *http.Request, err error) {
	var serviceError *account.Error
	if !errors.As(err, &serviceError) {
		serviceError = &account.Error{Code: "INTERNAL_ERROR", Status: 500, Title: "Internal server error", Detail: "The request could not be completed.", Retryable: true}
	}
	writeProblem(w, r, serviceError.Status, serviceError.Code, serviceError.Title, serviceError.Detail, serviceError.Retryable)
}

func requireOrigin(w http.ResponseWriter, r *http.Request, allowedOrigin string) bool {
	if allowedOrigin == "" || r.Header.Get("Origin") != allowedOrigin {
		writeProblem(w, r, http.StatusForbidden, "ORIGIN_INVALID", "Origin validation failed", "The request origin is not allowed.", false)
		return false
	}
	return true
}

func adminCORSMiddleware(allowedOrigin string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("Origin") == allowedOrigin && allowedOrigin != "" {
				w.Header().Set("Access-Control-Allow-Origin", allowedOrigin)
				w.Header().Set("Access-Control-Allow-Credentials", "true")
				w.Header().Set("Vary", "Origin")
			}
			if r.Method == http.MethodOptions {
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key, X-CSRF-Token, X-Request-ID")
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func setAdminCookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{
		Name: account.AdminCookieName, Value: token, Path: "/api/v1/admin", MaxAge: 8 * 60 * 60,
		Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode,
	})
}

func clearAdminCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name: account.AdminCookieName, Value: "", Path: "/api/v1/admin", MaxAge: -1,
		Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode,
	})
}
