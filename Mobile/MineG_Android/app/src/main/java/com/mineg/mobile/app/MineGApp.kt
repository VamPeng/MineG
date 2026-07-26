package com.mineg.mobile.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineg.mobile.BuildConfig

@Composable
fun MineGApp(viewModel: MineGAppViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val route = state.currentRoute

  BackHandler(enabled = state.dialog != null || state.backStack.isNotEmpty()) {
    viewModel.back()
  }

  Box(Modifier.fillMaxSize()) {
    when (route) {
    AppRoute.Login -> LoginPage(
      state = state.auth,
      onPhoneChange = viewModel::updatePhone,
      onPasswordChange = viewModel::updatePassword,
      onAgreementChange = viewModel::updateAgreement,
      onLogin = viewModel::submitLogin,
      onSignUp = viewModel::openSignUp,
      onLegal = { viewModel.navigate(AppRoute.Legal(it)) },
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
    AppRoute.Permission -> PermissionPage(viewModel::grantLibraryAccess, viewModel::deferLibraryAccess)
    AppRoute.PrivateSpace -> PrivateSpacePage(
      state.privateSpace,
      state.selectedTab,
      viewModel::selectTab,
      viewModel::openPrivateMedia,
    )
    AppRoute.FamilyAlbum -> FamilyAlbumPage(
      state.familyAlbum,
      state.selectedTab,
      viewModel::selectTab,
      viewModel::setFamilyFilter,
      viewModel::openFamilyMedia,
    )
    AppRoute.Backup -> BackupPage(
      state.backup,
      state.selectedTab,
      viewModel::selectTab,
      onSettings = { viewModel.navigate(AppRoute.BackupSettings) },
      onOpenAlbum = viewModel::openLocalAlbum,
      onStartBackup = viewModel::startBackup,
    )
    AppRoute.Profile -> ProfilePage(
      state.profile,
      state.selectedTab,
      viewModel::selectTab,
      onEdit = { viewModel.navigate(AppRoute.ProfileEdit) },
      onBackupSettings = { viewModel.navigate(AppRoute.BackupSettings) },
      onRecycleBin = { viewModel.navigate(AppRoute.RecycleBin) },
      onFamilyAlbum = { viewModel.selectTab(MainTab.FAMILY_ALBUM) },
      onHelp = { viewModel.navigate(AppRoute.HelpFeedback) },
      onLogout = viewModel::requestLogout,
    )
    is AppRoute.PrivateMediaDetail -> PrivateMediaDetailPage(
      media = viewModel.mediaById(route.mediaId),
      actionState = state.selectedMediaAction,
      onBack = viewModel::back,
      onDownload = viewModel::downloadSelectedMedia,
      onFinishDownload = viewModel::finishMockDownload,
      onShare = { viewModel.toggleShare(route.mediaId) },
      onDelete = { viewModel.requestDelete(route.mediaId) },
    )
    is AppRoute.FamilyMediaDetail -> FamilyMediaDetailPage(viewModel.mediaById(route.mediaId), viewModel::back)
    is AppRoute.LocalAlbum -> {
      val album = state.backup.albums.firstOrNull { it.id == route.albumId }
      LocalAlbumPage(album, state.privateSpace.items, viewModel::back)
    }
    AppRoute.BackupSettings -> BackupSettingsPage(
      state.backup,
      viewModel::back,
      viewModel::setAutoBackupEnabled,
      viewModel::setCellularBackupEnabled,
    )
    AppRoute.ProfileEdit -> ProfileEditPage(state.profile, viewModel::updateNickname, viewModel::back)
    AppRoute.RecycleBin -> RecycleBinPage(state.recycleBin, viewModel::back, viewModel::requestRestore)
    AppRoute.HelpFeedback -> HelpFeedbackPage(
      state.feedback,
      viewModel::back,
      viewModel::setFeedbackCategory,
      viewModel::updateFeedbackDescription,
      viewModel::updateFeedbackContact,
      viewModel::submitFeedback,
    )
    }

    if (BuildConfig.DEBUG && !state.debugPanelVisible) {
      FloatingActionButton(
        onClick = viewModel::showDebugPanel,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      ) {
        Row(Modifier.padding(horizontal = 13.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          Icon(Icons.Outlined.Build, contentDescription = null)
          Text("调试")
        }
      }
    }
  }

  if (BuildConfig.DEBUG && state.debugPanelVisible) {
    DebugStatePanel(
      state = state,
      onDismiss = viewModel::hideDebugPanel,
      onNavigate = viewModel::debugNavigate,
      onPrivateState = viewModel::setPrivateSpaceLoadState,
      onFamilyState = viewModel::setFamilyAlbumLoadState,
      onRecycleState = viewModel::setRecycleBinLoadState,
      onBackupStatus = viewModel::setBackupStatus,
      onMediaAction = viewModel::setMediaActionState,
      onReset = viewModel::resetAcceptanceState,
    )
  }

  state.dialog?.let { dialog ->
    val title: String
    val message: String
    val confirmLabel: String
    val destructive: Boolean
    when (dialog) {
      is AppDialog.DeleteMedia -> {
        title = "移入回收站？"
        message = "媒体将从私人空间和家庭相册隐藏，但不会删除设备本地媒体。"
        confirmLabel = "移入回收站"
        destructive = true
      }
      is AppDialog.RestoreMedia -> {
        title = "恢复这项媒体？"
        message = "恢复后将回到私人空间并保持未共享。"
        confirmLabel = "恢复"
        destructive = false
      }
      AppDialog.Logout -> {
        title = "确定退出登录？"
        message = "退出后将停止当前账号未完成的任务，并清理本机凭据与内存密钥。"
        confirmLabel = "退出登录"
        destructive = true
      }
    }
    AlertDialog(
      onDismissRequest = viewModel::dismissDialog,
      title = { Text(title) },
      text = { Text(message) },
      confirmButton = {
        TextButton(onClick = viewModel::confirmDialog) {
          Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
      },
      dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("取消") } },
    )
  }
}
