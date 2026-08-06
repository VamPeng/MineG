/** Android MediaStore implementation of the device-library source port. */
package com.mineg.mobile.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.mineg.mobile.platform.port.LibraryPermissionState
import com.mineg.mobile.bridge.library.model.LocalMediaAvailability
import com.mineg.mobile.bridge.library.model.LocalMediaType
import com.mineg.mobile.platform.port.MediaScanCursor
import com.mineg.mobile.platform.port.MediaSourcePort
import com.mineg.mobile.platform.port.OpenedMediaResource
import com.mineg.mobile.platform.port.PermissionSnapshot
import com.mineg.mobile.platform.port.PlatformAlbum
import com.mineg.mobile.platform.port.PlatformMedia
import com.mineg.mobile.platform.port.PlatformMediaPage
import java.time.Instant

/** Maps permission, album, media and descriptor APIs into stable platform-port models. */
class AndroidMediaSourcePort(private val context: Context) : MediaSourcePort {
  private val preferences = context.getSharedPreferences("mineg_media_permission", Context.MODE_PRIVATE)

  override fun getPermissionSnapshot(): PermissionSnapshot {
    val granted = { permission: String ->
      ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val state = when {
      Build.VERSION.SDK_INT >= 33 &&
        granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) ->
        LibraryPermissionState.FULL
      Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
        LibraryPermissionState.LIMITED
      Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) ->
        LibraryPermissionState.FULL
      preferences.getBoolean(PERMISSION_REQUESTED, false) -> LibraryPermissionState.DENIED
      else -> LibraryPermissionState.NOT_DETERMINED
    }
    return PermissionSnapshot(state)
  }

  override fun requestFullLibraryAccess(): PermissionSnapshot {
    markPermissionRequested()
    return getPermissionSnapshot()
  }

  /** Persists that the app has already attempted a permission request. */
  fun markPermissionRequested() {
    preferences.edit().putBoolean(PERMISSION_REQUESTED, true).apply()
  }

  /** Observes MediaStore changes until the returned handle is closed. */
  fun observeLibraryChanges(listener: () -> Unit): AutoCloseable {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) = listener()
      override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = listener()
    }
    context.contentResolver.registerContentObserver(COLLECTION, true, observer)
    return AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
  }

  override fun listAlbums(): List<PlatformAlbum> {
    if (getPermissionSnapshot().library != LibraryPermissionState.FULL) return emptyList()
    val projection = arrayOf(
      MediaStore.Files.FileColumns.BUCKET_ID,
      MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
    )
    val result = linkedMapOf<String, PlatformAlbum>()
    context.contentResolver.query(
      COLLECTION,
      projection,
      MEDIA_SELECTION,
      MEDIA_SELECTION_ARGS,
      "${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} ASC",
    )?.use { cursor ->
      val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
      val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
      while (cursor.moveToNext()) {
        val bucketId = cursor.getString(idColumn) ?: continue
        result.putIfAbsent(
          albumRef(bucketId),
          PlatformAlbum(albumRef(bucketId), cursor.getString(nameColumn)?.ifBlank { "未命名相册" } ?: "未命名相册"),
        )
      }
    }
    return result.values.toList()
  }

  override fun listMedia(cursor: MediaScanCursor?, limit: Int): PlatformMediaPage {
    if (getPermissionSnapshot().library != LibraryPermissionState.FULL) return PlatformMediaPage(emptyList(), null)
    val pageSize = limit.coerceIn(1, 500)
    val modifiedColumn = MediaStore.Files.FileColumns.DATE_MODIFIED
    val selection = buildString {
      append("($MEDIA_SELECTION)")
      if (cursor != null) append(" AND ($modifiedColumn > ? OR ($modifiedColumn = ? AND ${MediaStore.Files.FileColumns._ID} > ?))")
    }
    val selectionArgs = MEDIA_SELECTION_ARGS.toMutableList().apply {
      cursor?.let {
        add(it.modifiedVersion.toString())
        add(it.modifiedVersion.toString())
        add(assetId(it.platformAssetRef).toString())
      }
    }.toTypedArray()
    val items = mutableListOf<PlatformMedia>()
    val queryLimit = pageSize + 1
    val mediaCursor = if (Build.VERSION.SDK_INT >= 30) {
      context.contentResolver.query(
        COLLECTION,
        PROJECTION,
        Bundle().apply {
          putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
          putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
          putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(modifiedColumn, MediaStore.Files.FileColumns._ID))
          putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
          putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, queryLimit)
        },
        null,
      )
    } else {
      context.contentResolver.query(
        COLLECTION,
        PROJECTION,
        selection,
        selectionArgs,
        "$modifiedColumn ASC, ${MediaStore.Files.FileColumns._ID} ASC LIMIT $queryLimit",
      )
    }
    mediaCursor?.use { result ->
      while (result.moveToNext() && items.size < queryLimit) {
        val id = result.long(MediaStore.Files.FileColumns._ID)
        val mime = result.string(MediaStore.Files.FileColumns.MIME_TYPE).ifBlank { "application/octet-stream" }
        val mediaType = when {
          mime.equals("image/gif", ignoreCase = true) -> LocalMediaType.GIF
          result.int(MediaStore.Files.FileColumns.MEDIA_TYPE) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> LocalMediaType.VIDEO
          else -> LocalMediaType.PHOTO
        }
        val capturedMillis = result.long(MediaStore.Files.FileColumns.DATE_TAKEN).takeIf { it > 0 }
          ?: result.long(MediaStore.Files.FileColumns.DATE_ADDED).coerceAtLeast(0) * 1000
        val modifiedSeconds = result.long(modifiedColumn).coerceAtLeast(0)
        val bucketId = result.string(MediaStore.Files.FileColumns.BUCKET_ID).ifBlank { "unfiled" }
        val size = result.long(MediaStore.Files.FileColumns.SIZE).coerceAtLeast(0)
        val contentUri = ContentUris.withAppendedId(COLLECTION, id).toString()
        items += PlatformMedia(
          platformAssetRef = assetRef(id),
          platformAlbumRef = albumRef(bucketId),
          mediaType = mediaType,
          mimeType = mime,
          width = result.int(MediaStore.Files.FileColumns.WIDTH).coerceAtLeast(0),
          height = result.int(MediaStore.Files.FileColumns.HEIGHT).coerceAtLeast(0),
          durationMs = result.long(MediaStore.Files.FileColumns.DURATION).takeIf { mediaType == LocalMediaType.VIDEO && it >= 0 },
          capturedAt = Instant.ofEpochMilli(capturedMillis).toString(),
          modifiedAt = Instant.ofEpochSecond(modifiedSeconds).toString(),
          modifiedVersion = modifiedSeconds,
          contentVersion = "$modifiedSeconds:$size",
          availability = LocalMediaAvailability.AVAILABLE,
          thumbnailUri = contentUri,
        )
      }
    }
    val hasMore = items.size > pageSize
    val pageItems = if (hasMore) items.take(pageSize) else items
    val next = if (hasMore && pageItems.isNotEmpty()) {
      pageItems.last().let { MediaScanCursor(it.modifiedVersion, it.platformAssetRef) }
    } else {
      null
    }
    return PlatformMediaPage(pageItems, next)
  }

  override fun openFirstMediaResource(): OpenedMediaResource? {
    val first = listMedia(null, 1).items.firstOrNull() ?: return null
    return openMediaResource(first.platformAssetRef)
  }

  override fun openMediaResource(platformAssetRef: String): OpenedMediaResource? {
    if (getPermissionSnapshot().library != LibraryPermissionState.FULL) return null
    val id = assetId(platformAssetRef)
    if (id <= 0) return null
    val uri = ContentUris.withAppendedId(COLLECTION, id)
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
    val detached = descriptor.detachFd()
    descriptor.close()
    return OpenedMediaResource(
      platformAssetRef = platformAssetRef,
      descriptor = detached,
      byteLength = context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Files.FileColumns.SIZE),
        null,
        null,
        null,
      )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null },
      release = { ParcelFileDescriptor.adoptFd(detached).close() },
    )
  }

  /** Resolves an available MediaStore item and proves it can still be opened for reading. */
  internal fun resolveAvailableMediaUri(platformAssetRef: String): String? {
    if (getPermissionSnapshot().library != LibraryPermissionState.FULL) return null
    val id = assetId(platformAssetRef)
    if (id <= 0) return null
    val uri = ContentUris.withAppendedId(COLLECTION, id)
    return runCatching {
      context.contentResolver.openFileDescriptor(uri, "r")?.use { uri.toString() }
    }.getOrNull()
  }

  /** Reads a required string column from the current cursor row. */
  private fun android.database.Cursor.string(column: String): String =
    getString(getColumnIndexOrThrow(column)).orEmpty()

  /** Reads a required long column from the current cursor row. */
  private fun android.database.Cursor.long(column: String): Long =
    getLong(getColumnIndexOrThrow(column))

  /** Reads a required integer column from the current cursor row. */
  private fun android.database.Cursor.int(column: String): Int =
    getInt(getColumnIndexOrThrow(column))

  /** Builds the stable Android asset reference consumed by Core. */
  private fun assetRef(id: Long) = "android:external:$id"
  /** Builds the stable Android album reference consumed by Core. */
  private fun albumRef(id: String) = "android:bucket:$id"
  /** Extracts the MediaStore row identifier from a validated asset reference. */
  private fun assetId(ref: String): Long = ref.substringAfterLast(':').toLongOrNull() ?: 0

  private companion object {
    const val PERMISSION_REQUESTED = "permissionRequested"
    val COLLECTION = MediaStore.Files.getContentUri("external")
    const val MEDIA_SELECTION = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
    val MEDIA_SELECTION_ARGS = arrayOf(
      MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
      MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    val PROJECTION = arrayOf(
      MediaStore.Files.FileColumns._ID,
      MediaStore.Files.FileColumns.MEDIA_TYPE,
      MediaStore.Files.FileColumns.MIME_TYPE,
      MediaStore.Files.FileColumns.WIDTH,
      MediaStore.Files.FileColumns.HEIGHT,
      MediaStore.Files.FileColumns.DURATION,
      MediaStore.Files.FileColumns.DATE_TAKEN,
      MediaStore.Files.FileColumns.DATE_ADDED,
      MediaStore.Files.FileColumns.DATE_MODIFIED,
      MediaStore.Files.FileColumns.SIZE,
      MediaStore.Files.FileColumns.BUCKET_ID,
      MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
    )
  }
}
