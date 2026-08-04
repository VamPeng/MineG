package com.mineg.mobile.account

import com.mineg.mobile.app.PrivateMediaSaveReceiptRecorder
import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.contracts.PrivateMediaPage
import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaView
import com.mineg.mobile.contracts.PrivateMediaResourceSummary
import com.mineg.mobile.contracts.PrivateMediaSummary
import com.mineg.mobile.contracts.PrivateMediaTrashResult
import com.mineg.mobile.contracts.PrivateMediaSaveOperation
import com.mineg.mobile.contracts.PrivateMediaSaveResource
import com.mineg.mobile.contracts.PrivateMediaSaveResult
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** The only Android entry point for the Stage 05 private-media page cache. */
class CoreStage05Client(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) : PrivateMediaSaveReceiptRecorder {
  private val operationIds = AtomicLong(5_000_000_000L)

  suspend fun refreshPrivateMedia(limit: Int = 50, allowCached: Boolean = true): PrivateMediaPage =
    run("RefreshPrivateMedia", limit, allowCached)

  suspend fun loadMorePrivateMedia(limit: Int = 50, allowCached: Boolean = false): PrivateMediaPage =
    run("LoadMorePrivateMedia", limit, allowCached)

  suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail =
    runDetailCommand("GetPrivateMediaDetail", mediaId).toPrivateMediaDetail()

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

  suspend fun closePrivateMedia(viewHandle: String): Boolean {
    require(viewHandle.matches(VIEW_HANDLE_PATTERN)) { "viewHandle is invalid" }
    return runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "ClosePrivateMedia")
        .put("viewHandle", viewHandle),
    ).optBoolean("closed", false)
  }

  suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult =
    runDetailCommand("TrashPrivateMedia", mediaId, java.util.UUID.randomUUID().toString())
      .let { payload ->
        PrivateMediaTrashResult(
          mediaId = payload.getString("mediaId"),
          outcome = payload.getString("outcome"),
          trashedAt = payload.getString("trashedAt"),
        )
      }

  override suspend fun record(
    mediaId: String,
    resourceId: String,
    platformAssetRef: String,
  ): PrivateMediaSaveResult = runDetailCommand("RecordPrivateMediaSystemSave", mediaId) {
    put("resourceId", resourceId)
    put("platformAssetRef", platformAssetRef)
  }.toPrivateMediaSaveResult()

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

  fun getPrivateMediaSaveOperation(mediaId: String): PrivateMediaSaveOperation? {
    require(mediaId.matches(UUID_PATTERN)) { "mediaId must be a UUID" }
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", CONTRACT_VERSION)
          .put("type", "GetPrivateMediaSaveOperation")
          .put("mediaId", mediaId)
          .toString(),
      ),
    )
    return payload.optJSONObject("snapshot")?.toPrivateMediaSaveOperation()
  }

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
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(terminal.resultJson ?: "{}").toPrivateMediaPage()
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw AccountProblem(
          error.optString("code", "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE"),
          error.optString("messageKey", "private_media.resource_unavailable"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending private-media operation")
    }
  }

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

  private suspend fun runCommand(command: JSONObject): JSONObject {
    val terminal = runner.run(core.startOperation(operationIds.getAndIncrement(), command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(terminal.resultJson ?: "{}")
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw AccountProblem(
          error.optString("code", "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE"),
          error.optString("messageKey", "private_media.resource_unavailable"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending private-media operation")
    }
  }

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
    localPlatformAssetRef = nullableString("localPlatformAssetRef"),
    localSourceUri = nullableString("localSourceUri"),
  )

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
    localPlatformAssetRef = nullableString("localPlatformAssetRef"),
    localSourceUri = nullableString("localSourceUri"),
  )

  private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

  private fun JSONObject.toPrivateMediaSaveResult() = PrivateMediaSaveResult(
    mediaId = getString("mediaId"),
    state = getString("state"),
    savedResourceCount = optInt("savedResourceCount", 0),
  )

  private fun JSONObject.toPrivateMediaSaveOperation() = PrivateMediaSaveOperation(
    operationId = getString("operationId"),
    mediaId = getString("mediaId"),
    state = getString("state"),
    failureCode = optString("failureCode").takeIf(String::isNotBlank),
    retryCount = getInt("retryCount"),
    updatedAt = getString("updatedAt"),
    resources = List(getJSONArray("resources").length()) { index ->
      getJSONArray("resources").getJSONObject(index).run {
        PrivateMediaSaveResource(getString("resourceId"), getString("state"))
      }
    },
  )

  private companion object {
    const val CONTRACT_VERSION = "stage05-v1"
    val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val VIEW_HANDLE_PATTERN = Regex("^[A-Za-z0-9._:-]{8,256}$")
  }
}
