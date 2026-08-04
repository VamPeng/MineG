package com.mineg.mobile.app

import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaResourceSummary
import com.mineg.mobile.contracts.PrivateMediaSaveResult
import com.mineg.mobile.contracts.SystemAlbumSource
import com.mineg.mobile.contracts.SystemAlbumWriteRequest
import com.mineg.mobile.contracts.SystemAlbumWriterPort
import com.mineg.mobile.platform.PrivateOriginalDiskStore
import java.io.File

internal interface PrivateMediaSaveReceiptRecorder {
  suspend fun record(mediaId: String, resourceId: String, platformAssetRef: String): PrivateMediaSaveResult
}

internal class PrivateMediaLocalSaver(
  private val privateOriginals: PrivateOriginalDiskStore,
  private val album: SystemAlbumWriterPort,
  private val receiptRecorder: PrivateMediaSaveReceiptRecorder,
) {
  private val retainedPlatformAssetRefs = mutableMapOf<String, String>()

  suspend fun save(userId: String, detail: PrivateMediaDetail): PrivateMediaSaveResult {
    val mappingKey = "$userId:${detail.id}"
    listOfNotNull(retainedPlatformAssetRefs[mappingKey], detail.localPlatformAssetRef)
      .distinct()
      .firstOrNull(album::isSystemAlbumEntryPresent)?.let {
      return completeAfterCacheRemoval(userId, detail.id)
    }
    val original = detail.resources.singleOrNull {
      it.resourceType == "ORIGINAL" && it.mimeType.startsWith("image/")
    } ?: return PrivateMediaSaveResult(detail.id, "PRIVATE_MEDIA_ORIGINAL_NOT_READY", 0)
    val cache = privateOriginals.get(userId, detail.id, original.contentSize, original.contentSha256)
      ?: return PrivateMediaSaveResult(detail.id, "PRIVATE_MEDIA_ORIGINAL_NOT_READY", 0)
    val saved = album.writeVerifiedMedia(cacheRequest(cache, detail, original))
    try {
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
    retainedPlatformAssetRefs[mappingKey] = saved.platformAssetRef
    return completeAfterCacheRemoval(userId, detail.id)
  }

  private fun completeAfterCacheRemoval(userId: String, mediaId: String): PrivateMediaSaveResult =
    if (privateOriginals.remove(userId, mediaId)) {
      PrivateMediaSaveResult(mediaId, "COMPLETED", 1)
    } else {
      PrivateMediaSaveResult(mediaId, "PRIVATE_MEDIA_CACHE_CLEANUP_FAILED", 1)
    }

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

  private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/heic", "image/heif" -> "heic"
    else -> "img"
  }
}
