/** Immutable local-library, album, media cursor and backup-setting models. */
package com.mineg.mobile.bridge.library.model

enum class LocalMediaType { PHOTO, VIDEO, GIF, LIVE_PHOTO, DYNAMIC }
enum class LocalMediaAvailability { AVAILABLE, WAITING_LOCAL_RESOURCE, LOCAL_MISSING }
enum class LocalScanStatus { IDLE, SCANNING, COMPLETE, BLOCKED_PERMISSION }

data class BackupSettings(
  val autoBackupEnabled: Boolean = false,
  val allowCellularBackup: Boolean = false,
  val updatedAt: String? = null,
)

data class LocalScanState(
  val cursorModifiedVersion: Long,
  val cursorAssetRef: String,
  val status: LocalScanStatus,
  val indexedCount: Long,
  val scanGeneration: String,
  val updatedAt: String?,
)

data class LocalLibrarySummary(
  val generationId: String,
  val indexedCount: Long,
  val completedAt: String,
)

data class LocalAlbum(
  val platformAlbumRef: String,
  val name: String,
  val mediaCount: Long,
  val coverThumbnailUri: String?,
)

data class LocalMedia(
  val platformAssetRef: String,
  val mediaType: LocalMediaType,
  val mimeType: String,
  val width: Int,
  val height: Int,
  val durationMs: Long?,
  val capturedAt: String,
  val modifiedAt: String,
  val contentVersion: String,
  val availability: LocalMediaAvailability,
  val thumbnailUri: String?,
)

data class AlbumCursor(val name: String, val platformAlbumRef: String)
data class LocalAlbumPage(val items: List<LocalAlbum>, val nextCursor: AlbumCursor?)
data class LocalMediaCursor(val capturedAt: String, val platformAssetRef: String)
data class LocalMediaPage(val items: List<LocalMedia>, val nextCursor: LocalMediaCursor?)
