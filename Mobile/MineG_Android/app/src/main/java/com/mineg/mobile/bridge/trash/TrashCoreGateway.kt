/** Recycle-bin listing and restoration through the shared Stage 06 wire contract. */
package com.mineg.mobile.bridge.trash

import com.mineg.mobile.bridge.internal.CoreContractOperationExecutor
import com.mineg.mobile.bridge.shared.model.TrashMediaPage
import com.mineg.mobile.bridge.shared.model.TrashMediaRestoreResult
import com.mineg.mobile.bridge.shared.model.TrashMediaSummary
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.UUID
import org.json.JSONObject

/** Issues recycle-bin list and restore operations while Core owns retention and authorization. */
class TrashCoreGateway(core: CoreClient, runner: CoreOperationRunner) {
  private val executor = CoreContractOperationExecutor(
    core,
    runner,
    initialOperationId = 6_100_000_000L,
    defaultErrorCode = "STAGE06_UNAVAILABLE",
    defaultMessageKey = "stage06.unavailable",
    pendingOperationMessage = "CoreOperationRunner returned a pending trash operation",
  )

  /** Refreshes the recycle bin or loads the page selected by [cursor]. */
  suspend fun refreshTrash(cursor: String? = null, limit: Int = 50): TrashMediaPage {
    val commandType = if (cursor == null) "RefreshTrashMedia" else "LoadMoreTrashMedia"
    return executor.execute(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", commandType)
        .put("limit", limit.coerceIn(1, 100))
        .apply { cursor?.takeIf(String::isNotBlank)?.let { put("cursor", it) } },
    ).toTrashMediaPage()
  }

  /** Restores one owned media item and returns the idempotent outcome confirmed by Core. */
  suspend fun restoreTrash(mediaId: String): TrashMediaRestoreResult {
    require(mediaId.matches(UUID_PATTERN)) { "mediaId must be a UUID" }
    return executor.execute(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "RestoreTrashMedia")
        .put("mediaId", mediaId)
        .put("idempotencyKey", UUID.randomUUID().toString()),
    ).run {
      TrashMediaRestoreResult(
        mediaId = getString("mediaId"),
        outcome = getString("outcome"),
        restoredAt = getString("restoredAt"),
      )
    }
  }

  /** Maps the Core recycle-bin page and preserves its opaque cursor. */
  private fun JSONObject.toTrashMediaPage(): TrashMediaPage {
    val values = getJSONArray("items")
    return TrashMediaPage(
      items = List(values.length()) { index -> values.getJSONObject(index).toTrashMediaSummary() },
      nextCursor = nullableString("nextCursor"),
      fullyLoaded = optBoolean("fullyLoaded", false),
    )
  }

  /** Maps one recycle-bin item without calculating retention policy in Android. */
  private fun JSONObject.toTrashMediaSummary() = TrashMediaSummary(
    id = getString("id"),
    mediaType = getString("mediaType"),
    capturedAt = getString("capturedAt"),
    createdAt = getString("createdAt"),
    durationMs = nullableLong("durationMs"),
    originalTotalSize = getLong("originalTotalSize"),
    trashedAt = getString("trashedAt"),
  )

  /** Reads an optional non-blank string field. */
  private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

  /** Reads an optional long field. */
  private fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else getLong(name)

  private companion object {
    const val CONTRACT_VERSION = "stage06-v1"
    val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
  }
}
