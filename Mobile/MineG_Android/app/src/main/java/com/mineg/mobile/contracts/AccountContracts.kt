package com.mineg.mobile.contracts

data class AccountProblem(
  val code: String,
  val messageKey: String,
  val retryable: Boolean,
  val requestId: String,
  val details: Map<String, String> = emptyMap(),
) : RuntimeException(code)

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
  suspend fun signUp(phone: String, password: ByteArray, idempotencyKey: String): AccountSession
  suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountSession
  suspend fun signOut()
  suspend fun restoreSession(): AccountSession?
  suspend fun refreshReviewStatus(): ApprovalStatus
  suspend fun getProfile(): Profile
}
