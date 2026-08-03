package com.mineg.mobile.platform

import android.content.Context
import android.net.Uri
import com.mineg.mobile.contracts.MediaPlaybackPort
import com.mineg.mobile.contracts.VerifiedMediaOpenRequest
import com.mineg.mobile.contracts.VerifiedMediaOpenResult
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Exposes only in-process file URIs for verified Core task files. The file remains owned by this
 * port and is removed when its opaque handle is closed; no object-store address is retained.
 */
class AndroidMediaPlaybackPort(context: Context) : MediaPlaybackPort {
  private val taskFilesDirectory = File(context.noBackupFilesDir, "mineg-task-files")
    .also { check(it.isDirectory || it.mkdirs()) }
    .canonicalFile
  private val openedFiles = ConcurrentHashMap<String, File>()

  override fun openVerifiedMedia(request: VerifiedMediaOpenRequest): VerifiedMediaOpenResult {
    require(request.mimeType.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) {
      "verified media MIME type is invalid"
    }
    val file = File(request.verifiedFilePath).canonicalFile
    require(file.parentFile == taskFilesDirectory && file.isFile && file.length() > 0L) {
      "verified media file is outside the task directory"
    }
    require(file.name.matches(Regex("private-view-[0-9]{1,19}\\.mineg-task"))) {
      "verified media file name is invalid"
    }
    val handle = "view-${UUID.randomUUID()}"
    check(openedFiles.putIfAbsent(handle, file) == null)
    return VerifiedMediaOpenResult(handle, Uri.fromFile(file).toString())
  }

  override fun closeVerifiedMedia(viewHandle: String): Boolean {
    val file = openedFiles.remove(viewHandle) ?: return false
    return !file.exists() || file.delete()
  }
}
