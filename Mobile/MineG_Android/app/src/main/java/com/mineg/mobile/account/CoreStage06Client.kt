package com.mineg.mobile.account

import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.contracts.FamilyMediaDetail
import com.mineg.mobile.contracts.FamilyMediaOwner
import com.mineg.mobile.contracts.FamilyMediaPage
import com.mineg.mobile.contracts.FamilyMediaSummary
import com.mineg.mobile.contracts.FeedbackSubmissionResult
import com.mineg.mobile.contracts.PrivateMediaResourceSummary
import com.mineg.mobile.contracts.PrivateMediaShareResult
import com.mineg.mobile.contracts.PrivateMediaView
import com.mineg.mobile.contracts.TrashMediaPage
import com.mineg.mobile.contracts.TrashMediaRestoreResult
import com.mineg.mobile.contracts.TrashMediaSummary
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Android's typed entry point for the Stage 06 family, trash, and feedback contract. */
class CoreStage06Client(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(6_000_000_000L)

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

  suspend fun refreshFamilyMedia(
    filter: String = "all",
    cursor: String? = null,
    limit: Int = 50,
  ): FamilyMediaPage {
    require(filter == "all" || filter == "mine") { "family filter is invalid" }
    return runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", if (cursor == null) "RefreshFamilyMedia" else "LoadMoreFamilyMedia")
        .put("filter", filter)
        .put("limit", limit.coerceIn(1, 100))
        .apply { cursor?.takeIf(String::isNotBlank)?.let { put("cursor", it) } },
    ).toFamilyMediaPage()
  }

  suspend fun getFamilyMediaDetail(mediaId: String): FamilyMediaDetail =
    runMediaCommand("GetFamilyMediaDetail", mediaId).toFamilyMediaDetail()

  suspend fun openFamilyMedia(mediaId: String, variant: String = "THUMBNAIL"): PrivateMediaView {
    require(variant == "THUMBNAIL" || variant == "DETAIL") { "unsupported family media view variant" }
    return runMediaCommand("OpenFamilyMedia", mediaId) { put("variant", variant) }.run {
      PrivateMediaView(
        mediaId = getString("mediaId"),
        resourceType = getString("resourceType"),
        mimeType = getString("mimeType"),
        viewHandle = getString("viewHandle"),
        sourceUri = getString("sourceUri"),
      )
    }
  }

  suspend fun closeFamilyMedia(viewHandle: String): Boolean {
    require(viewHandle.matches(VIEW_HANDLE_PATTERN)) { "viewHandle is invalid" }
    return runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "CloseFamilyMedia")
        .put("viewHandle", viewHandle),
    ).optBoolean("closed", false)
  }

  suspend fun refreshTrash(cursor: String? = null, limit: Int = 50): TrashMediaPage =
    runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", if (cursor == null) "RefreshTrashMedia" else "LoadMoreTrashMedia")
        .put("limit", limit.coerceIn(1, 100))
        .apply { cursor?.takeIf(String::isNotBlank)?.let { put("cursor", it) } },
    ).toTrashMediaPage()

  suspend fun restoreTrash(mediaId: String): TrashMediaRestoreResult =
    runMediaCommand("RestoreTrashMedia", mediaId) {
      put("idempotencyKey", UUID.randomUUID().toString())
    }.run {
      TrashMediaRestoreResult(
        mediaId = getString("mediaId"),
        outcome = getString("outcome"),
        restoredAt = getString("restoredAt"),
      )
    }

  suspend fun sendFeedback(
    category: String,
    description: String,
    contact: String,
    appVersion: String,
    osVersion: String,
  ): FeedbackSubmissionResult = runCommand(
    JSONObject()
      .put("contractVersion", CONTRACT_VERSION)
      .put("type", "SubmitFeedback")
      .put("category", category)
      .put("description", description)
      .put("contact", contact)
      .put("appVersion", appVersion)
      .put("osVersion", osVersion)
      .put("idempotencyKey", UUID.randomUUID().toString()),
  ).run {
    FeedbackSubmissionResult(
      feedbackId = getString("feedbackId"),
      outcome = getString("outcome"),
      createdAt = getString("createdAt"),
    )
  }

  private suspend fun runMediaCommand(
    type: String,
    mediaId: String,
    extra: JSONObject.() -> Unit = {},
  ): JSONObject {
    require(mediaId.matches(UUID_PATTERN)) { "mediaId must be a UUID" }
    return runCommand(
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", type)
        .put("mediaId", mediaId)
        .apply(extra),
    )
  }

  private suspend fun runCommand(command: JSONObject): JSONObject {
    val terminal = runner.run(core.startOperation(operationIds.getAndIncrement(), command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(terminal.resultJson ?: "{}")
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw AccountProblem(
          error.optString("code", "STAGE06_UNAVAILABLE"),
          error.optString("messageKey", "stage06.unavailable"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending Stage 06 operation")
    }
  }

  private fun JSONObject.toFamilyMediaPage(): FamilyMediaPage {
    val values = getJSONArray("items")
    return FamilyMediaPage(
      items = List(values.length()) { values.getJSONObject(it).toFamilyMediaSummary() },
      nextCursor = nullableString("nextCursor"),
      fullyLoaded = optBoolean("fullyLoaded", false),
    )
  }

  private fun JSONObject.toFamilyMediaSummary() = FamilyMediaSummary(
    id = getString("id"),
    owner = getJSONObject("owner").toFamilyMediaOwner(),
    mediaType = getString("mediaType"),
    capturedAt = getString("capturedAt"),
    createdAt = getString("createdAt"),
    durationMs = nullableLong("durationMs"),
    originalTotalSize = getLong("originalTotalSize"),
  )

  private fun JSONObject.toFamilyMediaDetail() = FamilyMediaDetail(
    id = getString("id"),
    owner = getJSONObject("owner").toFamilyMediaOwner(),
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

  private fun JSONObject.toFamilyMediaOwner() = FamilyMediaOwner(
    id = getString("id"),
    nickname = getString("nickname"),
  )

  private fun JSONObject.toTrashMediaPage(): TrashMediaPage {
    val values = getJSONArray("items")
    return TrashMediaPage(
      items = List(values.length()) { index ->
        values.getJSONObject(index).run {
          TrashMediaSummary(
            id = getString("id"),
            mediaType = getString("mediaType"),
            capturedAt = getString("capturedAt"),
            createdAt = getString("createdAt"),
            durationMs = nullableLong("durationMs"),
            originalTotalSize = getLong("originalTotalSize"),
            trashedAt = getString("trashedAt"),
          )
        }
      },
      nextCursor = nullableString("nextCursor"),
      fullyLoaded = optBoolean("fullyLoaded", false),
    )
  }

  private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

  private fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else getLong(name)

  private fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)

  private companion object {
    const val CONTRACT_VERSION = "stage06-v1"
    val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val VIEW_HANDLE_PATTERN = Regex("^[A-Za-z0-9._:-]{8,256}$")
  }
}
