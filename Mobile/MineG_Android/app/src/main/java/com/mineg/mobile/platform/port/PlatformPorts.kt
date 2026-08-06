/**
 * Domain-neutral platform capability interfaces consumed only by the Core effect dispatcher.
 *
 * Implementations may use Android APIs, but these contracts expose no Activity, Context or
 * lifecycle type to Core-facing code.
 */
package com.mineg.mobile.platform.port

import com.mineg.mobile.bridge.library.model.LocalMediaAvailability
import com.mineg.mobile.bridge.library.model.LocalMediaType

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

  /** Releases the underlying descriptor at most once. */
  override fun close() {
    if (closed.compareAndSet(false, true)) release()
  }
}

interface SecureStorePort {
  /** Reads one encrypted value or returns null when absent. */
  fun readSecret(name: String): ByteArray?
  /** Encrypts and atomically stores one value. */
  fun writeSecret(name: String, value: ByteArray)
  /** Removes one encrypted value. */
  fun deleteSecret(name: String)
  /** Reads a bounded set of secrets under one port call. */
  fun readSecrets(names: List<String>): Map<String, ByteArray?> = names.associateWith(::readSecret)
  /** Writes a bounded set of secrets. */
  fun writeSecrets(values: Map<String, ByteArray>) = values.forEach(::writeSecret)
  /** Removes a bounded set of secrets. */
  fun deleteSecrets(names: List<String>) = names.forEach(::deleteSecret)
}

interface TransportPort {
  /** Sends one authenticated service API request. */
  suspend fun sendApiRequest(request: ApiRequest): ApiResponse
  /** Uploads one byte range from a file or descriptor. */
  suspend fun uploadPart(request: UploadPartRequest): UploadPartResult
  /** Uploads one in-memory object body. */
  suspend fun uploadObject(request: UploadObjectRequest): UploadObjectResult
  /** Streams one object into a pre-authorized destination file. */
  suspend fun downloadObject(request: DownloadObjectRequest): DownloadObjectResult
}

interface MediaSourcePort {
  /** Returns current device-library permission without prompting. */
  fun getPermissionSnapshot(): PermissionSnapshot
  /** Requests full device-library access and returns the resulting permission. */
  fun requestFullLibraryAccess(): PermissionSnapshot
  /** Lists platform albums visible under the current permission. */
  fun listAlbums(): List<PlatformAlbum>
  /** Lists a stable page of platform media after [cursor]. */
  fun listMedia(cursor: MediaScanCursor?, limit: Int): PlatformMediaPage
  /** Opens the first available media descriptor for contract diagnostics. */
  fun openFirstMediaResource(): OpenedMediaResource?
  /** Opens a read-only descriptor for one platform asset. */
  fun openMediaResource(platformAssetRef: String): OpenedMediaResource?
}

interface BackgroundSchedulerPort {
  /** Enqueues background backup work. */
  fun scheduleBackup()
  /** Cancels background backup work. */
  fun cancelBackup()
  /** Returns a diagnostic description of the current execution window. */
  fun reportExecutionWindow(): String
  /** Applies account and network policy to background backup. */
  fun configureBackup(accountId: String, allowCellularBackup: Boolean)
}

interface ConnectivityPort {
  /** Returns connectivity and metering state for Core policy evaluation. */
  fun getConnectivitySnapshot(): ConnectivitySnapshot
}

interface FilePort {
  /** Creates a bounded task file in app-private storage. */
  fun createTaskTempFile(name: String): String
  /** Returns usable bytes in the task-file volume. */
  fun getAvailableSpace(): Long
  /** Deletes one task file after verifying it belongs to this port. */
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
  /** Opens a display-only URI for an already verified private file. */
  fun openVerifiedMedia(request: VerifiedMediaOpenRequest): VerifiedMediaOpenResult
  /** Closes the display URI and deletes temporary data when required. */
  fun closeVerifiedMedia(viewHandle: String): Boolean
}

enum class SystemAlbumSource { VERIFIED_TASK_FILE, VERIFIED_PRIVATE_ORIGINAL }

data class SystemAlbumWriteRequest(
  val verifiedFilePath: String,
  val displayName: String,
  val mimeType: String,
  val capturedAt: String?,
  val source: SystemAlbumSource = SystemAlbumSource.VERIFIED_TASK_FILE,
)

data class SystemAlbumWriteResult(val platformAssetRef: String)

/**
 * This port consumes only Core-verified task files or integrity-checked private originals. It
 * neither requests object grants nor makes domain decisions; Core retains the durable save receipt.
 */
interface SystemAlbumWriterPort {
  /** Copies verified media into the platform album and returns its asset reference. */
  fun writeVerifiedMedia(request: SystemAlbumWriteRequest): SystemAlbumWriteResult
  /** Checks whether a previously recorded platform asset still exists. */
  fun isSystemAlbumEntryPresent(platformAssetRef: String): Boolean
  /** Deletes a previously recorded platform asset. */
  fun deleteSystemAlbumEntry(platformAssetRef: String): Boolean
}
