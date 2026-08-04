package com.mineg.mobile.app

import android.net.Uri

import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalMediaAvailability
import com.mineg.mobile.contracts.LocalMediaType
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.LocalLibrarySummary
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.LocalMediaCursor
import com.mineg.mobile.contracts.LocalMediaPage
import com.mineg.mobile.contracts.PrivateMediaPage
import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaView
import com.mineg.mobile.contracts.PrivateMediaSaveResult
import com.mineg.mobile.contracts.PrivateMediaSummary
import com.mineg.mobile.contracts.PrivateMediaTrashResult
import com.mineg.mobile.contracts.PrivateMediaShareResult
import com.mineg.mobile.contracts.FamilyMediaDetail
import com.mineg.mobile.contracts.FamilyMediaOwner
import com.mineg.mobile.contracts.FamilyMediaPage
import com.mineg.mobile.contracts.FamilyMediaSummary
import com.mineg.mobile.contracts.FeedbackSubmissionResult
import com.mineg.mobile.contracts.TrashMediaPage
import com.mineg.mobile.contracts.TrashMediaRestoreResult
import com.mineg.mobile.contracts.TrashMediaSummary
import com.mineg.mobile.account.BackupOverview
import com.mineg.mobile.account.LocalAlbumBackupProgress
import com.mineg.mobile.contracts.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
      testScheduler.advanceUntilIdle()
      viewModel.openPrivateMedia(media.id)
      viewModel.downloadSelectedMedia()
      assertEquals(MediaActionState.SAVED, viewModel.state.value.selectedMediaAction)
      assertEquals(listOf(media.id), runtime.savedMediaIds)
      assertEquals(media.id, runtime.savedMediaDetails.single().id)

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
  fun `private media save fails locally when detail metadata was not opened`() = runTest {
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

      viewModel.navigate(AppRoute.PrivateMediaDetail(media.id))
      viewModel.downloadSelectedMedia()

      assertEquals(MediaActionState.SAVE_FAILED, viewModel.state.value.selectedMediaAction)
      assertTrue(runtime.savedMediaIds.isEmpty())
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private media save session failure logs out and clears the detail session`() = runTest {
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
        saveFailure = AccountProblem("SESSION_INVALID", "session.invalid", false, "request-save-test"),
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.openPrivateMedia(media.id)
      viewModel.downloadSelectedMedia()

      assertEquals(AppRoute.Login, viewModel.state.value.currentRoute)
      assertTrue(runtime.signOutCalled)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `cloud media scroll positions survive detail and main page navigation`() = runTest {
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
      val viewModel = MineGAppViewModel(FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
      ))

      viewModel.updatePrivateMediaListScrollPosition(27, 36)
      viewModel.updateFamilyMediaListScrollPosition(11, 18)
      viewModel.openPrivateMedia(media.id)

      assertEquals(AppRoute.PrivateMediaDetail(media.id), viewModel.state.value.currentRoute)
      assertEquals(MediaListScrollPosition(27, 36), viewModel.state.value.privateMediaListScrollPosition)

      viewModel.back()
      viewModel.selectTab(MainTab.BACKUP)
      viewModel.selectTab(MainTab.PRIVATE_SPACE)

      assertEquals(MediaListScrollPosition(27, 36), viewModel.state.value.privateMediaListScrollPosition)
      assertEquals(MediaListScrollPosition(11, 18), viewModel.state.value.familyMediaListScrollPosition)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private detail uses the verified app-private original when no local mapping survives`() = runTest {
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
        resolvedPrivateOriginalUri = "file:///app-private/${media.id}",
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.openPrivateMedia(media.id)
      testScheduler.advanceUntilIdle()

      assertEquals("file:///app-private/${media.id}", viewModel.mediaById(media.id)?.detailImageUrl)
      assertEquals(listOf(media.id), runtime.resolvedPrivateOriginalIds)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private detail original is not replaced by the list thumbnail`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = PrivateMediaSummary(
        "33333333-3333-4333-8333-333333333333",
        "PHOTO",
        "2026-08-03T00:00:00Z",
        "2026-08-03T00:00:00Z",
        null,
        4_096L,
        null,
      )
      val original = "file:///app-private/${media.id}"
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
        resolvedPrivateOriginalUri = original,
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.loadPrivateMediaPreview(media.id)
      testScheduler.advanceUntilIdle()
      assertEquals("file:///verified/${media.id}", viewModel.mediaById(media.id)?.detailImageUrl)

      viewModel.openPrivateMedia(media.id)
      testScheduler.advanceUntilIdle()

      val detail = assertNotNull(viewModel.mediaById(media.id))
      assertEquals("file:///verified/${media.id}", detail.imageUrl)
      assertEquals(original, detail.detailImageUrl)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private detail keeps a readable mapped original ahead of the app-private fallback`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = PrivateMediaSummary(
        "22222222-2222-4222-8222-222222222222",
        "PHOTO",
        "2026-08-03T00:00:00Z",
        "2026-08-03T00:00:00Z",
        null,
        2_048L,
        null,
        localPlatformAssetRef = "android:external:123",
        localSourceUri = "content://media/external/file/123",
      )
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
        resolvedPrivateOriginalUri = "file:///app-private/${media.id}",
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.openPrivateMedia(media.id)
      testScheduler.advanceUntilIdle()

      assertEquals("content://media/external/file/123", viewModel.mediaById(media.id)?.detailImageUrl)
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
  fun `private media keeps loaded items and exposes a retry after the next page fails`() = runTest {
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
        loadMorePrivateMediaFailure = true,
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.loadMorePrivateMedia()

      assertEquals(listOf("media-1"), viewModel.state.value.privateSpace.items.map(MediaItem::id))
      assertEquals("媒体加载失败，请稍后重试。", viewModel.state.value.privateSpace.errorMessage)
      assertFalse(viewModel.state.value.privateSpace.loadingMore)
      assertFalse(viewModel.state.value.privateSpace.fullyLoaded)

      runtime.loadMorePrivateMediaFailure = false
      viewModel.loadMorePrivateMedia()

      assertEquals(listOf("media-1", "media-2"), viewModel.state.value.privateSpace.items.map(MediaItem::id))
      assertNull(viewModel.state.value.privateSpace.errorMessage)
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
      val itemsBeforePreview = viewModel.state.value.privateSpace.items

      viewModel.loadPrivateMediaPreview(media.id)
      testScheduler.advanceUntilIdle()

      assertEquals("file:///verified/media-1", viewModel.mediaById(media.id)?.imageUrl)
      assertSame(itemsBeforePreview, viewModel.state.value.privateSpace.items)
      assertEquals(listOf(media.id), runtime.openedMediaIds)
      assertTrue(viewModel.privateMediaPreviewSources.containsKey(media.id))
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private media preview retries a temporary access failure`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = PrivateMediaSummary(
        "media-1", "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null, 1_024L, null,
      )
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
        openPrivateMediaFailuresRemaining = 1,
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.loadPrivateMediaPreview(media.id)
      testScheduler.advanceUntilIdle()

      assertEquals(2, runtime.openedMediaIds.size)
      assertEquals("file:///verified/media-1", viewModel.mediaById(media.id)?.imageUrl)
      assertFalse(media.id in viewModel.state.value.privateSpace.previewUnavailableIds)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `visible private media previews are opened serially`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = List(12) { index ->
        PrivateMediaSummary(
          "media-$index", "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z",
          null, 1_024L, null,
        )
      }
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = media,
        openPrivateMediaDelayMs = 1_000L,
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.updateVisiblePrivateMedia(media.map(PrivateMediaSummary::id))
      testScheduler.runCurrent()

      assertEquals(1, runtime.activePrivateMediaOpens)
      assertEquals(1, runtime.maximumConcurrentPrivateMediaOpens)

      testScheduler.advanceUntilIdle()

      assertEquals(12, runtime.openedMediaIds.size)
      assertEquals(1, runtime.maximumConcurrentPrivateMediaOpens)
      assertEquals(0, runtime.activePrivateMediaOpens)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private media preview can be retried after its automatic attempts fail`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = PrivateMediaSummary(
        "media-1", "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null, 1_024L, null,
      )
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
        openPrivateMediaFailuresRemaining = 3,
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.loadPrivateMediaPreview(media.id)
      testScheduler.advanceUntilIdle()
      assertTrue(media.id in viewModel.state.value.privateSpace.previewUnavailableIds)

      viewModel.retryPrivateMediaPreview(media.id)
      testScheduler.advanceUntilIdle()

      assertEquals(4, runtime.openedMediaIds.size)
      assertEquals("file:///verified/media-1", viewModel.mediaById(media.id)?.imageUrl)
      assertFalse(media.id in viewModel.state.value.privateSpace.previewUnavailableIds)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `private media preview retry closes an existing verified view before reopening`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val media = PrivateMediaSummary(
        "media-1", "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null, 1_024L, null,
      )
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.loadPrivateMediaPreview(media.id)
      testScheduler.advanceUntilIdle()
      viewModel.retryPrivateMediaPreview(media.id)
      testScheduler.advanceUntilIdle()

      assertEquals(listOf(media.id, media.id), runtime.openedMediaIds)
      assertEquals(listOf("view-handle-${media.id}"), runtime.closedViewHandles)
      assertEquals("file:///verified/media-1", viewModel.mediaById(media.id)?.imageUrl)
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
  fun `local album keeps its cursor and appends every media page without duplicates`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val localMedia = (1..3).map { index ->
        LocalMedia(
          platformAssetRef = "asset-$index",
          mediaType = LocalMediaType.PHOTO,
          mimeType = "image/jpeg",
          width = 100,
          height = 100,
          durationMs = null,
          capturedAt = "2026-08-0${4 - index}T00:00:00Z",
          modifiedAt = "2026-08-03T00:00:00Z",
          contentVersion = "v$index",
          availability = LocalMediaAvailability.AVAILABLE,
          thumbnailUri = null,
        )
      }
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        localMedia = localMedia,
        localMediaPageSize = 1,
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.openLocalAlbum("album-1")
      assertEquals(listOf("asset-1"), viewModel.state.value.backup.localMedia.map(MediaItem::id))
      assertFalse(viewModel.state.value.backup.localMediaFullyLoaded)

      viewModel.loadMoreLocalAlbumMedia()
      assertEquals(listOf("asset-1", "asset-2"), viewModel.state.value.backup.localMedia.map(MediaItem::id))
      assertFalse(viewModel.state.value.backup.localMediaFullyLoaded)

      viewModel.loadMoreLocalAlbumMedia()
      assertEquals(listOf("asset-1", "asset-2", "asset-3"), viewModel.state.value.backup.localMedia.map(MediaItem::id))
      assertTrue(viewModel.state.value.backup.localMediaFullyLoaded)
      assertFalse(viewModel.state.value.backup.localMediaLoadingMore)
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

  @Test
  fun `Stage06 share family trash restore and feedback wait for Core-backed runtime results`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
      val mediaId = "11111111-1111-4111-8111-111111111111"
      val media = PrivateMediaSummary(
        mediaId, "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null, 1_024L, null,
      )
      val owner = FamilyMediaOwner("user-1", "测试用户")
      val runtime = FakeRuntime(
        restoredSession = approvedSession(),
        access = LibraryAccess.FULL,
        privateMedia = listOf(media),
        familyMedia = listOf(
          FamilyMediaSummary(
            mediaId, owner, "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null, 1_024L,
          ),
        ),
        trashMedia = listOf(
          TrashMediaSummary(
            mediaId, "PHOTO", "2026-08-03T00:00:00Z", "2026-08-03T00:00:00Z", null,
            1_024L, "2026-08-03T01:00:00Z",
          ),
        ),
      )
      val viewModel = MineGAppViewModel(runtime)

      viewModel.setSelectedMediaSharing(mediaId)
      assertEquals(listOf(mediaId to true), runtime.shareRequests)
      assertTrue(viewModel.state.value.privateSpace.items.single().isShared)

      viewModel.selectLibraryTab(LibraryTab.SHARED)
      assertEquals(listOf(mediaId), viewModel.state.value.familyAlbum.items.map(MediaItem::id))

      viewModel.navigate(AppRoute.RecycleBin)
      assertEquals(listOf(mediaId), viewModel.state.value.recycleBin.items.map { it.media.id })
      viewModel.requestRestore(mediaId)
      viewModel.confirmDialog()
      assertEquals(listOf(mediaId), runtime.restoredMediaIds)
      assertTrue(viewModel.state.value.recycleBin.items.isEmpty())

      viewModel.updateFeedbackDescription("备份任务一直没有完成")
      viewModel.sendFeedback()
      assertEquals(listOf(FeedbackCategory.BACKUP.name), runtime.feedbackCategories)
      assertTrue(viewModel.state.value.feedback.submitted)
      assertEquals("55555555-5555-4555-8555-555555555555", viewModel.state.value.feedback.feedbackId)
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
    var localMediaPageSize: Int = 120,
    var openPrivateMediaFailuresRemaining: Int = 0,
    var openPrivateMediaDelayMs: Long = 0L,
    var loadMorePrivateMediaFailure: Boolean = false,
    var saveFailure: Throwable? = null,
    var resolvedPrivateOriginalUri: String? = null,
    var familyMedia: List<FamilyMediaSummary> = emptyList(),
    var trashMedia: List<TrashMediaSummary> = emptyList(),
  ) : MineGAppRuntime {
    var restoreCalled = false
    var signInCalled = false
    var signOutCalled = false
    val enqueuedAssetRefs = mutableListOf<String>()
    val savedMediaIds = mutableListOf<String>()
    val savedMediaDetails = mutableListOf<PrivateMediaDetail>()
    val trashedMediaIds = mutableListOf<String>()
    val detailedMediaIds = mutableListOf<String>()
    val resolvedPrivateOriginalIds = mutableListOf<String>()
    val openedMediaIds = mutableListOf<String>()
    val closedViewHandles = mutableListOf<String>()
    val shareRequests = mutableListOf<Pair<String, Boolean>>()
    val restoredMediaIds = mutableListOf<String>()
    val feedbackCategories = mutableListOf<String>()
    var activePrivateMediaOpens = 0
    var maximumConcurrentPrivateMediaOpens = 0
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
      if (loadMorePrivateMediaFailure) {
        throw AccountProblem(
          "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE",
          "private_media.resource_unavailable",
          true,
          "request-load-more-test",
        )
      }
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
        localPlatformAssetRef = media.localPlatformAssetRef,
        localSourceUri = media.localSourceUri,
      )
    }
    override suspend fun resolvePrivateMediaOriginal(userId: String, detail: PrivateMediaDetail): String? {
      resolvedPrivateOriginalIds += detail.id
      return detail.localSourceUri ?: resolvedPrivateOriginalUri
    }
    override suspend fun openPrivateMedia(userId: String, mediaId: String): PrivateMediaView {
      openedMediaIds += mediaId
      require(privateMedia.any { it.id == mediaId })
      activePrivateMediaOpens += 1
      maximumConcurrentPrivateMediaOpens = maxOf(maximumConcurrentPrivateMediaOpens, activePrivateMediaOpens)
      return try {
        if (openPrivateMediaDelayMs > 0) delay(openPrivateMediaDelayMs)
        if (openPrivateMediaFailuresRemaining > 0) {
          openPrivateMediaFailuresRemaining -= 1
          throw AccountProblem(
            "PRIVATE_MEDIA_ACCESS_UNAVAILABLE",
            "private_media.access_unavailable",
            true,
            "request-preview-test",
          )
        }
        PrivateMediaView(
          mediaId = mediaId,
          resourceType = "THUMBNAIL",
          mimeType = "image/jpeg",
          viewHandle = "view-handle-$mediaId",
          sourceUri = "file:///verified/$mediaId",
        )
      } finally {
        activePrivateMediaOpens -= 1
      }
    }
    override suspend fun closePrivateMedia(viewHandle: String): Boolean {
      closedViewHandles += viewHandle
      return true
    }

    override suspend fun invalidatePrivateMediaThumbnail(userId: String, mediaId: String) = Unit
    override suspend fun savePrivateMediaToSystemAlbum(
      userId: String,
      detail: PrivateMediaDetail,
    ): PrivateMediaSaveResult {
      val mediaId = detail.id
      savedMediaIds += mediaId
      savedMediaDetails += detail
      saveFailure?.let { throw it }
      return PrivateMediaSaveResult(mediaId, "COMPLETED", 1)
    }
    override suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult {
      trashedMediaIds += mediaId
      privateMedia = privateMedia.filterNot { it.id == mediaId }
      return PrivateMediaTrashResult(mediaId, "TRASHED", "2026-08-03T00:00:00Z")
    }
    override suspend fun setPrivateMediaShare(mediaId: String, shared: Boolean): PrivateMediaShareResult {
      shareRequests += mediaId to shared
      return PrivateMediaShareResult(
        mediaId = mediaId,
        state = if (shared) "ACTIVE" else "INACTIVE",
        outcome = if (shared) "SHARED" else "UNSHARED",
        effectiveAt = "2026-08-03T00:00:00Z",
      )
    }
    override suspend fun refreshFamilyMedia(filter: String, cursor: String?, limit: Int): FamilyMediaPage =
      FamilyMediaPage(
        items = familyMedia.filter { filter == "all" || it.owner.id == profile.id }.take(limit),
        nextCursor = null,
        fullyLoaded = true,
      )
    override suspend fun getFamilyMediaDetail(mediaId: String): FamilyMediaDetail {
      val media = requireNotNull(familyMedia.firstOrNull { it.id == mediaId })
      return FamilyMediaDetail(
        id = media.id,
        owner = media.owner,
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
    override suspend fun openFamilyMedia(mediaId: String): PrivateMediaView = PrivateMediaView(
      mediaId = mediaId,
      resourceType = "THUMBNAIL",
      mimeType = "image/jpeg",
      viewHandle = "family-view-$mediaId",
      sourceUri = "file:///verified/family/$mediaId",
    )
    override suspend fun closeFamilyMedia(viewHandle: String): Boolean = true
    override suspend fun refreshTrashMedia(cursor: String?, limit: Int): TrashMediaPage = TrashMediaPage(
      items = trashMedia.take(limit),
      nextCursor = null,
      fullyLoaded = true,
    )
    override suspend fun restoreTrashMedia(mediaId: String): TrashMediaRestoreResult {
      restoredMediaIds += mediaId
      val restored = trashMedia.firstOrNull { it.id == mediaId }
      trashMedia = trashMedia.filterNot { it.id == mediaId }
      if (restored != null && privateMedia.none { it.id == mediaId }) {
        privateMedia = listOf(
          PrivateMediaSummary(
            restored.id,
            restored.mediaType,
            restored.capturedAt,
            restored.createdAt,
            restored.durationMs,
            restored.originalTotalSize,
            null,
          ),
        ) + privateMedia
      }
      return TrashMediaRestoreResult(mediaId, "RESTORED", "2026-08-03T02:00:00Z")
    }
    override suspend fun sendFeedback(
      category: String,
      description: String,
      contact: String,
      appVersion: String,
      osVersion: String,
    ): FeedbackSubmissionResult {
      feedbackCategories += category
      return FeedbackSubmissionResult(
        "55555555-5555-4555-8555-555555555555",
        "SUBMITTED",
        "2026-08-03T03:00:00Z",
      )
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
    override suspend fun listLocalMedia(
      userId: String,
      albumRef: String,
      cursor: LocalMediaCursor?,
      limit: Int,
    ): LocalMediaPage {
      val start = cursor?.let { value ->
        localMedia.indexOfFirst { it.platformAssetRef == value.platformAssetRef }
          .takeIf { it >= 0 }?.plus(1)
      } ?: 0
      val end = minOf(localMedia.size, start + minOf(limit, localMediaPageSize))
      val items = localMedia.subList(start, end)
      val nextCursor = if (end < localMedia.size) {
        items.last().let { LocalMediaCursor(it.capturedAt, it.platformAssetRef) }
      } else null
      return LocalMediaPage(items, nextCursor)
    }
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
