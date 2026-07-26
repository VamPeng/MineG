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

  override fun scheduleBackup() {
    val request = OneTimeWorkRequestBuilder<FoundationExecutionWindowWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
      .addTag(WORK_NAME)
      .build()
    workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
  }

  override fun cancelBackup() {
    workManager.cancelUniqueWork(WORK_NAME)
  }

  override fun reportExecutionWindow(): String = WORK_NAME

  private companion object {
    const val WORK_NAME = "mineg.foundation.execution-window"
  }
}

class FoundationExecutionWindowWorker(context: Context, parameters: WorkerParameters) :
  CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result = Result.success()
}
