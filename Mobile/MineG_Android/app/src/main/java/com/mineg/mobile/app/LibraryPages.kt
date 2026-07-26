@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mineg.mobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors

@Composable
fun PrivateSpacePage(
  state: PrivateSpaceUiState,
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
  onOpenMedia: (String) -> Unit,
) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      MineGPageTitle("MineG 私人空间", "只有你可以查看的加密媒体")
      MineGCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.mineGColors.success)
          Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("端到端加密保护", fontWeight = FontWeight.SemiBold)
            Text("${state.items.size} 项 Mock 云端媒体", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Text("安全", color = MaterialTheme.mineGColors.success, fontWeight = FontWeight.Bold)
        }
      }
      when (state.loadState) {
        PageLoadState.LOADING -> PageLoading()
        PageLoadState.EMPTY -> EmptyState("私人空间还是空的", "完成首次备份后，照片和视频会出现在这里。")
        PageLoadState.ERROR -> EmptyState("暂时无法加载", state.errorMessage ?: "请检查网络后重试。")
        PageLoadState.CONTENT -> LazyVerticalGrid(
          columns = GridCells.Fixed(3),
          modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
          contentPadding = PaddingValues(bottom = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(state.items, key = MediaItem::id) { media ->
            MediaPlaceholder(
              media,
              Modifier.fillMaxWidth().aspectRatio(1f),
              onClick = { onOpenMedia(media.id) },
            )
          }
        }
      }
    }
  }
}

@Composable
fun FamilyAlbumPage(
  state: FamilyAlbumUiState,
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
  onFilter: (FamilyFilter) -> Unit,
  onOpenMedia: (String) -> Unit,
) {
  val visibleItems = if (state.filter == FamilyFilter.ALL) state.items else state.items.filter(MediaItem::sharedByMe)
  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
  ) { padding ->
    LazyColumn(
      Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(bottom = 18.dp),
    ) {
      item { MineGPageTitle("MineG 家庭相册", "家人主动共享的只读回忆") }
      item {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          FilterChip(state.filter == FamilyFilter.ALL, onClick = { onFilter(FamilyFilter.ALL) }, label = { Text("全部") })
          FilterChip(state.filter == FamilyFilter.SHARED_BY_ME, onClick = { onFilter(FamilyFilter.SHARED_BY_ME) }, label = { Text("我分享的") })
        }
      }
      if (state.loadState == PageLoadState.LOADING) {
        item {
          Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
      } else if (state.loadState == PageLoadState.ERROR) {
        item { EmptyState("家庭相册加载失败", state.errorMessage ?: "请检查网络后重试。") }
      } else if (state.loadState == PageLoadState.EMPTY || visibleItems.isEmpty()) {
        item { EmptyState("暂无共享内容", "在私人媒体详情中主动共享后，内容会出现在这里。") }
      } else {
        visibleItems.groupBy(MediaItem::dateGroup).forEach { (group, media) ->
          item { Text(group, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp)) }
          items(media.chunked(3), key = { row -> row.joinToString { it.id } }) { row ->
            Row(
              Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
              horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
              row.forEach { item ->
                MediaPlaceholder(
                  item,
                  Modifier.weight(1f).height(136.dp),
                  showOwner = true,
                  onClick = { onOpenMedia(item.id) },
                )
              }
              repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
          }
        }
      }
    }
  }
}

