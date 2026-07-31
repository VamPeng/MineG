@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mineg.mobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Collections
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors
import coil3.compose.AsyncImage

@Composable
fun PrivateSpacePage(
  privateState: PrivateSpaceUiState,
  sharedState: FamilyAlbumUiState,
  selectedLibraryTab: LibraryTab,
  selectedTab: MainTab,
  onSelectLibraryTab: (LibraryTab) -> Unit,
  onSelectTab: (MainTab) -> Unit,
  onOpenPrivateMedia: (String) -> Unit,
  onOpenSharedMedia: (String) -> Unit,
) {
  Scaffold(
    modifier = Modifier.testTag("home.private"),
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      LibraryTabSwitcher(selectedLibraryTab, onSelectLibraryTab)
      Box(Modifier.weight(1f)) {
        when (selectedLibraryTab) {
          LibraryTab.PRIVATE -> PrivateSpaceContent(privateState, onOpenPrivateMedia)
          LibraryTab.SHARED -> SharedAlbumContent(sharedState, sharedState.items, onOpenSharedMedia)
        }
      }
    }
  }
}

@Composable
private fun LibraryTabSwitcher(
  selectedTab: LibraryTab,
  onSelectTab: (LibraryTab) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp).clip(RoundedCornerShape(28.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    LibraryTabButton(
      selected = selectedTab == LibraryTab.PRIVATE,
      label = "私人",
      icon = Icons.Outlined.Lock,
      modifier = Modifier.weight(1f),
      onClick = { onSelectTab(LibraryTab.PRIVATE) },
    )
    LibraryTabButton(
      selected = selectedTab == LibraryTab.SHARED,
      label = "共享",
      icon = Icons.Outlined.Share,
      modifier = Modifier.weight(1f),
      onClick = { onSelectTab(LibraryTab.SHARED) },
    )
  }
}

