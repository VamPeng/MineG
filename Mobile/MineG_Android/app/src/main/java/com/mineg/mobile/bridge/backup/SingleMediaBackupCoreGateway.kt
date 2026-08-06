/** Retained single-item backup flow for the frozen `stage03-v2` contract. */
package com.mineg.mobile.bridge.backup

import com.mineg.mobile.core.protocol.CoreProblem
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

data class OriginalMediaUploadResult(
  val uploadId: String,
  val mediaId: String,
  val deduplicated: Boolean,
)

/** Typed gateway for the retained `stage03-v2` single-media backup command. */
class SingleMediaBackupCoreGateway(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(3_000_000_000L)

  /** Uploads one local asset under Core authority and returns its durable media identity. */
  suspend fun backupSingleMedia(userId: String, platformAssetRef: String): OriginalMediaUploadResult {
    require(userId.isNotBlank() && platformAssetRef.isNotBlank())
    val terminal = runner.run(
      core.startOperation(
        operationIds.getAndIncrement(),
        JSONObject()
          .put("contractVersion", "stage03-v2")
          .put("type", "BackupSingleMedia")
          .put("userId", userId)
          .put("platformAssetRef", platformAssetRef)
          .toString(),
      ),
    )
    // The native terminal state is the only source of upload success or retryable failure.
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(checkNotNull(terminal.resultJson)).run {
        OriginalMediaUploadResult(getString("uploadId"), getString("mediaId"), getBoolean("deduplicated"))
      }
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw CoreProblem(
          error.optString("code", "MEDIA_UPLOAD_FAILED"),
          error.optString("messageKey", "account.media_upload_failed"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw CoreProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending upload")
    }
  }
}
