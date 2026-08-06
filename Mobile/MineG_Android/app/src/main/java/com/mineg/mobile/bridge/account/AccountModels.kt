/** Immutable account, session and profile models shared by presentation and the Core gateway. */
package com.mineg.mobile.bridge.account

enum class ApprovalStatus { PENDING, APPROVED }
enum class AccountNextStep { REVIEW_PENDING, APP_HOME }
enum class AccountPageState { INITIAL, LOADING, CONTENT, ERROR, BLOCKED, SUCCESS }

data class AccountSession(
  val userId: String,
  val accessToken: String,
  val accessExpiresAt: String,
  val refreshToken: String,
  val refreshExpiresAt: String,
  val approvalStatus: ApprovalStatus,
  val nextStep: AccountNextStep,
)

data class AccountRouteSnapshot(
  val userId: String,
  val approvalStatus: ApprovalStatus,
  val nextStep: AccountNextStep,
)

data class AccountStateSnapshot(
  val userId: String,
  val maskedPhone: String,
  val approvalStatus: ApprovalStatus,
  val updatedAt: String,
)

data class Profile(
  val id: String,
  val nickname: String,
  val maskedPhone: String,
  val avatarUrl: String?,
)

interface AccountClient {
  /** Creates an account and returns its persisted session. */
  suspend fun signUp(phone: String, password: ByteArray, idempotencyKey: String): AccountSession
  /** Authenticates an approved or review-pending account. */
  suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountSession
  /** Revokes and removes the current local session. */
  suspend fun signOut()
  /** Restores a valid local session or returns null. */
  suspend fun restoreSession(): AccountSession?
  /** Reads the latest approval state for the current account. */
  suspend fun refreshReviewStatus(): ApprovalStatus
  /** Loads the current account profile. */
  suspend fun getProfile(): Profile
}
