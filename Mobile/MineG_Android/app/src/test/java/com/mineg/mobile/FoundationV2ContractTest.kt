package com.mineg.mobile

import com.mineg.mobile.platform.port.ApiRequest
import com.mineg.mobile.platform.port.ApiResponse
import com.mineg.mobile.core.protocol.CoreOperationStatus
import com.mineg.mobile.platform.port.ConnectivityPort
import com.mineg.mobile.platform.port.ConnectivitySnapshot
import com.mineg.mobile.platform.port.DownloadObjectRequest
import com.mineg.mobile.platform.port.DownloadObjectResult
import com.mineg.mobile.platform.port.FilePort
import com.mineg.mobile.platform.port.MediaScanCursor
import com.mineg.mobile.platform.port.MediaSourcePort
import com.mineg.mobile.platform.port.OpenedMediaResource
import com.mineg.mobile.platform.port.PermissionSnapshot
import com.mineg.mobile.platform.port.PlatformAlbum
import com.mineg.mobile.core.protocol.PlatformEffect
import com.mineg.mobile.core.protocol.PlatformEffectResultStatus
import com.mineg.mobile.core.protocol.PlatformEffectType
import com.mineg.mobile.platform.port.PlatformMediaPage
import com.mineg.mobile.platform.port.SecureStorePort
import com.mineg.mobile.platform.port.TransportPort
import com.mineg.mobile.platform.port.UploadPartRequest
import com.mineg.mobile.platform.port.UploadPartResult
import com.mineg.mobile.platform.port.UploadObjectRequest
import com.mineg.mobile.platform.port.UploadObjectResult
import com.mineg.mobile.platform.port.LibraryPermissionState
import com.mineg.mobile.core.protocol.FOUNDATION_V2_CONTRACT
import com.mineg.mobile.core.protocol.CoreOperationStep
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.effect.PlatformEffectDispatcher
import java.lang.reflect.Modifier
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoundationV2ContractTest {
  private val manifest by lazy { resource("foundation-v2.json") }

  @Test
  fun manifestDefinesRecoverableEffectContract() {
    listOf(
      "foundation-v2",
      "startOperation",
      "resumeOperation",
      "recoverOperations",
      "PlatformEffect",
      "TransportEffect",
      "SecureStoreEffect",
      "MediaSourceEffect",
      "FileEffect",
      "WAITING_FOR_EFFECT",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "\"cAbiVersion\": 5")
    assertContains(manifest, "\"status\": \"BASELINED\"")
  }

  @Test
  fun coreClientPublishesV2LifecycleWithoutRemovingV1Surface() {
    val methods = CoreClient::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(methods.containsAll(setOf("execute", "query", "startOperation", "resumeOperation", "recoverOperations")))
  }

  @Test
  fun operationStepValidatesAndParsesEffectIdentity() {
    val step = CoreOperationStep.parse(
      """{"contractVersion":"foundation-v2","operationId":41,"sequence":2,"status":"WAITING_FOR_EFFECT","effect":{"contractVersion":"foundation-v2","operationId":41,"sequence":2,"effectType":"FileEffect","payload":{"action":"getAvailableSpace"}}}""",
    )
    assertEquals(CoreOperationStatus.WAITING_FOR_EFFECT, step.status)
    assertEquals(41, step.effect?.operationId)
    assertEquals(PlatformEffectType.FILE, step.effect?.effectType)
    assertNull(step.resultJson)
  }

  @Test
  fun dispatcherExecutesOnlyPortPrimitivesAndReturnsMechanicalResults() = runTest {
    val transport = FakeTransportPort()
    val secureStore = FakeSecureStorePort()
    val mediaSource = FakeMediaSourcePort()
    val files = FakeFilePort()
    PlatformEffectDispatcher(transport, secureStore, mediaSource, files, FakeConnectivityPort()).use { dispatcher ->
      val transportResult = dispatcher.dispatch(
        effect(
          PlatformEffectType.TRANSPORT,
          """{"action":"sendApiRequest","method":"POST","path":"/probe","headers":{"X-Probe":"1"},"bodyBase64":"aGVsbG8="}""",
        ),
      )
      assertEquals(PlatformEffectResultStatus.SUCCEEDED, transportResult.status)
      assertEquals("/probe", transport.lastRequest?.path)
      assertEquals("hello", transport.lastRequest?.body?.toString(Charsets.UTF_8))
      assertEquals(204, JSONObject(transportResult.payloadJson!!).getInt("status"))

      val secret = Base64.getEncoder().encodeToString("secret".toByteArray())
      assertEquals(
        PlatformEffectResultStatus.SUCCEEDED,
        dispatcher.dispatch(
          effect(PlatformEffectType.SECURE_STORE, """{"action":"writeSecret","name":"session","valueBase64":"$secret"}"""),
        ).status,
      )
      val readResult = dispatcher.dispatch(
        effect(PlatformEffectType.SECURE_STORE, """{"action":"readSecret","name":"session"}"""),
      )
      assertEquals(secret, JSONObject(readResult.payloadJson!!).getString("valueBase64"))

      val batchWrite = dispatcher.dispatch(
        effect(
          PlatformEffectType.SECURE_STORE,
          """{"action":"writeSecrets","values":[{"name":"access","valueBase64":"$secret"},{"name":"refresh","valueBase64":"$secret"}]}""",
        ),
      )
      assertEquals(PlatformEffectResultStatus.SUCCEEDED, batchWrite.status)
      val batchRead = dispatcher.dispatch(
        effect(PlatformEffectType.SECURE_STORE, """{"action":"readSecrets","names":["access","refresh","missing"]}"""),
      )
      val values = JSONObject(batchRead.payloadJson!!).getJSONArray("values")
      assertEquals(secret, values.getJSONObject(0).getString("valueBase64"))
      assertTrue(values.getJSONObject(2).isNull("valueBase64"))

      val mediaResult = dispatcher.dispatch(
        effect(PlatformEffectType.MEDIA_SOURCE, """{"action":"listAlbums"}"""),
      )
      assertEquals("Camera", JSONObject(mediaResult.payloadJson!!).getJSONArray("items").getJSONObject(0).getString("name"))

      val fileResult = dispatcher.dispatch(
        effect(PlatformEffectType.FILE, """{"action":"getAvailableSpace"}"""),
      )
      assertEquals(4096, JSONObject(fileResult.payloadJson!!).getLong("availableBytes"))

      val connectivityResult = dispatcher.dispatch(
        effect(PlatformEffectType.CONNECTIVITY, "{\"action\":\"getConnectivitySnapshot\"}"),
      )
      assertTrue(JSONObject(connectivityResult.payloadJson!!).getBoolean("connected"))

      val rejected = dispatcher.dispatch(
        effect(PlatformEffectType.FILE, """{"action":"decideBackupSuccess"}"""),
      )
      assertEquals(PlatformEffectResultStatus.FAILED, rejected.status)
      val rejectedError = checkNotNull(rejected.error)
      assertEquals("INVALID_EFFECT_PAYLOAD", rejectedError.code)
      assertFalse(rejectedError.retryable)
    }
  }

  @Test
  fun failedResultDoesNotExposeExceptionMessage() = runTest {
    val dispatcher = PlatformEffectDispatcher(
      FakeTransportPort(fail = true),
      FakeSecureStorePort(),
      FakeMediaSourcePort(),
      FakeFilePort(),
      FakeConnectivityPort(),
    )
    val result = dispatcher.dispatch(
      effect(PlatformEffectType.TRANSPORT, """{"action":"sendApiRequest","method":"GET","path":"/secret"}"""),
    )
    assertEquals(PlatformEffectResultStatus.FAILED, result.status)
    val error = checkNotNull(result.error)
    assertEquals("PLATFORM_IO_ERROR", error.code)
    assertTrue(error.retryable)
    assertNull(error.message)
  }

  private fun effect(type: PlatformEffectType, payload: String) = PlatformEffect(
    contractVersion = FOUNDATION_V2_CONTRACT,
    operationId = 100,
    sequence = 1,
    effectType = type,
    payloadJson = payload,
  )

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}

