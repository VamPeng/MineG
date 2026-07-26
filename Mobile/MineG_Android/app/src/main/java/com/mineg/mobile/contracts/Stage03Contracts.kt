package com.mineg.mobile.contracts

enum class BackupTaskState {
  PREPARING, PREPARED, UPLOADING, SERVER_VERIFYING, COMPLETED, RETRYABLE_FAILED, PERMANENT_FAILED,
}

enum class MediaResourceType {
  ORIGINAL, THUMBNAIL, VIDEO_COVER, PREVIEW, LIVE_PHOTO_VIDEO, DYNAMIC_PREVIEW,
}

class DerivedMediaResource(
  val resourceType: MediaResourceType,
  val opened: OpenedMediaResource,
) : AutoCloseable {
  override fun close() = opened.close()
}

data class BackupPart(
  val partNumber: Int,
  val offset: Long,
  val ciphertextSize: Long,
  val ciphertextSHA256: ByteArray,
  val etag: String? = null,
)

data class BackupResource(
  val resourceId: String,
  val resourceType: MediaResourceType,
  val ciphertextPath: String,
  val ciphertextSize: Long,
  val ciphertextSHA256: ByteArray,
  val manifestJson: String,
  val parts: List<BackupPart>,
)

data class SingleMediaBackup(
  val taskId: String,
  val state: BackupTaskState,
  val serverUploadId: String? = null,
  val serverMediaId: String? = null,
  val uploadedParts: Int = 0,
  val partCount: Int = 0,
  val errorCode: String? = null,
)

interface Stage03Client {
  suspend fun backupSingleMedia(userId: String, media: LocalMedia): SingleMediaBackup
  fun getSingleMediaBackup(taskId: String): SingleMediaBackup?
}
