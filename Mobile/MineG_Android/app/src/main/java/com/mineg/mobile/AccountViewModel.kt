package com.mineg.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mineg.mobile.account.AccountValidation
import com.mineg.mobile.account.AndroidAccountClient
import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.LocalScanState
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.contracts.SingleMediaBackup
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.platform.AndroidSecureStorePort
import com.mineg.mobile.platform.AndroidTransportPort
import com.mineg.mobile.platform.AndroidMediaSourcePort
import com.mineg.mobile.platform.AndroidFilePort
import java.util.UUID
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AccountScreen {
  RESTORING, LOGIN, SIGNUP, REVIEW_PENDING, PROFILE, PROFILE_EDIT, PERMISSION,
  BACKUP_OVERVIEW, BACKUP_SETTINGS, LOCAL_ALBUM, LEGAL,
}

data class AccountUiState(
  val screen: AccountScreen = AccountScreen.RESTORING,
  val loading: Boolean = false,
  val phone: String = "",
  val password: String = "",
  val passwordConfirmation: String = "",
  val agreementAccepted: Boolean = false,
  val fieldErrors: Map<String, String> = emptyMap(),
  val message: String? = null,
  val messageIsError: Boolean = false,
  val profile: Profile? = null,
  val editingNickname: String = "",
  val permissionState: LibraryPermissionState = LibraryPermissionState.NOT_DETERMINED,
  val backupSettings: BackupSettings = BackupSettings(),
  val scanState: LocalScanState? = null,
  val albums: List<LocalAlbum> = emptyList(),
  val selectedAlbum: LocalAlbum? = null,
  val localMedia: List<LocalMedia> = emptyList(),
  val legalDocument: String? = null,
  val singleMediaBackup: SingleMediaBackup? = null,
)

class AccountViewModel(application: Application) : AndroidViewModel(application) {
  private val core = CoreClient()
  private val mediaSource = AndroidMediaSourcePort(application)
  private val account: AndroidAccountClient
  private val mutableState = MutableStateFlow(AccountUiState())
  val state: StateFlow<AccountUiState> = mutableState.asStateFlow()
  private var pollingJob: Job? = null
  private var signUpIdempotencyKey: String? = null

  init {
    core.initialize(application.getDatabasePath("mineg-core.db").absolutePath)
    account = AndroidAccountClient(
      core = core,
      secureStore = AndroidSecureStorePort(application),
      transport = AndroidTransportPort(BuildConfig.MINEG_API_BASE_URL, allowPrivateHttp = BuildConfig.DEBUG),
      mediaSource = mediaSource,
      files = AndroidFilePort(application),
    )
    restoreSession()
  }

  fun updatePhone(value: String) = updateForm { copy(phone = value, fieldErrors = fieldErrors - "phone", message = null) }
  fun updatePassword(value: String) = updateForm { copy(password = value, fieldErrors = fieldErrors - "password", message = null) }
  fun updatePasswordConfirmation(value: String) = updateForm {
    copy(passwordConfirmation = value, fieldErrors = fieldErrors - "passwordConfirmation", message = null)
  }
  fun updateAgreement(value: Boolean) = updateForm {
    copy(agreementAccepted = value, fieldErrors = fieldErrors - "agreement", message = null)
  }

  fun openSignUp() {
    stopPolling()
    signUpIdempotencyKey = null
    mutableState.value = AccountUiState(screen = AccountScreen.SIGNUP, phone = mutableState.value.phone)
  }

  fun openLogin(message: String? = null) {
    stopPolling()
    signUpIdempotencyKey = null
    mutableState.value = AccountUiState(screen = AccountScreen.LOGIN, phone = mutableState.value.phone, message = message)
  }

  fun openLegal(document: String) {
    if (!mutableState.value.loading) {
      mutableState.value = mutableState.value.copy(screen = AccountScreen.LEGAL, legalDocument = document)
    }
  }

  fun closeLegal() {
    if (mutableState.value.screen == AccountScreen.LEGAL) {
      mutableState.value = mutableState.value.copy(screen = AccountScreen.LOGIN, legalDocument = null)
    }
  }

