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

enum class LibraryPermissionState { FULL, LIMITED, DENIED, RESTRICTED }

data class PermissionSnapshot(val library: LibraryPermissionState)
data class ConnectivitySnapshot(val connected: Boolean, val metered: Boolean)

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
}

interface TransportPort {
  suspend fun sendApiRequest(request: ApiRequest): ApiResponse
}

interface MediaSourcePort {
  fun getPermissionSnapshot(): PermissionSnapshot
  fun openFirstMediaResource(): OpenedMediaResource?
}

interface BackgroundSchedulerPort {
  fun scheduleBackup()
  fun cancelBackup()
  fun reportExecutionWindow(): String
}

interface ConnectivityPort {
  fun getConnectivitySnapshot(): ConnectivitySnapshot
}

interface FilePort {
  fun createEncryptedTempFile(name: String): String
  fun getAvailableSpace(): Long
  fun deleteTempFile(path: String): Boolean
}

interface MediaPlaybackPort
interface SystemAlbumWriterPort
