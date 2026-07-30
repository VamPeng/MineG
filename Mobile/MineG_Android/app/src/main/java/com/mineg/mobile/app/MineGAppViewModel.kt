package com.mineg.mobile.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mineg.mobile.account.AccountValidation
import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.OwnerMediaSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MineGAppViewModel internal constructor(
  private val runtime: MineGAppRuntime,
  autoRestore: Boolean = true,
) : ViewModel() {
  private val mutableState = MutableStateFlow(MineGAppState())
  val state: StateFlow<MineGAppState> = mutableState.asStateFlow()
  private var currentUserId: String? = null
  private var reviewPollingJob: Job? = null

  init {
    if (autoRestore) restoreAuthentication()
  }

  fun restoreAuthentication() {
    stopReviewPolling()
    currentUserId = null
    mutableState.value = MineGAppState(currentRoute = AppRoute.Restoring)
    viewModelScope.launch {
      try {
        val session = runtime.restoreSession()
        if (session == null) {
          showLogin()
        } else {
          routeSession(session, allowCachedProfile = true)
        }
      } catch (problem: AccountProblem) {
        showLogin(messageFor(problem), true)
      } catch (_: Throwable) {
        showLogin("会话恢复失败，请重新登录。", true)
      }
    }
  }

  fun navigate(route: AppRoute) {
    mutableState.update { current ->
      if (route.requiresProfile() && current.profile == null) {
        return@update MineGAppState(
          currentRoute = AppRoute.Login,
          auth = AuthUiState(message = "登录状态已失效，请重新登录。", messageIsError = true),
        )
      }
      if (current.currentRoute == route) current
      else current.copy(
        currentRoute = route,
        backStack = current.backStack + current.currentRoute,
        profileDraftNickname = if (route == AppRoute.ProfileEdit) current.profile?.nickname.orEmpty() else current.profileDraftNickname,
        auth = if (route == AppRoute.ProfileEdit) AuthUiState() else current.auth,
        selectedMediaAction = MediaActionState.IDLE,
        dialog = null,
      )
    }
  }

  fun showDebugPanel() {
    mutableState.update { it.copy(debugPanelVisible = true) }
  }

  fun hideDebugPanel() {
    mutableState.update { it.copy(debugPanelVisible = false) }
  }

  fun resetAcceptanceState() {
    restoreAuthentication()
  }

  fun debugNavigate(route: AppRoute) {
    when (route) {
      AppRoute.PrivateSpace -> selectTab(MainTab.PRIVATE_SPACE)
      AppRoute.Backup -> selectTab(MainTab.BACKUP)
      AppRoute.Profile -> selectTab(MainTab.PROFILE)
      else -> navigate(route)
    }
    hideDebugPanel()
  }

  fun setPrivateSpaceLoadState(loadState: PageLoadState) {
    mutableState.update { it.copy(privateSpace = it.privateSpace.copy(loadState = loadState), debugPanelVisible = false) }
  }

  fun setFamilyAlbumLoadState(loadState: PageLoadState) {
    mutableState.update { it.copy(familyAlbum = it.familyAlbum.copy(loadState = loadState), debugPanelVisible = false) }
  }

  fun setRecycleBinLoadState(loadState: PageLoadState) {
    mutableState.update { it.copy(recycleBin = it.recycleBin.copy(loadState = loadState), debugPanelVisible = false) }
  }

  fun setBackupStatus(status: BackupStatus) {
    mutableState.update {
      it.copy(
        backup = it.backup.copy(
          loadState = PageLoadState.CONTENT,
          status = status,
          autoBackupEnabled = status != BackupStatus.PAUSED,
          progress = if (status == BackupStatus.SCANNING) 0.24f else 0.68f,
        ),
        debugPanelVisible = false,
      )
    }
  }

  fun setMediaActionState(actionState: MediaActionState) {
    mutableState.update { it.copy(selectedMediaAction = actionState, debugPanelVisible = false) }
  }

  fun selectTab(tab: MainTab) {
    mutableState.update {
      if (it.profile == null) {
        return@update MineGAppState(
          currentRoute = AppRoute.Login,
          auth = AuthUiState(message = "请先登录后继续。", messageIsError = true),
        )
      }
      it.copy(
        currentRoute = tab.route(),
        backStack = emptyList(),
        selectedTab = tab,
        selectedLibraryTab = if (tab == MainTab.PRIVATE_SPACE) LibraryTab.PRIVATE else it.selectedLibraryTab,
        selectedMediaAction = MediaActionState.IDLE,
        dialog = null,
        debugPanelVisible = false,
      )
    }
  }

  fun back(): Boolean {
    val current = mutableState.value
    if (current.dialog != null) {
      dismissDialog()
      return true
    }
    val previous = current.backStack.lastOrNull() ?: return false
    mutableState.value = current.copy(
      currentRoute = previous,
      backStack = current.backStack.dropLast(1),
      selectedMediaAction = MediaActionState.IDLE,
      debugPanelVisible = false,
    )
    return true
  }

  fun updatePhone(value: String) = updateAuth { copy(phone = value, fieldErrors = fieldErrors - "phone", message = null) }
  fun updatePassword(value: String) = updateAuth { copy(password = value, fieldErrors = fieldErrors - "password", message = null) }
  fun updatePasswordConfirmation(value: String) = updateAuth { copy(passwordConfirmation = value, fieldErrors = fieldErrors - "passwordConfirmation", message = null) }
  fun updateAgreement(value: Boolean) = updateAuth { copy(agreementAccepted = value, fieldErrors = fieldErrors - "agreement", message = null) }

  fun submitLogin() {
    val auth = mutableState.value.auth
    val errors = buildMap {
      if (AccountValidation.normalizePhone(auth.phone) == null) put("phone", "请输入有效的中国大陆手机号")
      if (auth.password.isBlank()) put("password", "请输入密码")
      if (!auth.agreementAccepted) put("agreement", "请先阅读并同意服务协议与隐私政策")
    }
    if (errors.isNotEmpty()) {
      updateAuth { copy(fieldErrors = errors) }
      return
    }
    if (auth.loading) return
    viewModelScope.launch {
      updateAuth { copy(loading = true, fieldErrors = emptyMap(), message = null, messageIsError = false) }
      try {
        routeSession(runtime.signIn(auth.phone, auth.password, auth.agreementAccepted), allowCachedProfile = true)
      } catch (problem: AccountProblem) {
        showProblem(problem)
      } catch (_: Throwable) {
        updateAuth { copy(loading = false, message = "登录失败，请稍后重试。", messageIsError = true) }
      }
    }
  }

  fun openSignUp() = navigate(AppRoute.SignUp)

  fun submitSignUp() {
    val auth = mutableState.value.auth
    val errors = buildMap {
      if (AccountValidation.normalizePhone(auth.phone) == null) put("phone", "手机号格式不正确")
      AccountValidation.passwordError(auth.password)?.let { put("password", it) }
      if (auth.password != auth.passwordConfirmation) put("passwordConfirmation", "两次输入的密码不一致")
    }
    if (errors.isNotEmpty()) {
      updateAuth { copy(fieldErrors = errors) }
      return
    }
    if (auth.loading) return
    viewModelScope.launch {
      updateAuth { copy(loading = true, fieldErrors = emptyMap(), message = null, messageIsError = false) }
      try {
        routeSession(runtime.signUp(auth.phone, auth.password), allowCachedProfile = false)
      } catch (problem: AccountProblem) {
        showProblem(problem)
      } catch (_: Throwable) {
        updateAuth { copy(loading = false, message = "注册失败，请稍后重试。", messageIsError = true) }
      }
    }
  }

  fun refreshReviewStatus() = refreshReviewStatus(manual = true)

  private fun refreshReviewStatus(manual: Boolean) {
    val userId = currentUserId ?: run {
      returnToLogin()
      return
    }
    if (manual && mutableState.value.auth.reviewSyncing) return
    viewModelScope.launch {
      if (manual) updateAuth { copy(reviewSyncing = true, message = null, messageIsError = false) }
      try {
        when (runtime.refreshReviewStatus()) {
          ApprovalStatus.PENDING -> updateAuth {
            copy(
              reviewSyncing = false,
              message = if (manual) "状态已刷新，申请仍在处理中。" else null,
              messageIsError = false,
            )
          }
          ApprovalStatus.APPROVED -> loadApprovedProfile(userId, allowCached = false)
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else if (manual) {
          updateAuth { copy(reviewSyncing = false, message = messageFor(problem), messageIsError = true) }
        }
      } catch (_: Throwable) {
        if (manual) updateAuth { copy(reviewSyncing = false, message = "审核状态刷新失败，请稍后重试。", messageIsError = true) }
      }
    }
  }

  fun returnToLogin() {
    performLogout()
  }

  fun markLibraryPermissionRequested() {
    runtime.markLibraryPermissionRequested()
  }

  fun onLibraryPermissionResult() {
    val access = runtime.libraryAccess()
    if (access == LibraryAccess.FULL) {
      enterPrivateSpace(access)
    } else {
      mutableState.update {
        it.copy(
          currentRoute = AppRoute.Permission,
          libraryAccess = access,
          backup = it.backup.copy(status = BackupStatus.PERMISSION_REQUIRED),
          auth = it.auth.copy(
            message = if (access == LibraryAccess.LIMITED) {
              "已选择部分照片，但自动备份需要完整相册权限。"
            } else {
              "未获得完整相册权限，不会创建扫描或备份任务。"
            },
            messageIsError = true,
          ),
        )
      }
    }
  }

  fun onForeground() {
    if (mutableState.value.currentRoute == AppRoute.ReviewPending) {
      refreshReviewStatus(manual = false)
      return
    }
    if (mutableState.value.profile == null) return
    val previous = mutableState.value.libraryAccess
    val current = runtime.libraryAccess()
    when {
      previous == LibraryAccess.FULL && current != LibraryAccess.FULL -> mutableState.update {
        it.copy(
          currentRoute = AppRoute.Permission,
          backStack = emptyList(),
          libraryAccess = current,
          backup = it.backup.copy(status = BackupStatus.PERMISSION_REQUIRED),
        )
      }
      previous != LibraryAccess.FULL && current == LibraryAccess.FULL -> enterPrivateSpace(current)
      else -> mutableState.update { it.copy(libraryAccess = current) }
    }
  }

  fun deferLibraryAccess() {
    if (mutableState.value.profile == null) returnToLogin()
    enterPrivateSpace(runtime.libraryAccess())
  }

  fun selectLibraryTab(tab: LibraryTab) {
    mutableState.update {
      it.copy(
        currentRoute = AppRoute.PrivateSpace,
        backStack = emptyList(),
        selectedTab = MainTab.PRIVATE_SPACE,
        selectedLibraryTab = tab,
        selectedMediaAction = MediaActionState.IDLE,
        dialog = null,
      )
    }
  }

  fun openPrivateMedia(mediaId: String) = navigate(AppRoute.PrivateMediaDetail(mediaId))
  fun openFamilyMedia(mediaId: String) = navigate(AppRoute.FamilyMediaDetail(mediaId))
  fun openLocalAlbum(albumId: String) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val owner = mutableState.value.profile ?: return
    navigate(AppRoute.LocalAlbum(albumId))
    mutableState.update { it.copy(backup = it.backup.copy(localMedia = emptyList())) }
    viewModelScope.launch {
      try {
        val media = runtime.listLocalMedia(userId, albumId).map { item ->
          val instant = runCatching { Instant.parse(item.capturedAt) }.getOrElse { Instant.EPOCH }
          val dateTime = instant.atZone(ZoneId.systemDefault())
          MediaItem(
            id = item.platformAssetRef,
            title = when (item.mediaType.name) {
              "VIDEO" -> "视频"
              "GIF" -> "GIF"
              "LIVE_PHOTO", "DYNAMIC" -> "动态照片"
              else -> "照片"
            },
            kind = when (item.mediaType.name) {
              "VIDEO" -> MediaKind.VIDEO
              "GIF" -> MediaKind.GIF
              "LIVE_PHOTO", "DYNAMIC" -> MediaKind.LIVE_PHOTO
              else -> MediaKind.PHOTO
            },
            capturedAt = DATE_TIME_FORMAT.format(dateTime),
            dateGroup = DATE_GROUP_FORMAT.format(dateTime),
            sizeLabel = "本地媒体",
            owner = owner,
            colorSeed = item.platformAssetRef.hashCode(),
            imageUrl = item.thumbnailUri,
          )
        }
        mutableState.update { state ->
          if (state.profile?.id != userId || state.currentRoute != AppRoute.LocalAlbum(albumId)) state
          else state.copy(backup = state.backup.copy(localMedia = media))
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          if (state.profile?.id != userId) state
          else state.copy(backup = state.backup.copy(localMedia = emptyList()))
        }
      }
    }
  }

  fun downloadSelectedMedia() {
    mutableState.update { it.copy(selectedMediaAction = MediaActionState.DOWNLOADING) }
  }

  fun finishMockDownload(success: Boolean) {
    mutableState.update { it.copy(selectedMediaAction = if (success) MediaActionState.SAVED else MediaActionState.SAVE_FAILED) }
  }

  fun toggleShare(mediaId: String) {
    mutableState.update { state ->
      val original = state.privateSpace.items.firstOrNull { it.id == mediaId } ?: return@update state
      val toggled = original.copy(isShared = !original.isShared, sharedByMe = !original.isShared)
      val updated = state.privateSpace.items.map { media ->
        if (media.id == mediaId) toggled else media
      }
      val familyItems = if (toggled.isShared) {
        if (state.familyAlbum.items.any { it.id == mediaId }) {
          state.familyAlbum.items.map { if (it.id == mediaId) toggled else it }
        } else {
          listOf(toggled) + state.familyAlbum.items
        }
      } else {
        state.familyAlbum.items.filterNot { it.id == mediaId }
      }
      state.copy(
        privateSpace = state.privateSpace.copy(items = updated),
        familyAlbum = state.familyAlbum.copy(items = familyItems),
        selectedMediaAction = MediaActionState.SHARED,
      )
    }
  }

  fun requestDelete(mediaId: String) {
    mutableState.update { it.copy(dialog = AppDialog.DeleteMedia(mediaId)) }
  }

  fun requestRestore(mediaId: String) {
    mutableState.update { it.copy(dialog = AppDialog.RestoreMedia(mediaId)) }
  }

  fun requestLogout() {
    mutableState.update { it.copy(dialog = AppDialog.Logout) }
  }

  fun dismissDialog() {
    mutableState.update { it.copy(dialog = null) }
  }

  fun confirmDialog() {
    val current = mutableState.value
    when (val dialog = current.dialog) {
      is AppDialog.DeleteMedia -> {
        val media = current.privateSpace.items.firstOrNull { it.id == dialog.mediaId } ?: return dismissDialog()
        mutableState.value = current.copy(
          currentRoute = AppRoute.PrivateSpace,
          backStack = emptyList(),
          selectedTab = MainTab.PRIVATE_SPACE,
          selectedLibraryTab = LibraryTab.PRIVATE,
          privateSpace = current.privateSpace.copy(items = current.privateSpace.items.filterNot { it.id == dialog.mediaId }),
          familyAlbum = current.familyAlbum.copy(items = current.familyAlbum.items.filterNot { it.id == dialog.mediaId }),
          recycleBin = current.recycleBin.copy(
            loadState = PageLoadState.CONTENT,
            items = listOf(DeletedMedia(media.copy(isShared = false), "刚刚")) + current.recycleBin.items,
          ),
          dialog = null,
        )
      }
      is AppDialog.RestoreMedia -> {
        val deleted = current.recycleBin.items.firstOrNull { it.media.id == dialog.mediaId } ?: return dismissDialog()
        val remaining = current.recycleBin.items.filterNot { it.media.id == dialog.mediaId }
        mutableState.value = current.copy(
          privateSpace = current.privateSpace.copy(items = listOf(deleted.media.copy(isShared = false)) + current.privateSpace.items),
          recycleBin = current.recycleBin.copy(
            loadState = if (remaining.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
            items = remaining,
          ),
          dialog = null,
        )
      }
      AppDialog.Logout -> performLogout()
      null -> Unit
    }
  }

  fun setAutoBackupEnabled(enabled: Boolean) {
    persistBackupSettings { copy(autoBackupEnabled = enabled) }
  }

  fun setCellularBackupEnabled(enabled: Boolean) {
    persistBackupSettings { copy(allowCellularBackup = enabled) }
  }

  fun startBackup() = setAutoBackupEnabled(true)

  fun backupSingleMedia(platformAssetRef: String) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val profile = mutableState.value.profile ?: return
    val item = mutableState.value.backup.localMedia.firstOrNull { it.id == platformAssetRef } ?: return
    if (mutableState.value.backup.status == BackupStatus.UPLOADING) return
    viewModelScope.launch {
      mutableState.update { state ->
        state.copy(backup = state.backup.copy(
          status = BackupStatus.UPLOADING,
          progress = 0f,
          currentMediaTitle = item.title,
          uploadMessage = "正在上传原始媒体（不加密）…",
        ))
      }
      try {
        val result = runtime.backupSingleMedia(userId, platformAssetRef)
        val media = runtime.listOwnerMedia().map { it.toMediaItem(profile) }
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            backup = state.backup.copy(
              status = BackupStatus.COMPLETE,
              progress = 1f,
              uploadMessage = if (result.deduplicated) "媒体已存在，无需重复上传" else "上传完成",
            ),
            privateSpace = PrivateSpaceUiState(
              loadState = if (media.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
              items = media,
            ),
          )
        }
      } catch (problem: AccountProblem) {
        mutableState.update { state ->
          state.copy(backup = state.backup.copy(
            status = if (problem.retryable) BackupStatus.SERVICE_UNAVAILABLE else BackupStatus.PAUSED,
            uploadMessage = messageFor(problem),
          ))
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          state.copy(backup = state.backup.copy(
            status = BackupStatus.SERVICE_UNAVAILABLE,
            uploadMessage = "媒体上传失败，请稍后重试。",
          ))
        }
      }
    }
  }

  fun refreshLocalLibrary() {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    if (mutableState.value.libraryAccess != LibraryAccess.FULL) return
    viewModelScope.launch {
      mutableState.update {
        it.copy(backup = it.backup.copy(loadState = PageLoadState.LOADING, status = BackupStatus.SCANNING))
      }
      loadLocalLibrary(userId, forceRefresh = true)
    }
  }

  private fun persistBackupSettings(update: com.mineg.mobile.contracts.BackupSettings.() -> com.mineg.mobile.contracts.BackupSettings) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    viewModelScope.launch {
      try {
        val current = runtime.getBackupSettings(userId)
        val confirmed = runtime.updateBackupSettings(userId, current.update())
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            backup = state.backup.copy(
              autoBackupEnabled = confirmed.autoBackupEnabled,
              allowCellularBackup = confirmed.allowCellularBackup,
              status = if (state.backup.albums.isEmpty()) state.backup.status else BackupStatus.INDEXED,
            ),
          )
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            backup = state.backup.copy(loadState = PageLoadState.ERROR),
          )
        }
      }
    }
  }

  fun updateNickname(value: String) {
    if (value.length <= 20) mutableState.update {
      it.copy(profileDraftNickname = value, auth = it.auth.copy(message = null, messageIsError = false))
    }
  }

  fun saveProfile() {
    val current = mutableState.value
    val existing = current.profile ?: run {
      returnToLogin()
      return
    }
    val nickname = current.profileDraftNickname.trim()
    if (nickname.length !in 2..20 || !nickname.matches(Regex("[\\p{L}\\p{N} _-]+"))) {
      updateAuth { copy(message = "昵称需为 2～20 个中文、字母、数字、空格、连字符或下划线。", messageIsError = true) }
      return
    }
    if (current.auth.loading) return
    viewModelScope.launch {
      updateAuth { copy(loading = true, message = null, messageIsError = false) }
      try {
        val updated = runtime.updateProfile(nickname)
        if (updated.id != existing.id) throw AccountProblem("PROFILE_MISMATCH", "account.profile.mismatch", false, "")
        mutableState.update {
          it.copy(
            currentRoute = AppRoute.Profile,
            backStack = it.backStack.dropLast(1),
            profile = existing.copy(
              nickname = updated.nickname,
              maskedPhone = updated.maskedPhone,
              avatarLabel = updated.nickname.firstOrNull()?.toString() ?: "我",
              avatarUrl = updated.avatarUrl,
            ),
            profileDraftNickname = "",
            auth = AuthUiState(),
          )
        }
      } catch (problem: AccountProblem) {
        updateAuth { copy(loading = false, message = messageFor(problem), messageIsError = true) }
      } catch (_: Throwable) {
        updateAuth { copy(loading = false, message = "资料保存失败，请稍后重试。", messageIsError = true) }
      }
    }
  }

  fun updateAvatar(uri: Uri) {
    val existing = mutableState.value.profile ?: run {
      returnToLogin()
      return
    }
    if (mutableState.value.auth.loading) return
    viewModelScope.launch {
      updateAuth { copy(loading = true, message = null, messageIsError = false) }
      try {
        val updated = runtime.updateAvatar(uri)
        if (updated.id != existing.id) {
          throw AccountProblem("PROFILE_MISMATCH", "account.profile.mismatch", false, "")
        }
        mutableState.update {
          it.copy(
            profile = existing.copy(
              nickname = updated.nickname,
              maskedPhone = updated.maskedPhone,
              avatarLabel = updated.nickname.firstOrNull()?.toString() ?: "我",
              avatarUrl = updated.avatarUrl,
            ),
            auth = AuthUiState(message = "头像已更新。"),
          )
        }
      } catch (problem: AccountProblem) {
        updateAuth { copy(loading = false, message = messageFor(problem), messageIsError = true) }
      } catch (_: Throwable) {
        updateAuth { copy(loading = false, message = "头像处理或上传失败，请稍后重试。", messageIsError = true) }
      }
    }
  }

  fun setFeedbackCategory(category: FeedbackCategory) {
    mutableState.update { it.copy(feedback = it.feedback.copy(category = category, submitted = false, errorMessage = null)) }
  }

  fun updateFeedbackDescription(value: String) {
    mutableState.update { it.copy(feedback = it.feedback.copy(description = value, submitted = false, errorMessage = null)) }
  }

  fun updateFeedbackContact(value: String) {
    mutableState.update { it.copy(feedback = it.feedback.copy(contact = value, submitted = false)) }
  }

  fun submitFeedback() {
    mutableState.update {
      val feedback = it.feedback
      it.copy(
        feedback = if (feedback.description.trim().length < 5) {
          feedback.copy(errorMessage = "请至少输入 5 个字符的问题描述")
        } else {
          feedback.copy(submitted = true, submitting = false, errorMessage = null)
        },
      )
    }
  }

  fun mediaById(id: String): MediaItem? =
    (mutableState.value.privateSpace.items + mutableState.value.familyAlbum.items + mutableState.value.recycleBin.items.map { it.media })
      .firstOrNull { it.id == id }

  private suspend fun routeSession(session: AccountRouteSnapshot, allowCachedProfile: Boolean) {
    currentUserId = session.userId
    when (session.nextStep) {
      AccountNextStep.REVIEW_PENDING -> {
        mutableState.value = MineGAppState(
          currentRoute = AppRoute.ReviewPending,
          auth = AuthUiState(),
        )
        startReviewPolling()
      }
      AccountNextStep.APP_HOME -> loadApprovedProfile(session.userId, allowCachedProfile)
    }
  }

  private suspend fun loadApprovedProfile(userId: String, allowCached: Boolean) {
    stopReviewPolling()
    val profile = runtime.loadProfile(userId, allowCached)
    if (profile.id != userId) {
      throw AccountProblem("PROFILE_MISMATCH", "account.profile.mismatch", false, "")
    }
    val userProfile = UserProfile(
      id = profile.id,
      nickname = profile.nickname,
      maskedPhone = profile.maskedPhone,
      avatarLabel = profile.nickname.firstOrNull()?.toString() ?: "我",
      avatarUrl = profile.avatarUrl,
    )
    val access = runtime.libraryAccess()
    mutableState.value = MineGAppState(
      currentRoute = if (access == LibraryAccess.FULL) AppRoute.PrivateSpace else AppRoute.Permission,
      selectedTab = MainTab.PRIVATE_SPACE,
      profile = userProfile,
      libraryAccess = access,
      privateSpace = PrivateSpaceUiState(loadState = PageLoadState.LOADING),
      familyAlbum = FamilyAlbumUiState(loadState = PageLoadState.EMPTY),
      backup = BackupUiState(
        loadState = if (access == LibraryAccess.FULL) PageLoadState.LOADING else PageLoadState.EMPTY,
        status = if (access == LibraryAccess.FULL) BackupStatus.SCANNING else BackupStatus.PERMISSION_REQUIRED,
      ),
      recycleBin = RecycleBinUiState(loadState = PageLoadState.EMPTY),
    )
    loadHomeModels(userId, userProfile, access)
  }

  private fun enterPrivateSpace(access: LibraryAccess) {
    val profile = mutableState.value.profile ?: run {
      showLogin("请先登录后继续。", true)
      return
    }
    mutableState.update {
      it.copy(
        currentRoute = AppRoute.PrivateSpace,
        backStack = emptyList(),
        selectedTab = MainTab.PRIVATE_SPACE,
        libraryAccess = access,
        backup = it.backup.copy(
          loadState = if (access == LibraryAccess.FULL) PageLoadState.LOADING else it.backup.loadState,
          status = if (access == LibraryAccess.FULL) BackupStatus.SCANNING else BackupStatus.PERMISSION_REQUIRED,
        ),
        auth = AuthUiState(),
      )
    }
    val userId = currentUserId ?: profile.id.also { currentUserId = it }
    viewModelScope.launch { loadHomeModels(userId, profile, access) }
  }

  private suspend fun loadHomeModels(userId: String, profile: UserProfile, access: LibraryAccess) {
    try {
      val media = runtime.listOwnerMedia().map { it.toMediaItem(profile) }
      mutableState.update { state ->
        if (state.profile?.id != userId) state else state.copy(
          privateSpace = PrivateSpaceUiState(
            loadState = if (media.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
            items = media,
          ),
        )
      }
    } catch (problem: AccountProblem) {
      if (problem.code in SESSION_ERRORS) {
        performLogout("登录状态已失效，请重新登录。")
        return
      }
      mutableState.update { state ->
        if (state.profile?.id != userId) state else state.copy(
          privateSpace = PrivateSpaceUiState(
            loadState = PageLoadState.ERROR,
            errorMessage = messageFor(problem),
          ),
        )
      }
    } catch (_: Throwable) {
      mutableState.update { state ->
        if (state.profile?.id != userId) state else state.copy(
          privateSpace = PrivateSpaceUiState(loadState = PageLoadState.ERROR, errorMessage = "私人空间加载失败，请稍后重试。"),
        )
      }
    }

    if (access != LibraryAccess.FULL || mutableState.value.profile?.id != userId) return
    loadLocalLibrary(userId, forceRefresh = false)
  }

  private suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean) {
    try {
      val settings = runtime.getBackupSettings(userId)
      val local = runtime.loadLocalLibrary(userId, forceRefresh)
      val albums = local.albums.map { album ->
        LocalAlbum(
          id = album.platformAlbumRef,
          name = album.name,
          mediaCount = album.mediaCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          mediaIds = emptyList(),
          coverUrls = listOfNotNull(album.coverThumbnailUri),
        )
      }
      mutableState.update { state ->
        if (state.profile?.id != userId) state else state.copy(
          backup = state.backup.copy(
            loadState = if (albums.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
            status = BackupStatus.INDEXED,
            progress = 1f,
            indexedCount = local.summary.indexedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            totalCount = local.summary.indexedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            autoBackupEnabled = settings.autoBackupEnabled,
            allowCellularBackup = settings.allowCellularBackup,
            albums = albums,
          ),
        )
      }
    } catch (_: Throwable) {
      mutableState.update { state ->
        if (state.profile?.id != userId) state else state.copy(
          backup = state.backup.copy(loadState = PageLoadState.ERROR, status = BackupStatus.SERVICE_UNAVAILABLE),
        )
      }
    }
  }

  private fun OwnerMediaSummary.toMediaItem(owner: UserProfile): MediaItem {
    val instant = runCatching { Instant.parse(capturedAt) }.getOrElse { Instant.EPOCH }
    val dateTime = instant.atZone(ZoneId.systemDefault())
    val kind = when (mediaType) {
      "VIDEO" -> MediaKind.VIDEO
      "GIF" -> MediaKind.GIF
      "LIVE_PHOTO", "DYNAMIC" -> MediaKind.LIVE_PHOTO
      else -> MediaKind.PHOTO
    }
    return MediaItem(
      id = id,
      title = when (kind) {
        MediaKind.VIDEO -> "视频"
        MediaKind.GIF -> "GIF"
        MediaKind.LIVE_PHOTO -> "动态照片"
        MediaKind.PHOTO -> "照片"
      },
      kind = kind,
      capturedAt = DATE_TIME_FORMAT.format(dateTime),
      dateGroup = DATE_GROUP_FORMAT.format(dateTime),
      sizeLabel = "已安全备份",
      owner = owner,
      colorSeed = id.hashCode(),
    )
  }

  private fun startReviewPolling() {
    if (reviewPollingJob?.isActive == true) return
    reviewPollingJob = viewModelScope.launch {
      while (mutableState.value.currentRoute == AppRoute.ReviewPending) {
        delay(10_000)
        if (mutableState.value.currentRoute == AppRoute.ReviewPending) refreshReviewStatus(manual = false)
      }
    }
  }

  private fun stopReviewPolling() {
    reviewPollingJob?.cancel()
    reviewPollingJob = null
  }

  private fun performLogout(message: String? = null) {
    stopReviewPolling()
    mutableState.update { it.copy(currentRoute = AppRoute.Restoring, backStack = emptyList(), dialog = null) }
    viewModelScope.launch {
      runCatching { runtime.signOut() }
      currentUserId = null
      showLogin(message)
    }
  }

  private fun showLogin(message: String? = null, isError: Boolean = false) {
    stopReviewPolling()
    currentUserId = null
    mutableState.value = MineGAppState(
      currentRoute = AppRoute.Login,
      auth = AuthUiState(message = message, messageIsError = isError),
    )
  }

  private fun showProblem(problem: AccountProblem) {
    val field = when (problem.code) {
      "PHONE_INVALID", "PHONE_ALREADY_REGISTERED" -> "phone"
      "PASSWORD_INVALID", "CREDENTIALS_INVALID" -> "password"
      "AGREEMENT_REQUIRED" -> "agreement"
      else -> null
    }
    updateAuth {
      copy(
        loading = false,
        reviewSyncing = false,
        fieldErrors = if (field == null) emptyMap() else mapOf(field to messageFor(problem)),
        message = if (field == null) messageFor(problem) else null,
        messageIsError = true,
      )
    }
  }

  private fun messageFor(problem: AccountProblem): String = when (problem.code) {
    "PHONE_INVALID" -> "请输入有效的中国大陆手机号。"
    "PHONE_ALREADY_REGISTERED" -> "该手机号已经注册，请直接登录。"
    "PASSWORD_INVALID" -> "密码需为 8～64 位，并同时包含字母和数字。"
    "CREDENTIALS_INVALID" -> "手机号或密码错误。"
    "AGREEMENT_REQUIRED" -> "请先同意服务协议和隐私政策。"
    "ACCOUNT_PENDING" -> "账号仍在等待管理员审核。"
    "NETWORK_UNAVAILABLE" -> "网络不可用，请检查连接后重试。"
    "SERVICE_UNAVAILABLE" -> "服务暂时不可用，请稍后重试。"
    "PROFILE_MISMATCH" -> "账号资料校验失败，请重新登录。"
    else -> "操作失败，请稍后重试。"
  }

  private fun updateAuth(update: AuthUiState.() -> AuthUiState) {
    mutableState.update { it.copy(auth = it.auth.update()) }
  }

  override fun onCleared() {
    stopReviewPolling()
    runtime.close()
    super.onCleared()
  }

  companion object {
    private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
    private val DATE_GROUP_FORMAT = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val SESSION_ERRORS = setOf("AUTH_REQUIRED", "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REPLAYED")

    fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MineGAppViewModel::class.java))
        return MineGAppViewModel(AndroidMineGAppRuntime(context)) as T
      }
    }
  }
}

private fun AppRoute.requiresProfile(): Boolean = when (this) {
  AppRoute.PrivateSpace,
  AppRoute.Backup,
  AppRoute.Profile,
  is AppRoute.PrivateMediaDetail,
  is AppRoute.FamilyMediaDetail,
  AppRoute.SharedByMe,
  is AppRoute.LocalAlbum,
  AppRoute.BackupSettings,
  AppRoute.ProfileEdit,
  AppRoute.RecycleBin,
  AppRoute.HelpFeedback,
  AppRoute.Permission,
  -> true
  AppRoute.Restoring,
  AppRoute.Login,
  AppRoute.SignUp,
  AppRoute.ReviewPending,
  is AppRoute.Legal,
  -> false
}
