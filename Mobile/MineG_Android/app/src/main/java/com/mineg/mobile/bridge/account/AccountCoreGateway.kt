/** Account authentication/profile commands and their typed Android mapping. */
package com.mineg.mobile.bridge.account

import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.core.protocol.CoreOperationStep
import com.mineg.mobile.core.protocol.CoreProblem
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Typed Android gateway for the `account-v3` Core contract.
 *
 * It serializes account intents, lets [CoreOperationRunner] execute every requested platform
 * effect, and maps the terminal Core payload into immutable Android snapshots.
 */
class AccountCoreGateway(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(1_000_000_000L)

  /** Registers an account and returns the Core-decided admission route. */
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
  ) ?: throw CoreProblem("SESSION_INVALID", "account.session.invalid", false, "")

  /** Authenticates an account after recording the user's agreement decision. */
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
  ) ?: throw CoreProblem("SESSION_INVALID", "account.session.invalid", false, "")

  /** Revokes the current session and clears Core-owned account state. */
  suspend fun signOut() {
    runCommand(JSONObject().put("contractVersion", ACCOUNT_V3).put("type", "AccountSignOut"))
  }

  /** Restores and refreshes the secure session, returning `null` when none remains valid. */
  suspend fun restoreSession(): AccountRouteSnapshot? = runRouteCommand(
    JSONObject().put("contractVersion", ACCOUNT_V3).put("type", "AccountRestoreSession"),
  )

  /** Refreshes administrator approval and returns the status accepted by Core. */
  suspend fun refreshReviewStatus(): ApprovalStatus {
    val payload = runCommand(
      JSONObject().put("contractVersion", ACCOUNT_V3).put("type", "AccountRefreshReviewStatus"),
    ) ?: throw CoreProblem("RESPONSE_INVALID", "account.response.invalid", false, "")
    return ApprovalStatus.valueOf(payload.getString("approvalStatus"))
  }

  /** Loads the current profile under Core's cache policy. */
  suspend fun getProfile(allowCached: Boolean): Profile {
    val payload = runCommand(
      JSONObject()
        .put("contractVersion", ACCOUNT_V3)
        .put("type", "ProfileGetCurrent")
        .put("allowCached", allowCached),
    ) ?: throw CoreProblem("PROFILE_MISSING", "account.profile.missing", false, "")
    return payload.toProfile()
  }

  /** Updates the nickname and returns the server-confirmed profile snapshot. */
  suspend fun updateProfile(nickname: String): Profile {
    val payload = runCommand(
      JSONObject()
        .put("contractVersion", ACCOUNT_V3)
        .put("type", "ProfileUpdateCurrent")
        .put("nickname", nickname),
    ) ?: throw CoreProblem("PROFILE_MISSING", "account.profile.missing", false, "")
    return payload.toProfile()
  }

  /** Maps an account command result to the next protected application route. */
  private suspend fun runRouteCommand(command: JSONObject): AccountRouteSnapshot? =
    runCommand(command)?.let { payload ->
      AccountRouteSnapshot(
        userId = payload.getString("userId"),
        approvalStatus = ApprovalStatus.valueOf(payload.getString("approvalStatus")),
        nextStep = AccountNextStep.valueOf(payload.getString("nextStep")),
      )
    }

  /** Drives an account operation to a terminal state and returns its optional payload. */
  private suspend fun runCommand(command: JSONObject): JSONObject? {
    val operationId = operationIds.getAndIncrement()
    val terminal = runner.run(core.startOperation(operationId, command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> terminal.resultJson?.let(::JSONObject)
      CoreOperationStatus.FAILED -> throw terminal.toCoreProblem()
      CoreOperationStatus.CANCELLED ->
        throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending operation")
    }
  }

  /** Converts the stable Core error envelope to an Android exception. */
  private fun CoreOperationStep.toCoreProblem(): CoreProblem {
    val error = errorJson?.let(::JSONObject) ?: JSONObject()
    return CoreProblem(
      code = error.optString("code", "INTERNAL_ERROR"),
      messageKey = error.optString("messageKey", "account.internal.error"),
      retryable = error.optBoolean("retryable", false),
      requestId = error.optString("requestId"),
    )
  }

  /** Maps a Core profile payload without retaining the JSON object. */
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
