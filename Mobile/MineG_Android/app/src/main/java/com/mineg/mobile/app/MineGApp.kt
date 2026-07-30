package com.mineg.mobile.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineg.mobile.BuildConfig

@Composable
fun MineGApp(viewModel: MineGAppViewModel, onRequestLibraryAccess: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val route = state.currentRoute

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
    AppRoute.Permission -> PermissionPage(
      message = state.auth.message,
      onGrant = onRequestLibraryAccess,
      onDefer = viewModel::deferLibraryAccess,
    )
    AppRoute.PrivateSpace -> PrivateSpacePage(
      privateState = state.privateSpace,
      sharedState = state.familyAlbum,
      selectedLibraryTab = state.selectedLibraryTab,
      selectedTab = state.selectedTab,
      onSelectLibraryTab = viewModel::selectLibraryTab,
      onSelectTab = viewModel::selectTab,
      onOpenPrivateMedia = viewModel::openPrivateMedia,
      onOpenSharedMedia = viewModel::openFamilyMedia,
    )
    AppRoute.Backup -> BackupPage(
      state.backup,
      state.selectedTab,
      viewModel::selectTab,
      onSettings = { viewModel.navigate(AppRoute.BackupSettings) },
      onOpenAlbum = viewModel::openLocalAlbum,
      onStartBackup = viewModel::startBackup,
      onRefresh = viewModel::refreshLocalLibrary,
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
      onBack = viewModel::back,
      onDownload = viewModel::downloadSelectedMedia,
      onFinishDownload = viewModel::finishMockDownload,
      onShare = { viewModel.toggleShare(route.mediaId) },
      onDelete = { viewModel.requestDelete(route.mediaId) },
    )
    is AppRoute.FamilyMediaDetail -> FamilyMediaDetailPage(viewModel.mediaById(route.mediaId), viewModel::back)
    AppRoute.SharedByMe -> SharedByMePage(state.familyAlbum, viewModel::back, viewModel::openFamilyMedia)
    is AppRoute.LocalAlbum -> {
      val album = state.backup.albums.firstOrNull { it.id == route.albumId }
      LocalAlbumPage(album, state.backup, viewModel::backupSingleMedia, viewModel::back)
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
      viewModel::submitFeedback,
    )
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
    MineGConfirmDialog(
      title = title,
      message = message,
      confirmLabel = confirmLabel,
      destructive = destructive,
      icon = when (dialog) {
        is AppDialog.DeleteMedia -> Icons.Outlined.DeleteOutline
        is AppDialog.RestoreMedia -> Icons.Outlined.Restore
        AppDialog.Logout -> Icons.AutoMirrored.Outlined.Logout
      },
      onConfirm = viewModel::confirmDialog,
      onDismiss = viewModel::dismissDialog,
    )
  }
}

@Composable
private fun MineGConfirmDialog(
  title: String,
  message: String,
  confirmLabel: String,
  destructive: Boolean,
  icon: ImageVector,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
          Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            Modifier.size(56.dp).background(
              if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
              CircleShape,
            ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              icon,
              null,
              tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
          }
          Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
          Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
          Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
          Text("取消", color = MaterialTheme.colorScheme.onSurface)
        }
      }
    }
  }
}
