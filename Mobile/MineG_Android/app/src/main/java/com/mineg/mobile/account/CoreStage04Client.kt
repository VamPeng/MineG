package com.mineg.mobile.account

import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import org.json.JSONObject

data class BackupCycleResult(
  val processed: Boolean,
  val waitingForNetwork: Boolean,
)

data class BackupOverview(
  val state: String,
  val autoBackupEnabled: Boolean,
  val allowCellularBackup: Boolean,
  val discoveredCount: Int,
  val pendingCount: Int,
  val completedCount: Int,
  val failedCount: Int,
  val currentMediaRef: String? = null,
  val confirmedBytes: Long = 0,
  val transferredBytes: Long = 0,
  val totalBytes: Long = 0,
)

data class LocalAlbumBackupProgress(
  val completedCount: Int,
  val totalCount: Int,
  val mediaStates: Map<String, String> = emptyMap(),
)

data class BackupQueueSummary(
  val runnableCount: Int,
  val waitingNetworkCount: Int,
  val earliestNextRetryAt: Instant?,
  val manualPendingCount: Int,
  val manualRunnableCount: Int,
  val manualWaitingNetworkCount: Int,
  val manualEarliestNextRetryAt: Instant?,
  val reconciliationRequired: Boolean,
  val scheduleRequested: Boolean,
)

class CoreStage04Client(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(4_000_000_000L)

  suspend fun reconcileBackupQueue(userId: String, deviceInstallationId: String) {
    run("ReconcileBackupQueue", userId, deviceInstallationId)
  }

  suspend fun runBackupCycle(userId: String, deviceInstallationId: String): BackupCycleResult {
    val payload = run("RunBackupCycle", userId, deviceInstallationId)
    return BackupCycleResult(
      processed = payload.optBoolean("processed", false),
      waitingForNetwork = payload.optBoolean("waitingForNetwork", false),
    )
  }

  fun getBackupOverview(userId: String, deviceInstallationId: String): BackupOverview {
    val snapshot = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", "stage04-v1")
          .put("type", "GetBackupOverview")
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .toString(),
      ),
    ).getJSONObject("snapshot")
    return BackupOverview(
      state = snapshot.getString("state"),
      autoBackupEnabled = snapshot.getBoolean("autoBackupEnabled"),
      allowCellularBackup = snapshot.getBoolean("allowCellularBackup"),
      discoveredCount = snapshot.getInt("discoveredCount"),
      pendingCount = snapshot.getInt("pendingCount"),
      completedCount = snapshot.getInt("completedCount"),
      failedCount = snapshot.getInt("failedCount"),
      currentMediaRef = snapshot.optString("currentMediaRef").takeIf(String::isNotBlank),
      confirmedBytes = snapshot.optLong("confirmedBytes", 0),
      transferredBytes = snapshot.optLong("transferredBytes", 0),
      totalBytes = snapshot.optLong("totalBytes", 0),
    )
  }

  fun getLocalAlbumBackupProgress(
    userId: String,
    deviceInstallationId: String,
    platformAlbumRef: String,
  ): LocalAlbumBackupProgress {
    val snapshot = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", "stage04-v1")
          .put("type", "GetLocalAlbumBackupProgress")
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .put("platformAlbumRef", platformAlbumRef)
          .toString(),
      ),
    ).getJSONObject("snapshot")
    return LocalAlbumBackupProgress(
      completedCount = snapshot.getInt("completedCount"),
      totalCount = snapshot.getInt("totalCount"),
      mediaStates = buildMap {
        val values = snapshot.optJSONArray("mediaStates") ?: return@buildMap
        repeat(values.length()) { index ->
          val item = values.getJSONObject(index)
          val ref = item.optString("platformAssetRef")
          val state = item.optString("state")
          if (ref.isNotBlank()) {
            put(ref, state)
          }
        }
      },
    )
  }

  fun getBackupQueueSummary(userId: String, deviceInstallationId: String): BackupQueueSummary {
    val summary = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", "stage04-v1")
          .put("type", "GetBackupQueueSummary")
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .toString(),
      ),
    ).getJSONObject("summary")
    return BackupQueueSummary(
      runnableCount = summary.optInt("runnableCount", 0),
      waitingNetworkCount = summary.optInt("waitingNetworkCount", 0),
      earliestNextRetryAt = summary.optString("earliestNextRetryAt")
        .takeIf(String::isNotBlank)
        ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() },
      manualPendingCount = summary.optInt("manualPendingCount", 0),
      manualRunnableCount = summary.optInt("manualRunnableCount", 0),
      manualWaitingNetworkCount = summary.optInt("manualWaitingNetworkCount", 0),
      manualEarliestNextRetryAt = summary.optString("manualEarliestNextRetryAt")
        .takeIf(String::isNotBlank)
        ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() },
      reconciliationRequired = summary.optBoolean("reconciliationRequired", true),
      scheduleRequested = summary.optBoolean("scheduleRequested", false),
    )
  }

  fun notifyLibraryChanged(userId: String, deviceInstallationId: String) {
    core.execute(
      operationIds.getAndIncrement(),
      JSONObject()
        .put("contractVersion", "stage04-v1")
        .put("type", "NotifyLibraryChanged")
        .put("userId", userId)
        .put("deviceInstallationId", deviceInstallationId)
        .toString(),
    )
  }

  fun enqueueBackupMedia(userId: String, deviceInstallationId: String, platformAssetRef: String) {
    require(userId.isNotBlank() && deviceInstallationId.isNotBlank() && platformAssetRef.isNotBlank())
    val response = JSONObject(
      core.execute(
        operationIds.getAndIncrement(),
        JSONObject()
          .put("contractVersion", "stage04-v1")
          .put("type", "EnqueueBackupMedia")
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .put("platformAssetRef", platformAssetRef)
          .toString(),
      ),
    )
    check(response.getString("status") == "SUCCESS")
  }

  private suspend fun run(type: String, userId: String, deviceInstallationId: String): JSONObject {
    require(userId.isNotBlank() && deviceInstallationId.isNotBlank())
    val terminal = runner.run(
      core.startOperation(
        operationIds.getAndIncrement(),
        JSONObject()
          .put("contractVersion", "stage04-v1")
          .put("type", type)
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .toString(),
      ),
    )
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> JSONObject(terminal.resultJson ?: "{}")
      CoreOperationStatus.FAILED -> {
        val error = terminal.errorJson?.let(::JSONObject) ?: JSONObject()
        throw AccountProblem(
          error.optString("code", "BACKUP_SERVICE_UNAVAILABLE"),
          error.optString("messageKey", "account.backup_service_unavailable"),
          error.optBoolean("retryable", false),
          error.optString("requestId"),
        )
      }
      CoreOperationStatus.CANCELLED -> throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending backup operation")
    }
  }
}
