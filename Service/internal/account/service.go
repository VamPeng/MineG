package account

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/vampeng/mineg/service/internal/platform/database"
	"github.com/vampeng/mineg/service/internal/platform/database/dbgen"
	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

const AdminCookieName = "mineg_admin_session"

var (
	phonePattern       = regexp.MustCompile(`^1[3-9][0-9]{9}$`)
	idempotencyPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,128}$`)
)

type Error struct {
	Code      string
	Status    int
	Title     string
	Detail    string
	Retryable bool
}

func (e *Error) Error() string { return e.Code }

type Config struct {
	AccessLifetime      time.Duration
	RefreshLifetime     time.Duration
	AdminIdleLifetime   time.Duration
	AdminAbsoluteExpiry time.Duration
	CursorKey           []byte
	Now                 func() time.Time
	ProfileObjects      objectstore.ProfileObjects
}

type Service struct {
	pool           *database.Pool
	config         Config
	profileObjects objectstore.ProfileObjects
}

func New(pool *database.Pool, config Config) *Service {
	if config.AccessLifetime <= 0 {
		config.AccessLifetime = 15 * time.Minute
	}
	if config.RefreshLifetime <= 0 {
		config.RefreshLifetime = 30 * 24 * time.Hour
	}
	if config.AdminIdleLifetime <= 0 {
		config.AdminIdleLifetime = 30 * time.Minute
	}
	if config.AdminAbsoluteExpiry <= 0 {
		config.AdminAbsoluteExpiry = 8 * time.Hour
	}
	if len(config.CursorKey) < 32 {
		config.CursorKey = []byte("mineg-local-cursor-key-change-before-deployment")
	}
	if config.Now == nil {
		config.Now = time.Now
	}
	if config.ProfileObjects == nil {
		config.ProfileObjects = objectstore.DisabledProfileObjects{}
	}
	return &Service{pool: pool, config: config, profileObjects: config.ProfileObjects}
}

type SignUpInput struct {
	Phone                string
	Password             string
	PublicKey            []byte
	EncryptedKeyBundle   []byte
	KDFParameters        json.RawMessage
	BundleVersion        int32
	DeviceInstallationID string
	Platform             string
	IdempotencyKey       string
	RequestID            string
}

type SignUpResult struct {
	UserID string `json:"user_id"`
	TokenResult
}

func (s *Service) SignUp(ctx context.Context, input SignUpInput) (SignUpResult, error) {
	phone, err := NormalizePhone(input.Phone)
	if err != nil {
		return SignUpResult{}, validationError("PHONE_INVALID", "Invalid phone number", err.Error())
	}
	if err := ValidatePassword(input.Password); err != nil {
		return SignUpResult{}, validationError("PASSWORD_INVALID", "Invalid password", err.Error())
	}
	legacyKeyBundle := len(input.PublicKey) > 0 || len(input.EncryptedKeyBundle) > 0 ||
		len(input.KDFParameters) > 0 || input.BundleVersion > 0
	if legacyKeyBundle && (len(input.PublicKey) != 32 || len(input.EncryptedKeyBundle) < 48 ||
		len(input.EncryptedKeyBundle) > 1024*1024 || !validJSONObject(input.KDFParameters) ||
		input.BundleVersion <= 0) {
		return SignUpResult{}, validationError("KEY_BUNDLE_INVALID", "Invalid key bundle", "Legacy key bundle fields must be complete and valid when provided.")
	}
	if len(input.DeviceInstallationID) < 8 || len(input.DeviceInstallationID) > 128 {
		return SignUpResult{}, validationError("DEVICE_INVALID", "Invalid device", "The device installation identifier is invalid.")
	}
	if !validPlatform(input.Platform) {
		return SignUpResult{}, validationError("DEVICE_INVALID", "Invalid device", "The device platform is invalid.")
	}
	if !idempotencyPattern.MatchString(input.IdempotencyKey) {
		return SignUpResult{}, validationError("IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "Idempotency-Key must contain 8 to 128 safe characters.")
	}
	passwordHash, err := HashPassword(input.Password)
	if err != nil {
		return SignUpResult{}, internalError()
	}
	requestHash := s.registrationFingerprint(phone, input)
	var result SignUpResult
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		queries := dbgen.New(tx)
		if err := queries.AcquireRegistrationLock(ctx, dbgen.AcquireRegistrationLockParams{
			DeviceInstallationID: input.DeviceInstallationID,
			IdempotencyKey:       input.IdempotencyKey,
		}); err != nil {
			return err
		}
		existing, findErr := queries.FindRegistrationRequest(ctx, dbgen.FindRegistrationRequestParams{
			DeviceInstallationID: input.DeviceInstallationID,
			IdempotencyKey:       input.IdempotencyKey,
		})
		if findErr == nil {
			if !hmac.Equal(existing.RequestHash, requestHash) {
				return conflictError("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The idempotency key was already used for another registration.")
			}
			if err := queries.RevokeUserSessionFamily(ctx, dbgen.RevokeUserSessionFamilyParams{RotationFamilyID: existing.RotationFamilyID, RevokedAt: pgTime(s.now())}); err != nil {
				return err
			}
			device, err := queries.UpsertDevice(ctx, dbgen.UpsertDeviceParams{UserID: existing.UserID, InstallationID: input.DeviceInstallationID, Platform: strings.ToUpper(input.Platform)})
			if err != nil {
				return err
			}
			familyID := newPGUUID()
			tokens, err := s.createUserSession(ctx, queries, existing.UserID, device.ID, familyID)
			if err != nil {
				return err
			}
			if err := queries.UpdateRegistrationFamily(ctx, dbgen.UpdateRegistrationFamilyParams{DeviceInstallationID: input.DeviceInstallationID, IdempotencyKey: input.IdempotencyKey, RotationFamilyID: familyID}); err != nil {
				return err
			}
			user, err := queries.FindUserByID(ctx, existing.UserID)
			if err != nil {
				return err
			}
			tokens.ApprovalStatus = user.Status
			tokens.NextStep = nextStep(user.Status)
			tokens.UserID = uuidString(existing.UserID)
			result = SignUpResult{UserID: uuidString(existing.UserID), TokenResult: tokens}
			return nil
		}
		if !errors.Is(findErr, pgx.ErrNoRows) {
			return findErr
		}
		user, createErr := queries.CreateUser(ctx, dbgen.CreateUserParams{
			PhoneE164: phone, PasswordHash: passwordHash, Nickname: phone[len(phone)-4:],
		})
		if isUniqueViolation(createErr) {
			return conflictError("PHONE_ALREADY_REGISTERED", "Phone already registered", "This phone number is already registered.")
		}
		if createErr != nil {
			return createErr
		}
		if legacyKeyBundle {
			if err := queries.CreateUserKeyBundle(ctx, dbgen.CreateUserKeyBundleParams{
				UserID: user.ID, PublicKey: input.PublicKey, EncryptedKeyBundle: input.EncryptedKeyBundle,
				KdfParameters: input.KDFParameters, BundleVersion: input.BundleVersion,
			}); err != nil {
				return err
			}
		}
		device, err := queries.UpsertDevice(ctx, dbgen.UpsertDeviceParams{UserID: user.ID, InstallationID: input.DeviceInstallationID, Platform: strings.ToUpper(input.Platform)})
		if err != nil {
			return err
		}
		familyID := newPGUUID()
		tokens, err := s.createUserSession(ctx, queries, user.ID, device.ID, familyID)
		if err != nil {
			return err
		}
		if err := queries.CreateRegistrationRequest(ctx, dbgen.CreateRegistrationRequestParams{
			DeviceInstallationID: input.DeviceInstallationID, IdempotencyKey: input.IdempotencyKey,
			RequestHash: requestHash, UserID: user.ID, RotationFamilyID: familyID,
		}); err != nil {
			return err
		}
		if err := recordAudit(ctx, queries, "ANONYMOUS", pgtype.UUID{}, "USER_REGISTER", "USER", user.ID, "SUCCESS", input.RequestID); err != nil {
			return err
		}
		tokens.ApprovalStatus = "PENDING"
		tokens.NextStep = "REVIEW_PENDING"
		tokens.UserID = uuidString(user.ID)
		result = SignUpResult{UserID: uuidString(user.ID), TokenResult: tokens}
		return nil
	})
	if err != nil {
		return SignUpResult{}, normalizeDatabaseError(err)
	}
	return result, nil
}

type SignInInput struct {
	Phone                string
	Password             string
	DeviceInstallationID string
	Platform             string
	TermsVersion         string
	PrivacyVersion       string
	AgreementAccepted    bool
	RequestID            string
}

type TokenResult struct {
	UserID           string    `json:"user_id"`
	AccessToken      string    `json:"access_token"`
	AccessExpiresAt  time.Time `json:"access_expires_at"`
	RefreshToken     string    `json:"refresh_token"`
	RefreshExpiresAt time.Time `json:"refresh_expires_at"`
	ApprovalStatus   string    `json:"approval_status"`
	NextStep         string    `json:"next_step"`
}

func (s *Service) SignIn(ctx context.Context, input SignInInput) (TokenResult, error) {
	phone, err := NormalizePhone(input.Phone)
	if err != nil {
		_ = VerifyPasswordOrDummy("", input.Password)
		return TokenResult{}, credentialError()
	}
	if !input.AgreementAccepted || input.TermsVersion == "" || input.PrivacyVersion == "" {
		return TokenResult{}, validationError("AGREEMENT_REQUIRED", "Agreement required", "The current terms and privacy policy must be accepted.")
	}
	if len(input.DeviceInstallationID) < 8 || len(input.DeviceInstallationID) > 128 || !validPlatform(input.Platform) {
		return TokenResult{}, validationError("DEVICE_INVALID", "Invalid device", "The device fields are invalid.")
	}
	queries := dbgen.New(s.pool)
	user, findErr := queries.FindUserByPhone(ctx, phone)
	passwordHash := ""
	if findErr == nil {
		passwordHash = user.PasswordHash
	} else if !errors.Is(findErr, pgx.ErrNoRows) {
		return TokenResult{}, internalError()
	}
	if !VerifyPasswordOrDummy(passwordHash, input.Password) {
		_ = recordAudit(ctx, queries, "ANONYMOUS", pgtype.UUID{}, "USER_LOGIN", "USER", pgtype.UUID{}, "FAILURE", input.RequestID)
		return TokenResult{}, credentialError()
	}
	var result TokenResult
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		device, err := q.UpsertDevice(ctx, dbgen.UpsertDeviceParams{UserID: user.ID, InstallationID: input.DeviceInstallationID, Platform: strings.ToUpper(input.Platform)})
		if err != nil {
			return err
		}
		if err := q.RecordAgreement(ctx, dbgen.RecordAgreementParams{
			UserID: user.ID, TermsVersion: input.TermsVersion, PrivacyVersion: input.PrivacyVersion,
			DeviceInstallationID: input.DeviceInstallationID,
		}); err != nil {
			return err
		}
		result, err = s.createUserSession(ctx, q, user.ID, device.ID, newPGUUID())
		if err != nil {
			return err
		}
		result.ApprovalStatus = user.Status
		result.NextStep = nextStep(user.Status)
		result.UserID = uuidString(user.ID)
		return recordAudit(ctx, q, "USER", user.ID, "USER_LOGIN", "USER_SESSION", pgtype.UUID{}, "SUCCESS", input.RequestID)
	})
	if err != nil {
		return TokenResult{}, normalizeDatabaseError(err)
	}
	return result, nil
}

func (s *Service) Refresh(ctx context.Context, refreshToken, requestID string) (TokenResult, error) {
	if refreshToken == "" {
		return TokenResult{}, sessionError("SESSION_INVALID", "Session invalid")
	}
	now := s.now()
	var result TokenResult
	var terminalError error
	err := s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		session, err := q.FindUserSessionByRefreshForUpdate(ctx, tokenHash(refreshToken))
		if errors.Is(err, pgx.ErrNoRows) {
			return sessionError("SESSION_INVALID", "Session invalid")
		}
		if err != nil {
			return err
		}
		if session.RotatedAt.Valid || session.RevokedAt.Valid {
			if err := q.RevokeUserSessionFamily(ctx, dbgen.RevokeUserSessionFamilyParams{RotationFamilyID: session.RotationFamilyID, RevokedAt: pgTime(now)}); err != nil {
				return err
			}
			_ = recordAudit(ctx, q, "USER", session.UserID, "REFRESH_TOKEN_REPLAY", "USER_SESSION", session.ID, "REPLAY", requestID)
			terminalError = sessionError("SESSION_REPLAYED", "Session replay detected")
			return nil
		}
		if !now.Before(session.RefreshExpiresAt.Time) {
			if err := q.RevokeUserSession(ctx, dbgen.RevokeUserSessionParams{ID: session.ID, RevokedAt: pgTime(now)}); err != nil {
				return err
			}
			terminalError = sessionError("SESSION_EXPIRED", "Session expired")
			return nil
		}
		if err := q.MarkUserSessionRotated(ctx, dbgen.MarkUserSessionRotatedParams{ID: session.ID, RotatedAt: pgTime(now)}); err != nil {
			return err
		}
		result, err = s.createUserSession(ctx, q, session.UserID, session.DeviceID, session.RotationFamilyID)
		if err != nil {
			return err
		}
		result.ApprovalStatus = session.Status
		result.NextStep = nextStep(session.Status)
		result.UserID = uuidString(session.UserID)
		return nil
	})
	if err != nil {
		return TokenResult{}, normalizeDatabaseError(err)
	}
	if terminalError != nil {
		return TokenResult{}, terminalError
	}
	return result, nil
}

func (s *Service) SignOut(ctx context.Context, refreshToken, requestID string) error {
	now := s.now()
	return normalizeDatabaseError(s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		session, err := q.FindUserSessionByRefreshForUpdate(ctx, tokenHash(refreshToken))
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if session.RotatedAt.Valid {
			if err := q.RevokeUserSessionFamily(ctx, dbgen.RevokeUserSessionFamilyParams{RotationFamilyID: session.RotationFamilyID, RevokedAt: pgTime(now)}); err != nil {
				return err
			}
			return recordAudit(ctx, q, "USER", session.UserID, "USER_LOGOUT", "USER_SESSION", session.ID, "REPLAY", requestID)
		}
		if err := q.RevokeUserSession(ctx, dbgen.RevokeUserSessionParams{ID: session.ID, RevokedAt: pgTime(now)}); err != nil {
			return err
		}
		return recordAudit(ctx, q, "USER", session.UserID, "USER_LOGOUT", "USER_SESSION", session.ID, "SUCCESS", requestID)
	}))
}

type UserSession struct {
	SessionID string
	UserID    string
	Status    string
	RawUserID pgtype.UUID
}

func (s *Service) AuthenticateUser(ctx context.Context, authorization string) (UserSession, error) {
	prefix, token, ok := strings.Cut(strings.TrimSpace(authorization), " ")
	if !ok || !strings.EqualFold(prefix, "Bearer") || token == "" {
		return UserSession{}, sessionError("AUTH_REQUIRED", "Authentication required")
	}
	row, err := dbgen.New(s.pool).FindUserSessionByAccess(ctx, tokenHash(token))
	if errors.Is(err, pgx.ErrNoRows) {
		return UserSession{}, sessionError("SESSION_INVALID", "Session invalid")
	}
	if err != nil {
		return UserSession{}, internalError()
	}
	if row.RevokedAt.Valid || row.RotatedAt.Valid || !s.now().Before(row.AccessExpiresAt.Time) {
		return UserSession{}, sessionError("SESSION_EXPIRED", "Session expired")
	}
	return UserSession{SessionID: uuidString(row.ID), UserID: uuidString(row.UserID), Status: row.Status, RawUserID: row.UserID}, nil
}

type ApprovalStatus struct {
	Status   string `json:"status"`
	NextStep string `json:"next_step"`
}

func (s *Service) GetApprovalStatus(_ context.Context, session UserSession) ApprovalStatus {
	return ApprovalStatus{Status: session.Status, NextStep: nextStep(session.Status)}
}

type Profile struct {
	ID          string `json:"id"`
	Nickname    string `json:"nickname"`
	MaskedPhone string `json:"masked_phone"`
	AvatarURL   string `json:"avatar_url,omitempty"`
	Version     int64  `json:"version"`
}

func (s *Service) GetProfile(ctx context.Context, session UserSession) (Profile, error) {
	if session.Status != "APPROVED" {
		return Profile{}, &Error{Code: "ACCOUNT_PENDING", Status: 403, Title: "Account pending", Detail: "The account is still awaiting administrator approval."}
	}
	user, err := dbgen.New(s.pool).FindUserByID(ctx, session.RawUserID)
	if err != nil {
		return Profile{}, internalError()
	}
	profile := Profile{ID: uuidString(user.ID), Nickname: user.Nickname, MaskedPhone: MaskPhone(user.PhoneE164), Version: user.ProfileVersion}
	avatar, avatarErr := dbgen.New(s.pool).GetReadyAvatar(ctx, session.RawUserID)
	if avatarErr == nil {
		if grant, grantErr := s.profileObjects.IssueAvatarRead(ctx, avatar.ObjectKey, 5*time.Minute); grantErr == nil {
			profile.AvatarURL = grant.URL
		}
	} else if !errors.Is(avatarErr, pgx.ErrNoRows) {
		return Profile{}, internalError()
	}
	return profile, nil
}

type AdminLoginResult struct {
	SessionToken string
	CSRFToken    string `json:"csrf_token"`
	Username     string `json:"username"`
}

func (s *Service) AdminLogin(ctx context.Context, username, password, requestID string) (AdminLoginResult, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	queries := dbgen.New(s.pool)
	admin, err := queries.FindAdminByUsername(ctx, username)
	encoded := ""
	if err == nil && !admin.DisabledAt.Valid {
		encoded = admin.PasswordHash
	} else if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return AdminLoginResult{}, internalError()
	}
	if !VerifyPasswordOrDummy(encoded, password) {
		_ = recordAudit(ctx, queries, "ANONYMOUS", pgtype.UUID{}, "ADMIN_LOGIN", "ADMIN_SESSION", pgtype.UUID{}, "FAILURE", requestID)
		return AdminLoginResult{}, credentialError()
	}
	sessionToken, err := randomToken()
	if err != nil {
		return AdminLoginResult{}, internalError()
	}
	csrfToken, err := randomToken()
	if err != nil {
		return AdminLoginResult{}, internalError()
	}
	_, err = queries.CreateAdminSession(ctx, dbgen.CreateAdminSessionParams{
		AdminUserID: admin.ID, SessionTokenHash: tokenHash(sessionToken), CsrfTokenHash: tokenHash(csrfToken),
		AbsoluteExpiresAt: pgTime(s.now().Add(s.config.AdminAbsoluteExpiry)),
	})
	if err != nil {
		return AdminLoginResult{}, internalError()
	}
	_ = recordAudit(ctx, queries, "ADMIN", admin.ID, "ADMIN_LOGIN", "ADMIN_SESSION", pgtype.UUID{}, "SUCCESS", requestID)
	return AdminLoginResult{SessionToken: sessionToken, CSRFToken: csrfToken, Username: admin.Username}, nil
}

type AdminSession struct {
	ID         pgtype.UUID
	AdminID    pgtype.UUID
	Username   string
	CSRFHash   []byte
	RawSession string
}

func (s *Service) AuthenticateAdmin(ctx context.Context, sessionToken string) (AdminSession, error) {
	if sessionToken == "" {
		return AdminSession{}, sessionError("AUTH_REQUIRED", "Authentication required")
	}
	now := s.now()
	var result AdminSession
	var terminalError error
	err := s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		row, err := q.FindAdminSessionForUpdate(ctx, tokenHash(sessionToken))
		if errors.Is(err, pgx.ErrNoRows) {
			return sessionError("SESSION_INVALID", "Session invalid")
		}
		if err != nil {
			return err
		}
		if row.RevokedAt.Valid || row.DisabledAt.Valid || !now.Before(row.AbsoluteExpiresAt.Time) || now.Sub(row.LastActiveAt.Time) >= s.config.AdminIdleLifetime {
			_ = q.RevokeAdminSession(ctx, dbgen.RevokeAdminSessionParams{ID: row.ID, RevokedAt: pgTime(now)})
			terminalError = sessionError("SESSION_EXPIRED", "Session expired")
			return nil
		}
		if err := q.TouchAdminSession(ctx, dbgen.TouchAdminSessionParams{ID: row.ID, LastActiveAt: pgTime(now)}); err != nil {
			return err
		}
		result = AdminSession{ID: row.ID, AdminID: row.AdminUserID, Username: row.Username, CSRFHash: row.CsrfTokenHash, RawSession: sessionToken}
		return nil
	})
	if err != nil {
		return AdminSession{}, normalizeDatabaseError(err)
	}
	if terminalError != nil {
		return AdminSession{}, terminalError
	}
	return result, nil
}

func (s *Service) CheckCSRF(session AdminSession, csrfToken string) error {
	if csrfToken == "" || !hmac.Equal(session.CSRFHash, tokenHash(csrfToken)) {
		return &Error{Code: "CSRF_INVALID", Status: 403, Title: "CSRF validation failed", Detail: "The CSRF token is missing or invalid."}
	}
	return nil
}

func (s *Service) RotateAdminCSRF(ctx context.Context, session AdminSession) (string, error) {
	csrfToken, err := randomToken()
	if err != nil {
		return "", internalError()
	}
	err = dbgen.New(s.pool).RotateAdminCSRF(ctx, dbgen.RotateAdminCSRFParams{ID: session.ID, CsrfTokenHash: tokenHash(csrfToken), LastActiveAt: pgTime(s.now())})
	if err != nil {
		return "", internalError()
	}
	return csrfToken, nil
}

func (s *Service) AdminLogout(ctx context.Context, session AdminSession, requestID string) error {
	q := dbgen.New(s.pool)
	if err := q.RevokeAdminSession(ctx, dbgen.RevokeAdminSessionParams{ID: session.ID, RevokedAt: pgTime(s.now())}); err != nil {
		return internalError()
	}
	_ = recordAudit(ctx, q, "ADMIN", session.AdminID, "ADMIN_LOGOUT", "ADMIN_SESSION", session.ID, "SUCCESS", requestID)
	return nil
}

type Approval struct {
	ID          string    `json:"id"`
	MaskedPhone string    `json:"masked_phone"`
	Status      string    `json:"status"`
	CreatedAt   time.Time `json:"created_at"`
}

type ApprovalPage struct {
	Items      []Approval `json:"items"`
	NextCursor *string    `json:"next_cursor"`
}

type cursorPayload struct {
	CreatedAt time.Time `json:"created_at"`
	ID        string    `json:"id"`
}

func (s *Service) ListApprovals(ctx context.Context, cursor string, limit int) (ApprovalPage, error) {
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	var afterTime pgtype.Timestamptz
	var afterID pgtype.UUID
	if cursor != "" {
		payload, err := s.decodeCursor(cursor)
		if err != nil {
			return ApprovalPage{}, validationError("CURSOR_INVALID", "Invalid cursor", "The pagination cursor is invalid.")
		}
		afterTime = pgTime(payload.CreatedAt)
		afterID, err = parsePGUUID(payload.ID)
		if err != nil {
			return ApprovalPage{}, validationError("CURSOR_INVALID", "Invalid cursor", "The pagination cursor is invalid.")
		}
	}
	rows, err := dbgen.New(s.pool).ListPendingApprovals(ctx, dbgen.ListPendingApprovalsParams{Column1: afterTime, Column2: afterID, Limit: int32(limit + 1)})
	if err != nil {
		return ApprovalPage{}, internalError()
	}
	page := ApprovalPage{Items: make([]Approval, 0, min(len(rows), limit))}
	for index, row := range rows {
		if index == limit {
			break
		}
		page.Items = append(page.Items, Approval{ID: uuidString(row.ID), MaskedPhone: MaskPhone(row.PhoneE164), Status: "PENDING", CreatedAt: row.CreatedAt.Time})
	}
	if len(rows) > limit && len(page.Items) > 0 {
		last := page.Items[len(page.Items)-1]
		next := s.encodeCursor(cursorPayload{CreatedAt: last.CreatedAt, ID: last.ID})
		page.NextCursor = &next
	}
	return page, nil
}

func (s *Service) GetApproval(ctx context.Context, id string) (Approval, error) {
	parsed, err := parsePGUUID(id)
	if err != nil {
		return Approval{}, validationError("APPROVAL_ID_INVALID", "Invalid approval", "The approval identifier is invalid.")
	}
	row, err := dbgen.New(s.pool).FindApproval(ctx, parsed)
	if errors.Is(err, pgx.ErrNoRows) {
		return Approval{}, &Error{Code: "APPROVAL_NOT_FOUND", Status: 404, Title: "Approval not found", Detail: "The approval request does not exist."}
	}
	if err != nil {
		return Approval{}, internalError()
	}
	status := "PENDING"
	if row.ReviewedAt.Valid {
		status = "PROCESSED"
	}
	return Approval{ID: uuidString(row.ID), MaskedPhone: MaskPhone(row.PhoneE164), Status: status, CreatedAt: row.CreatedAt.Time}, nil
}

type ApproveResult struct {
	Approval Approval `json:"approval"`
	Outcome  string   `json:"outcome"`
}

func (s *Service) Approve(ctx context.Context, session AdminSession, userID, idempotencyKey, requestID string) (ApproveResult, error) {
	if !idempotencyPattern.MatchString(idempotencyKey) {
		return ApproveResult{}, validationError("IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "Idempotency-Key must contain 8 to 128 safe characters.")
	}
	parsedUserID, err := parsePGUUID(userID)
	if err != nil {
		return ApproveResult{}, validationError("APPROVAL_ID_INVALID", "Invalid approval", "The approval identifier is invalid.")
	}
	var result ApproveResult
	now := s.now()
	err = s.pool.WithinTransaction(ctx, func(tx pgx.Tx) error {
		q := dbgen.New(tx)
		existing, findErr := q.FindApprovalRequest(ctx, dbgen.FindApprovalRequestParams{AdminUserID: session.AdminID, IdempotencyKey: idempotencyKey})
		if findErr == nil {
			if existing.UserID != parsedUserID {
				return conflictError("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The idempotency key was already used for another approval.")
			}
			approval, err := approvalFromQuery(ctx, q, parsedUserID)
			if err != nil {
				return err
			}
			result = ApproveResult{Approval: approval, Outcome: "ALREADY_PROCESSED"}
			return nil
		}
		if !errors.Is(findErr, pgx.ErrNoRows) {
			return findErr
		}
		approval, findErr := q.FindApproval(ctx, parsedUserID)
		if errors.Is(findErr, pgx.ErrNoRows) {
			return &Error{Code: "APPROVAL_NOT_FOUND", Status: 404, Title: "Approval not found", Detail: "The approval request does not exist."}
		}
		if findErr != nil {
			return findErr
		}
		inserted, err := q.CreateApprovalRequest(ctx, dbgen.CreateApprovalRequestParams{AdminUserID: session.AdminID, IdempotencyKey: idempotencyKey, UserID: parsedUserID})
		if err != nil {
			return err
		}
		if inserted == 0 {
			existing, err := q.FindApprovalRequest(ctx, dbgen.FindApprovalRequestParams{AdminUserID: session.AdminID, IdempotencyKey: idempotencyKey})
			if err != nil {
				return err
			}
			if existing.UserID != parsedUserID {
				return conflictError("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", "The idempotency key was already used for another approval.")
			}
			current, err := approvalFromQuery(ctx, q, parsedUserID)
			if err != nil {
				return err
			}
			result = ApproveResult{Approval: current, Outcome: "ALREADY_PROCESSED"}
			return nil
		}
		outcome := "APPROVED"
		if approval.ReviewedAt.Valid {
			outcome = "ALREADY_PROCESSED"
		} else {
			updated, err := q.ApproveUserAfterReview(ctx, dbgen.ApproveUserAfterReviewParams{ID: parsedUserID, UpdatedAt: pgTime(now), ReviewedBy: session.AdminID})
			if err != nil {
				return err
			}
			if updated == 0 {
				outcome = "ALREADY_PROCESSED"
			}
		}
		result = ApproveResult{Approval: Approval{ID: uuidString(approval.ID), MaskedPhone: MaskPhone(approval.PhoneE164), Status: "PROCESSED", CreatedAt: approval.CreatedAt.Time}, Outcome: outcome}
		return recordAudit(ctx, q, "ADMIN", session.AdminID, "APPROVAL_APPROVE", "USER", parsedUserID, "SUCCESS", requestID)
	})
	if err != nil {
		return ApproveResult{}, normalizeDatabaseError(err)
	}
	return result, nil
}

func BootstrapAdmin(ctx context.Context, pool *database.Pool, username, password string) (string, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	if !regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{2,63}$`).MatchString(username) {
		return "", errors.New("admin username must contain 3 to 64 lowercase letters, numbers, dots, hyphens, or underscores")
	}
	if err := ValidatePassword(password); err != nil {
		return "", err
	}
	passwordHash, err := HashPassword(password)
	if err != nil {
		return "", err
	}
	queries := dbgen.New(pool)
	count, err := queries.CountAdminUsers(ctx)
	if err != nil {
		return "", fmt.Errorf("count administrators: %w", err)
	}
	if count != 0 {
		return "", errors.New("administrator bootstrap is already complete")
	}
	admin, err := queries.CreateAdminUser(ctx, dbgen.CreateAdminUserParams{Username: username, PasswordHash: passwordHash})
	if err != nil {
		return "", fmt.Errorf("create administrator: %w", err)
	}
	return uuidString(admin.ID), nil
}