@Composable
private fun PrivateSpaceContent(
  state: PrivateSpaceUiState,
  onOpenMedia: (String) -> Unit,
) {
  when (state.loadState) {
    PageLoadState.LOADING -> Box(Modifier.fillMaxSize()) { PageLoading() }
    PageLoadState.EMPTY -> Box(Modifier.fillMaxSize()) { EmptyState("私人空间还是空的", "完成首次备份后，照片和视频会出现在这里。") }
    PageLoadState.ERROR -> Box(Modifier.fillMaxSize()) { EmptyState("暂时无法加载", state.errorMessage ?: "请检查网络后重试。") }
    PageLoadState.CONTENT -> LazyVerticalGrid(
      columns = GridCells.Fixed(3),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 28.dp),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
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

@Composable
private fun SharedAlbumContent(
  state: FamilyAlbumUiState,
  visibleItems: List<MediaItem>,
  onOpenMedia: (String) -> Unit,
) {
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 18.dp),
  ) {
    if (state.loadState == PageLoadState.LOADING) {
      item {
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      }
    } else if (state.loadState == PageLoadState.ERROR) {
      item { EmptyState("共享相册加载失败", state.errorMessage ?: "请检查网络后重试。") }
    } else if (state.loadState == PageLoadState.EMPTY || visibleItems.isEmpty()) {
      item { EmptyState("暂无共享内容", "在私人媒体详情中主动共享后，内容会出现在这里。") }
    } else {
      visibleItems.groupBy(MediaItem::dateGroup).forEach { (group, media) ->
        item { Text(group, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp)) }
        items(media.chunked(3), key = { row -> row.joinToString { it.id } }) { row ->
          Row(
            Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            row.forEach { item ->
              MediaPlaceholder(
                item,
                Modifier.weight(1f).height(112.dp),
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

@Composable
private fun LibraryTabButton(
  selected: Boolean,
  label: String,
  icon: ImageVector,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  androidx.compose.material3.Surface(
    modifier = modifier.height(46.dp).clickable(onClick = onClick),
    shape = RoundedCornerShape(24.dp),
    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
    shadowElevation = if (selected) 1.dp else 0.dp,
  ) {
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, null, modifier = Modifier.size(18.dp), tint = if (selected) MaterialTheme.mineGColors.success else MaterialTheme.colorScheme.onSurfaceVariant)
      Text("  $label", fontWeight = FontWeight.SemiBold, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
fun SharedByMePage(
  state: FamilyAlbumUiState,
  onBack: () -> Unit,
  onOpenMedia: (String) -> Unit,
) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = { DetailTopBar("我分享的", onBack) },
  ) { padding ->
    Box(Modifier.padding(padding).fillMaxSize()) {
      SharedAlbumContent(state, state.items.filter(MediaItem::sharedByMe), onOpenMedia)
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
  onRefresh: () -> Unit,
) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
    floatingActionButton = {
      if (!state.autoBackupEnabled) FloatingActionButton(onClick = onStartBackup) {
        Icon(Icons.Outlined.CloudQueue, contentDescription = null)
        Text(" 开启备份偏好")
      }
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(bottom = 88.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        MineGPageTitle("本地相册") {
          IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "刷新本地索引") }
          IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "备份设置") }
        }
      }
      item { BackupStatusCard(state, state.albums.firstOrNull()?.coverUrls?.firstOrNull(), Modifier.padding(horizontal = 20.dp)) }
      items(state.albums, key = LocalAlbum::id) { album ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable { onOpenAlbum(album.id) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(album.name, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
            Text("${album.mediaCount} 项", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
          }
          album.coverUrls.take(6).chunked(3).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              row.forEachIndexed { index, url ->
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                  PrototypeCroppedImage(
                    MockVisualAssets.mediaCrops[Math.floorMod(album.id.hashCode() + rowIndex * 3 + index, MockVisualAssets.mediaCrops.size)],
                    Modifier.matchParentSize(),
                  )
                  AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                  if (index == 2 && album.id != "album-screenshots") {
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                      Text("▶", color = Color.White, fontSize = 8.sp)
                    }
                  }
                }
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
private fun BackupStatusCard(state: BackupUiState, heroUrl: String?, modifier: Modifier = Modifier) {
  val presentation = backupPresentation(state.status)
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("同步状态", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
      androidx.compose.material3.Surface(color = presentation.container, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(presentation.icon, null, tint = presentation.foreground, modifier = Modifier.size(16.dp))
          Text(
            if (state.status == BackupStatus.UPLOADING) " 仅 Wi-Fi · 4.8 MB/s" else " ${presentation.title}",
            color = presentation.foreground,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
          )
        }
      }
    }
    Box(
      Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
      heroUrl?.let {
        PrototypeCroppedImage(MockVisualAssets.backupHero, Modifier.matchParentSize())
        AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
      }
      Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.76f)))))
      Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
          Text(presentation.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 23.sp, modifier = Modifier.weight(1f))
          if (state.status in setOf(BackupStatus.UPLOADING, BackupStatus.SCANNING)) {
            Text("${(state.progress * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
          }
        }
        Text(presentation.description, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
      if (state.status in setOf(BackupStatus.UPLOADING, BackupStatus.SCANNING)) {
        LinearProgressIndicator(
          progress = { state.progress },
          modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
          color = if (state.status == BackupStatus.UPLOADING) MaterialTheme.mineGColors.success else MaterialTheme.colorScheme.primary,
          trackColor = Color.White.copy(alpha = 0.35f),
        )
      }
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
  BackupStatus.PERMISSION_REQUIRED -> BackupPresentation("需要相册权限", "授予完整权限后才能建立本地索引", Icons.Outlined.Lock, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
  BackupStatus.SCANNING -> BackupPresentation("正在扫描媒体库", "首次扫描会分批进行，不影响浏览", Icons.Outlined.Refresh, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
  BackupStatus.UPLOADING -> BackupPresentation("正在安全备份", "原始媒体通过安全连接上传", Icons.Outlined.CloudQueue, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
  BackupStatus.WAITING_WIFI -> BackupPresentation("等待 Wi-Fi", "连接 Wi-Fi 后自动继续", Icons.Outlined.CloudQueue, MaterialTheme.mineGColors.warning, MaterialTheme.mineGColors.warningContainer)
  BackupStatus.NETWORK_OFFLINE -> BackupPresentation("网络离线", "网络恢复后从已确认进度继续", Icons.Outlined.CloudOff, MaterialTheme.mineGColors.warning, MaterialTheme.mineGColors.warningContainer)
  BackupStatus.DEVICE_STORAGE_FULL -> BackupPresentation("设备空间不足", "释放设备空间后重试", Icons.Outlined.Storage, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
  BackupStatus.CLOUD_STORAGE_FULL -> BackupPresentation("服务空间不足", "请稍后重试或联系管理员", Icons.Outlined.Storage, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
  BackupStatus.SERVICE_UNAVAILABLE -> BackupPresentation("服务暂时不可用", "本地相册仍可浏览，稍后自动重试", Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
  BackupStatus.INDEXED -> BackupPresentation("本地索引已完成", "当前仅建立本地索引，尚未发生云端备份", Icons.Outlined.Collections, MaterialTheme.mineGColors.success, MaterialTheme.mineGColors.successContainer)
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
      Text("设置会立即保存，供后续备份队列使用；当前阶段不会开始上传。关闭自动备份后，你仍然可以浏览本地相册。", color = MaterialTheme.colorScheme.onSurfaceVariant)
      MineGCard(Modifier.fillMaxWidth()) {
        BackupSettingRow(Icons.Outlined.CloudQueue, "自动备份", "保存偏好；当前阶段不会创建上传任务", state.autoBackupEnabled, onAutoBackup)
        HorizontalDivider(color = MaterialTheme.mineGColors.divider)
        BackupSettingRow(Icons.Outlined.Storage, "允许移动网络备份", "保存后续队列的网络偏好，当前不会触发网络任务", state.allowCellularBackup, onCellular)
      }
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.mineGColors.successContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.mineGColors.success)
        Text("备份媒体默认只保存在你的私人空间。只有主动共享的媒体才会出现在家庭相册中。", color = MaterialTheme.mineGColors.onSuccessContainer, fontSize = 13.sp)
      }
    }
  }
}

@Composable
private fun BackupSettingRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
  Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(
      Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.mineGColors.successContainer),
      contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.mineGColors.success) }
    Spacer(Modifier.size(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
    Switch(checked, onCheckedChange = onChange, enabled = enabled)
  }
}

@Composable
fun LocalAlbumPage(album: LocalAlbum?, state: BackupUiState, onUpload: (String) -> Unit, onBack: () -> Unit) {
  Scaffold(topBar = { DetailTopBar(album?.name ?: "本地相册", onBack) }) { padding ->
    if (album == null) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("相册不存在") }
    } else {
      Column(Modifier.padding(padding).fillMaxSize()) {
        state.uploadMessage?.let { message ->
          Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.primary)
        }
        LazyVerticalGrid(
          columns = GridCells.Fixed(3),
          modifier = Modifier.fillMaxSize().padding(3.dp),
          horizontalArrangement = Arrangement.spacedBy(3.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          items(state.localMedia, key = MediaItem::id) { item ->
            MediaPlaceholder(
              item,
              Modifier.fillMaxWidth().aspectRatio(1f).clickable(
                enabled = state.status != BackupStatus.UPLOADING,
                onClick = { onUpload(item.id) },
              ),
            )
          }
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
  Scaffold(
    topBar = {
      DetailTopBar("Memory", onBack) {
        Icon(Icons.Outlined.Lock, "私人内容", tint = MaterialTheme.mineGColors.success)
      }
    },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    if (media == null) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("媒体不存在") }
      return@Scaffold
    }
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Box(Modifier.fillMaxWidth().height(430.dp)) {
        MediaPlaceholder(media, Modifier.matchParentSize())
        androidx.compose.material3.Surface(
          modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
          shape = CircleShape,
          shadowElevation = 2.dp,
        ) {
          Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.mineGColors.success, modifier = Modifier.size(20.dp))
            Text(" 仅私人空间可见", fontWeight = FontWeight.Medium)
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(media.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
          Text(media.capturedAt.substringBefore(" "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
        }
        Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
          Text(media.owner.avatarLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
      }
      HorizontalDivider(color = MaterialTheme.mineGColors.divider)
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.mineGColors.success, modifier = Modifier.size(28.dp))
        Column {
          Text("私有存储", fontWeight = FontWeight.Medium)
          Text("此照片保存在私有云存储中，仅您和当前获准的家庭成员可以查看。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
      }
      when (actionState) {
        MediaActionState.DOWNLOADING -> MineGCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("正在下载并校验原文件", fontWeight = FontWeight.Bold)
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
fun DetailTopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
  TopAppBar(
    title = { Text(title, fontWeight = FontWeight.SemiBold) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
    actions = { action?.invoke() },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
  )
}

@Composable
private fun PageLoading() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