@Composable
fun BackupPage(
  state: BackupUiState,
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
  onSettings: () -> Unit,
  onOpenAlbum: (String) -> Unit,
  onStartBackup: () -> Unit,
) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
    floatingActionButton = {
      if (!state.autoBackupEnabled) FloatingActionButton(onClick = onStartBackup) {
        Icon(Icons.Outlined.CloudQueue, contentDescription = null)
        Text(" 开始备份")
      }
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(bottom = 88.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        MineGPageTitle("本地相册", "设备中的照片和视频") {
          IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "备份设置") }
        }
      }
      item { BackupStatusCard(state, Modifier.padding(horizontal = 20.dp)) }
      item { Text("设备相册", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
      items(state.albums, key = LocalAlbum::id) { album ->
        MineGCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable { onOpenAlbum(album.id) }) {
          Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(album.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${album.mediaCount} 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
              }
              Text("查看", color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              album.mediaIds.take(3).forEachIndexed { index, _ ->
                Box(
                  Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(
                    Brush.linearGradient(
                      listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        if (index % 2 == 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                      ),
                    ),
                  ),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BackupStatusCard(state: BackupUiState, modifier: Modifier = Modifier) {
  val presentation = backupPresentation(state.status)
  MineGCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
          Modifier.size(44.dp).clip(CircleShape).background(presentation.container),
          contentAlignment = Alignment.Center,
        ) { Icon(presentation.icon, null, tint = presentation.foreground) }
        Column(Modifier.weight(1f)) {
          Text(presentation.title, fontWeight = FontWeight.Bold)
          Text(presentation.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      if (state.status in setOf(BackupStatus.UPLOADING, BackupStatus.SCANNING)) {
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape))
        Text(
          if (state.status == BackupStatus.UPLOADING) "${state.currentMediaTitle} · ${(state.progress * 100).toInt()}%"
          else "已扫描 ${state.indexedCount} / ${state.totalCount} 项",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private data class BackupPresentation(
  val title: String,
  val description: String,
  val icon: ImageVector,
  val foreground: Color,
  val container: Color,
)

@Composable
private fun backupPresentation(status: BackupStatus): BackupPresentation = when (status) {
  BackupStatus.PERMISSION_REQUIRED -> BackupPresentation("需要相册权限", "授予完整权限后才会开始备份", Icons.Outlined.Lock, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
  BackupStatus.SCANNING -> BackupPresentation("正在扫描媒体库", "首次扫描会分批进行，不影响浏览", Icons.Outlined.Refresh, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
  BackupStatus.UPLOADING -> BackupPresentation("正在安全备份", "媒体已在设备端加密", Icons.Outlined.CloudQueue, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
  BackupStatus.WAITING_WIFI -> BackupPresentation("等待 Wi-Fi", "连接 Wi-Fi 后自动继续", Icons.Outlined.CloudQueue, MaterialTheme.mineGColors.warning, MaterialTheme.mineGColors.warningContainer)
  BackupStatus.NETWORK_OFFLINE -> BackupPresentation("网络离线", "网络恢复后从已确认进度继续", Icons.Outlined.CloudOff, MaterialTheme.mineGColors.warning, MaterialTheme.mineGColors.warningContainer)
  BackupStatus.DEVICE_STORAGE_FULL -> BackupPresentation("设备空间不足", "释放设备空间后重试", Icons.Outlined.Storage, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
  BackupStatus.CLOUD_STORAGE_FULL -> BackupPresentation("服务空间不足", "请稍后重试或联系管理员", Icons.Outlined.Storage, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
  BackupStatus.SERVICE_UNAVAILABLE -> BackupPresentation("服务暂时不可用", "本地相册仍可浏览，稍后自动重试", Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
  BackupStatus.COMPLETE -> BackupPresentation("同步完成", "符合条件的媒体均已安全备份", Icons.Outlined.CloudDone, MaterialTheme.mineGColors.success, MaterialTheme.mineGColors.successContainer)
  BackupStatus.PAUSED -> BackupPresentation("自动备份已关闭", "你仍可以浏览本地相册", Icons.Outlined.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
}

@Composable
fun BackupSettingsPage(state: BackupUiState, onBack: () -> Unit, onAutoBackup: (Boolean) -> Unit, onCellular: (Boolean) -> Unit) {
  Scaffold(
    topBar = { DetailTopBar("备份设置", onBack) },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Text("选择备份方式", fontWeight = FontWeight.Bold, fontSize = 20.sp)
      Text("设置会立即生效。关闭自动备份后，你仍然可以浏览本地相册。", color = MaterialTheme.colorScheme.onSurfaceVariant)
      MineGCard(Modifier.fillMaxWidth()) {
        BackupSettingRow("自动备份", "发现新的本地媒体后自动上传", state.autoBackupEnabled, onAutoBackup)
        BackupSettingRow("允许移动网络备份", "无 Wi-Fi 时继续上传，可能产生流量费用", state.allowCellularBackup, onCellular, state.autoBackupEnabled)
      }
      MineGCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.mineGColors.success)
          Text("备份媒体默认只保存在私人空间。只有主动共享的媒体才会出现在家庭相册中。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun BackupSettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
  Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
    Switch(checked, onCheckedChange = onChange, enabled = enabled)
  }
}

@Composable
fun LocalAlbumPage(album: LocalAlbum?, media: List<MediaItem>, onBack: () -> Unit) {
  Scaffold(topBar = { DetailTopBar(album?.name ?: "本地相册", onBack) }) { padding ->
    if (album == null) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("相册不存在") }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.padding(padding).fillMaxSize().padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        items(media.filter { it.id in album.mediaIds }, key = MediaItem::id) { item ->
          MediaPlaceholder(item, Modifier.fillMaxWidth().aspectRatio(1f))
        }
      }
    }
  }
}

@Composable
fun PrivateMediaDetailPage(
  media: MediaItem?,
  actionState: MediaActionState,
  onBack: () -> Unit,
  onDownload: () -> Unit,
  onFinishDownload: (Boolean) -> Unit,
  onShare: () -> Unit,
  onDelete: () -> Unit,
) {
  Scaffold(topBar = { DetailTopBar(media?.title ?: "媒体详情", onBack) }) { padding ->
    if (media == null) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("媒体不存在") }
      return@Scaffold
    }
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      MediaPlaceholder(media, Modifier.fillMaxWidth().height(430.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(media.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
          Text("${media.capturedAt} · ${media.sizeLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.Lock, "已加密", tint = MaterialTheme.mineGColors.success)
      }
      when (actionState) {
        MediaActionState.DOWNLOADING -> MineGCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("正在下载并解密原文件", fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { 0.62f }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(onClick = { onFinishDownload(false) }) { Text("模拟失败") }
              TextButton(onClick = { onFinishDownload(true) }) { Text("完成") }
            }
          }
        }
        MediaActionState.SAVED -> ActionBanner("已成功保存到系统相册", false)
        MediaActionState.SAVE_FAILED -> ActionBanner("保存失败，输入内容和任务进度已保留，可重试。", true)
        MediaActionState.SHARED -> ActionBanner(if (media.isShared) "已共享到家庭相册" else "已取消家庭共享", false)
        MediaActionState.IDLE -> Unit
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailAction(Icons.Outlined.Download, "保存", Modifier.weight(1f), onDownload)
        DetailAction(Icons.Outlined.Share, if (media.isShared) "取消共享" else "共享", Modifier.weight(1f), onShare)
        DetailAction(Icons.Outlined.DeleteOutline, "删除", Modifier.weight(1f), onDelete, destructive = true)
      }
    }
  }
}

@Composable
fun FamilyMediaDetailPage(media: MediaItem?, onBack: () -> Unit) {
  Scaffold(topBar = { DetailTopBar("家庭相册", onBack) }) { padding ->
    if (media == null) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("共享媒体不存在") }
      return@Scaffold
    }
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      MediaPlaceholder(media, Modifier.fillMaxWidth().height(470.dp))
      Text(media.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      MineGCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(42.dp).clip(CircleShape).background(mineGBrandGradient()), contentAlignment = Alignment.Center) {
            Text(media.owner.avatarLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
          }
          Column(Modifier.padding(start = 12.dp)) {
            Text("来自：${media.owner.nickname}", fontWeight = FontWeight.SemiBold)
            Text(media.capturedAt, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      Text("家庭相册为只读空间，不提供下载、导出或二次分享。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun DetailAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit, destructive: Boolean = false) {
  OutlinedButton(onClick = onClick, modifier = modifier.height(54.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
    Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
    Text(" $label", color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun ActionBanner(message: String, error: Boolean) {
  val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
  val foreground = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(container).padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null, tint = foreground)
    Text(message, color = foreground)
  }
}

@Composable
fun DetailTopBar(title: String, onBack: () -> Unit) {
  TopAppBar(
    title = { Text(title, fontWeight = FontWeight.SemiBold) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
  )
}

@Composable
private fun PageLoading() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