func NormalizePhone(value string) (string, error) {
	trimmed := strings.TrimSpace(value)
	if strings.HasPrefix(trimmed, "+86") {
		trimmed = strings.TrimPrefix(trimmed, "+86")
	}
	if !phonePattern.MatchString(trimmed) {
		return "", errors.New("phone must be a mainland China 11-digit mobile number")
	}
	return "+86" + trimmed, nil
}

func MaskPhone(phone string) string {
	if len(phone) != 14 {
		return "***********"
	}
	return phone[3:6] + "****" + phone[10:]
}

func (s *Service) createUserSession(ctx context.Context, q *dbgen.Queries, userID, deviceID, familyID pgtype.UUID) (TokenResult, error) {
	accessToken, err := randomToken()
	if err != nil {
		return TokenResult{}, err
	}
	refreshToken, err := randomToken()
	if err != nil {
		return TokenResult{}, err
	}
	now := s.now()
	accessExpiry := now.Add(s.config.AccessLifetime)
	refreshExpiry := now.Add(s.config.RefreshLifetime)
	_, err = q.CreateUserSession(ctx, dbgen.CreateUserSessionParams{
		UserID: userID, DeviceID: deviceID, RotationFamilyID: familyID,
		AccessTokenHash: tokenHash(accessToken), RefreshTokenHash: tokenHash(refreshToken),
		AccessExpiresAt: pgTime(accessExpiry), RefreshExpiresAt: pgTime(refreshExpiry),
	})
	if err != nil {
		return TokenResult{}, err
	}
	return TokenResult{UserID: uuidString(userID), AccessToken: accessToken, AccessExpiresAt: accessExpiry, RefreshToken: refreshToken, RefreshExpiresAt: refreshExpiry}, nil
}

