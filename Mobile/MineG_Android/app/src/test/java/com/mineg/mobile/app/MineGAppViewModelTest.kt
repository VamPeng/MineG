package com.mineg.mobile.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MineGAppViewModelTest {
  @Test
  fun `mock login permission and main navigation form a typed route flow`() {
    val viewModel = MineGAppViewModel()

    viewModel.returnToLogin()
    assertEquals(AppRoute.Login, viewModel.state.value.currentRoute)
    viewModel.submitLogin()
    assertEquals(AppRoute.Permission, viewModel.state.value.currentRoute)

    viewModel.grantLibraryAccess()
    assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
    assertEquals(LibraryAccess.FULL, viewModel.state.value.libraryAccess)

    viewModel.selectLibraryTab(LibraryTab.SHARED)
    assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
    assertEquals(MainTab.PRIVATE_SPACE, viewModel.state.value.selectedTab)
    assertEquals(LibraryTab.SHARED, viewModel.state.value.selectedLibraryTab)

    viewModel.openFamilyMedia("family-01")
    assertIs<AppRoute.FamilyMediaDetail>(viewModel.state.value.currentRoute)
    assertTrue(viewModel.back())
    assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
    assertEquals(LibraryTab.SHARED, viewModel.state.value.selectedLibraryTab)
  }

  @Test
  fun `acceptance build starts on populated private home`() {
    val viewModel = MineGAppViewModel()

    assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
    assertEquals(MainTab.PRIVATE_SPACE, viewModel.state.value.selectedTab)
    assertEquals(LibraryAccess.FULL, viewModel.state.value.libraryAccess)
    assertEquals(PageLoadState.CONTENT, viewModel.state.value.privateSpace.loadState)
    assertTrue(viewModel.state.value.privateSpace.items.isNotEmpty())
  }

  @Test
  fun `sign up validation remains page state and valid form routes to review`() {
    val viewModel = MineGAppViewModel()
    viewModel.openSignUp()
    viewModel.updatePhone("123")
    viewModel.updatePassword("short")
    viewModel.updatePasswordConfirmation("different")

    viewModel.submitSignUp()
    assertEquals(AppRoute.SignUp, viewModel.state.value.currentRoute)
    assertEquals(setOf("phone", "password", "passwordConfirmation"), viewModel.state.value.auth.fieldErrors.keys)

    viewModel.updatePhone("13800138000")
    viewModel.updatePassword("mineg2026")
    viewModel.updatePasswordConfirmation("mineg2026")
    viewModel.submitSignUp()
    assertEquals(AppRoute.ReviewPending, viewModel.state.value.currentRoute)
  }

  @Test
  fun `delete and restore use dialogs and restored media remains private`() {
    val viewModel = MineGAppViewModel()
    viewModel.submitLogin()
    viewModel.grantLibraryAccess()
    val media = viewModel.state.value.privateSpace.items.first()

    viewModel.openPrivateMedia(media.id)
    viewModel.requestDelete(media.id)
    assertIs<AppDialog.DeleteMedia>(viewModel.state.value.dialog)
    viewModel.confirmDialog()

    assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
    assertFalse(viewModel.state.value.privateSpace.items.any { it.id == media.id })
    assertTrue(viewModel.state.value.recycleBin.items.any { it.media.id == media.id })

    viewModel.navigate(AppRoute.RecycleBin)
    viewModel.requestRestore(media.id)
    viewModel.confirmDialog()
    val restored = viewModel.state.value.privateSpace.items.first { it.id == media.id }
    assertFalse(restored.isShared)
    assertFalse(viewModel.state.value.recycleBin.items.any { it.media.id == media.id })
  }

  @Test
  fun `backup settings drive backup page status`() {
    val viewModel = MineGAppViewModel()

    viewModel.setAutoBackupEnabled(false)
    assertFalse(viewModel.state.value.backup.autoBackupEnabled)
    assertEquals(BackupStatus.PAUSED, viewModel.state.value.backup.status)

    viewModel.startBackup()
    assertTrue(viewModel.state.value.backup.autoBackupEnabled)
    assertEquals(BackupStatus.UPLOADING, viewModel.state.value.backup.status)
  }

  @Test
  fun `sharing state is reflected in family album`() {
    val viewModel = MineGAppViewModel()
    val media = viewModel.state.value.privateSpace.items.first { !it.isShared }

    viewModel.toggleShare(media.id)
    assertTrue(viewModel.state.value.privateSpace.items.first { it.id == media.id }.isShared)
    assertTrue(viewModel.state.value.familyAlbum.items.any { it.id == media.id })

    viewModel.toggleShare(media.id)
    assertFalse(viewModel.state.value.privateSpace.items.first { it.id == media.id }.isShared)
    assertFalse(viewModel.state.value.familyAlbum.items.any { it.id == media.id })
  }

  @Test
  fun `profile opens media shared by the current member`() {
    val viewModel = MineGAppViewModel()

    viewModel.selectTab(MainTab.PROFILE)
    viewModel.navigate(AppRoute.SharedByMe)

    assertEquals(AppRoute.SharedByMe, viewModel.state.value.currentRoute)
    assertTrue(viewModel.state.value.familyAlbum.items.any(MediaItem::sharedByMe))

    val sharedMedia = viewModel.state.value.familyAlbum.items.first(MediaItem::sharedByMe)
    viewModel.openFamilyMedia(sharedMedia.id)
    assertIs<AppRoute.FamilyMediaDetail>(viewModel.state.value.currentRoute)
    assertTrue(viewModel.back())
    assertEquals(AppRoute.SharedByMe, viewModel.state.value.currentRoute)
  }

  @Test
  fun `debug controls switch visual states without mutating mock content`() {
    val viewModel = MineGAppViewModel()
    val originalItems = viewModel.state.value.privateSpace.items

    viewModel.showDebugPanel()
    assertTrue(viewModel.state.value.debugPanelVisible)
    viewModel.setPrivateSpaceLoadState(PageLoadState.EMPTY)
    assertEquals(PageLoadState.EMPTY, viewModel.state.value.privateSpace.loadState)
    assertEquals(originalItems, viewModel.state.value.privateSpace.items)
    assertFalse(viewModel.state.value.debugPanelVisible)

    viewModel.debugNavigate(AppRoute.Backup)
    viewModel.setBackupStatus(BackupStatus.WAITING_WIFI)
    assertEquals(AppRoute.Backup, viewModel.state.value.currentRoute)
    assertEquals(BackupStatus.WAITING_WIFI, viewModel.state.value.backup.status)

    viewModel.resetAcceptanceState()
    assertEquals(AppRoute.PrivateSpace, viewModel.state.value.currentRoute)
    assertEquals(PageLoadState.CONTENT, viewModel.state.value.privateSpace.loadState)
  }
}
