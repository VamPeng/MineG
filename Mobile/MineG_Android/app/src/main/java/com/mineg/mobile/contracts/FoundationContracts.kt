package com.mineg.mobile.contracts

import java.util.concurrent.atomic.AtomicBoolean

data class ApiRequest(
  val method: String,
  val path: String,
  val body: ByteArray? = null,
  val headers: Map<String, String> = emptyMap(),
)

data class ApiResponse(
  val status: Int,
  val contentType: String,
  val requestId: String?,
  val body: ByteArray,
  val retryAfterSeconds: Long? = null,
)

data class UploadPartRequest(
  val url: String,
  val method: String,
  val headers: Map<String, String>,
  val sourcePath: String? = null,
  val offset: Long,
  val size: Long,
  val sourceDescriptor: Int? = null,
)

data class UploadPartResult(val etag: String)

data class UploadObjectRequest(
  val url: String,
  val method: String,
  val headers: Map<String, String>,
  val body: ByteArray,
)

data class UploadObjectResult(val status: Int)

/** A short-lived object-store read is streamed into a Core-created task file. */
data class DownloadObjectRequest(
  val url: String,
  val method: String,
  val headers: Map<String, String>,
  val destinationPath: String,
  /** Required for registered source objects; omitted for OSS on-the-fly previews. */
  val expectedSize: Long?,
  /** Mandatory hard ceiling for every object download, including dynamic previews. */
  val maximumSize: Long,
)

/** The digest is unpadded standard Base64, matching the service resource manifest. */
data class DownloadObjectResult(
  val status: Int,
  val bytesWritten: Long,
  val sha256Base64: String,
  val contentType: String,
)

enum class LibraryPermissionState { NOT_DETERMINED, FULL, LIMITED, RESTRICTED, DENIED, SYSTEM_RESTRICTED }

data class PermissionSnapshot(val library: LibraryPermissionState)
data class ConnectivitySnapshot(val connected: Boolean, val metered: Boolean)

data class PlatformAlbum(
  val platformAlbumRef: String,
  val name: String,
)

data class PlatformMedia(
  val platformAssetRef: String,
  val platformAlbumRef: String,
  val mediaType: LocalMediaType,
  val mimeType: String,
  val width: Int,
  val height: Int,
  val durationMs: Long?,
  val capturedAt: String,
  val modifiedAt: String,
  val modifiedVersion: Long,
  val contentVersion: String,
  val availability: LocalMediaAvailability,
  val thumbnailUri: String?,
)

data class MediaScanCursor(val modifiedVersion: Long, val platformAssetRef: String)
data class PlatformMediaPage(val items: List<PlatformMedia>, val nextCursor: MediaScanCursor?)

class OpenedMediaResource(
  val platformAssetRef: String,
  val descriptor: Int,
  val byteLength: Long?,
  private val release: () -> Unit,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)

  override fun close() {
    if (closed.compareAndSet(false, true)) release()
  }
}

interface SecureStorePort {
  fun readSecret(name: String): ByteArray?
  fun writeSecret(name: String, value: ByteArray)
  fun deleteSecret(name: String)
  fun readSecrets(names: List<String>): Map<String, ByteArray?> = names.associateWith(::readSecret)
  fun writeSecrets(values: Map<String, ByteArray>) = values.forEach(::writeSecret)
  fun deleteSecrets(names: List<String>) = names.forEach(::deleteSecret)
}

interface TransportPort {
  suspend fun sendApiRequest(request: ApiRequest): ApiResponse
  suspend fun uploadPart(request: UploadPartRequest): UploadPartResult
  suspend fun uploadObject(request: UploadObjectRequest): UploadObjectResult
  suspend fun downloadObject(request: DownloadObjectRequest): DownloadObjectResult
}

interface MediaSourcePort {
  fun getPermissionSnapshot(): PermissionSnapshot
  fun requestFullLibraryAccess(): PermissionSnapshot
  fun listAlbums(): List<PlatformAlbum>
  fun listMedia(cursor: MediaScanCursor?, limit: Int): PlatformMediaPage
  fun openFirstMediaResource(): OpenedMediaResource?
  fun openMediaResource(platformAssetRef: String): OpenedMediaResource?
}

interface BackgroundSchedulerPort {
  fun scheduleBackup()
  fun cancelBackup()
  fun reportExecutionWindow(): String
  fun configureBackup(accountId: String, allowCellularBackup: Boolean)
}

interface ConnectivityPort {
  fun getConnectivitySnapshot(): ConnectivitySnapshot
}

interface FilePort {
  fun createTaskTempFile(name: String): String
  fun getAvailableSpace(): Long
  fun deleteTempFile(path: String): Boolean
}

/** A display-only handle for a Core-verified temporary media file. */
data class VerifiedMediaOpenRequest(
  val verifiedFilePath: String,
  val mimeType: String,
)

data class VerifiedMediaOpenResult(
  val viewHandle: String,
  val sourceUri: String,
)

interface MediaPlaybackPort {
  fun openVerifiedMedia(request: VerifiedMediaOpenRequest): VerifiedMediaOpenResult
  fun closeVerifiedMedia(viewHandle: String): Boolean
}

data class SystemAlbumWriteRequest(
  val verifiedFilePath: String,
  val displayName: String,
  val mimeType: String,
  val capturedAt: String?,
)

data class SystemAlbumWriteResult(val platformAssetRef: String)

/**
 * This port is deliberately limited to consuming a verified task file. It neither requests
 * object grants nor makes domain decisions; Core retains the durable save receipt.
 */
interface SystemAlbumWriterPort {
  fun writeVerifiedMedia(request: SystemAlbumWriteRequest): SystemAlbumWriteResult
  fun isSystemAlbumEntryPresent(platformAssetRef: String): Boolean
  fun deleteSystemAlbumEntry(platformAssetRef: String): Boolean
}
