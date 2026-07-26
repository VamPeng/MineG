package com.mineg.mobile.contracts

enum class KeyGrantKind { FAMILY_BOOTSTRAP, MEMBER_GRANT }
enum class LocalMediaType { PHOTO, VIDEO, GIF, LIVE_PHOTO, DYNAMIC }
enum class LocalMediaAvailability { AVAILABLE, WAITING_LOCAL_RESOURCE, LOCAL_MISSING }
enum class LocalScanStatus { IDLE, SCANNING, COMPLETE, BLOCKED_PERMISSION }

data class KeyMaterial(
  val publicKey: ByteArray,
  val encryptedKeyBundle: ByteArray,
  val kdfParameters: String,
  val bundleVersion: Int,
  val familyEnvelope: ByteArray?,
)

data class PendingKeyGrant(
  val id: String,
  val userId: String,
  val familyId: String,
  val kind: KeyGrantKind,
  val recipientPublicKey: ByteArray,
  val bundleVersion: Int,
  val createdAt: String,
)

data class BackupSettings(
  val autoBackupEnabled: Boolean = true,
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

interface Stage02Client {
  suspend fun updateProfile(nickname: String): Profile
  suspend fun updateAvatar(displayBytes: ByteArray, sourceSize: Long, width: Int, height: Int, contentType: String = "image/webp"): Profile
  suspend fun getKeyBundle(): KeyMaterial
  suspend fun completeFamilyKeyGrant(password: ByteArray?): Boolean
  fun getBackupSettings(userId: String, deviceInstallationId: String): BackupSettings
  fun updateBackupSettings(userId: String, deviceInstallationId: String, settings: BackupSettings)
  fun scanLocalMedia(userId: String): LocalScanState
  fun listLocalAlbums(userId: String, cursor: AlbumCursor? = null, limit: Int = 50): LocalAlbumPage
  fun listLocalMedia(userId: String, albumRef: String?, cursor: LocalMediaCursor? = null, limit: Int = 60): LocalMediaPage
}
