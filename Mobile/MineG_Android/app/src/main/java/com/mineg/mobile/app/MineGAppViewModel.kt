package com.mineg.mobile.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MineGAppViewModel : ViewModel() {
  private val mutableState = MutableStateFlow(MockMineGRepository.initialState())
  val state: StateFlow<MineGAppState> = mutableState.asStateFlow()

  fun navigate(route: AppRoute) {
    mutableState.update { current ->
      if (current.currentRoute == route) current
      else current.copy(
        currentRoute = route,
        backStack = current.backStack + current.currentRoute,
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
    mutableState.value = MockMineGRepository.initialState()
  }

  fun debugNavigate(route: AppRoute) {
    when (route) {
      AppRoute.PrivateSpace -> selectTab(MainTab.PRIVATE_SPACE)
      AppRoute.FamilyAlbum -> selectTab(MainTab.FAMILY_ALBUM)
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
      it.copy(
        currentRoute = tab.route(),
        backStack = emptyList(),
        selectedTab = tab,
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
      if (!auth.phone.matches(Regex("1\\d{10}"))) put("phone", "请输入有效的中国大陆手机号")
      if (auth.password.length < 8) put("password", "密码至少需要 8 个字符")
      if (!auth.agreementAccepted) put("agreement", "请先阅读并同意服务协议与隐私政策")
    }
    if (errors.isNotEmpty()) {
      updateAuth { copy(fieldErrors = errors) }
      return
    }
    mutableState.update {
      it.copy(currentRoute = AppRoute.Permission, backStack = emptyList(), auth = auth.copy(message = null))
    }
  }

  fun openSignUp() = navigate(AppRoute.SignUp)

  fun submitSignUp() {
    val auth = mutableState.value.auth
    val errors = buildMap {
      if (!auth.phone.matches(Regex("1\\d{10}"))) put("phone", "手机号格式不正确")
      if (auth.password.length < 8) put("password", "密码至少需要 8 个字符")
      if (auth.password != auth.passwordConfirmation) put("passwordConfirmation", "两次输入的密码不一致")
    }
    if (errors.isNotEmpty()) {
      updateAuth { copy(fieldErrors = errors) }
      return
    }
    mutableState.update {
      it.copy(currentRoute = AppRoute.ReviewPending, backStack = emptyList(), auth = auth.copy(reviewSyncing = false, message = null))
    }
  }

  fun refreshReviewStatus() {
    mutableState.update {
      it.copy(currentRoute = AppRoute.Permission, backStack = emptyList(), auth = it.auth.copy(reviewSyncing = false, message = "Mock 审核已通过"))
    }
  }

  fun returnToLogin() {
    mutableState.update {
      it.copy(
        currentRoute = AppRoute.Login,
        backStack = emptyList(),
        auth = it.auth.copy(fieldErrors = emptyMap(), message = null),
        dialog = null,
        debugPanelVisible = false,
      )
    }
  }

  fun grantLibraryAccess() {
    mutableState.update {
      it.copy(
        currentRoute = AppRoute.PrivateSpace,
        backStack = emptyList(),
        selectedTab = MainTab.PRIVATE_SPACE,
        libraryAccess = LibraryAccess.FULL,
        backup = it.backup.copy(status = BackupStatus.SCANNING, progress = 0.24f),
      )
    }
  }

  fun deferLibraryAccess() {
    mutableState.update {
      it.copy(
        currentRoute = AppRoute.PrivateSpace,
        backStack = emptyList(),
        selectedTab = MainTab.PRIVATE_SPACE,
        backup = it.backup.copy(status = BackupStatus.PERMISSION_REQUIRED),
      )
    }
  }

  fun setFamilyFilter(filter: FamilyFilter) {
    mutableState.update { it.copy(familyAlbum = it.familyAlbum.copy(filter = filter)) }
  }

  fun openPrivateMedia(mediaId: String) = navigate(AppRoute.PrivateMediaDetail(mediaId))
  fun openFamilyMedia(mediaId: String) = navigate(AppRoute.FamilyMediaDetail(mediaId))
  fun openLocalAlbum(albumId: String) = navigate(AppRoute.LocalAlbum(albumId))

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
      AppDialog.Logout -> mutableState.value = MockMineGRepository.initialState().copy(
        currentRoute = AppRoute.Login,
        libraryAccess = LibraryAccess.NOT_DETERMINED,
      )
      null -> Unit
    }
  }

  fun setAutoBackupEnabled(enabled: Boolean) {
    mutableState.update {
      it.copy(backup = it.backup.copy(autoBackupEnabled = enabled, status = if (enabled) BackupStatus.UPLOADING else BackupStatus.PAUSED))
    }
  }

  fun setCellularBackupEnabled(enabled: Boolean) {
    mutableState.update { it.copy(backup = it.backup.copy(allowCellularBackup = enabled)) }
  }

  fun startBackup() = setAutoBackupEnabled(true)

  fun updateNickname(value: String) {
    if (value.length <= 20) mutableState.update { it.copy(profile = it.profile.copy(nickname = value)) }
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

  private fun updateAuth(update: AuthUiState.() -> AuthUiState) {
    mutableState.update { it.copy(auth = it.auth.update()) }
  }
}
