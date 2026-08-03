package com.mineg.mobile.core
import com.mineg.mobile.contracts.ApiRequest
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.contracts.ConnectivityPort
import com.mineg.mobile.contracts.DownloadObjectRequest
import com.mineg.mobile.contracts.FilePort
import com.mineg.mobile.contracts.MediaPlaybackPort
import com.mineg.mobile.contracts.MediaScanCursor
import com.mineg.mobile.contracts.MediaSourcePort
import com.mineg.mobile.contracts.OpenedMediaResource
import com.mineg.mobile.contracts.PlatformEffect
import com.mineg.mobile.contracts.PlatformEffectError
import com.mineg.mobile.contracts.PlatformEffectResult
import com.mineg.mobile.contracts.PlatformEffectResultStatus
import com.mineg.mobile.contracts.PlatformEffectType
import com.mineg.mobile.contracts.SecureStorePort
import com.mineg.mobile.contracts.SystemAlbumWriteRequest
import com.mineg.mobile.contracts.SystemAlbumWriterPort
import com.mineg.mobile.contracts.TransportPort
import com.mineg.mobile.contracts.UploadPartRequest
import com.mineg.mobile.contracts.UploadObjectRequest
import com.mineg.mobile.contracts.VerifiedMediaOpenRequest
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

class PlatformEffectDispatcher(
  private val transport: TransportPort,
  private val secureStore: SecureStorePort,
  private val mediaSource: MediaSourcePort,
  private val files: FilePort,
  private val connectivity: ConnectivityPort,
  private val systemAlbum: SystemAlbumWriterPort? = null,
  private val mediaPlayback: MediaPlaybackPort? = null,
) : AutoCloseable {
  private val openedMediaResources = ConcurrentHashMap<String, OpenedMediaResource>()
  private val openedVerifiedMedia = ConcurrentHashMap.newKeySet<String>()

  suspend fun dispatch(effect: PlatformEffect): PlatformEffectResult = try {
    val payload = JSONObject(effect.payloadJson)
    val result = when (effect.effectType) {
      PlatformEffectType.TRANSPORT -> dispatchTransport(payload)
      PlatformEffectType.SECURE_STORE -> dispatchSecureStore(payload)
      PlatformEffectType.MEDIA_SOURCE -> dispatchMediaSource(effect, payload)
      PlatformEffectType.FILE -> dispatchFile(payload)
      PlatformEffectType.CONNECTIVITY -> dispatchConnectivity(payload)
      PlatformEffectType.MEDIA_PLAYBACK -> dispatchMediaPlayback(payload)
      PlatformEffectType.SYSTEM_ALBUM -> dispatchSystemAlbum(payload)
    }
    PlatformEffectResult(
      operationId = effect.operationId,
      sequence = effect.sequence,
      effectType = effect.effectType,
      status = PlatformEffectResultStatus.SUCCEEDED,
      payloadJson = result.toString(),
    )
  } catch (_: CancellationException) {
    PlatformEffectResult(
      effect.operationId,
      effect.sequence,
      effect.effectType,
      PlatformEffectResultStatus.CANCELLED,
    )
  } catch (error: Exception) {
    PlatformEffectResult(
      operationId = effect.operationId,
      sequence = effect.sequence,
      effectType = effect.effectType,
      status = PlatformEffectResultStatus.FAILED,
      error = PlatformEffectError(
        code = when (error) {
          is IOException -> "PLATFORM_IO_ERROR"
          is SecurityException -> "PLATFORM_PERMISSION_DENIED"
          is IllegalArgumentException -> "INVALID_EFFECT_PAYLOAD"
          else -> "PLATFORM_EFFECT_FAILED"
        },
        retryable = error is IOException,
      ),
    )
  }

  private suspend fun dispatchTransport(payload: JSONObject): JSONObject = when (payload.requireAction()) {
    "sendApiRequest" -> {
      val response = transport.sendApiRequest(
        ApiRequest(
          method = payload.getString("method"),
          path = payload.getString("path"),
          body = payload.optionalBase64("bodyBase64"),
          headers = payload.stringMap("headers"),
        ),
      )
      JSONObject()
        .put("status", response.status)
        .put("contentType", response.contentType)
        .put("requestId", response.requestId ?: JSONObject.NULL)
        .put("retryAfterSeconds", response.retryAfterSeconds ?: JSONObject.NULL)
        .put("bodyBase64", Base64.getEncoder().encodeToString(response.body))
    }
    "uploadPart" -> {
      val response = transport.uploadPart(
        UploadPartRequest(
          url = payload.getString("url"),
          method = payload.getString("method"),
          headers = payload.stringMap("headers"),
          sourcePath = payload.optString("sourcePath").takeIf { payload.has("sourcePath") },
          offset = payload.getLong("offset"),
          size = payload.getLong("size"),
          sourceDescriptor = payload.optInt("sourceDescriptor").takeIf { payload.has("sourceDescriptor") },
        ),
      )
      JSONObject().put("etag", response.etag)
    }
    "uploadParts" -> coroutineScope {
      val parts = payload.getJSONArray("parts")
      require(parts.length() in 1..2)
      val uploads = List(parts.length()) { index ->
        val part = parts.getJSONObject(index)
        async {
          val response = transport.uploadPart(
            UploadPartRequest(
              url = part.getString("url"),
              method = part.getString("method"),
              headers = part.stringMap("headers"),
              sourcePath = part.optString("sourcePath").takeIf { part.has("sourcePath") },
              offset = part.getLong("offset"),
              size = part.getLong("size"),
              sourceDescriptor = part.optInt("sourceDescriptor").takeIf { part.has("sourceDescriptor") },
            ),
          )
          JSONObject().put("partNumber", part.getLong("partNumber")).put("etag", response.etag)
        }
      }.awaitAll()
      JSONObject().put("parts", JSONArray(uploads))
    }
    "uploadObject" -> {
      val body = Base64.getDecoder().decode(payload.getString("bodyBase64"))
      try {
        val response = transport.uploadObject(
          UploadObjectRequest(
            url = payload.getString("url"),
            method = payload.getString("method"),
            headers = payload.stringMap("headers"),
            body = body,
          ),
        )
        JSONObject().put("status", response.status)
      } finally {
        body.fill(0)
      }
    }
    "downloadObject" -> {
      val response = transport.downloadObject(
        DownloadObjectRequest(
          url = payload.getString("url"),
          method = payload.getString("method"),
          headers = payload.stringMap("headers"),
          destinationPath = payload.getString("destinationPath"),
          expectedSize = payload.optLong("expectedSize").takeIf { payload.has("expectedSize") },
          maximumSize = payload.getLong("maximumSize"),
        ),
      )
      JSONObject()
        .put("status", response.status)
        .put("bytesWritten", response.bytesWritten)
        .put("sha256Base64", response.sha256Base64)
        .put("contentType", response.contentType)
    }
    else -> unsupportedAction(payload)
  }

  private fun dispatchSecureStore(payload: JSONObject): JSONObject = when (payload.requireAction()) {
    "readSecret" -> JSONObject().put(
      "valueBase64",
      secureStore.readSecret(payload.getString("name"))
        ?.let { Base64.getEncoder().encodeToString(it) }
        ?: JSONObject.NULL,
    )
    "writeSecret" -> {
      val value = Base64.getDecoder().decode(payload.getString("valueBase64"))
      try {
        secureStore.writeSecret(payload.getString("name"), value)
      } finally {
        value.fill(0)
      }
      JSONObject().put("written", true)
    }
    "deleteSecret" -> {
      secureStore.deleteSecret(payload.getString("name"))
      JSONObject().put("deleted", true)
    }
    "readSecrets" -> {
      val names = payload.getJSONArray("names")
      val nameList = List(names.length(), names::getString)
      val secrets = secureStore.readSecrets(nameList)
      JSONObject().put(
        "values",
        JSONArray(nameList.map { name ->
          val value = secrets[name]
          try {
            JSONObject()
              .put("name", name)
              .put(
                "valueBase64",
                value?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL,
              )
          } finally {
            value?.fill(0)
          }
        }),
      )
    }
    "writeSecrets" -> {
      val values = payload.getJSONArray("values")
      val decoded = buildMap {
        repeat(values.length()) { index ->
        val item = values.getJSONObject(index)
          put(item.getString("name"), Base64.getDecoder().decode(item.getString("valueBase64")))
        }
      }
      try {
        secureStore.writeSecrets(decoded)
      } finally {
        decoded.values.forEach { it.fill(0) }
      }
      JSONObject().put("written", true)
    }
    "deleteSecrets" -> {
      val names = payload.getJSONArray("names")
      secureStore.deleteSecrets(List(names.length(), names::getString))
      JSONObject().put("deleted", true)
    }
    else -> unsupportedAction(payload)
  }

  private fun dispatchMediaSource(effect: PlatformEffect, payload: JSONObject): JSONObject =
    when (payload.requireAction()) {
      "getPermissionSnapshot" -> JSONObject()
        .put("library", mediaSource.getPermissionSnapshot().library.name)
      "requestFullLibraryAccess" -> JSONObject()
        .put("library", mediaSource.requestFullLibraryAccess().library.name)
      "listAlbums" -> JSONObject().put(
        "items",
        JSONArray(mediaSource.listAlbums().map { album ->
          JSONObject().put("platformAlbumRef", album.platformAlbumRef).put("name", album.name)
        }),
      )
      "listMedia" -> {
        val cursor = payload.optJSONObject("cursor")?.let {
          MediaScanCursor(it.getLong("modifiedVersion"), it.getString("platformAssetRef"))
        }
        val page = mediaSource.listMedia(cursor, payload.getInt("limit"))
        JSONObject()
          .put("items", JSONArray(page.items.map { media ->
            JSONObject()
              .put("platformAssetRef", media.platformAssetRef)
              .put("platformAlbumRef", media.platformAlbumRef)
              .put("mediaType", media.mediaType.name)
              .put("mimeType", media.mimeType)
              .put("width", media.width)
              .put("height", media.height)
              .put("durationMs", media.durationMs ?: JSONObject.NULL)
              .put("capturedAt", media.capturedAt)
              .put("modifiedAt", media.modifiedAt)
              .put("modifiedVersion", media.modifiedVersion)
              .put("contentVersion", media.contentVersion)
              .put("availability", media.availability.name)
              .put("thumbnailUri", media.thumbnailUri ?: JSONObject.NULL)
          }))
          .put("nextCursor", page.nextCursor?.let {
            JSONObject()
              .put("modifiedVersion", it.modifiedVersion)
              .put("platformAssetRef", it.platformAssetRef)
          } ?: JSONObject.NULL)
      }
      "openMediaResource" -> {
        val resource = mediaSource.openMediaResource(payload.getString("platformAssetRef"))
        if (resource == null) {
          JSONObject().put("resource", JSONObject.NULL)
        } else {
          val handle = "${effect.operationId}:${effect.sequence}"
          openedMediaResources.put(handle, resource)?.close()
          JSONObject().put(
            "resource",
            JSONObject()
              .put("resourceHandle", handle)
              .put("platformAssetRef", resource.platformAssetRef)
              .put("descriptor", resource.descriptor)
              .put("byteLength", resource.byteLength ?: JSONObject.NULL),
          )
        }
      }
      "releaseMediaResource" -> {
        val released = openedMediaResources.remove(payload.getString("resourceHandle"))?.let {
          it.close()
          true
        } ?: false
        JSONObject().put("released", released)
      }
      else -> unsupportedAction(payload)
    }

  private fun dispatchFile(payload: JSONObject): JSONObject = when (payload.requireAction()) {
    "createTaskTempFile" -> JSONObject()
      .put("path", files.createTaskTempFile(payload.getString("name")))
    "getAvailableSpace" -> JSONObject().put("availableBytes", files.getAvailableSpace())
    "deleteTempFile" -> JSONObject().put("deleted", files.deleteTempFile(payload.getString("path")))
    else -> unsupportedAction(payload)
  }

  private fun dispatchConnectivity(payload: JSONObject): JSONObject = when (payload.requireAction()) {
    "getConnectivitySnapshot" -> connectivity.getConnectivitySnapshot().let { snapshot ->
      JSONObject().put("connected", snapshot.connected).put("metered", snapshot.metered)
    }
    else -> unsupportedAction(payload)
  }

  private fun dispatchMediaPlayback(payload: JSONObject): JSONObject = when (payload.requireAction()) {
    "openVerifiedMedia" -> {
      val response = requireNotNull(mediaPlayback) { "Media playback port is not configured" }.openVerifiedMedia(
        VerifiedMediaOpenRequest(
          verifiedFilePath = payload.getString("verifiedFilePath"),
          mimeType = payload.getString("mimeType"),
        ),
      )
      openedVerifiedMedia += response.viewHandle
      JSONObject().put("viewHandle", response.viewHandle).put("sourceUri", response.sourceUri)
    }
    "closeVerifiedMedia" -> {
      val handle = payload.getString("viewHandle")
      val closed = requireNotNull(mediaPlayback) { "Media playback port is not configured" }
        .closeVerifiedMedia(handle)
      openedVerifiedMedia.remove(handle)
      JSONObject().put("closed", closed)
    }
    else -> unsupportedAction(payload)
  }

  private fun dispatchSystemAlbum(payload: JSONObject): JSONObject = when (payload.requireAction()) {
    "writeVerifiedMedia" -> {
      val response = requireNotNull(systemAlbum) { "System album port is not configured" }.writeVerifiedMedia(
        SystemAlbumWriteRequest(
          verifiedFilePath = payload.getString("verifiedFilePath"),
          displayName = payload.getString("displayName"),
          mimeType = payload.getString("mimeType"),
          capturedAt = payload.optString("capturedAt").takeIf { payload.has("capturedAt") },
        ),
      )
      JSONObject().put("platformAssetRef", response.platformAssetRef)
    }
    "isSystemAlbumEntryPresent" -> JSONObject().put(
      "present",
      requireNotNull(systemAlbum) { "System album port is not configured" }
        .isSystemAlbumEntryPresent(payload.getString("platformAssetRef")),
    )
    "deleteSystemAlbumEntry" -> JSONObject().put(
      "deleted",
      requireNotNull(systemAlbum) { "System album port is not configured" }
        .deleteSystemAlbumEntry(payload.getString("platformAssetRef")),
    )
    else -> unsupportedAction(payload)
  }

  override fun close() {
    openedMediaResources.values.forEach(OpenedMediaResource::close)
    openedMediaResources.clear()
    openedVerifiedMedia.forEach { handle -> mediaPlayback?.closeVerifiedMedia(handle) }
    openedVerifiedMedia.clear()
  }

  private fun JSONObject.requireAction(): String = getString("action").also { require(it.isNotBlank()) }

  private fun JSONObject.optionalBase64(name: String): ByteArray? =
    if (!has(name) || isNull(name)) null else Base64.getDecoder().decode(getString(name))

  private fun JSONObject.stringMap(name: String): Map<String, String> {
    val value = optJSONObject(name) ?: return emptyMap()
    return value.keys().asSequence().associateWith(value::getString)
  }

  private fun unsupportedAction(payload: JSONObject): Nothing =
    throw IllegalArgumentException("Unsupported platform effect action: ${payload.optString("action")}")
}

class CoreOperationRunner(
  private val core: CoreClient,
  private val dispatcher: PlatformEffectDispatcher,
) {
  suspend fun run(initial: com.mineg.mobile.contracts.CoreOperationStep): com.mineg.mobile.contracts.CoreOperationStep {
    var step = initial
    var dispatchedEffects = 0
    while (step.status == CoreOperationStatus.WAITING_FOR_EFFECT) {
      check(++dispatchedEffects <= MAX_EFFECTS_PER_RUN) { "Core operation exceeded effect limit" }
      val result = dispatcher.dispatch(checkNotNull(step.effect))
      step = core.resumeOperation(step.operationId, result.toJson())
    }
    return step
  }

  suspend fun recover(): List<com.mineg.mobile.contracts.CoreOperationStep> =
    core.recoverOperations().map { run(it) }

  private companion object {
    const val MAX_EFFECTS_PER_RUN = 10_000
  }
}
