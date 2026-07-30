package com.mineg.mobile.account

import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.contracts.OwnerMediaSummary
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

class CoreStage02Client(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(2_000_000_000L)

  suspend fun coordinateFamilyKeyGrants(password: ByteArray?): Int {
    val command = JSONObject()
      .put("contractVersion", STAGE02_V2)
      .put("type", "CoordinateFamilyKeyGrants")
    password?.let { command.put("password", it.toString(Charsets.UTF_8)) }
    val result = runCommand(command) ?: return 0
    return result.getInt("completedCount")
  }

  suspend fun listPrivateMedia(limit: Int = 100, allowCached: Boolean = true): List<OwnerMediaSummary> {
    val result = runCommand(
      JSONObject()
        .put("contractVersion", STAGE02_V2)
        .put("type", "PrivateMediaList")
        .put("limit", limit.coerceIn(1, 100))
        .put("allowCached", allowCached),
    ) ?: return emptyList()
    val items = result.getJSONArray("items")
    return List(items.length()) { index ->
      items.getJSONObject(index).run {
        OwnerMediaSummary(
          id = getString("id"),
          mediaType = getString("mediaType"),
          contentRevision = getInt("contentRevision"),
          capturedAt = getString("capturedAt"),
          createdAt = getString("createdAt"),
        )
      }
    }
  }

  suspend fun updateAvatar(
    displayBytes: ByteArray,
    sourceSize: Long,
    width: Int,
    contentType: String = "image/webp",
  ): Profile {
    val result = runCommand(
      JSONObject()
        .put("contractVersion", STAGE02_V2)
        .put("type", "ProfileUpdateAvatar")
        .put("displayBase64", Base64.getEncoder().withoutPadding().encodeToString(displayBytes))
        .put("sourceSize", sourceSize)
        .put("width", width)
        .put("contentType", contentType)
        .put("idempotencyKey", java.util.UUID.randomUUID().toString()),
    ) ?: throw AccountProblem("PROFILE_MISSING", "account.profile.missing", false, "")
    return result.toProfile()
  }

  private suspend fun runCommand(command: JSONObject): JSONObject? {
    val operationId = operationIds.getAndIncrement()
    val terminal = runner.run(core.startOperation(operationId, command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> terminal.resultJson?.let(::JSONObject)
      CoreOperationStatus.FAILED -> throw terminal.toProblem()
      CoreOperationStatus.CANCELLED ->
        throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending operation")
    }
  }

  private fun com.mineg.mobile.contracts.CoreOperationStep.toProblem(): AccountProblem {
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
    const val STAGE02_V2 = "stage02-v2"
  }
}
