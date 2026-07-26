package com.mineg.mobile.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.MediaSourcePort
import com.mineg.mobile.contracts.OpenedMediaResource
import com.mineg.mobile.contracts.PermissionSnapshot

class AndroidMediaSourcePort(private val context: Context) : MediaSourcePort {
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
      else -> LibraryPermissionState.DENIED
    }
    return PermissionSnapshot(state)
  }

  override fun openFirstMediaResource(): OpenedMediaResource? {
    if (getPermissionSnapshot().library != LibraryPermissionState.FULL) return null
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.SIZE)
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
    val arguments = arrayOf(
      MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
      MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    context.contentResolver.query(
      collection,
      projection,
      selection,
      arguments,
      "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
    )?.use { cursor ->
      if (!cursor.moveToFirst()) return null
      val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
      val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)).takeIf { it >= 0 }
      val descriptor = context.contentResolver.openFileDescriptor(ContentUris.withAppendedId(collection, id), "r")
        ?: return null
      val detached = descriptor.detachFd()
      descriptor.close()
      return OpenedMediaResource(
        platformAssetRef = "android:$id",
        descriptor = detached,
        byteLength = size,
        release = { ParcelFileDescriptor.adoptFd(detached).close() },
      )
    }
    return null
  }
}
