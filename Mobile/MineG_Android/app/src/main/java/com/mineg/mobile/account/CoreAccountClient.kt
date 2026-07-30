package com.mineg.mobile.account

import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

class CoreAccountClient(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(1_000_000_000L)

  suspend fun signUp(
    phone: String,
    password: ByteArray,
    idempotencyKey: String,
  ): AccountRouteSnapshot = runRouteCommand(
    JSONObject()
      .put("contractVersion", ACCOUNT_V3)
      .put("type", "AccountSignUp")
      .put("phone", phone)
      .put("password", password.toString(Charsets.UTF_8))
      .put("idempotencyKey", idempotencyKey),
  ) ?: throw AccountProblem("SESSION_INVALID", "account.session.invalid", false, "")

  suspend fun signIn(
    phone: String,
    password: String,
    agreementAccepted: Boolean,
  ): AccountRouteSnapshot = runRouteCommand(
    JSONObject()
      .put("contractVersion", ACCOUNT_V3)
      .put("type", "AccountSignIn")
      .put("phone", phone)
      .put("password", password)
      .put("agreementAccepted", agreementAccepted),
  ) ?: throw AccountProblem("SESSION_INVALID", "account.session.invalid", false, "")

  suspend fun signOut() {
    runCommand(JSONObject().put("contractVersion", ACCOUNT_V3).put("type", "AccountSignOut"))
  }

  suspend fun restoreSession(): AccountRouteSnapshot? = runRouteCommand(
    JSONObject().put("contractVersion", ACCOUNT_V3).put("type", "AccountRestoreSession"),
  )

  suspend fun refreshReviewStatus(): ApprovalStatus {
    val payload = runCommand(
      JSONObject().put("contractVersion", ACCOUNT_V3).put("type", "AccountRefreshReviewStatus"),
    ) ?: throw AccountProblem("RESPONSE_INVALID", "account.response.invalid", false, "")
    return ApprovalStatus.valueOf(payload.getString("approvalStatus"))
  }

  suspend fun getProfile(allowCached: Boolean): Profile {
    val payload = runCommand(
      JSONObject()
        .put("contractVersion", ACCOUNT_V3)
        .put("type", "ProfileGetCurrent")
        .put("allowCached", allowCached),
    ) ?: throw AccountProblem("PROFILE_MISSING", "account.profile.missing", false, "")
    return payload.toProfile()
  }

  suspend fun updateProfile(nickname: String): Profile {
    val payload = runCommand(
      JSONObject()
        .put("contractVersion", ACCOUNT_V3)
        .put("type", "ProfileUpdateCurrent")
        .put("nickname", nickname),
    ) ?: throw AccountProblem("PROFILE_MISSING", "account.profile.missing", false, "")
    return payload.toProfile()
  }

  private suspend fun runRouteCommand(command: JSONObject): AccountRouteSnapshot? =
    runCommand(command)?.let { payload ->
      AccountRouteSnapshot(
        userId = payload.getString("userId"),
        approvalStatus = ApprovalStatus.valueOf(payload.getString("approvalStatus")),
        nextStep = AccountNextStep.valueOf(payload.getString("nextStep")),
      )
    }

  private suspend fun runCommand(command: JSONObject): JSONObject? {
    val operationId = operationIds.getAndIncrement()
    val terminal = runner.run(core.startOperation(operationId, command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> terminal.resultJson?.let(::JSONObject)
      CoreOperationStatus.FAILED -> throw terminal.toAccountProblem()
      CoreOperationStatus.CANCELLED ->
        throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending operation")
    }
  }

  private fun com.mineg.mobile.contracts.CoreOperationStep.toAccountProblem(): AccountProblem {
    val error = errorJson?.let(::JSONObject) ?: JSONObject()
    return AccountProblem(
      code = error.optString("code", "INTERNAL_ERROR"),
      messageKey = error.optString("messageKey", "account.internal.error"),
      retryable = error.optBoolean("retryable", false),
      requestId = error.optString("requestId"),
    )
  }

  private fun JSONObject.toProfile() = Profile(
    id = getString("id"),
    nickname = getString("nickname"),
    maskedPhone = getString("maskedPhone"),
    avatarUrl = if (isNull("avatarUrl")) null else getString("avatarUrl").ifBlank { null },
  )

  private companion object {
    const val ACCOUNT_V3 = "account-v3"
  }
}
