@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

/** Developer-only UI for inspecting and overriding presentation state scenarios. */
package com.mineg.mobile.feature.debug.ui

import com.mineg.mobile.presentation.AppRoute
import com.mineg.mobile.presentation.BackupStatus
import com.mineg.mobile.presentation.LibraryTab
import com.mineg.mobile.presentation.MediaActionState
import com.mineg.mobile.presentation.MineGAppState
import com.mineg.mobile.presentation.PageLoadState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Renders developer controls for routes and deterministic UI state overrides. */
@Composable
fun DebugStatePanel(
  state: MineGAppState,
  onDismiss: () -> Unit,
  onNavigate: (AppRoute) -> Unit,
  onPrivateState: (PageLoadState) -> Unit,
  onSharedState: (PageLoadState) -> Unit,
  onRecycleState: (PageLoadState) -> Unit,
  onBackupStatus: (BackupStatus) -> Unit,
  onMediaAction: (MediaActionState) -> Unit,
  onReset: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("页面验收调试", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text(
        "仅在 Debug 包显示。页面状态切换不会写入设备数据；重新校验登录状态会读取本地会话并按需访问后端。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )

      DebugSection("主页面") {
        DebugChip("私人/共享") { onNavigate(AppRoute.PrivateSpace) }
        DebugChip("备份") { onNavigate(AppRoute.Backup) }
        DebugChip("我的") { onNavigate(AppRoute.Profile) }
      }

      DebugSection("次级页面") {
        DebugChip("我分享的") { onNavigate(AppRoute.SharedByMe) }
        DebugChip("回收站") { onNavigate(AppRoute.RecycleBin) }
        DebugChip("备份设置") { onNavigate(AppRoute.BackupSettings) }
        DebugChip("帮助反馈") { onNavigate(AppRoute.HelpFeedback) }
        DebugChip("权限说明") { onNavigate(AppRoute.Permission) }
        state.privateSpace.items.firstOrNull()?.let { media ->
          DebugChip("私人详情") { onNavigate(AppRoute.PrivateMediaDetail(media.id)) }
        }
        state.sharedAlbum.items.firstOrNull()?.let { media ->
          DebugChip("共享详情") { onNavigate(AppRoute.SharedMediaDetail(media.id)) }
        }
      }

      when (state.currentRoute) {
        AppRoute.PrivateSpace -> if (state.selectedLibraryTab == LibraryTab.PRIVATE) {
          LoadStateSection("私人内容状态", onPrivateState)
        } else {
          LoadStateSection("共享内容状态", onSharedState)
        }
        AppRoute.SharedByMe -> LoadStateSection("我分享的状态", onSharedState)
        AppRoute.RecycleBin -> LoadStateSection("回收站状态", onRecycleState)
        AppRoute.Backup -> DebugSection("备份状态") {
          BackupStatus.entries.forEach { status -> DebugChip(status.debugLabel()) { onBackupStatus(status) } }
        }
        is AppRoute.PrivateMediaDetail -> DebugSection("媒体操作状态") {
          MediaActionState.entries.forEach { action -> DebugChip(action.debugLabel()) { onMediaAction(action) } }
        }
        else -> Unit
      }

      HorizontalDivider()
      Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.RestartAlt, contentDescription = null)
        Text(" 重新校验登录状态")
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}

/** Renders the complete set of selectable page-load scenarios. */
@Composable
private fun LoadStateSection(title: String, onSelect: (PageLoadState) -> Unit) {
  DebugSection(title) {
    PageLoadState.entries.forEach { state -> DebugChip(state.debugLabel()) { onSelect(state) } }
  }
}

/** Groups related debug controls under one label. */
@Composable
private fun DebugSection(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
  }
}

/** Renders one compact debug action. */
@Composable
private fun DebugChip(label: String, onClick: () -> Unit) {
  AssistChip(onClick = onClick, label = { Text(label) })
}

/** Maps a load-state enum to concise debug copy. */
private fun PageLoadState.debugLabel(): String = when (this) {
  PageLoadState.LOADING -> "加载中"
  PageLoadState.CONTENT -> "默认内容"
  PageLoadState.EMPTY -> "空状态"
  PageLoadState.ERROR -> "加载失败"
}

/** Maps backup status to concise debug copy. */
private fun BackupStatus.debugLabel(): String = when (this) {
  BackupStatus.PERMISSION_REQUIRED -> "需要权限"
  BackupStatus.SCANNING -> "扫描中"
  BackupStatus.UPLOADING -> "上传中"
  BackupStatus.WAITING_WIFI -> "等待 Wi-Fi"
  BackupStatus.NETWORK_OFFLINE -> "网络离线"
  BackupStatus.DEVICE_STORAGE_FULL -> "设备空间不足"
  BackupStatus.CLOUD_STORAGE_FULL -> "服务空间不足"
  BackupStatus.SERVICE_UNAVAILABLE -> "服务不可用"
  BackupStatus.INDEXED -> "索引完成"
  BackupStatus.COMPLETE -> "同步完成"
  BackupStatus.PAUSED -> "自动备份关闭"
}

/** Maps media-action state to concise debug copy. */
private fun MediaActionState.debugLabel(): String = when (this) {
  MediaActionState.IDLE -> "默认"
  MediaActionState.DOWNLOADING -> "下载中"
  MediaActionState.SAVED -> "保存成功"
  MediaActionState.SAVE_FAILED -> "保存失败"
  MediaActionState.SHARED -> "共享反馈"
}
