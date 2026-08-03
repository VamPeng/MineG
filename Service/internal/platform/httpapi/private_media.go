package httpapi

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/media"
)

func mountPrivateMediaRoutes(api chi.Router, accounts *account.Service, privateMedia *media.Service) {
	api.Get("/private/media", handleListPrivateMedia(accounts, privateMedia))
	api.Get("/private/media/{mediaID}", handleGetPrivateMediaDetail(accounts, privateMedia))
	api.Post("/private/media/{mediaID}/access", handleCreatePrivateMediaAccess(accounts, privateMedia))
	api.Post("/private/media/{mediaID}/trash", handleTrashPrivateMedia(accounts, privateMedia))
}

func handleListPrivateMedia(accounts *account.Service, privateMedia *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		limit64, _ := strconv.ParseInt(r.URL.Query().Get("limit"), 10, 32)
		result, err := privateMedia.List(r.Context(), actor, r.URL.Query().Get("cursor"), int32(limit64))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleGetPrivateMediaDetail(accounts *account.Service, privateMedia *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		result, err := privateMedia.Detail(r.Context(), actor, chi.URLParam(r, "mediaID"))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

type privateMediaAccessRequest struct {
	Purpose string `json:"purpose"`
	Variant string `json:"variant"`
}

func handleCreatePrivateMediaAccess(accounts *account.Service, privateMedia *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		var request privateMediaAccessRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := privateMedia.Access(r.Context(), actor, chi.URLParam(r, "mediaID"), media.AccessInput{
			Purpose: request.Purpose, Variant: request.Variant,
		})
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleTrashPrivateMedia(accounts *account.Service, privateMedia *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		var request struct{}
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := privateMedia.Trash(r.Context(), actor, chi.URLParam(r, "mediaID"), r.Header.Get("Idempotency-Key"), RequestIDFromContext(r.Context()))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func requirePrivateMediaActor(w http.ResponseWriter, r *http.Request, accounts *account.Service) (media.Actor, bool) {
	session, ok := requireUser(w, r, accounts)
	if !ok {
		return media.Actor{}, false
	}
	return media.Actor{UserID: session.UserID, RawUserID: session.RawUserID, Status: session.Status}, true
}

func writePrivateMediaError(w http.ResponseWriter, r *http.Request, err error) {
	value := &media.Error{Code: "INTERNAL_ERROR", Status: http.StatusInternalServerError, Title: "Internal error", Detail: "The private media request could not be completed.", Retryable: true}
	if errors.As(err, &value) {
		writeProblem(w, r, value.Status, value.Code, value.Title, value.Detail, value.Retryable)
		return
	}
	writeProblem(w, r, value.Status, value.Code, value.Title, value.Detail, value.Retryable)
}
