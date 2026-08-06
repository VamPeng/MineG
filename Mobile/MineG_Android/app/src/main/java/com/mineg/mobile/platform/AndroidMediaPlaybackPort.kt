/** Verified-file playback adapter and temporary URI lifecycle owner. */
package com.mineg.mobile.platform

import android.content.Context
import android.net.Uri
import com.mineg.mobile.platform.port.MediaPlaybackPort
import com.mineg.mobile.platform.port.VerifiedMediaOpenRequest
import com.mineg.mobile.platform.port.VerifiedMediaOpenResult
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Exposes only in-process file URIs for verified Core task files or validated app-private cached
 * thumbnails. Task files are removed when their opaque handle closes; cached files are released
 * without deletion and remain subject to the thumbnail cache's LRU policy.
 */
class AndroidMediaPlaybackPort(context: Context) : MediaPlaybackPort {
  private data class OpenedFile(val file: File, val deleteOnClose: Boolean)

  private val taskFilesDirectory = File(context.noBackupFilesDir, "mineg-task-files")
    .also { check(it.isDirectory || it.mkdirs()) }
    .canonicalFile
  private val openedFiles = ConcurrentHashMap<String, OpenedFile>()

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
    return open(file, deleteOnClose = true)
  }

  /** Opens a persistent cache entry without deleting it when the view closes. */
  internal fun openCachedThumbnail(
    entry: PrivateThumbnailDiskCache.Entry,
  ): VerifiedMediaOpenResult {
    val file = entry.file.canonicalFile
    require(file.isFile && file.length() == entry.byteLength && file.extension == "thumb") {
      "cached thumbnail file is unavailable"
    }
    require(entry.mimeType.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) {
      "cached thumbnail MIME type is invalid"
    }
    return open(file, deleteOnClose = false)
  }

  override fun closeVerifiedMedia(viewHandle: String): Boolean {
    val opened = openedFiles.remove(viewHandle) ?: return false
    return !opened.deleteOnClose || !opened.file.exists() || opened.file.delete()
  }

  /** Creates an opaque view handle and tracks whether the underlying file is disposable. */
  private fun open(file: File, deleteOnClose: Boolean): VerifiedMediaOpenResult {
    val handle = "view-${UUID.randomUUID()}"
    check(openedFiles.putIfAbsent(handle, OpenedFile(file, deleteOnClose)) == null)
    return VerifiedMediaOpenResult(handle, Uri.fromFile(file).toString())
  }
}
