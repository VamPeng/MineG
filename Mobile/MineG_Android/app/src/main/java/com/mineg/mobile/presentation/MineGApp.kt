/** Root Compose navigation that renders exclusively from [MineGAppState]. */
package com.mineg.mobile.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.mineg.mobile.BuildConfig
import com.mineg.mobile.feature.auth.ui.LegalPage
import com.mineg.mobile.feature.auth.ui.LoginPage
import com.mineg.mobile.feature.auth.ui.PermissionPage
import com.mineg.mobile.feature.auth.ui.RestoringPage
import com.mineg.mobile.feature.auth.ui.ReviewPendingPage
import com.mineg.mobile.feature.auth.ui.SignUpPage
import com.mineg.mobile.feature.debug.ui.DebugStatePanel
import com.mineg.mobile.feature.library.ui.BackupPage
import com.mineg.mobile.feature.library.ui.BackupSettingsPage
import com.mineg.mobile.feature.library.ui.LocalAlbumPage
import com.mineg.mobile.feature.library.ui.PrivateMediaDetailPage
import com.mineg.mobile.feature.library.ui.PrivateSpacePage
import com.mineg.mobile.feature.library.ui.SharedByMePage
import com.mineg.mobile.feature.library.ui.SharedMediaDetailPage
import com.mineg.mobile.feature.profile.ui.HelpFeedbackPage
import com.mineg.mobile.feature.profile.ui.ProfileEditPage
import com.mineg.mobile.feature.profile.ui.ProfileLogoutDialog
import com.mineg.mobile.feature.profile.ui.ProfilePage
import com.mineg.mobile.feature.profile.ui.RecycleBinPage
import com.mineg.mobile.feature.profile.ui.RecycleRestoreDialog

