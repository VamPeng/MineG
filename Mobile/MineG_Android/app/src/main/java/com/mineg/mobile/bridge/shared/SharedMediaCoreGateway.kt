/** Shared-space media commands and typed Core response mapping. */
package com.mineg.mobile.bridge.shared

import com.mineg.mobile.bridge.internal.CoreContractOperationExecutor
import com.mineg.mobile.bridge.media.model.PrivateMediaResourceSummary
import com.mineg.mobile.bridge.media.model.PrivateMediaView
import com.mineg.mobile.bridge.shared.model.PrivateMediaShareResult
import com.mineg.mobile.bridge.shared.model.SharedMediaDetail
import com.mineg.mobile.bridge.shared.model.SharedMediaOwner
import com.mineg.mobile.bridge.shared.model.SharedMediaPage
import com.mineg.mobile.bridge.shared.model.SharedMediaSummary
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.UUID
import org.json.JSONObject

/**
 * Controls sharing and reads the approved-user shared space through `stage06-v1`.
 *
 * The gateway validates identifiers and view variants, while Core owns authorization, cursors,
 * object grants and the authoritative share state.
 */
class SharedMediaCoreGateway(core: CoreClient, runner: CoreOperationRunner) {
  private val executor = CoreContractOperationExecutor(
    core,
    runner,
    initialOperationId = 6_000_000_000L,
    defaultErrorCode = "STAGE06_UNAVAILABLE",
    defaultMessageKey = "stage06.unavailable",
    pendingOperationMessage = "CoreOperationRunner returned a pending shared-media operation",
  )

  /** Enables or disables sharing and returns the state confirmed by Core. */
  suspend fun setPrivateMediaShare(mediaId: String, shared: Boolean): PrivateMediaShareResult =
    runMediaCommand("SetPrivateMediaShare", mediaId) {
      put("shared", shared)
      put("idempotencyKey", UUID.randomUUID().toString())
    }.run {
      PrivateMediaShareResult(
        mediaId = getString("mediaId"),
        state = getString("state"),
        outcome = getString("outcome"),
        effectiveAt = getString("effectiveAt"),
      )
    }

  /** Refreshes the first shared page or loads the page identified by [cursor]. */
  suspend fun refreshSharedMedia(
    filter: String = "all",
    cursor: String? = null,
    limit: Int = 50,
  ): SharedMediaPage {
    require(filter == "all" || filter == "mine") { "shared filter is invalid" }
    val commandType = if (cursor == null) "RefreshSharedMedia" else "LoadMoreSharedMedia"
    return executor.execute(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", commandType)
        .put("filter", filter)
        .put("limit", limit.coerceIn(1, 100))
        .apply { cursor?.takeIf(String::isNotBlank)?.let { put("cursor", it) } },
    ).toSharedMediaPage()
  }

  /** Fetches the complete shared-media metadata visible to the current account. */
  suspend fun getSharedMediaDetail(mediaId: String): SharedMediaDetail =
    runMediaCommand("GetSharedMediaDetail", mediaId).toSharedMediaDetail()

  /** Opens a verified temporary thumbnail or detail resource produced by Core. */
  suspend fun openSharedMedia(mediaId: String, variant: String = "THUMBNAIL"): PrivateMediaView {
    require(variant == "THUMBNAIL" || variant == "DETAIL") { "unsupported shared media view variant" }
    return runMediaCommand("OpenSharedMedia", mediaId) { put("variant", variant) }.run {
      PrivateMediaView(
        mediaId = getString("mediaId"),
        resourceType = getString("resourceType"),
        mimeType = getString("mimeType"),
        viewHandle = getString("viewHandle"),
        sourceUri = getString("sourceUri"),
      )
    }
  }

  /** Releases the verified view handle and its temporary platform resource. */
  suspend fun closeSharedMedia(viewHandle: String): Boolean {
    require(viewHandle.matches(VIEW_HANDLE_PATTERN)) { "viewHandle is invalid" }
    return executor.execute(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "CloseSharedMedia")
        .put("viewHandle", viewHandle),
    ).optBoolean("closed", false)
  }

  /** Builds and executes a media-scoped command after validating the stable media identifier. */
  private suspend fun runMediaCommand(
    type: String,
    mediaId: String,
    extra: JSONObject.() -> Unit = {},
  ): JSONObject {
    require(mediaId.matches(UUID_PATTERN)) { "mediaId must be a UUID" }
    return executor.execute(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", type)
        .put("mediaId", mediaId)
        .apply(extra),
    )
  }

  /** Maps a Core page while preserving its opaque cursor. */
  private fun JSONObject.toSharedMediaPage(): SharedMediaPage {
    val values = getJSONArray("items")
    return SharedMediaPage(
      items = List(values.length()) { values.getJSONObject(it).toSharedMediaSummary() },
      nextCursor = nullableString("nextCursor"),
      fullyLoaded = optBoolean("fullyLoaded", false),
    )
  }

  /** Maps the fields needed by the shared-media timeline. */
  private fun JSONObject.toSharedMediaSummary() = SharedMediaSummary(
    id = getString("id"),
    owner = getJSONObject("owner").toSharedMediaOwner(),
    mediaType = getString("mediaType"),
    capturedAt = getString("capturedAt"),
    createdAt = getString("createdAt"),
    durationMs = nullableLong("durationMs"),
    originalTotalSize = getLong("originalTotalSize"),
  )

  /** Maps shared detail and its server-verified resource manifest. */
  private fun JSONObject.toSharedMediaDetail() = SharedMediaDetail(
    id = getString("id"),
    owner = getJSONObject("owner").toSharedMediaOwner(),
    mediaType = getString("mediaType"),
    capturedAt = getString("capturedAt"),
    createdAt = getString("createdAt"),
    width = nullableInt("width"),
    height = nullableInt("height"),
    durationMs = nullableLong("durationMs"),
    originalTotalSize = getLong("originalTotalSize"),
    resources = getJSONArray("resources").let { values ->
      List(values.length()) { index ->
        values.getJSONObject(index).run {
          PrivateMediaResourceSummary(
            resourceId = getString("resourceId"),
            resourceType = getString("resourceType"),
            mimeType = getString("mimeType"),
            contentSize = getLong("contentSize"),
            contentSha256 = getString("contentSha256"),
          )
        }
      }
    },
  )

  /** Maps the public owner projection included with shared media. */
  private fun JSONObject.toSharedMediaOwner() = SharedMediaOwner(
    id = getString("id"),
    nickname = getString("nickname"),
  )

  /** Reads an optional non-blank string from a Core JSON object. */
  private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

  /** Reads an optional long from a Core JSON object. */
  private fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else getLong(name)

  /** Reads an optional integer from a Core JSON object. */
  private fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)

  private companion object {
    const val CONTRACT_VERSION = "stage06-v1"
    val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val VIEW_HANDLE_PATTERN = Regex("^[A-Za-z0-9._:-]{8,256}$")
  }
}
