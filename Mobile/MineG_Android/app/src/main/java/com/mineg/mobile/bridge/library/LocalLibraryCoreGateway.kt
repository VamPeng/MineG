/** Account-isolated local-library scan and snapshot access through Core. */
package com.mineg.mobile.bridge.library

import com.mineg.mobile.core.protocol.CoreProblem
import com.mineg.mobile.bridge.library.model.AlbumCursor
import com.mineg.mobile.bridge.library.model.LocalAlbum
import com.mineg.mobile.bridge.library.model.LocalAlbumPage
import com.mineg.mobile.bridge.library.model.LocalLibrarySummary
import com.mineg.mobile.bridge.library.model.LocalMedia
import com.mineg.mobile.bridge.library.model.LocalMediaAvailability
import com.mineg.mobile.bridge.library.model.LocalMediaCursor
import com.mineg.mobile.bridge.library.model.LocalMediaPage
import com.mineg.mobile.bridge.library.model.LocalMediaType
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.core.protocol.CoreOperationStep
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Reads and refreshes the account-isolated local media index owned by C++ Core.
 *
 * Scanning is an effect-driven command; album and media pagination are side-effect-free Core
 * queries over the last completed local-library generation.
 */
class LocalLibraryCoreGateway(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(2_100_000_000L)

  /** Starts a complete foreground scan and returns the newly committed generation summary. */
  suspend fun startForegroundScan(userId: String): LocalLibrarySummary {
    require(userId.isNotBlank())
    val result = runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "StartForegroundLocalScan")
        .put("userId", userId),
    ) ?: error("Foreground scan completed without a summary")
    return result.toLocalLibrarySummary()
  }

  /** Returns the last completed scan summary without starting platform work. */
  fun getSummary(userId: String): LocalLibrarySummary? {
    val result = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", CONTRACT_VERSION)
          .put("type", "GetLocalLibrarySummary")
          .put("userId", userId)
          .toString(),
      ),
    )
    return result.optJSONObject("snapshot")?.toLocalLibrarySummary()
  }

  /** Lists local albums in the stable name/reference order defined by the Core contract. */
  fun listAlbums(userId: String, cursor: AlbumCursor? = null, limit: Int = 50): LocalAlbumPage {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", CONTRACT_VERSION)
          .put("type", "ListLocalAlbums")
          .put("userId", userId)
          .put("cursorName", cursor?.name.orEmpty())
          .put("cursorAlbumRef", cursor?.platformAlbumRef.orEmpty())
          .put("limit", limit.coerceIn(1, 100))
          .toString(),
      ),
    )
    val values = payload.getJSONArray("items")
    val items = List(values.length()) { index ->
      values.getJSONObject(index).run {
        LocalAlbum(
          platformAlbumRef = getString("platformAlbumRef"),
          name = getString("name"),
          mediaCount = getLong("mediaCount"),
          coverThumbnailUri = if (isNull("coverThumbnailUri")) null else getString("coverThumbnailUri"),
        )
      }
    }
    val next = payload.optJSONObject("nextCursor")?.run {
      AlbumCursor(getString("name"), getString("platformAlbumRef"))
    }
    return LocalAlbumPage(items, next)
  }

  /** Lists a bounded page of local media, optionally restricted to one platform album. */
  fun listMedia(
    userId: String,
    albumRef: String?,
    cursor: LocalMediaCursor? = null,
    limit: Int = 60,
  ): LocalMediaPage {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", CONTRACT_VERSION)
          .put("type", "ListLocalMedia")
          .put("userId", userId)
          .put("platformAlbumRef", albumRef.orEmpty())
          .put("cursorCapturedAt", cursor?.capturedAt.orEmpty())
          .put("cursorAssetRef", cursor?.platformAssetRef.orEmpty())
          .put("limit", limit.coerceIn(1, 500))
          .toString(),
      ),
    )
    val values = payload.getJSONArray("items")
    val items = List(values.length()) { index -> values.getJSONObject(index).toLocalMedia() }
    val next = payload.optJSONObject("nextCursor")?.run {
      LocalMediaCursor(getString("capturedAt"), getString("platformAssetRef"))
    }
    return LocalMediaPage(items, next)
  }

  /** Drives the effect-based scan command until Core commits or rejects the generation. */
  private suspend fun runCommand(command: JSONObject): JSONObject? {
    val terminal = runner.run(core.startOperation(operationIds.getAndIncrement(), command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> terminal.resultJson?.let(::JSONObject)
      CoreOperationStatus.FAILED -> throw terminal.toProblem()
      CoreOperationStatus.CANCELLED ->
        throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending library operation")
    }
  }

  /** Maps a terminal Core failure without making retry decisions in Android. */
  private fun CoreOperationStep.toProblem(): CoreProblem {
    val error = errorJson?.let(::JSONObject) ?: JSONObject()
    return CoreProblem(
      code = error.optString("code", "INTERNAL_ERROR"),
      messageKey = error.optString("messageKey", "account.internal.error"),
      retryable = error.optBoolean("retryable", false),
      requestId = error.optString("requestId"),
    )
  }

  /** Maps a completed scan payload to its stable generation summary. */
  private fun JSONObject.toLocalLibrarySummary() = LocalLibrarySummary(
    generationId = getString("generationId"),
    indexedCount = getLong("indexedCount"),
    completedAt = getString("completedAt"),
  )

  /** Maps one Core media row, preserving availability rather than guessing from its URI. */
  private fun JSONObject.toLocalMedia() = LocalMedia(
    platformAssetRef = getString("platformAssetRef"),
    mediaType = LocalMediaType.valueOf(getString("mediaType")),
    mimeType = getString("mimeType"),
    width = getInt("width"),
    height = getInt("height"),
    durationMs = if (isNull("durationMs")) null else getLong("durationMs"),
    capturedAt = getString("capturedAt"),
    modifiedAt = getString("modifiedAt"),
    contentVersion = getString("contentVersion"),
    availability = LocalMediaAvailability.valueOf(getString("availability")),
    thumbnailUri = if (isNull("thumbnailUri")) null else getString("thumbnailUri"),
  )

  private companion object {
    const val CONTRACT_VERSION = "stage02-v2"
  }
}