func approvalFromQuery(ctx context.Context, q *dbgen.Queries, id pgtype.UUID) (Approval, error) {
	row, err := q.FindApproval(ctx, id)
	if err != nil {
		return Approval{}, err
	}
	status := "PENDING"
	if row.ReviewedAt.Valid {
		status = "PROCESSED"
	}
	return Approval{ID: uuidString(row.ID), MaskedPhone: MaskPhone(row.PhoneE164), Status: status, CreatedAt: row.CreatedAt.Time}, nil
}

func (s *Service) encodeCursor(payload cursorPayload) string {
	body, _ := json.Marshal(payload)
	mac := hmac.New(sha256.New, s.config.CursorKey)
	_, _ = mac.Write(body)
	packet := append(body, mac.Sum(nil)...)
	return base64.RawURLEncoding.EncodeToString(packet)
}

func (s *Service) registrationFingerprint(phone string, input SignUpInput) []byte {
	mac := hmac.New(sha256.New, s.config.CursorKey)
	for _, value := range [][]byte{
		[]byte(phone), []byte(input.Password), input.PublicKey, input.EncryptedKeyBundle,
		input.KDFParameters, []byte(fmt.Sprintf("%d", input.BundleVersion)),
		[]byte(strings.ToUpper(input.Platform)), []byte(input.DeviceInstallationID),
	} {
		_, _ = mac.Write([]byte{0})
		_, _ = mac.Write(value)
	}
	return mac.Sum(nil)
}

