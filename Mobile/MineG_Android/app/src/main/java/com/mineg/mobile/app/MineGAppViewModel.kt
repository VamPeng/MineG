package com.mineg.mobile.app
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateMapOf
import com.mineg.mobile.account.AccountValidation
import com.mineg.mobile.account.BackupOverview
import com.mineg.mobile.account.LocalAlbumBackupProgress
import com.mineg.mobile.BuildConfig
import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaSummary
import com.mineg.mobile.contracts.FamilyMediaDetail
import com.mineg.mobile.contracts.FamilyMediaSummary
import com.mineg.mobile.contracts.TrashMediaSummary
import com.mineg.mobile.contracts.TrashMediaPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
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
  private var backupOverviewJob: Job? = null
  private val privateMediaDetails = mutableMapOf<String, PrivateMediaDetail>()
  private var privatePreviewVisibleIds: Set<String> = emptySet()
  private val privatePreviewPending = ArrayDeque<String>()
  private val privatePreviewPendingIds = mutableSetOf<String>()
  private val privatePreviewActiveIds = mutableSetOf<String>()
  private val privatePreviewPinnedIds = mutableSetOf<String>()
  private val privatePreviewHandleOrder = ArrayDeque<String>()
  private val privatePreviewHandles = mutableMapOf<String, String>()
  private val mutablePrivateMediaPreviewSources = mutableStateMapOf<String, String>()
  val privateMediaPreviewSources: Map<String, String> get() = mutablePrivateMediaPreviewSources
  private val familyPreviewHandles = mutableMapOf<String, String>()
  private val mutableFamilyMediaPreviewSources = mutableStateMapOf<String, String>()

  init {
    if (autoRestore) restoreAuthentication()
  }

  fun restoreAuthentication() {
    stopReviewPolling()
    currentUserId = null
    privateMediaDetails.clear()
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
    when (route) {
      AppRoute.RecycleBin -> refreshRecycleBin()
      AppRoute.SharedByMe -> refreshFamilyMedia("mine")
      else -> Unit
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
    if (tab == MainTab.BACKUP) refreshBackupOverview()
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
    }
    if (errors.isNotEmpty()) {
      updateAuth { copy(fieldErrors = errors) }
      return
    }
    if (auth.loading) return
    viewModelScope.launch {
      updateAuth { copy(loading = true, fieldErrors = emptyMap(), message = null, messageIsError = false) }
      try {
        routeSession(runtime.signIn(auth.phone, auth.password, true), allowCachedProfile = true)
      } catch (problem: AccountProblem) {
        showProblem(problem)
      } catch (_: Throwable) {
        updateAuth { copy(loading = false, message = "登录失败，请稍后重试。", messageIsError = true) }
      }
    }
  }

  fun openSignUp() {
    updateAuth { AuthUiState() }
    navigate(AppRoute.SignUp)
  }

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
    if (tab == LibraryTab.SHARED) refreshFamilyMedia("all")
  }

  fun updatePrivateMediaListScrollPosition(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
  ) {
    val position = mediaListScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    mutableState.update { state ->
      if (state.profile == null || state.privateMediaListScrollPosition == position) state
      else state.copy(privateMediaListScrollPosition = position)
    }
  }

  fun updateFamilyMediaListScrollPosition(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
  ) {
    val position = mediaListScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    mutableState.update { state ->
      if (state.profile == null || state.familyMediaListScrollPosition == position) state
      else state.copy(familyMediaListScrollPosition = position)
    }
  }

  fun openPrivateMedia(mediaId: String) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    mutableState.update { state ->
      state.copy(privateSpace = state.privateSpace.copy(
        items = state.privateSpace.items.map { item ->
          if (item.id == mediaId) item.copy(detailImageUrl = null) else item
        },
      ))
    }
    navigate(AppRoute.PrivateMediaDetail(mediaId))
    viewModelScope.launch {
      try {
        val detail = runtime.getPrivateMediaDetail(mediaId)
        if (currentUserId != userId) return@launch
        privateMediaDetails[mediaId] = detail
        val owner = mutableState.value.privateSpace.items.firstOrNull { it.id == mediaId }?.owner ?: return@launch
        val detailItem = detail.toMediaItem(owner)
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            privateSpace = state.privateSpace.copy(
              items = state.privateSpace.items.map { item ->
                if (item.id == mediaId) detailItem else item
              },
            ),
          )
        }
        val originalSource = runtime.resolvePrivateMediaOriginal(userId, detail) ?: return@launch
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            privateSpace = state.privateSpace.copy(
              items = state.privateSpace.items.map { item ->
                if (item.id == mediaId) item.copy(detailImageUrl = originalSource) else item
              },
            ),
          )
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) performLogout("登录状态已失效，请重新登录。")
      }
    }
  }
  fun openFamilyMedia(mediaId: String) {
    navigate(AppRoute.FamilyMediaDetail(mediaId))
    viewModelScope.launch {
      try {
        val detail = runtime.getFamilyMediaDetail(mediaId)
        mutableState.update { state ->
          state.copy(
            familyAlbum = state.familyAlbum.copy(
              items = state.familyAlbum.items.map { item ->
                if (item.id == mediaId) detail.toMediaItem(state.profile) else item
              },
            ),
          )
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state ->
            state.copy(familyAlbum = state.familyAlbum.copy(errorMessage = messageFor(problem)))
          }
        }
      }
    }
  }

  fun loadFamilyMediaPreview(mediaId: String) {
    if (mediaId in mutableFamilyMediaPreviewSources) return
    if (mutableState.value.familyAlbum.items.none { it.id == mediaId }) return
    viewModelScope.launch {
      try {
        val view = runtime.openFamilyMedia(mediaId)
        familyPreviewHandles.put(mediaId, view.viewHandle)?.let { previous ->
          runCatching { runtime.closeFamilyMedia(previous) }
        }
        mutableFamilyMediaPreviewSources[mediaId] = view.sourceUri
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) performLogout("登录状态已失效，请重新登录。")
      }
    }
  }

  fun loadMorePrivateMedia() {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val profile = mutableState.value.profile ?: return
    val privateSpace = mutableState.value.privateSpace
    if (privateSpace.loadingMore || privateSpace.fullyLoaded || privateSpace.loadState != PageLoadState.CONTENT) return
    mutableState.update { state -> state.copy(privateSpace = state.privateSpace.copy(loadingMore = true, errorMessage = null)) }
    viewModelScope.launch {
      try {
        val page = runtime.loadMorePrivateMedia()
        mutableState.update { state ->
          if (state.profile?.id != userId) state else {
            val existingIds = state.privateSpace.items.mapTo(mutableSetOf(), MediaItem::id)
            val additional = page.items
              .filter { existingIds.add(it.id) }
              .map { it.toMediaItem(profile) }
            state.copy(privateSpace = state.privateSpace.copy(
              items = state.privateSpace.items + additional,
              fullyLoaded = page.fullyLoaded,
              loadingMore = false,
              errorMessage = null,
            ))
          }
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state ->
            if (state.profile?.id != userId) state else state.copy(
              privateSpace = state.privateSpace.copy(loadingMore = false, errorMessage = messageFor(problem)),
            )
          }
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            privateSpace = state.privateSpace.copy(loadingMore = false, errorMessage = "加载更多失败，请稍后重试。"),
          )
        }
      }
    }
  }

  fun refreshPrivateMedia() {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val profile = mutableState.value.profile ?: return
    if (mutableState.value.privateSpace.loadState == PageLoadState.LOADING) return
    val previewHandles = privatePreviewHandles.values.toList()
    clearPrivatePreviewRequests()
    privatePreviewHandleOrder.clear()
    privatePreviewHandles.clear()
    mutablePrivateMediaPreviewSources.clear()
    mutableState.update { state -> state.copy(
      privateSpace = state.privateSpace.copy(loadState = PageLoadState.LOADING, errorMessage = null),
    ) }
    viewModelScope.launch {
      try {
        val page = runtime.refreshPrivateMedia()
        previewHandles.forEach { handle -> runCatching { runtime.closePrivateMedia(handle) } }
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            privateSpace = PrivateSpaceUiState(
              loadState = if (page.items.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
              items = page.items.map { it.toMediaItem(profile) },
              fullyLoaded = page.fullyLoaded,
            ),
          )
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state ->
            if (state.profile?.id != userId) state else state.copy(
              privateSpace = state.privateSpace.copy(
                loadState = PageLoadState.ERROR,
                errorMessage = messageFor(problem),
              ),
            )
          }
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            privateSpace = state.privateSpace.copy(
              loadState = PageLoadState.ERROR,
              errorMessage = "私人空间加载失败，请稍后重试。",
            ),
          )
        }
      }
    }
  }

  fun loadPrivateMediaPreview(mediaId: String) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    enqueuePrivateMediaPreview(userId, mediaId, pinned = true, prioritize = true)
  }

  fun updateVisiblePrivateMedia(mediaIds: List<String>) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val visibleIds = mediaIds.toCollection(LinkedHashSet())
    if (visibleIds == privatePreviewVisibleIds) return
    privatePreviewVisibleIds = visibleIds
    val iterator = privatePreviewPending.iterator()
    while (iterator.hasNext()) {
      val mediaId = iterator.next()
      if (mediaId !in privatePreviewVisibleIds && mediaId !in privatePreviewPinnedIds) {
        iterator.remove()
        privatePreviewPendingIds.remove(mediaId)
      }
    }
    mediaIds.forEach { mediaId ->
      enqueuePrivateMediaPreview(userId, mediaId, pinned = false, prioritize = false)
    }
    trimPrivatePreviewHandles()
    MediaLoadLog.debug(
      "preview-scheduler visible=${privatePreviewVisibleIds.size} " +
        "queued=${privatePreviewPending.size} active=${privatePreviewActiveIds.size}",
    )
  }

  fun retryPrivateMediaPreview(mediaId: String) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val privateSpace = mutableState.value.privateSpace
    if (privateSpace.items.none { it.id == mediaId } ||
      mediaId in privatePreviewActiveIds || mediaId in privatePreviewPendingIds) return
    val previousHandle = privatePreviewHandles.remove(mediaId)
    privatePreviewHandleOrder.remove(mediaId)
    mutablePrivateMediaPreviewSources.remove(mediaId)
    mutableState.update { state ->
      if (state.profile?.id != userId || state.privateSpace.items.none { it.id == mediaId }) state
      else state.copy(privateSpace = state.privateSpace.copy(
        previewLoadingIds = state.privateSpace.previewLoadingIds - mediaId,
        previewUnavailableIds = state.privateSpace.previewUnavailableIds - mediaId,
      ))
    }
    viewModelScope.launch {
      previousHandle?.let { handle -> runCatching { runtime.closePrivateMedia(handle) } }
      runCatching { runtime.invalidatePrivateMediaThumbnail(userId, mediaId) }
      enqueuePrivateMediaPreview(userId, mediaId, pinned = true, prioritize = true)
    }
  }

  fun openLocalAlbum(albumId: String) {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val owner = mutableState.value.profile ?: return
    navigate(AppRoute.LocalAlbum(albumId))
    mutableState.update {
      it.copy(backup = it.backup.copy(
        localMedia = emptyList(),
        localMediaNextCursor = null,
        localMediaInitialLoading = true,
        localMediaLoadingMore = false,
        localMediaFullyLoaded = false,
        localMediaErrorMessage = null,
        albumCompletedCount = null,
        albumTotalCount = null,
        localMediaSyncStates = emptyMap(),
      ))
    }
    viewModelScope.launch {
      try {
        val page = runtime.listLocalMedia(userId, albumId)
        val backupProgress = runCatching {
          runtime.getLocalAlbumBackupProgress(userId, albumId)
        }.getOrNull()
        val media = page.items.map { it.toMediaItem(owner) }
        mutableState.update { state ->
          if (state.profile?.id != userId || state.currentRoute != AppRoute.LocalAlbum(albumId)) state
          else state.copy(backup = state.backup.copy(
            localMedia = media,
            localMediaNextCursor = page.nextCursor,
            localMediaInitialLoading = false,
            localMediaLoadingMore = false,
            localMediaFullyLoaded = page.nextCursor == null,
            localMediaErrorMessage = null,
            albumCompletedCount = backupProgress?.completedCount,
            albumTotalCount = backupProgress?.totalCount,
            localMediaSyncStates = backupProgress.toLocalMediaSyncStates(),
          ))
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          if (state.profile?.id != userId) state
          else state.copy(backup = state.backup.copy(
            localMedia = emptyList(),
            localMediaNextCursor = null,
            localMediaInitialLoading = false,
            localMediaLoadingMore = false,
            localMediaFullyLoaded = false,
            localMediaErrorMessage = "本地媒体加载失败，请稍后重试。",
            localMediaSyncStates = emptyMap(),
          ))
        }
      }
    }
  }

  fun loadMoreLocalAlbumMedia() {
    val route = mutableState.value.currentRoute as? AppRoute.LocalAlbum ?: return
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    val owner = mutableState.value.profile ?: return
    val backup = mutableState.value.backup
    val retryInitialPage = backup.localMedia.isEmpty() &&
      backup.localMediaNextCursor == null && backup.localMediaErrorMessage != null
    if (backup.localMediaInitialLoading || backup.localMediaLoadingMore || backup.localMediaFullyLoaded ||
      (backup.localMediaNextCursor == null && !retryInitialPage)) return
    mutableState.update { state ->
      state.copy(backup = state.backup.copy(
        localMediaInitialLoading = retryInitialPage,
        localMediaLoadingMore = !retryInitialPage,
        localMediaErrorMessage = null,
      ))
    }
    viewModelScope.launch {
      try {
        val page = runtime.listLocalMedia(
          userId = userId,
          albumRef = route.albumId,
          cursor = if (retryInitialPage) null else backup.localMediaNextCursor,
        )
        val loaded = page.items.map { it.toMediaItem(owner) }
        mutableState.update { state ->
          if (state.profile?.id != userId || state.currentRoute != route) state
          else {
            val existingIds = state.backup.localMedia.mapTo(mutableSetOf(), MediaItem::id)
            val additional = loaded.filter { existingIds.add(it.id) }
            state.copy(backup = state.backup.copy(
              localMedia = if (retryInitialPage) loaded else state.backup.localMedia + additional,
              localMediaNextCursor = page.nextCursor,
              localMediaInitialLoading = false,
              localMediaLoadingMore = false,
              localMediaFullyLoaded = page.nextCursor == null,
              localMediaErrorMessage = null,
            ))
          }
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          if (state.profile?.id != userId || state.currentRoute != route) state
          else state.copy(backup = state.backup.copy(
            localMediaInitialLoading = false,
            localMediaLoadingMore = false,
            localMediaErrorMessage = "本地媒体加载失败，请稍后重试。",
          ))
        }
      }
    }
  }

  fun downloadSelectedMedia() {
    val mediaId = (mutableState.value.currentRoute as? AppRoute.PrivateMediaDetail)?.mediaId ?: return
    val userId = currentUserId ?: mutableState.value.profile?.id
    val detail = privateMediaDetails[mediaId]
    if (userId == null || detail == null) {
      mutableState.update { state ->
        if (state.currentRoute == AppRoute.PrivateMediaDetail(mediaId)) {
          state.copy(selectedMediaAction = MediaActionState.SAVE_FAILED)
        } else {
          state
        }
      }
      return
    }
    viewModelScope.launch {
      mutableState.update { it.copy(selectedMediaAction = MediaActionState.DOWNLOADING) }
      try {
        val result = runtime.savePrivateMediaToSystemAlbum(userId, detail)
        mutableState.update { state ->
          if (state.currentRoute == AppRoute.PrivateMediaDetail(mediaId)) {
            state.copy(selectedMediaAction = if (result.state == "COMPLETED") {
              MediaActionState.SAVED
            } else {
              MediaActionState.SAVE_FAILED
            })
          } else {
            state
          }
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state ->
            if (state.currentRoute == AppRoute.PrivateMediaDetail(mediaId)) {
              state.copy(selectedMediaAction = MediaActionState.SAVE_FAILED)
            } else {
              state
            }
          }
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          if (state.currentRoute == AppRoute.PrivateMediaDetail(mediaId)) {
            state.copy(selectedMediaAction = MediaActionState.SAVE_FAILED)
          } else {
            state
          }
        }
      }
    }
  }

  fun dismissSelectedMediaAction() {
    mutableState.update { it.copy(selectedMediaAction = MediaActionState.IDLE) }
  }

  fun setSelectedMediaSharing(mediaId: String) {
    val original = mutableState.value.privateSpace.items.firstOrNull { it.id == mediaId } ?: return
    val targetShared = !original.isShared
    viewModelScope.launch {
      try {
        val result = runtime.setPrivateMediaShare(mediaId, targetShared)
        val confirmedShared = result.state == "ACTIVE"
        mutableState.update { state ->
          val current = state.privateSpace.items.firstOrNull { it.id == mediaId } ?: return@update state
          val confirmed = current.copy(isShared = confirmedShared, sharedByMe = confirmedShared)
          val familyItems = if (confirmedShared) {
            if (state.familyAlbum.items.any { it.id == mediaId }) {
              state.familyAlbum.items.map { if (it.id == mediaId) confirmed else it }
            } else {
              listOf(confirmed) + state.familyAlbum.items
            }
          } else {
            state.familyAlbum.items.filterNot { it.id == mediaId }
          }
          state.copy(
            privateSpace = state.privateSpace.copy(
              items = state.privateSpace.items.map { if (it.id == mediaId) confirmed else it },
              errorMessage = null,
            ),
            familyAlbum = state.familyAlbum.copy(
              loadState = if (familyItems.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
              items = familyItems,
            ),
            selectedMediaAction = if (confirmedShared) MediaActionState.SHARED else MediaActionState.IDLE,
          )
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state ->
            state.copy(privateSpace = state.privateSpace.copy(errorMessage = messageFor(problem)))
          }
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          state.copy(privateSpace = state.privateSpace.copy(errorMessage = "共享状态更新失败，请稍后重试。"))
        }
      }
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
        if (current.privateSpace.items.none { it.id == dialog.mediaId }) return dismissDialog()
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launch {
          try {
            runtime.trashPrivateMedia(dialog.mediaId)
            privateMediaDetails.remove(dialog.mediaId)
            privatePreviewHandles.remove(dialog.mediaId)?.let { handle ->
              runtime.closePrivateMedia(handle)
            }
            privatePreviewHandleOrder.remove(dialog.mediaId)
            mutablePrivateMediaPreviewSources.remove(dialog.mediaId)
            val page = runtime.getPrivateMediaPage() ?: runtime.refreshPrivateMedia()
            mutableState.update { state ->
              val owner = state.profile ?: return@update state
              val retainedIds = page.items.mapTo(mutableSetOf()) { it.id }
              state.copy(
                currentRoute = AppRoute.PrivateSpace,
                backStack = emptyList(),
                selectedTab = MainTab.PRIVATE_SPACE,
                selectedLibraryTab = LibraryTab.PRIVATE,
                privateSpace = state.privateSpace.copy(
                  loadState = if (page.items.isEmpty()) {
                    PageLoadState.EMPTY
                  } else {
                    PageLoadState.CONTENT
                  },
                  items = page.items.map { summary -> summary.toMediaItem(owner) },
                  fullyLoaded = page.fullyLoaded,
                  previewLoadingIds = state.privateSpace.previewLoadingIds.intersect(retainedIds),
                  previewUnavailableIds = state.privateSpace.previewUnavailableIds.intersect(retainedIds),
                  errorMessage = null,
                ),
              )
            }
          } catch (problem: AccountProblem) {
            mutableState.update { state -> state.copy(
              privateSpace = state.privateSpace.copy(errorMessage = messageFor(problem)),
            ) }
          } catch (_: Throwable) {
            mutableState.update { state -> state.copy(
              privateSpace = state.privateSpace.copy(errorMessage = "删除失败，请稍后重试。"),
            ) }
          }
        }
      }
      is AppDialog.RestoreMedia -> {
        if (current.recycleBin.items.none { it.media.id == dialog.mediaId }) return dismissDialog()
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launch {
          try {
            runtime.restoreTrashMedia(dialog.mediaId)
            val trash = runtime.refreshTrashMedia()
            val privatePage = runtime.refreshPrivateMedia()
            mutableState.update { state ->
              val owner = state.profile ?: return@update state
              state.copy(
                privateSpace = state.privateSpace.copy(
                  loadState = if (privatePage.items.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
                  items = privatePage.items.map { it.toMediaItem(owner) },
                  fullyLoaded = privatePage.fullyLoaded,
                  errorMessage = null,
                ),
                recycleBin = trash.toUiState(owner),
              )
            }
          } catch (problem: AccountProblem) {
            if (problem.code in SESSION_ERRORS) {
              performLogout("登录状态已失效，请重新登录。")
            } else {
              mutableState.update { state ->
                state.copy(recycleBin = state.recycleBin.copy(
                  loadState = PageLoadState.ERROR,
                  errorMessage = messageFor(problem),
                ))
              }
            }
          } catch (_: Throwable) {
            mutableState.update { state ->
              state.copy(recycleBin = state.recycleBin.copy(
                loadState = PageLoadState.ERROR,
                errorMessage = "恢复失败，请稍后重试。",
              ))
            }
          }
        }
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
    val item = mutableState.value.backup.localMedia.firstOrNull { it.id == platformAssetRef } ?: return
    viewModelScope.launch {
      mutableState.update { state ->
        state.copy(backup = state.backup.copy(
          status = BackupStatus.UPLOADING,
          progress = 0f,
          currentMediaTitle = item.title,
          uploadMessage = null,
          localMediaSyncStates = state.backup.localMediaSyncStates +
            (platformAssetRef to LocalMediaSyncState.SYNCING),
        ))
      }
      startBackupOverviewPolling(userId)
      try {
        runtime.enqueueBackupMedia(userId, platformAssetRef)
        val overview = runtime.getBackupOverview(userId)
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            backup = state.backup.copy(
              status = overview.toUiStatus().takeUnless { it == BackupStatus.PAUSED } ?: BackupStatus.UPLOADING,
              progress = 0f,
              uploadMessage = null,
              localMediaSyncStates = state.backup.localMediaSyncStates +
                (platformAssetRef to LocalMediaSyncState.SYNCING),
            ),
          )
        }
      } catch (problem: AccountProblem) {
        mutableState.update { state ->
          state.copy(backup = state.backup.copy(
            status = if (problem.retryable) BackupStatus.SERVICE_UNAVAILABLE else BackupStatus.PAUSED,
            uploadMessage = null,
            localMediaSyncStates = state.backup.localMediaSyncStates +
              (platformAssetRef to LocalMediaSyncState.FAILED),
          ))
        }
      } catch (_: Throwable) {
        mutableState.update { state ->
          state.copy(backup = state.backup.copy(
            status = BackupStatus.SERVICE_UNAVAILABLE,
            uploadMessage = null,
            localMediaSyncStates = state.backup.localMediaSyncStates +
              (platformAssetRef to LocalMediaSyncState.FAILED),
          ))
        }
      }
    }
  }

  fun refreshLocalLibrary() {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    if (mutableState.value.libraryAccess != LibraryAccess.FULL) return
    if (mutableState.value.backup.status == BackupStatus.SCANNING) return
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
        val overview = runtime.getBackupOverview(userId)
        mutableState.update { state ->
          if (state.profile?.id != userId) state else state.copy(
            backup = state.backup.copy(
              autoBackupEnabled = overview.autoBackupEnabled,
              allowCellularBackup = overview.allowCellularBackup,
              status = overview.toUiStatus(),
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
    mutableState.update { it.copy(feedback = it.feedback.copy(
      category = category,
      submitted = false,
      feedbackId = null,
      errorMessage = null,
    )) }
  }

  fun updateFeedbackDescription(value: String) {
    mutableState.update { it.copy(feedback = it.feedback.copy(
      description = value,
      submitted = false,
      feedbackId = null,
      errorMessage = null,
    )) }
  }

  fun updateFeedbackContact(value: String) {
    mutableState.update { it.copy(feedback = it.feedback.copy(contact = value, submitted = false)) }
  }

  fun sendFeedback() {
    val feedback = mutableState.value.feedback
    if (feedback.submitting) return
    val description = feedback.description.trim()
    if (description.length < 5) {
      mutableState.update { it.copy(feedback = it.feedback.copy(errorMessage = "请至少输入 5 个字符的问题描述")) }
      return
    }
    mutableState.update { it.copy(feedback = it.feedback.copy(
      submitting = true,
      submitted = false,
      feedbackId = null,
      errorMessage = null,
    )) }
    viewModelScope.launch {
      try {
        val result = runtime.sendFeedback(
          category = feedback.category.name,
          description = description,
          contact = feedback.contact.trim(),
          appVersion = BuildConfig.VERSION_NAME,
          osVersion = "Android ${Build.VERSION.RELEASE}",
        )
        mutableState.update { state ->
          state.copy(feedback = state.feedback.copy(
            submitting = false,
            submitted = true,
            feedbackId = result.feedbackId,
            errorMessage = null,
          ))
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state -> state.copy(feedback = state.feedback.copy(
            submitting = false,
            errorMessage = messageFor(problem),
          )) }
        }
      } catch (_: Throwable) {
        mutableState.update { state -> state.copy(feedback = state.feedback.copy(
          submitting = false,
          errorMessage = "反馈提交失败，请稍后重试。",
        )) }
      }
    }
  }

  fun mediaById(id: String): MediaItem? =
    (mutableState.value.privateSpace.items + mutableState.value.familyAlbum.items + mutableState.value.recycleBin.items.map { it.media })
      .firstOrNull { it.id == id }
      ?.let { media ->
        val source = mutableFamilyMediaPreviewSources[id] ?: mutablePrivateMediaPreviewSources[id]
        source?.let { media.copy(imageUrl = it, detailImageUrl = media.detailImageUrl ?: it) } ?: media
      }

  private fun refreshFamilyMedia(filter: String) {
    val profile = mutableState.value.profile ?: return
    mutableState.update { it.copy(familyAlbum = it.familyAlbum.copy(
      loadState = PageLoadState.LOADING,
      errorMessage = null,
    )) }
    viewModelScope.launch {
      try {
        val page = runtime.refreshFamilyMedia(filter = filter)
        val items = page.items.map { it.toMediaItem(profile) }
        mutableState.update { state ->
          if (state.profile?.id != profile.id) state else state.copy(
            familyAlbum = FamilyAlbumUiState(
              loadState = if (items.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
              items = items,
              nextCursor = page.nextCursor,
              fullyLoaded = page.fullyLoaded,
            ),
          )
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state -> state.copy(familyAlbum = state.familyAlbum.copy(
            loadState = PageLoadState.ERROR,
            errorMessage = messageFor(problem),
          )) }
        }
      } catch (_: Throwable) {
        mutableState.update { state -> state.copy(familyAlbum = state.familyAlbum.copy(
          loadState = PageLoadState.ERROR,
          errorMessage = "共享相册加载失败，请稍后重试。",
        )) }
      }
    }
  }

  private fun refreshRecycleBin() {
    val profile = mutableState.value.profile ?: return
    mutableState.update { it.copy(recycleBin = it.recycleBin.copy(
      loadState = PageLoadState.LOADING,
      errorMessage = null,
    )) }
    viewModelScope.launch {
      try {
        val page = runtime.refreshTrashMedia()
        mutableState.update { state ->
          if (state.profile?.id != profile.id) state else state.copy(recycleBin = page.toUiState(profile))
        }
      } catch (problem: AccountProblem) {
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
        } else {
          mutableState.update { state -> state.copy(recycleBin = state.recycleBin.copy(
            loadState = PageLoadState.ERROR,
            errorMessage = messageFor(problem),
          )) }
        }
      } catch (_: Throwable) {
        mutableState.update { state -> state.copy(recycleBin = state.recycleBin.copy(
          loadState = PageLoadState.ERROR,
          errorMessage = "回收站加载失败，请稍后重试。",
        )) }
      }
    }
  }

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
      val page = runtime.refreshPrivateMedia()
      val media = page.items.map { it.toMediaItem(profile) }
      mutableState.update { state ->
        if (state.profile?.id != userId) state else state.copy(
          privateSpace = PrivateSpaceUiState(
            loadState = if (media.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
            items = media,
            fullyLoaded = page.fullyLoaded,
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
      runtime.startBackupChangeObservation(userId)
      val overview = runtime.getBackupOverview(userId)
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
            status = overview.toUiStatus(),
            progress = if (overview.state == "COMPLETED") 1f else 0f,
            indexedCount = local.summary.indexedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            totalCount = local.summary.indexedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            autoBackupEnabled = overview.autoBackupEnabled,
            allowCellularBackup = overview.allowCellularBackup,
            albums = albums,
          ),
        )
      }
      if (overview.state == "SCANNING" || overview.state == "UPLOADING") {
        startBackupOverviewPolling(userId)
      }
    } catch (_: Throwable) {
      mutableState.update { state ->
        if (state.profile?.id != userId) state else state.copy(
          backup = state.backup.copy(loadState = PageLoadState.ERROR, status = BackupStatus.SERVICE_UNAVAILABLE),
        )
      }
    }
  }

  private fun refreshBackupOverview() {
    val userId = currentUserId ?: mutableState.value.profile?.id ?: return
    viewModelScope.launch {
      runCatching { runtime.getBackupOverview(userId) }.getOrNull()?.let { applyBackupOverview(userId, it) }
    }
  }

  private fun startBackupOverviewPolling(userId: String) {
    if (backupOverviewJob?.isActive == true) return
    backupOverviewJob = viewModelScope.launch {
      while (isActive && currentUserId == userId) {
        val overview = runCatching { runtime.getBackupOverview(userId) }.getOrNull()
        if (overview != null) {
          applyBackupOverview(userId, overview)
          val albumId = (mutableState.value.currentRoute as? AppRoute.LocalAlbum)?.albumId
          if (albumId != null) {
            runCatching { runtime.getLocalAlbumBackupProgress(userId, albumId) }
              .getOrNull()
              ?.let {
                applyLocalAlbumBackupProgress(
                  userId,
                  albumId,
                  it.completedCount,
                  it.totalCount,
                  it.mediaStates,
                )
              }
          }
          if (overview.state == "COMPLETED" || overview.state == "AUTO_BACKUP_DISABLED") break
        }
        delay(1_500)
      }
    }
  }

  private fun stopBackupOverviewPolling() {
    backupOverviewJob?.cancel()
    backupOverviewJob = null
  }

  private fun applyBackupOverview(userId: String, overview: BackupOverview) {
    mutableState.update { state ->
      if (state.profile?.id != userId) return@update state
      val currentTitle = overview.currentMediaRef
        ?.let { ref -> state.backup.localMedia.firstOrNull { it.id == ref }?.title }
        ?: state.backup.currentMediaTitle
      val progress = when {
        overview.totalBytes > 0 -> (overview.confirmedBytes.toDouble() / overview.totalBytes)
          .toFloat()
          .coerceIn(0f, 1f)
        overview.state == "COMPLETED" -> 1f
        else -> state.backup.progress
      }
      state.copy(
        backup = state.backup.copy(
          status = overview.toUiStatus(),
          autoBackupEnabled = overview.autoBackupEnabled,
          allowCellularBackup = overview.allowCellularBackup,
          progress = progress,
          currentMediaTitle = currentTitle,
          uploadMessage = null,
        ),
      )
    }
  }

  private fun applyLocalAlbumBackupProgress(
    userId: String,
    albumId: String,
    completedCount: Int,
    totalCount: Int,
    mediaStates: Map<String, String>,
  ) {
    mutableState.update { state ->
      if (state.profile?.id != userId || state.currentRoute != AppRoute.LocalAlbum(albumId)) state
      else state.copy(backup = state.backup.copy(
        albumCompletedCount = completedCount,
        albumTotalCount = totalCount,
        localMediaSyncStates = mediaStates.toLocalMediaSyncStates(),
      ))
    }
  }

  private fun LocalAlbumBackupProgress?.toLocalMediaSyncStates(): Map<String, LocalMediaSyncState> =
    this?.mediaStates.toLocalMediaSyncStates()

  private fun Map<String, String>?.toLocalMediaSyncStates(): Map<String, LocalMediaSyncState> =
    this?.mapValues { (_, state) ->
      runCatching { LocalMediaSyncState.valueOf(state) }.getOrDefault(LocalMediaSyncState.UNSYNCED)
    } ?: emptyMap()

  private fun LocalMedia.toMediaItem(owner: UserProfile): MediaItem {
    val instant = runCatching { Instant.parse(capturedAt) }.getOrElse { Instant.EPOCH }
    val dateTime = instant.atZone(ZoneId.systemDefault())
    val kind = when (mediaType.name) {
      "VIDEO" -> MediaKind.VIDEO
      "GIF" -> MediaKind.GIF
      "LIVE_PHOTO", "DYNAMIC" -> MediaKind.LIVE_PHOTO
      else -> MediaKind.PHOTO
    }
    return MediaItem(
      id = platformAssetRef,
      title = when (kind) {
        MediaKind.VIDEO -> "视频"
        MediaKind.GIF -> "GIF"
        MediaKind.LIVE_PHOTO -> "动态照片"
        MediaKind.PHOTO -> "照片"
      },
      kind = kind,
      capturedAt = DATE_TIME_FORMAT.format(dateTime),
      dateGroup = DATE_GROUP_FORMAT.format(dateTime),
      duration = durationMs?.toDurationLabel(),
      sizeLabel = "本地媒体",
      owner = owner,
      colorSeed = platformAssetRef.hashCode(),
      imageUrl = thumbnailUri,
    )
  }

  private fun BackupOverview.toUiStatus(): BackupStatus = when (state) {
    "PERMISSION_REQUIRED" -> BackupStatus.PERMISSION_REQUIRED
    "SCANNING" -> BackupStatus.SCANNING
    "UPLOADING" -> BackupStatus.UPLOADING
    "WAITING_FOR_WIFI" -> BackupStatus.WAITING_WIFI
    "OFFLINE" -> BackupStatus.NETWORK_OFFLINE
    "DEVICE_STORAGE_LOW" -> BackupStatus.DEVICE_STORAGE_FULL
    "REMOTE_STORAGE_FULL" -> BackupStatus.CLOUD_STORAGE_FULL
    "SERVICE_UNAVAILABLE", "RETRY_REQUIRED" -> BackupStatus.SERVICE_UNAVAILABLE
    "AUTO_BACKUP_DISABLED" -> BackupStatus.PAUSED
    "COMPLETED" -> BackupStatus.COMPLETE
    else -> BackupStatus.INDEXED
  }

  private fun PrivateMediaSummary.toMediaItem(owner: UserProfile): MediaItem {
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
      duration = durationMs?.toDurationLabel(),
      sizeLabel = originalTotalSize.toReadableSize(),
      owner = owner,
      colorSeed = id.hashCode(),
      // A PHOTO/GIF can obtain a short-lived OSS dynamic thumbnail directly
      // from its registered original when no uploaded THUMBNAIL exists.
      canLoadRemotePreview = previewResource != null || mediaType in setOf("PHOTO", "GIF"),
    )
  }

  private fun FamilyMediaSummary.toMediaItem(currentProfile: UserProfile): MediaItem =
    stage06MediaItem(
      id = id,
      mediaType = mediaType,
      capturedAt = capturedAt,
      durationMs = durationMs,
      originalTotalSize = originalTotalSize,
      ownerId = owner.id,
      ownerNickname = owner.nickname,
      currentProfile = currentProfile,
      canLoadRemotePreview = true,
    )

  private fun FamilyMediaDetail.toMediaItem(currentProfile: UserProfile?): MediaItem {
    val profile = currentProfile ?: UserProfile("", "", "", "")
    return stage06MediaItem(
      id = id,
      mediaType = mediaType,
      capturedAt = capturedAt,
      durationMs = durationMs,
      originalTotalSize = originalTotalSize,
      ownerId = owner.id,
      ownerNickname = owner.nickname,
      currentProfile = profile,
      canLoadRemotePreview = resources.any {
        it.resourceType in setOf("THUMBNAIL", "VIDEO_COVER", "PREVIEW", "DYNAMIC_PREVIEW", "ORIGINAL")
      },
    )
  }

  private fun stage06MediaItem(
    id: String,
    mediaType: String,
    capturedAt: String,
    durationMs: Long?,
    originalTotalSize: Long,
    ownerId: String,
    ownerNickname: String,
    currentProfile: UserProfile,
    canLoadRemotePreview: Boolean,
  ): MediaItem {
    val instant = runCatching { Instant.parse(capturedAt) }.getOrElse { Instant.EPOCH }
    val dateTime = instant.atZone(ZoneId.systemDefault())
    val kind = when (mediaType) {
      "VIDEO" -> MediaKind.VIDEO
      "GIF" -> MediaKind.GIF
      "LIVE_PHOTO", "DYNAMIC" -> MediaKind.LIVE_PHOTO
      else -> MediaKind.PHOTO
    }
    val isMine = ownerId == currentProfile.id
    val owner = if (isMine) currentProfile else UserProfile(
      id = ownerId,
      nickname = ownerNickname,
      maskedPhone = "",
      avatarLabel = ownerNickname.firstOrNull()?.toString() ?: "家",
    )
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
      duration = durationMs?.toDurationLabel(),
      sizeLabel = originalTotalSize.toReadableSize(),
      owner = owner,
      sharedByMe = isMine,
      isShared = true,
      colorSeed = id.hashCode(),
      canLoadRemotePreview = canLoadRemotePreview,
    )
  }

  private fun TrashMediaSummary.toDeletedMedia(owner: UserProfile): DeletedMedia {
    val media = stage06MediaItem(
      id = id,
      mediaType = mediaType,
      capturedAt = capturedAt,
      durationMs = durationMs,
      originalTotalSize = originalTotalSize,
      ownerId = owner.id,
      ownerNickname = owner.nickname,
      currentProfile = owner,
      canLoadRemotePreview = false,
    ).copy(isShared = false, sharedByMe = false)
    val deleted = runCatching {
      DATE_TIME_FORMAT.format(Instant.parse(trashedAt).atZone(ZoneId.systemDefault())) + " 删除"
    }.getOrDefault("已移入回收站")
    return DeletedMedia(media, deleted)
  }

  private fun TrashMediaPage.toUiState(owner: UserProfile): RecycleBinUiState {
    val values = items.map { it.toDeletedMedia(owner) }
    return RecycleBinUiState(
      loadState = if (values.isEmpty()) PageLoadState.EMPTY else PageLoadState.CONTENT,
      items = values,
      nextCursor = nextCursor,
      fullyLoaded = fullyLoaded,
      errorMessage = null,
    )
  }

  private fun PrivateMediaDetail.toMediaItem(owner: UserProfile): MediaItem {
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
      duration = durationMs?.toDurationLabel(),
      sizeLabel = originalTotalSize.toReadableSize(),
      owner = owner,
      colorSeed = id.hashCode(),
      canLoadRemotePreview = mediaType in setOf("PHOTO", "GIF") || resources.any {
        it.resourceType in setOf("THUMBNAIL", "VIDEO_COVER", "PREVIEW", "DYNAMIC_PREVIEW")
      },
    )
  }

  private fun Long.toDurationLabel(): String {
    val totalSeconds = this / 1_000L
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
  }

  private fun markPrivateMediaPreviewUnavailable(userId: String, mediaId: String) {
    mutableState.update { state ->
      if (state.profile?.id != userId) state else state.copy(
        privateSpace = state.privateSpace.copy(
          previewLoadingIds = state.privateSpace.previewLoadingIds - mediaId,
          previewUnavailableIds = state.privateSpace.previewUnavailableIds + mediaId,
        ),
      )
    }
  }

  private fun enqueuePrivateMediaPreview(
    userId: String,
    mediaId: String,
    pinned: Boolean,
    prioritize: Boolean,
  ) {
    if (pinned) privatePreviewPinnedIds += mediaId
    if (mediaId in privatePreviewActiveIds || mediaId in privatePreviewPendingIds) return
    val privateSpace = mutableState.value.privateSpace
    val item = privateSpace.items.firstOrNull { it.id == mediaId } ?: run {
      privatePreviewPinnedIds.remove(mediaId)
      return
    }
    if (mediaId in mutablePrivateMediaPreviewSources || !item.canLoadRemotePreview ||
      mediaId in privateSpace.previewUnavailableIds) {
      if (pinned) privatePreviewPinnedIds.remove(mediaId)
      return
    }
    if (prioritize) privatePreviewPending.addFirst(mediaId) else privatePreviewPending.addLast(mediaId)
    privatePreviewPendingIds += mediaId
    pumpPrivateMediaPreviews(userId)
  }

  private fun pumpPrivateMediaPreviews(userId: String) {
    while (privatePreviewActiveIds.size < PRIVATE_PREVIEW_MAX_CONCURRENCY && privatePreviewPending.isNotEmpty()) {
      val mediaId = privatePreviewPending.removeFirst()
      privatePreviewPendingIds.remove(mediaId)
      if (mediaId !in privatePreviewVisibleIds && mediaId !in privatePreviewPinnedIds) continue
      val current = mutableState.value
      val item = current.privateSpace.items.firstOrNull { it.id == mediaId }
      if (current.profile?.id != userId || item == null ||
        mediaId in mutablePrivateMediaPreviewSources ||
        !item.canLoadRemotePreview || mediaId in current.privateSpace.previewUnavailableIds) {
        privatePreviewPinnedIds.remove(mediaId)
        continue
      }
      privatePreviewActiveIds += mediaId
      viewModelScope.launch {
        try {
          fetchPrivateMediaPreview(userId, mediaId)
        } finally {
          delay(PRIVATE_PREVIEW_NEXT_REQUEST_DELAY_MS)
          privatePreviewActiveIds.remove(mediaId)
          privatePreviewPinnedIds.remove(mediaId)
          privatePreviewVisibleIds.forEach { visibleId ->
            enqueuePrivateMediaPreview(userId, visibleId, pinned = false, prioritize = false)
          }
          pumpPrivateMediaPreviews(userId)
          if (privatePreviewActiveIds.isEmpty() && privatePreviewPending.isEmpty()) {
            trimPrivatePreviewHandles()
          }
        }
      }
    }
  }

  private fun recordPrivatePreviewHandle(mediaId: String) {
    privatePreviewHandleOrder.remove(mediaId)
    privatePreviewHandleOrder.addLast(mediaId)
  }

  private fun trimPrivatePreviewHandles() {
    val handles = privatePreviewHandles
    if (handles.size <= PRIVATE_PREVIEW_HANDLE_LIMIT) return
    val evictedIds = mutableListOf<String>()
    val evictedHandles = mutableListOf<String>()
    while (handles.size - evictedIds.size > PRIVATE_PREVIEW_HANDLE_LIMIT) {
      val iterator = privatePreviewHandleOrder.iterator()
      var candidate: String? = null
      while (iterator.hasNext()) {
        val mediaId = iterator.next()
        if (mediaId !in privatePreviewVisibleIds && mediaId !in privatePreviewPinnedIds &&
          mediaId !in privatePreviewActiveIds) {
          iterator.remove()
          candidate = mediaId
          break
        }
      }
      val mediaId = candidate ?: break
      handles[mediaId]?.let { handle ->
        evictedIds += mediaId
        evictedHandles += handle
      }
    }
    if (evictedIds.isEmpty()) return
    evictedIds.forEach { mediaId ->
      handles.remove(mediaId)
      mutablePrivateMediaPreviewSources.remove(mediaId)
    }
    viewModelScope.launch {
      evictedHandles.forEach { handle -> runCatching { runtime.closePrivateMedia(handle) } }
    }
    MediaLoadLog.debug(
      "preview-cache evicted=${evictedIds.size} retained=${handles.size}",
    )
  }

  private fun clearPrivatePreviewRequests() {
    privatePreviewVisibleIds = emptySet()
    privatePreviewPending.clear()
    privatePreviewPendingIds.clear()
    privatePreviewPinnedIds.clear()
  }

  private suspend fun fetchPrivateMediaPreview(userId: String, mediaId: String) {
    var attempt = 1
    while (true) {
      MediaLoadLog.trace(
        "private-preview request media=${MediaLoadLog.mediaRef(mediaId)} attempt=$attempt",
      )
      try {
        val view = runtime.openPrivateMedia(userId, mediaId)
        val shouldRetain = mutableState.value.let { state ->
          state.profile?.id == userId &&
            state.privateSpace.items.any { it.id == mediaId } &&
            (mediaId in privatePreviewVisibleIds || mediaId in privatePreviewPinnedIds)
        }
        if (shouldRetain) {
          privatePreviewHandles[mediaId] = view.viewHandle
          mutablePrivateMediaPreviewSources[mediaId] = view.sourceUri
          recordPrivatePreviewHandle(mediaId)
        } else {
          runtime.closePrivateMedia(view.viewHandle)
        }
        MediaLoadLog.trace(
          "private-preview ready media=${MediaLoadLog.mediaRef(mediaId)} attempt=$attempt",
        )
        return
      } catch (problem: AccountProblem) {
        MediaLoadLog.warning(
          "private-preview failed media=${MediaLoadLog.mediaRef(mediaId)} " +
            "attempt=$attempt code=${problem.code} retryable=${problem.retryable} " +
            "requestId=${problem.requestId.ifBlank { "none" }}",
        )
        if (problem.code in SESSION_ERRORS) {
          performLogout("登录状态已失效，请重新登录。")
          return
        }
        if (!problem.retryable || attempt >= PRIVATE_PREVIEW_MAX_ATTEMPTS) {
          markPrivateMediaPreviewUnavailable(userId, mediaId)
          return
        }
      } catch (failure: Throwable) {
        MediaLoadLog.warning(
          "private-preview failed media=${MediaLoadLog.mediaRef(mediaId)} " +
            "attempt=$attempt failure=${failure.javaClass.simpleName}",
        )
        if (attempt >= PRIVATE_PREVIEW_MAX_ATTEMPTS) {
          markPrivateMediaPreviewUnavailable(userId, mediaId)
          return
        }
      }
      delay(PRIVATE_PREVIEW_RETRY_DELAYS_MS[attempt - 1])
      if (mutableState.value.profile?.id != userId ||
        mutableState.value.privateSpace.items.none { it.id == mediaId }) {
        return
      }
      attempt += 1
    }
  }

  private fun Long.toReadableSize(): String = when {
    this >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", this / (1024.0 * 1024.0 * 1024.0))
    this >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", this / (1024.0 * 1024.0))
    this >= 1024L -> String.format(Locale.ROOT, "%.1f KB", this / 1024.0)
    else -> "$this B"
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

  private fun mediaListScrollPosition(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
  ) = MediaListScrollPosition(
    firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
  )

  private fun performLogout(message: String? = null) {
    stopReviewPolling()
    stopBackupOverviewPolling()
    val previewHandles = privatePreviewHandles.values.toList()
    val familyHandles = familyPreviewHandles.values.toList()
    clearPrivatePreviewRequests()
    privatePreviewHandleOrder.clear()
    privatePreviewHandles.clear()
    mutablePrivateMediaPreviewSources.clear()
    familyPreviewHandles.clear()
    mutableFamilyMediaPreviewSources.clear()
    privateMediaDetails.clear()
    mutableState.update { it.copy(currentRoute = AppRoute.Restoring, backStack = emptyList(), dialog = null) }
    viewModelScope.launch {
      previewHandles.forEach { handle -> runCatching { runtime.closePrivateMedia(handle) } }
      familyHandles.forEach { handle -> runCatching { runtime.closeFamilyMedia(handle) } }
      runCatching { runtime.signOut() }
      currentUserId = null
      showLogin(message)
    }
  }

  private fun showLogin(message: String? = null, isError: Boolean = false) {
    stopReviewPolling()
    stopBackupOverviewPolling()
    currentUserId = null
    val previewHandles = privatePreviewHandles.values.toList()
    val familyHandles = familyPreviewHandles.values.toList()
    clearPrivatePreviewRequests()
    privatePreviewHandleOrder.clear()
    privatePreviewHandles.clear()
    mutablePrivateMediaPreviewSources.clear()
    familyPreviewHandles.clear()
    mutableFamilyMediaPreviewSources.clear()
    privateMediaDetails.clear()
    if (previewHandles.isNotEmpty() || familyHandles.isNotEmpty()) {
      viewModelScope.launch {
        previewHandles.forEach { handle -> runCatching { runtime.closePrivateMedia(handle) } }
        familyHandles.forEach { handle -> runCatching { runtime.closeFamilyMedia(handle) } }
      }
    }
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
    "PHONE_ALREADY_REGISTERED" -> "该手机号已注册"
    "PASSWORD_INVALID" -> "密码需为 8～64 位，并同时包含字母和数字。"
    "CREDENTIALS_INVALID" -> "手机号或密码错误"
    "AGREEMENT_REQUIRED" -> "请先同意服务协议和隐私政策。"
    "ACCOUNT_PENDING" -> "账号仍在等待管理员审核。"
    "NETWORK_UNAVAILABLE" -> "网络不可用，请检查连接后重试。"
    "SERVICE_UNAVAILABLE" -> "服务暂时不可用，请稍后重试。"
    "PROFILE_MISMATCH" -> "账号资料校验失败，请重新登录。"
    "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE", "PRIVATE_MEDIA_ACCESS_UNAVAILABLE" ->
      "媒体加载失败，请稍后重试。"
    else -> "操作失败，请稍后重试。"
  }

  private fun updateAuth(update: AuthUiState.() -> AuthUiState) {
    mutableState.update { it.copy(auth = it.auth.update()) }
  }

  override fun onCleared() {
    stopReviewPolling()
    stopBackupOverviewPolling()
    runtime.close()
    super.onCleared()
  }

  companion object {
    private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
    private val DATE_GROUP_FORMAT = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val SESSION_ERRORS = setOf("AUTH_REQUIRED", "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REPLAYED")
    private const val PRIVATE_PREVIEW_MAX_ATTEMPTS = 3
    private const val PRIVATE_PREVIEW_MAX_CONCURRENCY = 1
    private const val PRIVATE_PREVIEW_NEXT_REQUEST_DELAY_MS = 24L
    private const val PRIVATE_PREVIEW_HANDLE_LIMIT = 72
    private val PRIVATE_PREVIEW_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)

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
