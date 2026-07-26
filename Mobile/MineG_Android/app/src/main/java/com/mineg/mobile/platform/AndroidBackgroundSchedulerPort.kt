package com.mineg.mobile.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mineg.mobile.contracts.BackgroundSchedulerPort

class AndroidBackgroundSchedulerPort(context: Context) : BackgroundSchedulerPort {
  private val workManager = WorkManager.getInstance(context)
  private val preferences = context.getSharedPreferences("mineg_backup_scheduler", Context.MODE_PRIVATE)

  override fun scheduleBackup() {
    val accountId = preferences.getString(ACCOUNT_ID, null) ?: return
    val allowCellular = preferences.getBoolean(ALLOW_CELLULAR, false)
    val workName = "$WORK_PREFIX.$accountId"
    val request = OneTimeWorkRequestBuilder<FoundationExecutionWindowWorker>()
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
          .build(),
      )
      .addTag(workName)
      .addTag(ACCOUNT_TAG_PREFIX + accountId)
      .build()
    workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
  }

  override fun cancelBackup() {
    preferences.getString(ACCOUNT_ID, null)?.let { workManager.cancelUniqueWork("$WORK_PREFIX.$it") }
  }

  override fun reportExecutionWindow(): String =
    preferences.getString(ACCOUNT_ID, null)?.let { "$WORK_PREFIX.$it" } ?: WORK_PREFIX

  override fun configureBackup(accountId: String, allowCellularBackup: Boolean) {
    require(accountId.isNotBlank())
    preferences.edit().putString(ACCOUNT_ID, accountId).putBoolean(ALLOW_CELLULAR, allowCellularBackup).apply()
  }

  private companion object {
    const val WORK_PREFIX = "mineg.backup"
    const val ACCOUNT_TAG_PREFIX = "mineg.account."
    const val ACCOUNT_ID = "accountId"
    const val ALLOW_CELLULAR = "allowCellularBackup"
  }
}

class FoundationExecutionWindowWorker(context: Context, parameters: WorkerParameters) :
  CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result = Result.success()
}
