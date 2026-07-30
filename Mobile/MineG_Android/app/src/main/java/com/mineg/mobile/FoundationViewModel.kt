package com.mineg.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mineg.mobile.contracts.ApiRequest
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.platform.AndroidConnectivityPort
import com.mineg.mobile.platform.AndroidFilePort
import com.mineg.mobile.platform.AndroidMediaSourcePort
import com.mineg.mobile.platform.AndroidSecureStorePort
import com.mineg.mobile.platform.AndroidTransportPort
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class FoundationPageState { INITIAL, LOADING, SUCCESS, BLOCKED, ERROR }

data class FoundationUiState(
  val state: FoundationPageState = FoundationPageState.INITIAL,
  val message: String = "准备验证 SQLite、C ABI、HTTPS 与流式加密。",
  val lastEvent: String? = null,
)

class FoundationViewModel(application: Application) : AndroidViewModel(application) {
  private val core = CoreClient()
  private val secureStore = AndroidSecureStorePort(application)
  private val transport = AndroidTransportPort(
    BuildConfig.MINEG_API_BASE_URL,
    allowPrivateHttp = BuildConfig.DEBUG,
  )
  private val mediaSource = AndroidMediaSourcePort(application)
  private val connectivity = AndroidConnectivityPort(application)
  private val files = AndroidFilePort(application)
  private val operationIds = AtomicLong(1)
  private var subscription = 0L
  private val mutableState = MutableStateFlow(FoundationUiState())
  val state: StateFlow<FoundationUiState> = mutableState.asStateFlow()

  init {
    core.initialize(application.getDatabasePath("mineg-core.db").absolutePath)
    subscription = core.subscribe { event ->
      mutableState.value = mutableState.value.copy(lastEvent = event)
    }
  }

  fun runProbe() {
    if (mutableState.value.state == FoundationPageState.LOADING) return
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = FoundationUiState(FoundationPageState.LOADING, "正在运行真实纵向探针…")
      try {
        val marker = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now())
        core.execute(
          operationIds.getAndIncrement(),
          "{\"version\":1,\"type\":\"FoundationWriteProbe\",\"value\":\"$marker\"}",
        )
        check(core.query("{\"version\":1,\"type\":\"FoundationReadProbe\"}").contains(marker))

        val connectivitySnapshot = connectivity.getConnectivitySnapshot()
        check(connectivitySnapshot.connected) { "设备当前没有可验证的网络连接" }
        val response = transport.sendApiRequest(ApiRequest("GET", "/api/v1/platform/probe"))
        check(response.status == 200 && response.contentType.contains("application/json")) {
          "HTTPS 探针失败（HTTP ${response.status}，requestId=${response.requestId.orEmpty()}）"
        }
        check(response.body.toString(Charsets.UTF_8).contains("\"status\":\"ok\""))

        if (mediaSource.getPermissionSnapshot().library != LibraryPermissionState.FULL) {
          mutableState.value = FoundationUiState(
            FoundationPageState.BLOCKED,
            "SQLite、C ABI 与 HTTPS 已通过；需要完整相册权限才能验证资源流式加密。",
            mutableState.value.lastEvent,
          )
          return@launch
        }
        val resource = mediaSource.openFirstMediaResource()
          ?: error("相册中没有可用于探针的照片或视频")
        resource.use {
          val key = core.randomKey()
          val outputPath = files.createTaskTempFile("foundation-probe")
          try {
            secureStore.writeSecret("foundationProbeKey", key)
            val storedKey = secureStore.readSecret("foundationProbeKey")
            try {
              check(storedKey?.contentEquals(key) == true)
            } finally {
              storedKey?.fill(0)
            }
            core.encryptResource(resource.descriptor, outputPath, key)
            check(java.io.File(outputPath).length() > 32L)
          } finally {
            secureStore.deleteSecret("foundationProbeKey")
            key.fill(0)
            files.deleteTempFile(outputPath)
          }
        }
        mutableState.value = FoundationUiState(
          FoundationPageState.SUCCESS,
          "SQLite 重启恢复、C ABI、HTTPS JSON、安全存储、媒体句柄与流式加密均通过。",
          mutableState.value.lastEvent,
        )
      } catch (error: Throwable) {
        mutableState.value = FoundationUiState(
          FoundationPageState.ERROR,
          error.message ?: "纵向探针失败",
          mutableState.value.lastEvent,
        )
      }
    }
  }

  override fun onCleared() {
    if (subscription != 0L) core.unsubscribe(subscription)
    core.close()
    super.onCleared()
  }
}