func (s *Service) decodeCursor(value string) (cursorPayload, error) {
	packet, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(packet) <= sha256.Size {
		return cursorPayload{}, errors.New("invalid cursor")
	}
	body, signature := packet[:len(packet)-sha256.Size], packet[len(packet)-sha256.Size:]
	mac := hmac.New(sha256.New, s.config.CursorKey)
	_, _ = mac.Write(body)
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return cursorPayload{}, errors.New("invalid cursor signature")
	}
	var payload cursorPayload
	if err := json.Unmarshal(body, &payload); err != nil || payload.ID == "" || payload.CreatedAt.IsZero() {
		return cursorPayload{}, errors.New("invalid cursor payload")
	}
	return payload, nil
}

func (s *Service) now() time.Time { return s.config.Now().UTC().Truncate(time.Millisecond) }

func randomToken() (string, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}

func tokenHash(token string) []byte {
	value := sha256.Sum256([]byte(token))
	return value[:]
}

func newPGUUID() pgtype.UUID {
	return pgtype.UUID{Bytes: uuid.New(), Valid: true}
}

func parsePGUUID(value string) (pgtype.UUID, error) {
	parsed, err := uuid.Parse(value)
	return pgtype.UUID{Bytes: parsed, Valid: err == nil}, err
}

func uuidString(value pgtype.UUID) string { return uuid.UUID(value.Bytes).String() }

