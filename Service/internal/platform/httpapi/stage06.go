package httpapi

import (
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/vampeng/mineg/service/internal/account"
	"github.com/vampeng/mineg/service/internal/media"
)

func mountStage06Routes(api chi.Router, accounts *account.Service, service *media.Service) {
	api.Post("/private/media/{mediaID}/share", handleSetPrivateMediaShare(accounts, service))
	api.Get("/family/media", handleListFamilyMedia(accounts, service))
	api.Get("/family/media/{mediaID}", handleGetFamilyMedia(accounts, service))
	api.Post("/family/media/{mediaID}/access", handleCreateFamilyMediaAccess(accounts, service))
	api.Get("/trash", handleListTrash(accounts, service))
	api.Post("/trash/{mediaID}/restore", handleRestoreTrash(accounts, service))
	api.Get("/help/faq", handleFAQ(accounts))
	api.Post("/feedback", handleSubmitFeedback(accounts, service))
}

func handleSetPrivateMediaShare(accounts *account.Service, service *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		var request struct {
			Shared bool `json:"shared"`
		}
		if !decodeJSON(w, r, &request) {
			return
		}
		state := "INACTIVE"
		if request.Shared {
			state = "ACTIVE"
		}
		result, err := service.Share(r.Context(), actor, chi.URLParam(r, "mediaID"), state, r.Header.Get("Idempotency-Key"), RequestIDFromContext(r.Context()))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleListFamilyMedia(accounts *account.Service, service *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		limit64, _ := strconv.ParseInt(r.URL.Query().Get("limit"), 10, 32)
		result, err := service.ListFamily(r.Context(), actor, r.URL.Query().Get("filter"), r.URL.Query().Get("cursor"), int32(limit64))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleGetFamilyMedia(accounts *account.Service, service *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		result, err := service.FamilyDetail(r.Context(), actor, chi.URLParam(r, "mediaID"))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleCreateFamilyMediaAccess(accounts *account.Service, service *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		var request privateMediaAccessRequest
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := service.FamilyAccess(r.Context(), actor, chi.URLParam(r, "mediaID"), media.AccessInput{Purpose: request.Purpose, Variant: request.Variant})
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleListTrash(accounts *account.Service, service *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		limit64, _ := strconv.ParseInt(r.URL.Query().Get("limit"), 10, 32)
		result, err := service.ListTrash(r.Context(), actor, r.URL.Query().Get("cursor"), int32(limit64))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleRestoreTrash(accounts *account.Service, service *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		var request struct{}
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := service.Restore(r.Context(), actor, chi.URLParam(r, "mediaID"), r.Header.Get("Idempotency-Key"), RequestIDFromContext(r.Context()))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func handleFAQ(accounts *account.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := requireUser(w, r, accounts); !ok {
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"version": "stage06-v1",
			"items": []map[string]string{
				{"id": "album-permission", "question": "为什么需要完整相册权限？", "answer": "完整权限用于建立本地索引；只有开启自动备份后才会上传媒体。"},
				{"id": "automatic-backup", "question": "自动备份何时开始？", "answer": "自动备份默认关闭，开启后按当前网络设置处理尚未完成的媒体。"},
				{"id": "cellular-backup", "question": "会使用移动网络吗？", "answer": "移动网络备份默认关闭，可在备份设置中单独开启。"},
				{"id": "transport-security", "question": "媒体如何传输？", "answer": "媒体经 HTTPS/TLS 和私有 OSS 短期授权传输，并校验长度与 SHA-256。"},
				{"id": "family-sharing", "question": "家庭共享会复制原文件吗？", "answer": "不会。共享只改变家庭可见性，家庭相册不提供保存或导出。"},
				{"id": "trash", "question": "移入回收站会删除手机照片吗？", "answer": "不会。媒体从 MineG 私人空间和共享相册隐藏，人工清理前可以恢复。"},
				{"id": "sign-out", "question": "退出登录会怎样？", "answer": "当前设备会话会撤销，账号数据和临时媒体句柄会从 App 清理。"},
			},
		})
	}
}

func handleSubmitFeedback(accounts *account.Service, service *media.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := requirePrivateMediaActor(w, r, accounts)
		if !ok {
			return
		}
		var request media.FeedbackInput
		if !decodeJSON(w, r, &request) {
			return
		}
		result, err := service.SubmitFeedback(r.Context(), actor, request, r.Header.Get("Idempotency-Key"), RequestIDFromContext(r.Context()))
		if err != nil {
			writePrivateMediaError(w, r, err)
			return
		}
		writeJSON(w, http.StatusCreated, result)
	}
}