private class FakeTransportPort(private val fail: Boolean = false) : TransportPort {
  var lastRequest: ApiRequest? = null

  override suspend fun sendApiRequest(request: ApiRequest): ApiResponse {
    if (fail) throw java.io.IOException("must not cross the EffectResult boundary")
    lastRequest = request
    return ApiResponse(204, "application/json", "request-1", ByteArray(0))
  }

  override suspend fun uploadPart(request: UploadPartRequest): UploadPartResult = UploadPartResult("etag-1")
  override suspend fun uploadObject(request: UploadObjectRequest): UploadObjectResult = UploadObjectResult(200)
  override suspend fun downloadObject(request: DownloadObjectRequest): DownloadObjectResult =
    DownloadObjectResult(200, request.expectedSize ?: 1L, "digest-1", "image/jpeg")
}

private class FakeSecureStorePort : SecureStorePort {
  private val values = mutableMapOf<String, ByteArray>()
  override fun readSecret(name: String): ByteArray? = values[name]?.copyOf()
  override fun writeSecret(name: String, value: ByteArray) {
    values[name] = value.copyOf()
  }
  override fun deleteSecret(name: String) {
    values.remove(name)?.fill(0)
  }
}

private class FakeMediaSourcePort : MediaSourcePort {
  override fun getPermissionSnapshot() = PermissionSnapshot(LibraryPermissionState.FULL)
  override fun requestFullLibraryAccess() = getPermissionSnapshot()
  override fun listAlbums() = listOf(PlatformAlbum("android:camera", "Camera"))
  override fun listMedia(cursor: MediaScanCursor?, limit: Int) = PlatformMediaPage(emptyList(), null)
  override fun openFirstMediaResource(): OpenedMediaResource? = null
  override fun openMediaResource(platformAssetRef: String): OpenedMediaResource? = null
}

private class FakeFilePort : FilePort {
  override fun createTaskTempFile(name: String) = "/fake/$name"
  override fun getAvailableSpace() = 4096L
  override fun deleteTempFile(path: String) = true
}

private class FakeConnectivityPort : ConnectivityPort {
  override fun getConnectivitySnapshot() = ConnectivitySnapshot(connected = true, metered = false)
}
