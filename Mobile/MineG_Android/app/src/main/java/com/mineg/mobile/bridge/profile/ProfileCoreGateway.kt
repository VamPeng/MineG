/** Profile mutation boundary for avatar updates performed through Core. */
package com.mineg.mobile.bridge.profile

import com.mineg.mobile.core.protocol.CoreProblem
import com.mineg.mobile.bridge.account.Profile
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.core.protocol.CoreOperationStep
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Updates the current profile through the profile portion of the `stage02-v2` Core contract.
 *
 * Android performs image preprocessing before this boundary. This gateway only serializes the
 * prepared display image, drives Core effects and converts the authoritative profile response.
 */
class ProfileCoreGateway(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(2_000_000_000L)

  /** Uploads a prepared avatar and returns the profile snapshot confirmed by Core. */
  suspend fun updateAvatar(
    displayBytes: ByteArray,
    sourceSize: Long,
    width: Int,
    contentType: String = "image/webp",
  ): Profile {
    // The idempotency key belongs to this user intent and is kept stable throughout Core effects.
    val command = JSONObject()
      .put("contractVersion", CONTRACT_VERSION)
      .put("type", "ProfileUpdateAvatar")
      .put("displayBase64", Base64.getEncoder().withoutPadding().encodeToString(displayBytes))
      .put("sourceSize", sourceSize)
      .put("width", width)
      .put("contentType", contentType)
      .put("idempotencyKey", UUID.randomUUID().toString())
    val result = runCommand(command)
      ?: throw CoreProblem("PROFILE_MISSING", "account.profile.missing", false, "")
    return result.toProfile()
  }

  /** Runs the operation until Core reaches a terminal state and exposes only its result payload. */
  private suspend fun runCommand(command: JSONObject): JSONObject? {
    val terminal = runner.run(core.startOperation(operationIds.getAndIncrement(), command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> terminal.resultJson?.let(::JSONObject)
      CoreOperationStatus.FAILED -> throw terminal.toProblem()
      CoreOperationStatus.CANCELLED ->
        throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending profile operation")
    }
  }

  /** Converts a terminal Core error envelope into the common Android business error. */
  private fun CoreOperationStep.toProblem(): CoreProblem {
    val error = errorJson?.let(::JSONObject) ?: JSONObject()
    return CoreProblem(
      code = error.optString("code", "INTERNAL_ERROR"),
      messageKey = error.optString("messageKey", "account.internal.error"),
      retryable = error.optBoolean("retryable", false),
      requestId = error.optString("requestId"),
    )
  }

  /** Maps the profile wire payload to the immutable Android snapshot. */
  private fun JSONObject.toProfile() = Profile(
    id = getString("id"),
    nickname = getString("nickname"),
    maskedPhone = getString("maskedPhone"),
    avatarUrl = if (isNull("avatarUrl")) null else getString("avatarUrl").ifBlank { null },
  )

  private companion object {
    const val CONTRACT_VERSION = "stage02-v2"
  }
}
