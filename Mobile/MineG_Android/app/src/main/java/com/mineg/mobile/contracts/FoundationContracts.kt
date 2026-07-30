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
}

interface MediaSourcePort {
  fun getPermissionSnapshot(): PermissionSnapshot
  fun requestFullLibraryAccess(): PermissionSnapshot
  fun listAlbums(): List<PlatformAlbum>
  fun listMedia(cursor: MediaScanCursor?, limit: Int): PlatformMediaPage
  fun openFirstMediaResource(): OpenedMediaResource?
  fun openMediaResource(platformAssetRef: String): OpenedMediaResource?
  fun createDerivedMediaResources(platformAssetRef: String, mediaType: LocalMediaType): List<DerivedMediaResource>
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

interface MediaPlaybackPort
interface SystemAlbumWriterPort
