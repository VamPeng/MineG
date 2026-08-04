package com.mineg.mobile.platform

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.mineg.mobile.contracts.SystemAlbumSource
import com.mineg.mobile.contracts.SystemAlbumWriteRequest
import com.mineg.mobile.contracts.SystemAlbumWriteResult
import com.mineg.mobile.contracts.SystemAlbumWriterPort
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant

/** Writes only Core-verified task files into the user's system media collection. */
class AndroidSystemAlbumWriterPort(context: Context) : SystemAlbumWriterPort {
  private val applicationContext = context.applicationContext
  private val resolver = applicationContext.contentResolver
  private val taskFilesDirectory: File
    get() = File(applicationContext.noBackupFilesDir, "mineg-task-files").canonicalFile
  private val privateOriginalsDirectory: File
    get() = File(applicationContext.noBackupFilesDir, "mineg-originals-v1").canonicalFile

  override fun writeVerifiedMedia(request: SystemAlbumWriteRequest): SystemAlbumWriteResult {
    require(request.displayName.length in 1..160 && request.displayName.none {
      it == '/' || it == '\\' || it.code < 0x20
    }) { "invalid media name" }
    val source = File(request.verifiedFilePath).canonicalFile
    val allowedRoot = when (request.source) {
      SystemAlbumSource.VERIFIED_TASK_FILE -> taskFilesDirectory
      SystemAlbumSource.VERIFIED_PRIVATE_ORIGINAL -> privateOriginalsDirectory
    }
    require(source.toPath().startsWith(allowedRoot.toPath())) {
      "system-album source is outside its verified root"
    }
    require(source.isFile && source.length() > 0) { "verified task file is unavailable" }
    val collection = when {
      request.mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      request.mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      else -> throw IllegalArgumentException("unsupported system-album media type")
    }
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, request.displayName)
      put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
      request.capturedAt?.let(::parseCapturedAtMillis)?.let { capturedAtMillis ->
        put(MediaStore.MediaColumns.DATE_TAKEN, capturedAtMillis)
      }
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values) ?: throw IOException("unable to create system-album entry")
    var published = false
    try {
      FileInputStream(source).use { input ->
        resolver.openOutputStream(uri, "w")?.use { output -> input.copyTo(output, 64 * 1024) }
          ?: throw IOException("unable to open system-album entry")
      }
      val publication = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      check(resolver.update(uri, publication, null, null) == 1) { "unable to publish system-album entry" }
      published = true
      return SystemAlbumWriteResult(platformAssetRef(uri))
    } finally {
      if (!published) resolver.delete(uri, null, null)
    }
  }

  override fun isSystemAlbumEntryPresent(platformAssetRef: String): Boolean {
    val id = parsePlatformAssetRef(platformAssetRef) ?: return false
    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
    return resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
  }

  override fun deleteSystemAlbumEntry(platformAssetRef: String): Boolean {
    val id = parsePlatformAssetRef(platformAssetRef) ?: return false
    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
    return resolver.delete(uri, null, null) > 0
  }

  private fun platformAssetRef(uri: Uri): String =
    "android:media-store:${checkNotNull(uri.lastPathSegment).toLong()}"

  private fun parsePlatformAssetRef(value: String): Long? = value
    .removePrefix("android:media-store:")
    .takeIf { value.startsWith("android:media-store:") }
    ?.toLongOrNull()
    ?.takeIf { it > 0 }

  private fun parseCapturedAtMillis(value: String): Long? = try {
    Instant.parse(value).toEpochMilli()
  } catch (_: Exception) {
    null
  }
}
