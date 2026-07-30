package com.mineg.mobile.account

import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

data class OriginalMediaUploadResult(
  val uploadId: String,
  val mediaId: String,
  val deduplicated: Boolean,
)

class CoreStage03Client(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(3_000_000_000L)

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
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(checkNotNull(terminal.resultJson)).run {
        OriginalMediaUploadResult(getString("uploadId"), getString("mediaId"), getBoolean("deduplicated"))
      }
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw AccountProblem(
          error.optString("code", "MEDIA_UPLOAD_FAILED"),
          error.optString("messageKey", "account.media_upload_failed"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending upload")
    }
  }
}