/** Renders the root route, dialogs and lifecycle forwarding from ViewModel state. */
@Composable
fun MineGApp(viewModel: MineGAppViewModel, onRequestLibraryAccess: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val route = state.currentRoute

  MineGSystemStatusBar(route)

  BackHandler(enabled = state.dialog != null || state.backStack.isNotEmpty()) {
    viewModel.back()
  }

  Box(Modifier.fillMaxSize()) {
    when (route) {
    AppRoute.Restoring -> RestoringPage(state.auth.message)
    AppRoute.Login -> LoginPage(
      state = state.auth,
      onPhoneChange = viewModel::updatePhone,
      onPasswordChange = viewModel::updatePassword,
      onLogin = viewModel::submitLogin,
      onSignUp = viewModel::openSignUp,
    )
    AppRoute.SignUp -> SignUpPage(
      state = state.auth,
      onBack = viewModel::back,
      onPhoneChange = viewModel::updatePhone,
      onPasswordChange = viewModel::updatePassword,
      onPasswordConfirmationChange = viewModel::updatePasswordConfirmation,
      onSubmit = viewModel::submitSignUp,
    )
    AppRoute.ReviewPending -> ReviewPendingPage(
      state = state.auth,
      onRefresh = viewModel::refreshReviewStatus,
      onBackToLogin = viewModel::returnToLogin,
    )
    is AppRoute.Legal -> LegalPage(route.document, viewModel::back)
    AppRoute.Permission -> PermissionPage(
      message = state.auth.message,
      onGrant = onRequestLibraryAccess,
      onDefer = viewModel::deferLibraryAccess,
    )
    AppRoute.PrivateSpace -> PrivateSpacePage(
      privateState = state.privateSpace,
      privatePreviewSources = viewModel.privateMediaPreviewSources,
      sharedState = state.sharedAlbum,
      selectedLibraryTab = state.selectedLibraryTab,
      privateMediaListScrollPosition = state.privateMediaListScrollPosition,
      sharedMediaListScrollPosition = state.sharedMediaListScrollPosition,
      selectedTab = state.selectedTab,
      onSelectLibraryTab = viewModel::selectLibraryTab,
      onSelectTab = viewModel::selectTab,
      onOpenPrivateMedia = viewModel::openPrivateMedia,
      onRefreshPrivateMedia = viewModel::refreshPrivateMedia,
      onLoadMorePrivateMedia = viewModel::loadMorePrivateMedia,
      onVisiblePrivateMediaChanged = viewModel::updateVisiblePrivateMedia,
      onRetryPrivateMediaPreview = viewModel::retryPrivateMediaPreview,
      onPrivateMediaListScrollPositionChanged = viewModel::updatePrivateMediaListScrollPosition,
      onSharedMediaListScrollPositionChanged = viewModel::updateSharedMediaListScrollPosition,
      onOpenSharedMedia = viewModel::openSharedMedia,
    )
    AppRoute.Backup -> BackupPage(
      state.backup,
      state.selectedTab,
      viewModel::selectTab,
      onSettings = { viewModel.navigate(AppRoute.BackupSettings) },
      onRefresh = viewModel::refreshLocalLibrary,
      onOpenAlbum = viewModel::openLocalAlbum,
      onStartBackup = viewModel::startBackup,
    )
    AppRoute.Profile -> state.profile?.let { profile -> ProfilePage(
      profile,
      state.selectedTab,
      viewModel::selectTab,
      onEdit = { viewModel.navigate(AppRoute.ProfileEdit) },
      onBackupSettings = { viewModel.navigate(AppRoute.BackupSettings) },
      onRecycleBin = { viewModel.navigate(AppRoute.RecycleBin) },
      onSharedByMe = { viewModel.navigate(AppRoute.SharedByMe) },
      onHelp = { viewModel.navigate(AppRoute.HelpFeedback) },
      onLogout = viewModel::requestLogout,
    ) } ?: RestoringPage("正在校验用户信息…")
    is AppRoute.PrivateMediaDetail -> PrivateMediaDetailPage(
      media = viewModel.mediaById(route.mediaId),
      actionState = state.selectedMediaAction,
      showDeleteConfirmation = state.dialog is AppDialog.DeleteMedia,
      onLoadPreview = { viewModel.loadPrivateMediaPreview(route.mediaId) },
      onBack = viewModel::back,
      onDownload = viewModel::downloadSelectedMedia,
      onShare = { viewModel.setSelectedMediaSharing(route.mediaId) },
      onDelete = { viewModel.requestDelete(route.mediaId) },
      onRetrySave = viewModel::downloadSelectedMedia,
      onDismissAction = viewModel::dismissSelectedMediaAction,
    )
    is AppRoute.SharedMediaDetail -> SharedMediaDetailPage(
      media = viewModel.mediaById(route.mediaId),
      onLoadPreview = { viewModel.loadSharedMediaPreview(route.mediaId) },
      onBack = viewModel::back,
    )
    AppRoute.SharedByMe -> SharedByMePage(state.sharedAlbum, viewModel::back, viewModel::openSharedMedia)
    is AppRoute.LocalAlbum -> {
      val album = state.backup.albums.firstOrNull { it.id == route.albumId }
      LocalAlbumPage(
        album = album,
        state = state.backup,
        onUpload = viewModel::backupSingleMedia,
        onLoadMore = viewModel::loadMoreLocalAlbumMedia,
        onBack = viewModel::back,
      )
    }
    AppRoute.BackupSettings -> BackupSettingsPage(
      state.backup,
      viewModel::back,
      viewModel::setAutoBackupEnabled,
      viewModel::setCellularBackupEnabled,
    )
    AppRoute.ProfileEdit -> state.profile?.let { profile ->
      ProfileEditPage(
        profile = profile,
        nickname = state.profileDraftNickname,
        saving = state.auth.loading,
        message = state.auth.message,
        onNickname = viewModel::updateNickname,
        onSave = viewModel::saveProfile,
        onAvatar = viewModel::updateAvatar,
        onBack = viewModel::back,
      )
    } ?: RestoringPage("正在校验用户信息…")
    AppRoute.RecycleBin -> RecycleBinPage(state.recycleBin, viewModel::back, viewModel::requestRestore)
    AppRoute.HelpFeedback -> HelpFeedbackPage(
      state.feedback,
      viewModel::back,
      viewModel::setFeedbackCategory,
      viewModel::updateFeedbackDescription,
      viewModel::updateFeedbackContact,
      viewModel::sendFeedback,
    )
    }

  }

  if (BuildConfig.DEBUG && state.debugPanelVisible) {
    DebugStatePanel(
      state = state,
      onDismiss = viewModel::hideDebugPanel,
      onNavigate = viewModel::debugNavigate,
      onPrivateState = viewModel::setPrivateSpaceLoadState,
      onSharedState = viewModel::setSharedAlbumLoadState,
      onRecycleState = viewModel::setRecycleBinLoadState,
      onBackupStatus = viewModel::setBackupStatus,
      onMediaAction = viewModel::setMediaActionState,
      onReset = viewModel::resetAcceptanceState,
    )
  }

  state.dialog?.let { dialog ->
    when (dialog) {
      is AppDialog.DeleteMedia -> PrivateMediaDeleteDialog(viewModel::confirmDialog, viewModel::dismissDialog)
      is AppDialog.RestoreMedia -> RecycleRestoreDialog(
        media = state.recycleBin.items.firstOrNull { it.media.id == dialog.mediaId }?.media,
        onConfirm = viewModel::confirmDialog,
        onDismiss = viewModel::dismissDialog,
      )
      AppDialog.Logout -> ProfileLogoutDialog(viewModel::confirmDialog, viewModel::dismissDialog)
    }
  }
}

/** Applies route-aware system status-bar appearance. */
@Composable
private fun MineGSystemStatusBar(route: AppRoute) {
  val view = LocalView.current
  val isDarkTheme = isSystemInDarkTheme()
  val statusBarColor = when {
    isDarkTheme -> MaterialTheme.colorScheme.background
    route is AppRoute.SharedMediaDetail -> Color(0xFFF5F2ED)
    else -> MaterialTheme.colorScheme.background
  }

  SideEffect {
    val window = view.context.findActivity()?.window ?: return@SideEffect
    window.statusBarColor = statusBarColor.toArgb()
    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/** Confirms destructive private-media deletion. */
@Composable
private fun PrivateMediaDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("private-media.delete.confirm"),
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = 0.dp,
    ) {
      Column(Modifier.padding(24.dp)) {
        Text("移入回收站？", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
          "移入后媒体将在私人空间和共享空间中隐藏，可从回收站恢复。",
          modifier = Modifier.padding(top = 16.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
        )
        Button(
          onClick = onConfirm,
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(48.dp),
          shape = RoundedCornerShape(8.dp),
          colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          ),
        ) { Text("移入回收站", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
        androidx.compose.material3.OutlinedButton(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
          shape = RoundedCornerShape(8.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC7D6E0)),
        ) {
          Text("取消", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}
