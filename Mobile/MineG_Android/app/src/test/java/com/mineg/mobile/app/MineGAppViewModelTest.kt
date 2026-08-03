package com.mineg.mobile.app

import android.net.Uri

import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalMediaAvailability
import com.mineg.mobile.contracts.LocalMediaType
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.LocalLibrarySummary
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.PrivateMediaPage
import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaView
import com.mineg.mobile.contracts.PrivateMediaSaveResult
import com.mineg.mobile.contracts.PrivateMediaSummary
import com.mineg.mobile.contracts.PrivateMediaTrashResult
import com.mineg.mobile.account.BackupOverview
import com.mineg.mobile.account.LocalAlbumBackupProgress
import com.mineg.mobile.contracts.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MineGAppViewModelTest {
  @Test
  fun `cold start without a cached session always enters login`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime()
      val viewModel = MineGAppViewModel(runtime)

      assertEquals(AppRoute.Login, viewModel.state.value.currentRoute)
      assertNull(viewModel.state.value.profile)
      assertTrue(runtime.restoreCalled)

      viewModel.selectTab(MainTab.PRIVATE_SPACE)
      assertEquals(AppRoute.Login, viewModel.state.value.currentRoute)
      assertNull(viewModel.state.value.profile)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `approved cached session restores profile and real home models`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        profile = Profile("user-1", "真实用户", "138****8000", null),
        privateMedia = listOf(
          PrivateMediaSummary(
            "media-1", "PHOTO", "2026-07-30T08:00:00Z", "2026-07-30T08:01:00Z",
            null, 1_024L, null,
          ),
        ),
      )
      val viewModel = MineGAppViewModel(runtime)

      assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
      assertEquals("user-1", viewModel.state.value.profile?.id)
      assertEquals("真实用户", viewModel.state.value.profile?.nickname)
      assertEquals(PageLoadState.CONTENT, viewModel.state.value.privateSpace.loadState)
      assertEquals(listOf("media-1"), viewModel.state.value.privateSpace.items.map(MediaItem::id))
      assertEquals(12, viewModel.state.value.backup.indexedCount)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `login validates then requires profile before permission or home`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime(
        signInSession = approvedSession(),
        access = LibraryAccess.NOT_DETERMINED,
        profile = Profile("user-1", "林深", "138****8000", null),
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.submitLogin()
      assertEquals(setOf("phone", "password"), viewModel.state.value.auth.fieldErrors.keys)
      assertFalse(runtime.signInCalled)

      viewModel.updatePhone("13800138000")
      viewModel.updatePassword("mineg2026")
      viewModel.submitLogin()

      assertTrue(runtime.signInCalled)
      assertEquals(AppRoute.Permission, viewModel.state.value.currentRoute)
      assertNotNull(viewModel.state.value.profile)

      viewModel.deferLibraryAccess()
      assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
      assertEquals(BackupStatus.PERMISSION_REQUIRED, viewModel.state.value.backup.status)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `pending session never exposes profile or home`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val pending = approvedSession().copy(
        approvalStatus = ApprovalStatus.PENDING,
        nextStep = AccountNextStep.REVIEW_PENDING,
      )
      val runtime = FakeRuntime(restoredSession = pending, access = LibraryAccess.FULL)
      val viewModel = MineGAppViewModel(runtime)

      assertEquals(AppRoute.ReviewPending, viewModel.state.value.currentRoute)
      assertNull(viewModel.state.value.profile)
      assertTrue(viewModel.state.value.privateSpace.items.isEmpty())

      viewModel.returnToLogin()
      assertEquals(AppRoute.Login, viewModel.state.value.currentRoute)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `profile identity mismatch never opens a protected route`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime(
        signInSession = approvedSession(),
        profile = Profile("other-user", "错误用户", "139****9000", null),
      )
      val viewModel = MineGAppViewModel(runtime)
      viewModel.updatePhone("13800138000")
      viewModel.updatePassword("mineg2026")
      viewModel.updateAgreement(true)

      viewModel.submitLogin()

      assertEquals(AppRoute.Login, viewModel.state.value.currentRoute)
      assertNull(viewModel.state.value.profile)
      assertTrue(viewModel.state.value.auth.messageIsError)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `logout clears runtime session and all protected state`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime(restoredSession = approvedSession(), access = LibraryAccess.FULL)
      val viewModel = MineGAppViewModel(runtime)
      assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)

      viewModel.requestLogout()
      viewModel.confirmDialog()

      assertTrue(runtime.signOutCalled)
      assertEquals(AppRoute.Login, viewModel.state.value.currentRoute)
      assertNull(viewModel.state.value.profile)
      assertTrue(viewModel.state.value.privateSpace.items.isEmpty())
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private media save and delete wait for the Stage05 runtime result`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = PrivateMediaSummary(
        "11111111-1111-4111-8111-111111111111",
        "PHOTO",
        "2026-08-03T00:00:00Z",
        "2026-08-03T00:00:00Z",
        null,
        1_024L,
        null,
      )
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.loadPrivateMediaPreview(media.id)
      viewModel.openPrivateMedia(media.id)
      viewModel.downloadSelectedMedia()
      assertEquals(MediaActionState.SAVED, viewModel.state.value.selectedMediaAction)
      assertEquals(listOf(media.id), runtime.savedMediaIds)

      viewModel.requestDelete(media.id)
      assertEquals(listOf(media.id), viewModel.state.value.privateSpace.items.map(MediaItem::id))
      viewModel.confirmDialog()
      assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
      assertTrue(viewModel.state.value.privateSpace.items.isEmpty())
      assertEquals(listOf(media.id), runtime.trashedMediaIds)
      assertEquals(listOf("view-handle-${media.id}"), runtime.closedViewHandles)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private media loads the next Stage05 page without duplicating the first page`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = listOf(
        PrivateMediaSummary("media-1", "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null, 1_024L, null),
        PrivateMediaSummary("media-2", "VIDEO", "2026-08-02T00:00:00Z", "2026-08-02T00:00:00Z", 30_000L, 2_048L, null),
      )
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = media,
        privateMediaPageSize = 1,
      )
      val viewModel = MineGAppViewModel(runtime)

      assertEquals(listOf("media-1"), viewModel.state.value.privateSpace.items.map(MediaItem::id))
      assertFalse(viewModel.state.value.privateSpace.fullyLoaded)

      viewModel.loadMorePrivateMedia()

      assertEquals(listOf("media-1", "media-2"), viewModel.state.value.privateSpace.items.map(MediaItem::id))
      assertTrue(viewModel.state.value.privateSpace.fullyLoaded)
      assertFalse(viewModel.state.value.privateSpace.loadingMore)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private media preview is rendered only after the Stage05 verified view result`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = PrivateMediaSummary(
        "media-1", "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null, 1_024L, null,
      )
      val runtime = FakeRuntime(restoredSession = approvedSession(), access = LibraryAccess.FULL, privateMedia = listOf(media))
      val viewModel = MineGAppViewModel(runtime)

      viewModel.loadPrivateMediaPreview(media.id)

      assertEquals("file:///verified/media-1", viewModel.state.value.privateSpace.items.single().imageUrl)
      assertEquals(listOf(media.id), runtime.openedMediaIds)
      assertTrue(viewModel.state.value.privateSpace.previewViewHandles.containsKey(media.id))
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `local album still queues a selected media while another backup is active`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        backupOverview = BackupOverview(
          state = "UPLOADING",
          autoBackupEnabled = true,
          allowCellularBackup = false,
          discoveredCount = 2,
          pendingCount = 1,
          completedCount = 1,
          failedCount = 0,
          currentMediaRef = "another-asset",
        ),
        backupOverviewAfterEnqueue = BackupOverview(
          state = "COMPLETED",
          autoBackupEnabled = true,
          allowCellularBackup = false,
          discoveredCount = 2,
          pendingCount = 0,
          completedCount = 2,
          failedCount = 0,
        ),
        localMedia = listOf(
          LocalMedia(
            platformAssetRef = "asset-1",
            mediaType = LocalMediaType.PHOTO,
            mimeType = "image/jpeg",
            width = 100,
            height = 100,
            durationMs = null,
            capturedAt = "2026-08-02T00:00:00Z",
            modifiedAt = "2026-08-02T00:00:00Z",
            contentVersion = "v1",
            availability = LocalMediaAvailability.AVAILABLE,
            thumbnailUri = null,
          ),
        ),
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.openLocalAlbum("album-1")
      viewModel.backupSingleMedia("asset-1")

      assertEquals(listOf("asset-1"), runtime.enqueuedAssetRefs)
      testScheduler.advanceUntilIdle()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `local album presents completed media count instead of byte progress`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        albumBackupProgress = LocalAlbumBackupProgress(
          completedCount = 5,
          totalCount = 12,
          mediaStates = mapOf("asset-1" to "SYNCED"),
        ),
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.openLocalAlbum("album-1")

      assertEquals(5, viewModel.state.value.backup.albumCompletedCount)
      assertEquals(12, viewModel.state.value.backup.albumTotalCount)
      assertEquals(LocalMediaSyncState.SYNCED, viewModel.state.value.backup.localMediaSyncStates["asset-1"])
      assertNull(viewModel.state.value.backup.uploadMessage)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `profile edit persists through the account API before updating provided user info`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val runtime = FakeRuntime(restoredSession = approvedSession(), access = LibraryAccess.FULL)
      val viewModel = MineGAppViewModel(runtime)
      val original = viewModel.state.value.profile?.nickname

      viewModel.navigate(AppRoute.ProfileEdit)
      viewModel.updateNickname("新的昵称")
      assertEquals(original, viewModel.state.value.profile?.nickname)

      viewModel.saveProfile()
      assertEquals(AppRoute.Profile, viewModel.state.value.currentRoute)
      assertEquals("新的昵称", viewModel.state.value.profile?.nickname)
      assertEquals("新的昵称", runtime.profile.nickname)
    } finally {
      Dispatchers.resetMain()
    }
  }

  private class FakeRuntime(
    private val restoredSession: AccountRouteSnapshot? = null,
    private val signInSession: AccountRouteSnapshot = approvedSession(),
    var access: LibraryAccess = LibraryAccess.NOT_DETERMINED,
    var profile: Profile = Profile("user-1", "测试用户", "138****8000", null),
    var privateMedia: List<PrivateMediaSummary> = emptyList(),
    var privateMediaPageSize: Int = 50,
    var backupOverview: BackupOverview = BackupOverview(
      state = "COMPLETED", autoBackupEnabled = false, allowCellularBackup = false,
      discoveredCount = 0, pendingCount = 0, completedCount = 0, failedCount = 0,
    ),
    var backupOverviewAfterEnqueue: BackupOverview? = null,
    var albumBackupProgress: LocalAlbumBackupProgress = LocalAlbumBackupProgress(0, 0),
    var localMedia: List<LocalMedia> = emptyList(),
  ) : MineGAppRuntime {
    var restoreCalled = false
    var signInCalled = false
    var signOutCalled = false
    val enqueuedAssetRefs = mutableListOf<String>()
    val savedMediaIds = mutableListOf<String>()
    val trashedMediaIds = mutableListOf<String>()
    val detailedMediaIds = mutableListOf<String>()
    val openedMediaIds = mutableListOf<String>()
    val closedViewHandles = mutableListOf<String>()
    private var privateMediaNextIndex = 0

    override suspend fun restoreSession(): AccountRouteSnapshot? {
      restoreCalled = true
      return restoredSession
    }

    override suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot {
      signInCalled = true
      return signInSession
    }

    override suspend fun signUp(phone: String, password: String): AccountRouteSnapshot = signInSession
    override suspend fun refreshReviewStatus(): ApprovalStatus = ApprovalStatus.PENDING
    override suspend fun loadProfile(userId: String, allowCached: Boolean): Profile = profile
    override suspend fun updateProfile(nickname: String): Profile = profile.copy(nickname = nickname).also { profile = it }
    override suspend fun updateAvatar(uri: Uri): Profile = profile.copy(avatarUrl = uri.toString()).also { profile = it }
    override suspend fun refreshPrivateMedia(limit: Int): PrivateMediaPage {
      privateMediaNextIndex = minOf(privateMedia.size, minOf(limit, privateMediaPageSize))
      return privateMediaPage(0, privateMediaNextIndex)
    }
    override suspend fun loadMorePrivateMedia(limit: Int): PrivateMediaPage {
      val start = privateMediaNextIndex
      privateMediaNextIndex = minOf(privateMedia.size, start + minOf(limit, privateMediaPageSize))
      return privateMediaPage(start, privateMediaNextIndex)
    }
    override suspend fun getPrivateMediaPage(limit: Int): PrivateMediaPage? = PrivateMediaPage(
      items = privateMedia.take(limit),
      nextCursor = if (privateMedia.size > limit) "cursor-$limit" else null,
      fullyLoaded = privateMedia.size <= limit,
      refreshedAt = "2026-08-03T00:00:00Z",
    )
    override suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail {
      detailedMediaIds += mediaId
      val media = requireNotNull(privateMedia.firstOrNull { it.id == mediaId })
      return PrivateMediaDetail(
        id = media.id,
        mediaType = media.mediaType,
        capturedAt = media.capturedAt,
        createdAt = media.createdAt,
        width = null,
        height = null,
        durationMs = media.durationMs,
        originalTotalSize = media.originalTotalSize,
        resources = emptyList(),
      )
    }
    override suspend fun openPrivateMedia(mediaId: String): PrivateMediaView {
      openedMediaIds += mediaId
      require(privateMedia.any { it.id == mediaId })
      return PrivateMediaView(
        mediaId = mediaId,
        resourceType = "THUMBNAIL",
        mimeType = "image/jpeg",
        viewHandle = "view-handle-$mediaId",
        sourceUri = "file:///verified/$mediaId",
      )
    }
    override suspend fun closePrivateMedia(viewHandle: String): Boolean {
      closedViewHandles += viewHandle
      return true
    }
    override suspend fun savePrivateMediaToSystemAlbum(mediaId: String): PrivateMediaSaveResult {
      savedMediaIds += mediaId
      return PrivateMediaSaveResult(mediaId, "COMPLETED", 1)
    }
    override suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult {
      trashedMediaIds += mediaId
      privateMedia = privateMedia.filterNot { it.id == mediaId }
      return PrivateMediaTrashResult(mediaId, "TRASHED", "2026-08-03T00:00:00Z")
    }
    override suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean): LocalLibrarySnapshot = LocalLibrarySnapshot(
      summary = LocalLibrarySummary("generation", 12, "2026-07-30T08:00:00Z"),
      albums = listOf(LocalAlbum("album-1", "相机", 12, null)),
    )
    override suspend fun getBackupSettings(userId: String): BackupSettings = BackupSettings()
    override suspend fun updateBackupSettings(userId: String, settings: BackupSettings): BackupSettings = settings
    override suspend fun getBackupOverview(userId: String): BackupOverview = backupOverview
    override suspend fun getLocalAlbumBackupProgress(
      userId: String,
      albumRef: String,
    ): LocalAlbumBackupProgress = albumBackupProgress
    override fun startBackupChangeObservation(userId: String) = Unit
    override suspend fun listLocalMedia(userId: String, albumRef: String, limit: Int): List<LocalMedia> = localMedia
    override suspend fun enqueueBackupMedia(userId: String, platformAssetRef: String) {
      enqueuedAssetRefs += platformAssetRef
      backupOverviewAfterEnqueue?.let { backupOverview = it }
    }

    override suspend fun signOut() {
      signOutCalled = true
    }

    override fun libraryAccess(): LibraryAccess = access
    override fun markLibraryPermissionRequested() = Unit
    override fun close() = Unit

    private fun privateMediaPage(start: Int, end: Int): PrivateMediaPage = PrivateMediaPage(
      items = privateMedia.subList(start, end),
      nextCursor = if (end < privateMedia.size) "cursor-$end" else null,
      fullyLoaded = end >= privateMedia.size,
      refreshedAt = "2026-08-03T00:00:00Z",
    )
  }

  private companion object {
    fun approvedSession() = AccountRouteSnapshot(
      userId = "user-1",
      approvalStatus = ApprovalStatus.APPROVED,
      nextStep = AccountNextStep.APP_HOME,
    )
  }
}
