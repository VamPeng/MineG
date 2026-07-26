package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/upload"
)

func TestAdminCookieSecurityAttributes(t *testing.T) {
	recorder := httptest.NewRecorder()
	setAdminCookie(recorder, "opaque-session-token")
	cookie := recorder.Header().Get("Set-Cookie")
	for _, attribute := range []string{"mineg_admin_session=", "Path=/api/v1/admin", "HttpOnly", "Secure", "SameSite=Strict"} {
		if !strings.Contains(cookie, attribute) {
			t.Fatalf("cookie %q is missing %q", cookie, attribute)
		}
	}
}

func TestAdminCookieCannotAuthenticateMediaRoutes(t *testing.T) {
	accounts := account.New(nil, account.Config{})
	handler := New(Dependencies{Account: accounts, Upload: upload.New(nil, upload.Config{})})
	for _, path := range []string{"/api/v1/media", "/api/v1/uploads/not-a-session"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		request.AddCookie(&http.Cookie{Name: account.AdminCookieName, Value: "admin-session"})
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusUnauthorized {
			t.Fatalf("admin cookie reached %s with status %d", path, recorder.Code)
		}
		if strings.Contains(recorder.Body.String(), "object_key") || strings.Contains(recorder.Body.String(), "grant") {
			t.Fatalf("media response leaked to admin cookie: %s", recorder.Body.String())
		}
	}
}

func TestOriginMustMatchExactly(t *testing.T) {
	for _, origin := range []string{"", "https://admin.example.test.evil", "http://admin.example.test"} {
		request := httptest.NewRequest(http.MethodPost, "/api/v1/admin/logout", nil)
		request.Header.Set("Origin", origin)
		recorder := httptest.NewRecorder()
		if requireOrigin(recorder, request, "https://admin.example.test") {
			t.Fatalf("origin %q unexpectedly passed", origin)
		}
		if recorder.Code != http.StatusForbidden {
			t.Fatalf("origin %q status = %d", origin, recorder.Code)
		}
	}
}