  fun submitSignIn() {
    val current = mutableState.value
    if (current.loading) return
    val errors = buildMap {
      if (AccountValidation.normalizePhone(current.phone) == null) put("phone", "请输入有效的中国大陆手机号")
      if (current.password.isBlank()) put("password", "请输入密码")
      if (!current.agreementAccepted) put("agreement", "请先阅读并同意服务协议与隐私政策")
    }
    if (errors.isNotEmpty()) {
      mutableState.value = current.copy(fieldErrors = errors)
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = current.copy(loading = true, fieldErrors = emptyMap(), message = null)
      try {
        routeSession(account.signIn(current.phone, current.password, current.agreementAccepted))
      } catch (problem: AccountProblem) {
        showProblem(problem)
      } catch (_: Throwable) {
        showUnexpectedError()
      }
    }
  }

  fun submitSignUp() {
    val current = mutableState.value
    if (current.loading) return
    val errors = buildMap {
      if (AccountValidation.normalizePhone(current.phone) == null) put("phone", "请输入有效的中国大陆手机号")
      AccountValidation.passwordError(current.password)?.let { put("password", it) }
      if (current.passwordConfirmation != current.password) put("passwordConfirmation", "两次输入的密码不一致")
    }
    if (errors.isNotEmpty()) {
      mutableState.value = current.copy(fieldErrors = errors)
      return
    }
    if (signUpIdempotencyKey == null) signUpIdempotencyKey = UUID.randomUUID().toString()
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = current.copy(loading = true, fieldErrors = emptyMap(), message = null)
      val passwordBytes = current.password.toByteArray()
      try {
        routeSession(account.signUp(current.phone, passwordBytes, checkNotNull(signUpIdempotencyKey)))
        signUpIdempotencyKey = null
      } catch (problem: AccountProblem) {
        showProblem(problem)
      } catch (_: Throwable) {
        showUnexpectedError()
      } finally {
        passwordBytes.fill(0)
      }
    }
  }

  fun refreshReviewStatus(manual: Boolean = true) {
    val current = mutableState.value
    if (current.loading && manual) return
    viewModelScope.launch(Dispatchers.IO) {
      if (manual) mutableState.value = current.copy(loading = true, message = null)
      try {
        when (account.refreshReviewStatus()) {
          ApprovalStatus.PENDING -> mutableState.value = mutableState.value.copy(
            screen = AccountScreen.REVIEW_PENDING,
            loading = false,
            message = if (manual) "状态已刷新，申请仍在处理中。" else null,
            messageIsError = false,
          )
          ApprovalStatus.APPROVED -> showProfile()
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          openLogin()
        } else if (manual) {
          showProblem(problem)
        }
      }
    }
  }

  fun onForeground() {
    if (mutableState.value.screen == AccountScreen.REVIEW_PENDING && !mutableState.value.loading) {
      refreshReviewStatus(manual = false)
      return
    }
    if (mutableState.value.screen == AccountScreen.PROFILE) return
    if (mutableState.value.screen in setOf(AccountScreen.PERMISSION, AccountScreen.BACKUP_OVERVIEW, AccountScreen.LOCAL_ALBUM)) {
      val permission = mediaSource.getPermissionSnapshot().library
      if (permission == LibraryPermissionState.FULL && mutableState.value.screen == AccountScreen.PERMISSION) {
        loadBackupOverview()
      } else if (permission != LibraryPermissionState.FULL && mutableState.value.screen != AccountScreen.PERMISSION) {
        mutableState.value.profile?.id?.let { account.scanLocalMedia(it) }
        mutableState.value = mutableState.value.copy(
          screen = AccountScreen.PERMISSION,
          permissionState = permission,
          localMedia = emptyList(),
          message = "相册权限已变化，请重新授予完整访问权限。",
          messageIsError = true,
        )
      } else {
        mutableState.value = mutableState.value.copy(permissionState = permission)
      }
    }
  }

  fun openProfileEdit() {
    val profile = mutableState.value.profile ?: return
    mutableState.value = mutableState.value.copy(
      screen = AccountScreen.PROFILE_EDIT,
      editingNickname = profile.nickname,
      message = null,
      fieldErrors = emptyMap(),
    )
  }

  fun updateEditingNickname(value: String) {
    if (!mutableState.value.loading) {
      mutableState.value = mutableState.value.copy(editingNickname = value, fieldErrors = emptyMap(), message = null)
    }
  }

  fun saveProfile() {
    val current = mutableState.value
    if (current.loading) return
    val nickname = current.editingNickname.trim()
    if (nickname.length !in 2..20 || !nickname.matches(Regex("[\\p{L}\\p{N} _-]+"))) {
      mutableState.value = current.copy(fieldErrors = mapOf("nickname" to "昵称需为 2～20 个中文、字母、数字、空格、连字符或下划线"))
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = current.copy(loading = true, message = null)
      try {
        val profile = account.updateProfile(nickname)
        mutableState.value = AccountUiState(
          screen = AccountScreen.PROFILE,
          profile = profile,
          message = "个人资料已更新。",
        )
      } catch (problem: AccountProblem) {
        showProblem(problem)
      } catch (_: Throwable) {
        showUnexpectedError()
      }
    }
  }

  fun updateAvatar(uri: Uri) {
    val current = mutableState.value
    if (current.loading) return
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = current.copy(loading = true, message = null)
      try {
        val resolver = getApplication<Application>().contentResolver
        val sourceSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
        require(sourceSize in 1..10L * 1024 * 1024) { "avatar source size invalid" }
        val source = ImageDecoder.createSource(resolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          val largest = max(info.size.width, info.size.height)
          if (largest > 2048) decoder.setTargetSampleSize(ceil(largest / 2048.0).toInt())
        }
        val side = min(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val targetSide = side.coerceAtMost(1024)
        val display = if (cropped.width == targetSide) cropped else Bitmap.createScaledBitmap(cropped, targetSide, targetSide, true)
        if (cropped !== decoded && decoded !== display) decoded.recycle()
        if (display !== cropped) cropped.recycle()
        val output = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        check(display.compress(format, 90, output))
        display.recycle()
        val bytes = output.toByteArray()
        require(bytes.size <= 10 * 1024 * 1024) { "avatar display size invalid" }
        try {
          val profile = account.updateAvatar(bytes, sourceSize, targetSide, targetSide)
          mutableState.value = mutableState.value.copy(
            screen = AccountScreen.PROFILE_EDIT,
            loading = false,
            profile = profile,
            editingNickname = profile.nickname,
            message = "头像已更新。",
            messageIsError = false,
          )
        } finally {
          bytes.fill(0)
        }
      } catch (problem: AccountProblem) {
        showProblem(problem)
      } catch (_: Throwable) {
        mutableState.value = mutableState.value.copy(
          loading = false,
          message = "头像处理或上传失败，请保留当前选择后重试。",
          messageIsError = true,
        )
      }
    }
  }

  fun openBackup() {
    val permission = mediaSource.getPermissionSnapshot().library
    if (permission == LibraryPermissionState.FULL) {
      loadBackupOverview()
    } else {
      mutableState.value = mutableState.value.copy(
        screen = AccountScreen.PERMISSION,
        permissionState = permission,
        message = null,
      )
    }
  }

  fun markPermissionRequestStarted() {
    mediaSource.requestFullLibraryAccess()
  }

  fun onPermissionResult() {
    val permission = mediaSource.getPermissionSnapshot().library
    if (permission == LibraryPermissionState.FULL) {
      loadBackupOverview()
    } else {
      mutableState.value = mutableState.value.copy(
        screen = AccountScreen.PERMISSION,
        permissionState = permission,
        message = if (permission == LibraryPermissionState.LIMITED) "已选择部分照片，但自动备份需要完整相册权限。" else "未获得完整相册权限，不会创建扫描或备份任务。",
        messageIsError = true,
      )
    }
  }

  fun deferPermission() {
    mutableState.value = mutableState.value.copy(screen = AccountScreen.PROFILE, message = null)
  }

  fun openBackupSettings() {
    val current = mutableState.value
    val userId = current.profile?.id ?: return
    val settings = account.getBackupSettings(userId, account.deviceInstallationId())
    mutableState.value = current.copy(screen = AccountScreen.BACKUP_SETTINGS, backupSettings = settings, message = null)
  }

  fun setAutoBackupEnabled(enabled: Boolean) {
    updateBackupSettings(mutableState.value.backupSettings.copy(autoBackupEnabled = enabled))
  }

  fun setAllowCellularBackup(enabled: Boolean) {
    if (mutableState.value.backupSettings.autoBackupEnabled) {
      updateBackupSettings(mutableState.value.backupSettings.copy(allowCellularBackup = enabled))
    }
  }

  fun startBackup() {
    val current = mutableState.value
    val userId = current.profile?.id ?: return
    val settings = current.backupSettings.copy(autoBackupEnabled = true)
    account.updateBackupSettings(userId, account.deviceInstallationId(), settings)
    mutableState.value = current.copy(backupSettings = settings)
    loadBackupOverview()
  }

  fun openLocalAlbum(album: LocalAlbum) {
    val current = mutableState.value
    val userId = current.profile?.id ?: return
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = current.copy(loading = true, selectedAlbum = album, localMedia = emptyList(), message = null)
      try {
        val media = account.listLocalMedia(userId, album.platformAlbumRef, limit = 120)
        mutableState.value = mutableState.value.copy(
          screen = AccountScreen.LOCAL_ALBUM,
          loading = false,
          selectedAlbum = album,
          localMedia = media.items,
        )
      } catch (_: Throwable) {
        mutableState.value = mutableState.value.copy(loading = false, message = "本地相册加载失败，请重试。", messageIsError = true)
      }
    }
  }

  fun backToProfile() {
    mutableState.value = mutableState.value.copy(screen = AccountScreen.PROFILE, message = null, localMedia = emptyList(), selectedAlbum = null)
  }

  fun backToBackupOverview() {
    mutableState.value = mutableState.value.copy(screen = AccountScreen.BACKUP_OVERVIEW, message = null, localMedia = emptyList(), selectedAlbum = null)
  }

  private fun updateBackupSettings(settings: BackupSettings) {
    val current = mutableState.value
    val userId = current.profile?.id ?: return
    account.updateBackupSettings(userId, account.deviceInstallationId(), settings)
    mutableState.value = current.copy(backupSettings = settings)
  }

  private fun loadBackupOverview() {
    val current = mutableState.value
    val userId = current.profile?.id ?: return
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = current.copy(
        screen = AccountScreen.BACKUP_OVERVIEW,
        loading = true,
        permissionState = LibraryPermissionState.FULL,
        message = null,
      )
      try {
        val installationId = account.deviceInstallationId()
        val settings = account.getBackupSettings(userId, installationId)
        if (settings.updatedAt == null) account.updateBackupSettings(userId, installationId, settings)
        val scan = account.scanLocalMedia(userId)
        val albums = account.listLocalAlbums(userId, limit = 100).items
        val firstMedia = account.listLocalMedia(userId, null, limit = 1).items.firstOrNull()
        val singleBackup = if (settings.autoBackupEnabled && firstMedia != null) {
          account.backupSingleMedia(userId, firstMedia)
        } else {
          null
        }
        mutableState.value = mutableState.value.copy(
          loading = false,
          backupSettings = settings,
          scanState = scan,
          albums = albums,
          singleMediaBackup = singleBackup,
          message = null,
        )
      } catch (_: Throwable) {
        mutableState.value = mutableState.value.copy(loading = false, message = "相册索引未完成，请稍后重试。", messageIsError = true)
      }
    }
  }

  fun signInAgain() {
    performSignOut("请重新登录以继续查看审核状态。")
  }

  fun confirmSignOut() {
    performSignOut()
  }

  private fun performSignOut(message: String? = null) {
    if (mutableState.value.loading) return
    stopPolling()
    viewModelScope.launch(Dispatchers.IO) {
      mutableState.value = mutableState.value.copy(loading = true)
      runCatching { account.signOut() }
      mutableState.value = AccountUiState(screen = AccountScreen.LOGIN, message = message)
    }
  }

  private fun restoreSession() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val restored = account.restoreSession()
        if (restored == null) {
          mutableState.value = AccountUiState(screen = AccountScreen.LOGIN)
        } else {
          routeSession(restored)
        }
      } catch (problem: AccountProblem) {
        mutableState.value = AccountUiState(
          screen = AccountScreen.LOGIN,
          message = messageFor(problem),
          messageIsError = true,
        )
      } catch (_: Throwable) {
        mutableState.value = AccountUiState(screen = AccountScreen.LOGIN, message = "会话恢复失败，请重新登录。", messageIsError = true)
      }
    }
  }

  private suspend fun routeSession(session: com.mineg.mobile.contracts.AccountSession) {
    when (session.nextStep) {
      AccountNextStep.REVIEW_PENDING -> {
        mutableState.value = AccountUiState(screen = AccountScreen.REVIEW_PENDING)
        startPolling()
      }
      AccountNextStep.APP_HOME -> showProfile()
    }
  }

  private suspend fun showProfile() {
    stopPolling()
    val profile = account.getProfile()
    mutableState.value = AccountUiState(screen = AccountScreen.PROFILE, profile = profile)
  }

  private fun startPolling() {
    if (pollingJob?.isActive == true) return
    pollingJob = viewModelScope.launch(Dispatchers.IO) {
      val backoff = longArrayOf(10_000, 20_000, 40_000, 60_000)
      var failureIndex = 0
      while (mutableState.value.screen == AccountScreen.REVIEW_PENDING) {
        delay(if (failureIndex == 0) 10_000 else backoff[failureIndex.coerceAtMost(backoff.lastIndex)])
        if (mutableState.value.screen != AccountScreen.REVIEW_PENDING) break
        try {
          when (account.refreshReviewStatus()) {
            ApprovalStatus.PENDING -> failureIndex = 0
            ApprovalStatus.APPROVED -> {
              showProfile()
              break
            }
          }
        } catch (problem: AccountProblem) {
          if (problem.code in SESSION_ERRORS) {
            mutableState.value = AccountUiState(screen = AccountScreen.LOGIN)
            break
          }
          failureIndex = (failureIndex + 1).coerceAtMost(backoff.lastIndex)
        }
      }
    }
  }

  private fun stopPolling() {
    pollingJob?.cancel()
    pollingJob = null
  }

  private fun showProblem(problem: AccountProblem) {
    val current = mutableState.value
    val field = when (problem.code) {
      "PHONE_INVALID", "PHONE_ALREADY_REGISTERED" -> "phone"
      "PASSWORD_INVALID", "CREDENTIALS_INVALID" -> "password"
      "AGREEMENT_REQUIRED" -> "agreement"
      else -> null
    }
    mutableState.value = current.copy(
      loading = false,
      fieldErrors = if (field == null) emptyMap() else mapOf(field to messageFor(problem)),
      message = if (field == null) messageFor(problem) else null,
      messageIsError = true,
    )
  }

  private fun showUnexpectedError() {
    mutableState.value = mutableState.value.copy(loading = false, message = "服务暂时不可用，请稍后重试。", messageIsError = true)
  }

  private fun messageFor(problem: AccountProblem): String = when (problem.code) {
    "PHONE_INVALID" -> "手机号格式不正确。"
    "PASSWORD_INVALID" -> "密码需为 8～64 个字符，并同时包含字母和数字。"
    "PHONE_ALREADY_REGISTERED" -> "该手机号已注册，请直接登录。"
    "CREDENTIALS_INVALID" -> "手机号或密码错误。"
    "AGREEMENT_REQUIRED" -> "请先阅读并同意服务协议与隐私政策。"
    "NETWORK_UNAVAILABLE" -> "网络暂时不可用，请检查连接后重试。"
    "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REPLAYED" -> "登录状态已失效，请重新登录。"
    else -> if (problem.requestId.isBlank()) "服务暂时不可用，请稍后重试。" else "请求未完成（编号 ${problem.requestId}）。"
  }

  private fun updateForm(update: AccountUiState.() -> AccountUiState) {
    if (!mutableState.value.loading) mutableState.value = mutableState.value.update()
  }

  override fun onCleared() {
    stopPolling()
    core.close()
    super.onCleared()
  }

  private companion object {
    val SESSION_ERRORS = setOf("AUTH_REQUIRED", "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REPLAYED")
  }
}
