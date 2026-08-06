/**
 * Core-backed private-media operations and JSON-to-model mapping for Android.
 *
 * Core owns authorization, cache freshness and resource verification; this gateway validates
 * Android inputs, drives operations and exposes typed immutable results.
 */
package com.mineg.mobile.bridge.media

import com.mineg.mobile.feature.private_media.PrivateMediaSaveReceiptRecorder
import com.mineg.mobile.core.protocol.CoreProblem
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.bridge.media.model.PrivateMediaPage
import com.mineg.mobile.bridge.media.model.PrivateMediaDetail
import com.mineg.mobile.bridge.media.model.PrivateMediaView
import com.mineg.mobile.bridge.media.model.PrivateMediaResourceSummary
import com.mineg.mobile.bridge.media.model.PrivateMediaSummary
import com.mineg.mobile.bridge.media.model.PrivateMediaTrashResult
import com.mineg.mobile.bridge.media.model.PrivateMediaSaveResult
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** The only Android entry point for the private-media page cache and resource lifecycle. */
class PrivateMediaCoreGateway(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) : PrivateMediaSaveReceiptRecorder {
  private val operationIds = AtomicLong(5_000_000_000L)

  /** Refreshes the first private-media page, optionally accepting Core's cached snapshot. */
  suspend fun refreshPrivateMedia(limit: Int = 50, allowCached: Boolean = true): PrivateMediaPage =
    run("RefreshPrivateMedia", limit, allowCached)

  /** Loads the next Core-owned private-media cursor page. */
  suspend fun loadMorePrivateMedia(limit: Int = 50, allowCached: Boolean = false): PrivateMediaPage =
    run("LoadMorePrivateMedia", limit, allowCached)

  /** Loads complete metadata and the verified resource manifest for one private item. */
  suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail =
    runDetailCommand("GetPrivateMediaDetail", mediaId).toPrivateMediaDetail()

  /** Opens a verified thumbnail or detail resource and returns its temporary view handle. */
  suspend fun openPrivateMedia(mediaId: String, variant: String = "THUMBNAIL"): PrivateMediaView {
    require(variant == "THUMBNAIL" || variant == "DETAIL") { "unsupported private media view variant" }
    val payload = runDetailCommand("OpenPrivateMedia", mediaId, extra = { put("variant", variant) })
    return PrivateMediaView(
      mediaId = payload.getString("mediaId"),
      resourceType = payload.getString("resourceType"),
      mimeType = payload.getString("mimeType"),
      viewHandle = payload.getString("viewHandle"),
      sourceUri = payload.getString("sourceUri"),
    )
  }

