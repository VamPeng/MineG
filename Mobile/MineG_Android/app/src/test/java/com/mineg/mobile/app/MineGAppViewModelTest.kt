package com.mineg.mobile.app

import android.net.Uri

import com.mineg.mobile.contracts.AccountNextStep
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalScanState
import com.mineg.mobile.contracts.LocalScanStatus
import com.mineg.mobile.contracts.OwnerMediaSummary
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
        media = listOf(OwnerMediaSummary("media-1", "PHOTO", 1, "2026-07-30T08:00:00Z", "2026-07-30T08:01:00Z")),
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
      assertEquals(setOf("phone", "password", "agreement"), viewModel.state.value.auth.fieldErrors.keys)
      assertFalse(runtime.signInCalled)

      viewModel.updatePhone("13800138000")
      viewModel.updatePassword("mineg2026")
      viewModel.updateAgreement(true)
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
    var media: List<OwnerMediaSummary> = emptyList(),
  ) : MineGAppRuntime {
    var restoreCalled = false
    var signInCalled = false
    var signOutCalled = false

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
    override suspend fun listOwnerMedia(limit: Int): List<OwnerMediaSummary> = media
    override suspend fun refreshLocalLibrary(userId: String): LocalLibrarySnapshot = LocalLibrarySnapshot(
      scan = LocalScanState(0, "", LocalScanStatus.COMPLETE, 12, "generation", "2026-07-30T08:00:00Z"),
      albums = listOf(LocalAlbum("album-1", "相机", 12, null)),
    )

    override suspend fun signOut() {
      signOutCalled = true
    }

    override fun libraryAccess(): LibraryAccess = access
    override fun markLibraryPermissionRequested() = Unit
    override fun close() = Unit
  }

  private companion object {
    fun approvedSession() = AccountRouteSnapshot(
      userId = "user-1",
      approvalStatus = ApprovalStatus.APPROVED,
      nextStep = AccountNextStep.APP_HOME,
    )
  }
}
