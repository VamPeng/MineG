/** Verified private-media save workflow coordinating cache, MediaStore and Core receipt. */
package com.mineg.mobile.feature.private_media

import com.mineg.mobile.bridge.media.model.PrivateMediaDetail
import com.mineg.mobile.bridge.media.model.PrivateMediaResourceSummary
import com.mineg.mobile.bridge.media.model.PrivateMediaSaveResult
import com.mineg.mobile.platform.port.SystemAlbumSource
import com.mineg.mobile.platform.port.SystemAlbumWriteRequest
import com.mineg.mobile.platform.port.SystemAlbumWriterPort
import com.mineg.mobile.platform.PrivateOriginalDiskStore
import java.io.File

internal interface PrivateMediaSaveReceiptRecorder {
  /** Persists the system-album asset reference after Android completes a verified save. */
  suspend fun record(mediaId: String, resourceId: String, platformAssetRef: String): PrivateMediaSaveResult
}

/** Coordinates verified original reuse, system-album write and durable Core receipt recording. */
internal class PrivateMediaLocalSaver(
  private val privateOriginals: PrivateOriginalDiskStore,
  private val album: SystemAlbumWriterPort,
  private val receiptRecorder: PrivateMediaSaveReceiptRecorder,
) {
  /** Saves the eligible original and returns Core's completed receipt state. */
  suspend fun save(userId: String, detail: PrivateMediaDetail): PrivateMediaSaveResult {
    detail.localPlatformAssetRef?.takeIf(album::isSystemAlbumEntryPresent)?.let {
      return completeAfterCacheRemoval(userId, detail.id)
    }
    val original = detail.resources.singleOrNull {
      it.resourceType == "ORIGINAL" && it.mimeType.startsWith("image/")
    } ?: return PrivateMediaSaveResult(detail.id, "PRIVATE_MEDIA_ORIGINAL_NOT_READY", 0)
    val cache = privateOriginals.get(userId, detail.id, original.contentSize, original.contentSha256)
      ?: return PrivateMediaSaveResult(detail.id, "PRIVATE_MEDIA_ORIGINAL_NOT_READY", 0)
    val saved = album.writeVerifiedMedia(cacheRequest(cache, detail, original))
    try {
      // A failed Core receipt rolls back MediaStore so local and durable state cannot diverge.
      receiptRecorder.record(detail.id, original.resourceId, saved.platformAssetRef)
    } catch (failure: Throwable) {
      val rollback = runCatching { album.deleteSystemAlbumEntry(saved.platformAssetRef) }
      val rollbackFailure = rollback.exceptionOrNull()
      if (rollbackFailure != null) {
        rollbackFailure.addSuppressed(failure)
        throw rollbackFailure
      }
      if (!rollback.getOrThrow()) {
        throw IllegalStateException("unable to roll back system-album entry").also {
          it.addSuppressed(failure)
        }
      }
      throw failure
    }
    return completeAfterCacheRemoval(userId, detail.id)
  }

  /** Clears the temporary original only after a complete save receipt. */
  private fun completeAfterCacheRemoval(userId: String, mediaId: String): PrivateMediaSaveResult =
    if (privateOriginals.remove(userId, mediaId)) {
      PrivateMediaSaveResult(mediaId, "COMPLETED", 1)
    } else {
      PrivateMediaSaveResult(mediaId, "PRIVATE_MEDIA_CACHE_CLEANUP_FAILED", 1)
    }

  /** Builds a MediaStore request from an integrity-checked private original. */
  private fun cacheRequest(
    cache: File,
    detail: PrivateMediaDetail,
    original: PrivateMediaResourceSummary,
  ) = SystemAlbumWriteRequest(
    verifiedFilePath = cache.absolutePath,
    displayName = "MineG-${detail.id}.${extensionFor(original.mimeType)}",
    mimeType = original.mimeType,
    capturedAt = detail.capturedAt,
    source = SystemAlbumSource.VERIFIED_PRIVATE_ORIGINAL,
  )

  /** Selects a safe filename extension from the verified MIME type. */
  private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/heic", "image/heif" -> "heic"
    else -> "img"
  }
}
