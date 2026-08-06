/** WorkManager scheduling and workers for durable Core-owned backup execution. */
package com.mineg.mobile.platform.work

import com.mineg.mobile.runtime.AndroidMineGAppRuntime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mineg.mobile.core.protocol.CoreProblem
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * WorkManager only contributes a constrained execution window. The Core SQLite queue remains
 * the source of truth for pending work, retry time, scan requirements and final upload state.
 */
internal class AndroidBackupScheduler(private val context: Context) {
  /** Ensures one constrained backup chain exists and optionally maintains daily reconciliation. */
  fun schedule(userId: String, allowCellularBackup: Boolean, ensurePeriodicReconciliation: Boolean = false) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      workName(userId),
      // Library loading and MediaStore notifications are frequent.  Replacing a running worker
      // here can leave its Core lease alive while a new worker starts, so normal wake-ups must
      // reuse the existing execution window.  Core remains responsible for queue progress.
      ExistingWorkPolicy.KEEP,
      request(userId, allowCellularBackup),
    )
    if (ensurePeriodicReconciliation) schedulePeriodicReconciliation(userId, allowCellularBackup)
  }

  /** Appends a queue continuation after an optional Core-derived retry delay. */
  fun continueQueue(userId: String, allowCellularBackup: Boolean, delayMillis: Long = 0) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      workName(userId),
      ExistingWorkPolicy.APPEND_OR_REPLACE,
      request(userId, allowCellularBackup, delayMillis),
    )
  }

  /** Cancels both immediate and periodic work for one account. */
  fun cancel(userId: String) {
    val manager = WorkManager.getInstance(context)
    manager.cancelUniqueWork(workName(userId))
    manager.cancelUniqueWork(periodicWorkName(userId))
  }

  /** Cancels only the periodic queue-reconciliation check. */
  fun cancelPeriodicReconciliation(userId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName(userId))
  }

  /** Starts the normal unique chain only when no chain is already active. */
  internal fun enqueueIfIdle(userId: String, allowCellularBackup: Boolean) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      workName(userId),
      ExistingWorkPolicy.KEEP,
      request(userId, allowCellularBackup),
    )
  }

  /** Builds one network-constrained backup request for an opaque account input. */
  private fun request(userId: String, allowCellularBackup: Boolean, delayMillis: Long = 0) =
    OneTimeWorkRequestBuilder<MineGBackupWorker>()
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(if (allowCellularBackup) NetworkType.CONNECTED else NetworkType.UNMETERED)
          .build(),
      )
      .setInputData(Data.Builder().putString(USER_ID, userId).build())
      .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
      .addTag(WORK_TAG)
      .build()

  /** Maintains the daily wake-up that asks the normal chain to reconcile the library. */
  private fun schedulePeriodicReconciliation(userId: String, allowCellularBackup: Boolean) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      periodicWorkName(userId),
      ExistingPeriodicWorkPolicy.UPDATE,
      PeriodicWorkRequestBuilder<MineGPeriodicBackupKickWorker>(PERIODIC_RECONCILIATION_CHECK_HOURS, TimeUnit.HOURS)
        .setConstraints(
          Constraints.Builder()
            .setRequiredNetworkType(if (allowCellularBackup) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build(),
        )
        .setInputData(
          Data.Builder()
            .putString(USER_ID, userId)
            .putBoolean("allowCellularBackup", allowCellularBackup)
            .build(),
        )
        .addTag(WORK_TAG)
        .build(),
    )
  }

  /** Derives a WorkManager name without persisting the raw account identifier in tags. */
  private fun workName(userId: String): String = "mineg.backup." +
    MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") {
      "%02x".format(it.toInt() and 0xff)
    }

  /** Derives the periodic work name from the same account-scoped hash. */
  private fun periodicWorkName(userId: String): String = workName(userId) + ".reconcile"

  private companion object {
    const val WORK_TAG = "mineg.backup"
    const val USER_ID = "userId"
    const val PERIODIC_RECONCILIATION_CHECK_HOURS = 24L
  }
}

/**
 * A periodic check never runs the queue itself. It only requests the normal unique chain when
 * that chain is idle, so a periodic wake-up cannot overlap an active scan or upload window.
 */
class MineGPeriodicBackupKickWorker(
  context: Context,
  parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
  /** Requests the normal backup chain and never executes Core queue work itself. */
  override suspend fun doWork(): Result {
    val userId = inputData.getString("userId")?.takeIf(String::isNotBlank) ?: return Result.failure()
    AndroidBackupScheduler(applicationContext).enqueueIfIdle(
      userId,
      inputData.getBoolean("allowCellularBackup", false),
    )
    return Result.success()
  }
}

