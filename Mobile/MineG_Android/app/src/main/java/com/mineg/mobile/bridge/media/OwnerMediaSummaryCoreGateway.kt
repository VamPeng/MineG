/** Retained owner-media summary reader kept separate from the private-media page gateway. */
package com.mineg.mobile.bridge.media

import com.mineg.mobile.core.protocol.CoreProblem
import com.mineg.mobile.bridge.media.model.OwnerMediaSummary
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.core.protocol.CoreOperationStep
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Retains the frozen Stage02 owner-media summary operation behind a domain-specific name.
 *
 * The current private-media UI uses [PrivateMediaCoreGateway]; this gateway remains isolated so
 * the older snapshot surface cannot be confused with the Stage05 paginated model.
 */
class OwnerMediaSummaryCoreGateway(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(2_300_000_000L)

  /** Lists the compact owner-media snapshots exposed by the frozen Stage02 contract. */
  suspend fun listOwnerMedia(limit: Int = 100, allowCached: Boolean = true): List<OwnerMediaSummary> {
    val result = runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "PrivateMediaList")
        .put("limit", limit.coerceIn(1, 100))
        .put("allowCached", allowCached),
    ) ?: return emptyList()
    val items = result.getJSONArray("items")
    return List(items.length()) { index -> items.getJSONObject(index).toOwnerMediaSummary() }
  }

  /** Runs the effect-driven list command to a terminal Core state. */
  private suspend fun runCommand(command: JSONObject): JSONObject? {
    val terminal = runner.run(core.startOperation(operationIds.getAndIncrement(), command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> terminal.resultJson?.let(::JSONObject)
      CoreOperationStatus.FAILED -> throw terminal.toProblem()
      CoreOperationStatus.CANCELLED ->
        throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending owner-media operation")
    }
  }

  /** Maps one compact owner-media row. */
  private fun JSONObject.toOwnerMediaSummary() = OwnerMediaSummary(
    id = getString("id"),
    mediaType = getString("mediaType"),
    contentRevision = getInt("contentRevision"),
    capturedAt = getString("capturedAt"),
    createdAt = getString("createdAt"),
  )

  /** Converts a Core error envelope without introducing media-specific retry policy. */
  private fun CoreOperationStep.toProblem(): CoreProblem {
    val error = errorJson?.let(::JSONObject) ?: JSONObject()
    return CoreProblem(
      code = error.optString("code", "INTERNAL_ERROR"),
      messageKey = error.optString("messageKey", "account.internal.error"),
      retryable = error.optBoolean("retryable", false),
      requestId = error.optString("requestId"),
    )
  }

  private companion object {
    const val CONTRACT_VERSION = "stage02-v2"
  }
}