func pgTime(value time.Time) pgtype.Timestamptz { return pgtype.Timestamptz{Time: value, Valid: true} }

func validJSONObject(value json.RawMessage) bool {
	var object map[string]any
	return len(value) > 0 && json.Unmarshal(value, &object) == nil && object != nil
}

func validPlatform(value string) bool {
	switch strings.ToUpper(value) {
	case "ANDROID", "IOS", "HARMONYOS":
		return true
	default:
		return false
	}
}

func nextStep(status string) string {
	if status == "APPROVED" {
		return "APP_HOME"
	}
	return "REVIEW_PENDING"
}

func recordAudit(ctx context.Context, q *dbgen.Queries, actorType string, actorID pgtype.UUID, action, targetType string, targetID pgtype.UUID, result, requestID string) error {
	if requestID == "" {
		requestID = "internal-no-request-id"
	}
	return q.RecordAuditEvent(ctx, dbgen.RecordAuditEventParams{
		ActorType: actorType, ActorID: actorID, Action: action, TargetType: targetType,
		TargetID: targetID, Result: result, RequestID: requestID, Metadata: []byte(`{}`),
	})
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

func normalizeDatabaseError(err error) error {
	if err == nil {
		return nil
	}
	var serviceError *Error
	if errors.As(err, &serviceError) {
		return serviceError
	}
	return internalError()
}

func validationError(code, title, detail string) *Error {
	return &Error{Code: code, Status: 422, Title: title, Detail: detail}
}

func conflictError(code, title, detail string) *Error {
	return &Error{Code: code, Status: 409, Title: title, Detail: detail}
}

func credentialError() *Error {
	return &Error{Code: "CREDENTIALS_INVALID", Status: 401, Title: "Invalid credentials", Detail: "The phone number or password is incorrect."}
}

func sessionError(code, title string) *Error {
	return &Error{Code: code, Status: 401, Title: title, Detail: "The session is missing, invalid, expired, or revoked."}
}

func internalError() *Error {
	return &Error{Code: "INTERNAL_ERROR", Status: 500, Title: "Internal server error", Detail: "The request could not be completed.", Retryable: true}
}
