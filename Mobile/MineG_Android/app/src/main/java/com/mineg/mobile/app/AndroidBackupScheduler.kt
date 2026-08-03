package com.mineg.mobile.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import com.mineg.mobile.contracts.AccountProblem
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

  fun continueQueue(userId: String, allowCellularBackup: Boolean, delayMillis: Long = 0) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      workName(userId),
      ExistingWorkPolicy.APPEND_OR_REPLACE,
      request(userId, allowCellularBackup, delayMillis),
    )
  }

  fun cancel(userId: String) {
    val manager = WorkManager.getInstance(context)
    manager.cancelUniqueWork(workName(userId))
    manager.cancelUniqueWork(periodicWorkName(userId))
  }

  fun cancelPeriodicReconciliation(userId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName(userId))
  }

  internal fun enqueueIfIdle(userId: String, allowCellularBackup: Boolean) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      workName(userId),
      ExistingWorkPolicy.KEEP,
      request(userId, allowCellularBackup),
    )
  }

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

  private fun workName(userId: String): String = "mineg.backup." +
    MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") {
      "%02x".format(it.toInt() and 0xff)
    }

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
  override suspend fun doWork(): Result {
    val userId = inputData.getString("userId")?.takeIf(String::isNotBlank) ?: return Result.failure()
    AndroidBackupScheduler(applicationContext).enqueueIfIdle(
      userId,
      inputData.getBoolean("allowCellularBackup", false),
    )
    return Result.success()
  }
}

class MineGBackupWorker(
  context: Context,
  parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
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
          .firstOrNull { problem -> problem !is AccountProblem || !problem.isLocalResourceUnavailable() }
          ?.let { throw it }
        if (cycles.any { it.getOrNull()?.processed == true }) continue
        if (cycles.any { it.exceptionOrNull() is AccountProblem }) continue
        break
      }
      val settings = runtime.getBackupSettings(userId)
      scheduleQueueContinuation(runtime, userId, settings.allowCellularBackup, settings.autoBackupEnabled)
      Result.success()
    } catch (problem: AccountProblem) {
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

  private fun foregroundInfo(): ForegroundInfo {
    val manager = applicationContext.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "MineG 自动备份", NotificationManager.IMPORTANCE_LOW),
      )
    }
    val notification = Notification.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("MineG 正在备份")
      .setContentText("正在安全上传本地媒体")
      .setOngoing(true)
      .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
    }
  }

  private companion object {
    const val USER_ID = "userId"
    const val MAX_MEDIA_PER_BATCH = 2
    const val EXECUTION_WINDOW_MILLIS = 8 * 60 * 1_000L
    const val CHANNEL_ID = "mineg_backup"
    const val FOREGROUND_NOTIFICATION_ID = 4_004
  }

  private fun AccountProblem.isLocalResourceUnavailable(): Boolean =
    code == "LOCAL_MEDIA_UNAVAILABLE" ||
      code == "LOCAL_MEDIA_READ_FAILED" ||
      code == "BACKUP_LOCAL_RESOURCE_UNAVAILABLE"
}