  /** Releases a previously opened Core view and its temporary platform resource. */
  suspend fun closePrivateMedia(viewHandle: String): Boolean {
    require(viewHandle.matches(VIEW_HANDLE_PATTERN)) { "viewHandle is invalid" }
    return runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "ClosePrivateMedia")
        .put("viewHandle", viewHandle),
    ).optBoolean("closed", false)
  }

  /** Moves one private item into Core's recycle bin using an idempotent command. */
  suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult =
    runDetailCommand("TrashPrivateMedia", mediaId, java.util.UUID.randomUUID().toString())
      .let { payload ->
        PrivateMediaTrashResult(
          mediaId = payload.getString("mediaId"),
          outcome = payload.getString("outcome"),
          trashedAt = payload.getString("trashedAt"),
        )
      }

  /** Records the platform asset created after Android saves a verified resource to an album. */
  override suspend fun record(
    mediaId: String,
    resourceId: String,
    platformAssetRef: String,
  ): PrivateMediaSaveResult = runDetailCommand("RecordPrivateMediaSystemSave", mediaId) {
    put("resourceId", resourceId)
    put("platformAssetRef", platformAssetRef)
  }.toPrivateMediaSaveResult()

  /** Reads the current cached page without starting network or file effects. */
  fun getPrivateMediaPage(limit: Int = 50): PrivateMediaPage? {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", CONTRACT_VERSION)
          .put("type", "GetPrivateMediaPage")
          .put("limit", limit.coerceIn(1, 100))
          .toString(),
      ),
    )
    return payload.optJSONObject("snapshot")?.toPrivateMediaPage()
  }

  /** Runs a paged refresh command and maps Core's terminal result or stable problem envelope. */
  private suspend fun run(type: String, limit: Int, allowCached: Boolean): PrivateMediaPage {
    val terminal = runner.run(
      core.startOperation(
        operationIds.getAndIncrement(),
        JSONObject()
          .put("contractVersion", CONTRACT_VERSION)
          .put("type", type)
          .put("limit", limit.coerceIn(1, 100))
          .put("allowCached", allowCached)
          .toString(),
      ),
    )
    // A page becomes visible only after Core declares the whole refresh operation complete.
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(terminal.resultJson ?: "{}").toPrivateMediaPage()
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw CoreProblem(
          error.optString("code", "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE"),
          error.optString("messageKey", "private_media.resource_unavailable"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending private-media operation")
    }
  }

  /** Builds a media-scoped command after enforcing the UUID boundary contract. */
  private suspend fun runDetailCommand(
    type: String,
    mediaId: String,
    idempotencyKey: String? = null,
    extra: JSONObject.() -> Unit = {},
  ): JSONObject {
    require(mediaId.matches(UUID_PATTERN)) { "mediaId must be a UUID" }
    val command = JSONObject()
      .put("contractVersion", CONTRACT_VERSION)
      .put("type", type)
      .put("mediaId", mediaId)
    idempotencyKey?.let { command.put("idempotencyKey", it) }
    command.extra()
    return runCommand(command)
  }

  /** Drives a prepared command through the shared effect runner. */
  private suspend fun runCommand(command: JSONObject): JSONObject {
    val terminal = runner.run(core.startOperation(operationIds.getAndIncrement(), command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(terminal.resultJson ?: "{}")
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw CoreProblem(
          error.optString("code", "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE"),
          error.optString("messageKey", "private_media.resource_unavailable"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending private-media operation")
    }
  }

  /** Maps an optional Core page snapshot while preserving its opaque cursor. */
  private fun JSONObject.toPrivateMediaPage(): PrivateMediaPage {
    val values = optJSONArray("items")
    val items = List(values?.length() ?: 0) { index ->
      values!!.getJSONObject(index).toPrivateMediaSummary()
    }
    return PrivateMediaPage(
      items = items,
      nextCursor = optString("nextCursor").takeIf(String::isNotBlank),
      fullyLoaded = optBoolean("fullyLoaded", false),
      refreshedAt = optString("refreshedAt").takeIf(String::isNotBlank),
    )
  }

  /** Maps timeline metadata for one private-media item. */
  private fun JSONObject.toPrivateMediaSummary() = PrivateMediaSummary(
    id = getString("id"),
    mediaType = getString("mediaType"),
    capturedAt = getString("capturedAt"),
    createdAt = getString("createdAt"),
    durationMs = if (isNull("durationMs")) null else getLong("durationMs"),
    originalTotalSize = getLong("originalTotalSize"),
    previewResource = optJSONObject("previewResource")?.run {
      PrivateMediaResourceSummary(
        resourceId = getString("resourceId"),
        resourceType = getString("resourceType"),
        mimeType = getString("mimeType"),
        contentSize = getLong("contentSize"),
        contentSha256 = getString("contentSha256"),
      )
    },
    contentRevision = getInt("contentRevision"),
    localPlatformAssetRef = nullableString("localPlatformAssetRef"),
    localSourceUri = nullableString("localSourceUri"),
  )

  /** Maps detail metadata and every resource hash used for verified access. */
  private fun JSONObject.toPrivateMediaDetail() = PrivateMediaDetail(
    id = getString("id"),
    mediaType = getString("mediaType"),
    capturedAt = getString("capturedAt"),
    createdAt = getString("createdAt"),
    width = if (isNull("width")) null else getInt("width"),
    height = if (isNull("height")) null else getInt("height"),
    durationMs = if (isNull("durationMs")) null else getLong("durationMs"),
    originalTotalSize = getLong("originalTotalSize"),
    resources = List(getJSONArray("resources").length()) { index ->
      getJSONArray("resources").getJSONObject(index).run {
        PrivateMediaResourceSummary(
          resourceId = getString("resourceId"),
          resourceType = getString("resourceType"),
          mimeType = getString("mimeType"),
          contentSize = getLong("contentSize"),
          contentSha256 = getString("contentSha256"),
        )
      }
    },
    contentRevision = getInt("contentRevision"),
    localPlatformAssetRef = nullableString("localPlatformAssetRef"),
    localSourceUri = nullableString("localSourceUri"),
  )

  /** Reads a nullable string without leaking JSON null or blank values into models. */
  private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

  /** Maps Core's acknowledgement of a successful system-album save receipt. */
  private fun JSONObject.toPrivateMediaSaveResult() = PrivateMediaSaveResult(
    mediaId = getString("mediaId"),
    state = getString("state"),
    savedResourceCount = optInt("savedResourceCount", 0),
  )

  private companion object {
    const val CONTRACT_VERSION = "stage05-v1"
    val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val VIEW_HANDLE_PATTERN = Regex("^[A-Za-z0-9._:-]{8,256}$")
  }
}
