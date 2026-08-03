package com.mineg.mobile.platform

import android.content.Context
import com.mineg.mobile.contracts.FilePort
import java.io.File

class AndroidFilePort(private val context: Context) : FilePort {
  private val taskFilesDirectory: File
    get() = File(context.noBackupFilesDir, "mineg-task-files").also { check(it.isDirectory || it.mkdirs()) }

  override fun createTaskTempFile(name: String): String {
    require(name.matches(Regex("[A-Za-z0-9._-]{1,80}")))
    return File(taskFilesDirectory, "$name.mineg-task").absolutePath
  }

  override fun getAvailableSpace(): Long = taskFilesDirectory.usableSpace

  override fun deleteTempFile(path: String): Boolean {
    val target = File(path).canonicalFile
    val taskFiles = taskFilesDirectory.canonicalFile
    require(target.parentFile == taskFiles) { "Only MineG task files can be removed" }
    return !target.exists() || target.delete()
  }

  fun deleteOrphanedPrivateViewFiles(): Int {
    val candidates = taskFilesDirectory.listFiles().orEmpty().filter { file ->
      file.isFile && file.name.matches(Regex("private-view-[0-9]{1,19}\\.mineg-task"))
    }
    return candidates.count(File::delete)
  }
}