/** Owns one bounded foreground execution window for the durable Core backup queue. */
class MineGBackupWorker(
  context: Context,
  parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
  /** Restores account authority, drains bounded cycles and schedules Core-derived continuation. */
  override suspend fun doWork(): Result {
    val userId = inputData.getString(USER_ID)?.takeIf(String::isNotBlank) ?: return Result.failure()
    val runtime = AndroidMineGAppRuntime(applicationContext)
    return try {
      val session = runtime.restoreSession()
      if (session?.userId != userId || session.approvalStatus.name != "APPROVED") return Result.failure()
      // Large scans and uploads must explicitly own a foreground execution window.  WorkManager
      // remains only the scheduler; Core still owns all queue and retry decisions.
      val initialSettings = runtime.getBackupSettings(userId)
      val initialSummary = runtime.getBackupQueueSummary(userId)
      if (!initialSettings.autoBackupEnabled && initialSummary.manualPendingCount == 0) return Result.success()
      if (initialSettings.autoBackupEnabled && !initialSummary.reconciliationRequired &&
        initialSummary.runnableCount == 0 && initialSummary.waitingNetworkCount == 0 &&
        initialSummary.earliestNextRetryAt == null) {
        return Result.success()
      }
      setForeground(foregroundInfo())
      if (initialSettings.autoBackupEnabled && initialSummary.reconciliationRequired) {
        runtime.reconcileBackupQueue(userId)
      }

      // Drain several bounded two-media batches in one foreground window. Recreating a Worker
      // after every pair causes avoidable process/runtime churn and used to repeat the scan above.
      val deadlineElapsedMillis = SystemClock.elapsedRealtime() + EXECUTION_WINDOW_MILLIS
      while (SystemClock.elapsedRealtime() < deadlineElapsedMillis) {
        // A missing local URI is persisted in Core as WAITING_RESOURCE.  It is a per-media gate,
        // not a failure of the entire queue: keep the sibling cycle alive and continue with the
        // next runnable media.  Other errors retain the existing worker-level handling below.
        val cycles = supervisorScope {
          List(MAX_MEDIA_PER_BATCH) { async { runCatching { runtime.runBackupCycle(userId) } } }.awaitAll()
        }
        cycles.mapNotNull { it.exceptionOrNull() }
          .firstOrNull { problem -> problem !is CoreProblem || !problem.isLocalResourceUnavailable() }
          ?.let { throw it }
        if (cycles.any { it.getOrNull()?.processed == true }) continue
        if (cycles.any { it.exceptionOrNull() is CoreProblem }) continue
        break
      }
      val settings = runtime.getBackupSettings(userId)
      scheduleQueueContinuation(runtime, userId, settings.allowCellularBackup, settings.autoBackupEnabled)
      Result.success()
    } catch (problem: CoreProblem) {
      if (!problem.retryable) return Result.failure()
      val settings = runCatching { runtime.getBackupSettings(userId) }.getOrNull()
      if (settings != null) {
        runCatching {
          scheduleQueueContinuation(runtime, userId, settings.allowCellularBackup, settings.autoBackupEnabled)
        }
      }
      // Core already stored the precise retry time. Returning success avoids WorkManager replacing
      // that queue-owned policy with its own opaque backoff schedule.
      Result.success()
    } catch (_: Throwable) {
      Result.retry()
    } finally {
      runtime.close()
    }
  }

  /** Translates Core queue counters and retry time into the next unique WorkManager request. */
  private suspend fun scheduleQueueContinuation(
    runtime: AndroidMineGAppRuntime,
    userId: String,
    allowCellularBackup: Boolean,
    automaticBackupEnabled: Boolean,
  ) {
    val summary = runtime.getBackupQueueSummary(userId)
    if (!automaticBackupEnabled && summary.manualPendingCount == 0) return
    val runnableCount = if (automaticBackupEnabled) summary.runnableCount else summary.manualRunnableCount
    val waitingNetworkCount = if (automaticBackupEnabled) summary.waitingNetworkCount else summary.manualWaitingNetworkCount
    val nextRetryAt = if (automaticBackupEnabled) summary.earliestNextRetryAt else summary.manualEarliestNextRetryAt
    val delayMillis = when {
      automaticBackupEnabled && summary.reconciliationRequired -> 0L
      runnableCount > 0 || waitingNetworkCount > 0 -> 0L
      nextRetryAt != null -> Duration.between(Instant.now(), nextRetryAt)
        .toMillis()
        .coerceAtLeast(0)
      else -> return
    }
    AndroidBackupScheduler(applicationContext).continueQueue(userId, allowCellularBackup, delayMillis)
  }

  /** Creates the low-priority data-sync notification required for long media work. */
  private fun foregroundInfo(): ForegroundInfo {
    val manager = applicationContext.getSystemService(NotificationManager::class.java)
    // minSdk 29 guarantees notification channels and the data-sync foreground-service type.
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "MineG 自动备份", NotificationManager.IMPORTANCE_LOW),
    )
    val notification = Notification.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("MineG 正在备份")
      .setContentText("正在安全上传本地媒体")
      .setOngoing(true)
      .build()
    return ForegroundInfo(
      FOREGROUND_NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private companion object {
    const val USER_ID = "userId"
    const val MAX_MEDIA_PER_BATCH = 2
    const val EXECUTION_WINDOW_MILLIS = 8 * 60 * 1_000L
    const val CHANNEL_ID = "mineg_backup"
    const val FOREGROUND_NOTIFICATION_ID = 4_004
  }

  /** Identifies per-item local-resource failures that must not abort sibling queue work. */
  private fun CoreProblem.isLocalResourceUnavailable(): Boolean =
    code == "LOCAL_MEDIA_UNAVAILABLE" ||
      code == "LOCAL_MEDIA_READ_FAILED" ||
      code == "BACKUP_LOCAL_RESOURCE_UNAVAILABLE"
}
