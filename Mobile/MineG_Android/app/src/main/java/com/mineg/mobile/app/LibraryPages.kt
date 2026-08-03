@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mineg.mobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun PrivateSpacePage(
    privateState: PrivateSpaceUiState,
    sharedState: FamilyAlbumUiState,
    selectedLibraryTab: LibraryTab,
    selectedTab: MainTab,
    onSelectLibraryTab: (LibraryTab) -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onOpenPrivateMedia: (String) -> Unit,
    onRefreshPrivateMedia: () -> Unit,
    onLoadMorePrivateMedia: () -> Unit,
    onLoadPrivateMediaPreview: (String) -> Unit,
    onOpenSharedMedia: (String) -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("home.private"),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
    ) { padding ->
        Column(Modifier
            .padding(padding)
            .fillMaxSize()) {
            LibraryTabSwitcher(selectedLibraryTab, onSelectLibraryTab)
            Box(Modifier.weight(1f)) {
                when (selectedLibraryTab) {
                    LibraryTab.PRIVATE -> PrivateSpaceContent(
                        privateState,
                        onOpenPrivateMedia,
                        onRefreshPrivateMedia,
                        onLoadMorePrivateMedia,
                        onLoadPrivateMediaPreview,
                    )
                    LibraryTab.SHARED -> SharedAlbumContent(
                        sharedState,
                        sharedState.items,
                        onOpenSharedMedia
                    )
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
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        LibraryTabButton(
            selected = selectedTab == LibraryTab.PRIVATE,
            label = "私人",
            modifier = Modifier.width(56.dp),
            onClick = { onSelectTab(LibraryTab.PRIVATE) },
        )
        LibraryTabButton(
            selected = selectedTab == LibraryTab.SHARED,
            label = "共享",
            modifier = Modifier.width(56.dp),
            onClick = { onSelectTab(LibraryTab.SHARED) },
        )
    }
}

@Composable
private fun PrivateSpaceContent(
    state: PrivateSpaceUiState,
    onOpenMedia: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLoadPreview: (String) -> Unit,
) {
    when (state.loadState) {
        PageLoadState.LOADING -> Box(Modifier.fillMaxSize()) { PageLoading() }
        PageLoadState.EMPTY -> Box(Modifier.fillMaxSize()) {
            EmptyState(
                "私人空间还是空的",
                "完成首次备份后，照片和视频会出现在这里。"
            )
        }

        PageLoadState.ERROR -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState("暂时无法加载", state.errorMessage ?: "请检查网络后重试。")
            TextButton(onClick = onRefresh, modifier = Modifier.testTag("private-media.grid.refresh")) {
                Text("重新加载")
            }
        }

        PageLoadState.CONTENT -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().testTag("private-media.grid"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.items, key = MediaItem::id) { media ->
                PrivateMediaThumbnail(
                    media = media,
                    previewLoading = media.id in state.previewLoadingIds,
                    previewUnavailable = media.id in state.previewUnavailableIds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    onClick = { onOpenMedia(media.id) },
                    onLoadPreview = { onLoadPreview(media.id) },
                )
            }
            if (!state.fullyLoaded) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onClick = onLoadMore,
                        enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("private-media.grid.load-more"),
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("加载更多")
                        }
                    }
                }
            }
            if (!state.loadingMore && state.errorMessage != null && !state.fullyLoaded) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth().testTag("private-media.grid.load-more"),
                    ) {
                        Text("加载失败，重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateMediaThumbnail(
    media: MediaItem,
    previewLoading: Boolean,
    previewUnavailable: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLoadPreview: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    LaunchedEffect(media.id, media.imageUrl, previewLoading, previewUnavailable) {
        if (media.imageUrl == null && media.canLoadRemotePreview && !previewLoading && !previewUnavailable) {
            onLoadPreview()
        }
    }
    Box(
        modifier = modifier
            .border(1.dp, Color(0xB8C7D6E0), shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        MediaArtwork(media, Modifier.fillMaxSize())
        if (previewLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        when (media.kind) {
            MediaKind.VIDEO -> {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                    color = Color.Black.copy(alpha = 0.40f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = media.duration ?: "视频",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = "▶",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White.copy(alpha = 0.80f),
                    fontSize = 20.sp,
                )
            }

            MediaKind.LIVE_PHOTO, MediaKind.GIF -> {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    color = Color.Black.copy(alpha = 0.30f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                ) {
                    Text(
                        text = if (media.kind == MediaKind.LIVE_PHOTO) "Live" else "GIF",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            MediaKind.PHOTO -> Unit
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
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        if (state.loadState == PageLoadState.LOADING) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        } else if (state.loadState == PageLoadState.ERROR) {
            item { EmptyState("共享相册加载失败", state.errorMessage ?: "请检查网络后重试。") }
        } else if (state.loadState == PageLoadState.EMPTY || visibleItems.isEmpty()) {
            item { EmptyState("暂无共享内容", "在私人媒体详情中主动共享后，内容会出现在这里。") }
        } else {
            visibleItems.groupBy(MediaItem::dateGroup).entries.forEachIndexed { groupIndex, (group, media) ->
                item(key = "family-date-$group") {
                    Text(
                        text = group,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            top = if (groupIndex == 0) 12.dp else 16.dp,
                            bottom = 12.dp,
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.60f),
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(media.chunked(3), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        row.forEach { item ->
                            FamilyMediaTile(
                                media = item,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
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
private fun FamilyMediaTile(
    media: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        MediaArtwork(media, Modifier.fillMaxSize())
        when (media.kind) {
            MediaKind.LIVE_PHOTO -> FamilyMediaTag(
                "LIVE",
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            )

            MediaKind.GIF -> FamilyMediaTag("GIF", Modifier
                .align(Alignment.TopStart)
                .padding(4.dp))
            MediaKind.VIDEO -> FamilyMediaTag(
                label = "▶  ${media.duration ?: "视频"}",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )

            MediaKind.PHOTO -> Unit
        }
        FamilyMediaOwnerTag(media, Modifier
            .align(Alignment.BottomStart)
            .padding(4.dp))
    }
}

@Composable
private fun FamilyMediaTag(label: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        color = Color(0xB3FFF8F4),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FamilyMediaOwnerTag(media: MediaItem, modifier: Modifier = Modifier) {
    val ownerLabel = if (media.sharedByMe) "我" else "TA"
    androidx.compose.material3.Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.40f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (media.owner.avatarUrl != null) {
                AsyncImage(
                    model = media.owner.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color.White, CircleShape),
                )
            } else {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color.White, CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        media.owner.avatarLabel,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Text(
                text = ownerLabel,
                modifier = Modifier.padding(end = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LibraryTabButton(
    selected: Boolean,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        topBar = { SharedByMeTopBar(onBack) },
    ) { padding ->
        Box(Modifier
            .padding(padding)
            .fillMaxSize()) {
            SharedAlbumContent(state, state.items.filter(MediaItem::sharedByMe), onOpenMedia)
        }
    }
}

@Composable
private fun SharedByMeTopBar(onBack: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "返回个人中心" },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
            }
            Text(
                text = "我分享的",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun BackupPage(
    state: BackupUiState,
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onStartBackup: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
    ) { padding ->
        Box(Modifier
            .padding(padding)
            .fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 20.dp,
                    end = 20.dp,
                    bottom = if (state.autoBackupEnabled) 20.dp else 82.dp
                ),
            ) {
                item {
                    BackupStatusCard(
                        state = state,
                        onRefresh = onRefresh,
                        onSettings = onSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 28.dp),
                    )
                }
                items(
                    state.albums.chunked(2),
                    key = { row -> row.joinToString(separator = "|") { it.id } }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { album ->
                            BackupAlbumCard(
                                album = album,
                                modifier = Modifier.weight(1f),
                                onOpenAlbum = onOpenAlbum,
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (!state.autoBackupEnabled) {
                StartBackupButton(
                    onClick = onStartBackup,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}

private data class BackupPanelPresentation(
    val title: String,
    val description: String,
    val progress: Float? = null,
    val imageUrl: String? = null,
)

@Composable
private fun BackupStatusCard(
    state: BackupUiState,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = backupPanelPresentation(state.status)
    if (presentation.imageUrl == null) {
        BackupTextStatusCard(presentation, onRefresh, onSettings, modifier)
        return
    }
    Box(
        modifier = modifier
            .height(218.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AsyncImage(
            model = presentation.imageUrl,
            contentDescription = "当前正在同步的本地媒体",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(Modifier
            .matchParentSize()
            .background(Color(0xFF1B2730).copy(alpha = 0.62f)))
        BackupStatusActions(
            onRefresh = onRefresh,
            onSettings = onSettings,
            iconColor = Color.White,
            containerColor = Color.Black.copy(alpha = 0.28f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = presentation.title,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                presentation.progress?.let { progress ->
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = presentation.description,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            presentation.progress?.let { progress ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.34f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupTextStatusCard(
    presentation: BackupPanelPresentation,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.height(190.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            BackupStatusActions(
                onRefresh = onRefresh,
                onSettings = onSettings,
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = presentation.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = presentation.description,
                    modifier = Modifier.padding(top = 7.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BackupStatusActions(
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    iconColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BackupStatusAction(
            Icons.Outlined.Refresh,
            "刷新本地相册",
            onRefresh,
            iconColor
        )
        BackupStatusAction(
            Icons.Outlined.Settings,
            "打开备份设置",
            onSettings,
            iconColor
        )
    }
}

@Composable
private fun BackupStatusAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconColor: Color,
) {
    Box(
        modifier = Modifier.size(30.dp).padding(end = 12.dp),
    ) {
        IconButton(
            onClick = onClick
        ) {
            Icon(icon, contentDescription, modifier = Modifier.size(21.dp), tint = iconColor)
        }
    }
}

private fun backupPanelPresentation(status: BackupStatus): BackupPanelPresentation = when (status) {
    BackupStatus.UPLOADING -> BackupPanelPresentation(
        "正在上传原始媒体",
        "正在上传所选单条媒体，不做应用层加密。"
    )

    BackupStatus.SCANNING -> BackupPanelPresentation(
        "正在更新本地索引",
        "正在读取手机相册；本次不会上传媒体。"
    )

    BackupStatus.WAITING_WIFI -> BackupPanelPresentation(
        "等待网络条件",
        "完整自动备份队列将在后续阶段提供。"
    )

    BackupStatus.NETWORK_OFFLINE -> BackupPanelPresentation(
        "网络不可用",
        "本地相册仍可浏览；请在网络恢复后手动重试上传。"
    )

    BackupStatus.SERVICE_UNAVAILABLE -> BackupPanelPresentation(
        "暂时无法连接服务",
        "本地相册仍可浏览；请稍后手动重试。"
    )

    BackupStatus.COMPLETE -> BackupPanelPresentation("媒体上传完成", "所选单条媒体已完成上传。")
    BackupStatus.INDEXED -> BackupPanelPresentation(
        "本地索引已更新",
        "可浏览已扫描的本地媒体；尚未自动上传。"
    )

    BackupStatus.PAUSED -> BackupPanelPresentation("自动备份偏好未开启", "当前不会自动上传媒体。")
    BackupStatus.PERMISSION_REQUIRED -> BackupPanelPresentation(
        "需要相册权限",
        "开启完整相册权限后才能建立本地索引。"
    )

    BackupStatus.DEVICE_STORAGE_FULL -> BackupPanelPresentation(
        "设备空间不足",
        "释放设备空间后重试"
    )

    BackupStatus.CLOUD_STORAGE_FULL -> BackupPanelPresentation(
        "服务空间不足",
        "请稍后重试或联系管理员"
    )
}

@Composable
private fun BackupAlbumCard(
    album: LocalAlbum,
    modifier: Modifier = Modifier,
    onOpenAlbum: (String) -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onOpenAlbum(album.id) }
            .semantics { contentDescription = "打开相册${album.name}" },
    ) {
        album.coverUrls.firstOrNull()?.let { coverUrl ->
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        androidx.compose.material3.Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            color = Color.Black.copy(alpha = 0.48f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = album.name,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StartBackupButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .width(178.dp)
            .height(50.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "开启自动备份偏好",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun BackupSettingsPage(
    state: BackupUiState,
    onBack: () -> Unit,
    onAutoBackup: (Boolean) -> Unit,
    onCellular: (Boolean) -> Unit
) {
    Scaffold(
        topBar = { BackupSettingsTopBar(onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier
            .padding(padding)
            .padding(20.dp)) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)),
            ) {
                Column {
                    BackupSettingRow(
                        "自动备份",
                        "当前仅保存偏好，不会自动上传",
                        state.autoBackupEnabled,
                        onAutoBackup
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f))
                    BackupSettingRow(
                        "允许移动网络备份",
                        "当前仅保存偏好，不会使用移动网络上传",
                        state.allowCellularBackup,
                        onCellular,
                        state.autoBackupEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(86.dp)
            .padding(16.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
        BackupSwitch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun BackupSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .width(50.dp)
            .height(30.dp)
            .clip(CircleShape)
            .background(if (checked) MaterialTheme.colorScheme.primary else Color(0xFFC8C9C5))
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .semantics { contentDescription = if (checked) "已开启" else "已关闭" },
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun BackupSettingsTopBar(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)),
    ) {
        Box(Modifier
            .fillMaxWidth()
            .height(63.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "返回本地相册" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "备份设置",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.60f),
            thickness = 1.dp
        )
    }
}

@Composable
fun LocalAlbumPage(
    album: LocalAlbum?,
    state: BackupUiState,
    onUpload: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(topBar = { DetailTopBar(album?.name ?: "本地相册", onBack) }) { padding ->
        if (album == null) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("相册不存在") }
        } else {
            Column(Modifier
                .padding(padding)
                .fillMaxSize()) {
                state.albumTotalCount?.let { totalCount ->
                    Text(
                        "本相册已完成 ${state.albumCompletedCount ?: 0} / $totalCount",
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(state.localMedia, key = MediaItem::id) { item ->
                        MediaPlaceholder(
                            item,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable(
                                    onClick = { onUpload(item.id) },
                                ),
                            bottomStartSyncState = state.localMediaSyncStates[item.id]
                                ?: LocalMediaSyncState.UNSYNCED,
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
    showDeleteConfirmation: Boolean,
    onLoadPreview: () -> Unit,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRetrySave: () -> Unit,
    onDismissAction: () -> Unit,
) {
    LaunchedEffect(media?.id, media?.imageUrl, media?.canLoadRemotePreview) {
        if (media != null && media.imageUrl == null && media.canLoadRemotePreview) onLoadPreview()
    }
    LaunchedEffect(actionState) {
        if (actionState in setOf(MediaActionState.SAVED, MediaActionState.SHARED)) {
            delay(3_000)
            onDismissAction()
        }
    }
    Scaffold(
        modifier = Modifier.testTag("private-media.detail"),
        topBar = { PrivateMediaTopBar(onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PrivateMediaActionBar(onDownload, onDelete)
        },
    ) { padding ->
        if (media == null) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("媒体不存在") }
            return@Scaffold
        }
        Box(Modifier
            .padding(padding)
            .fillMaxSize()) {
            PrivateMediaCanvas(
                media = media,
                actionState = actionState,
                showDeleteConfirmation = showDeleteConfirmation,
            )
            when (actionState) {
                MediaActionState.SHARED -> ShareSuccessToast()
                MediaActionState.SAVED -> SavedMediaToast()
                MediaActionState.SAVE_FAILED -> SaveFailedToast(onRetrySave, onDismissAction)
                else -> Unit
            }
        }
    }
}

@Composable
private fun PrivateMediaTopBar(onBack: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "返回" },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
            }
            Spacer(Modifier.size(40.dp))
        }
    }
}

@Composable
private fun PrivateMediaCanvas(
    media: MediaItem,
    actionState: MediaActionState,
    showDeleteConfirmation: Boolean,
) {
    val isWideSharedMedia = actionState == MediaActionState.SHARED
    val aspectRatio = when {
        isWideSharedMedia || actionState in setOf(
            MediaActionState.SAVED,
            MediaActionState.SAVE_FAILED
        ) || showDeleteConfirmation -> 4f / 5f

        else -> 3f / 4f
    }
    val topPadding = when {
        isWideSharedMedia -> 0.dp
        actionState == MediaActionState.SAVE_FAILED -> 12.dp
        showDeleteConfirmation -> 16.dp
        else -> 24.dp
    }
    val horizontalPadding = if (isWideSharedMedia) 0.dp else 20.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, top = topPadding, end = horizontalPadding)
            .aspectRatio(aspectRatio)
            .then(if (isWideSharedMedia) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
            .background(
                if (actionState == MediaActionState.DOWNLOADING || actionState == MediaActionState.SAVE_FAILED) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            ),
    ) {
        MediaArtwork(media, Modifier.matchParentSize())
        if (actionState == MediaActionState.DOWNLOADING) DownloadProgressOverlay()
    }
}

@Composable
private fun MediaArtwork(media: MediaItem, modifier: Modifier = Modifier) {
    val placeholderColor = when (media.kind) {
        MediaKind.VIDEO -> Color(0xFF485C70)
        MediaKind.GIF -> Color(0xFF526273)
        MediaKind.LIVE_PHOTO -> Color(0xFF465C67)
        MediaKind.PHOTO -> Color(0xFF627485)
    }
    Box(modifier.background(placeholderColor)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = when (media.kind) {
                    MediaKind.VIDEO -> "视频"
                    MediaKind.GIF -> "GIF"
                    MediaKind.LIVE_PHOTO -> "动态照片"
                    MediaKind.PHOTO -> "照片"
                },
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
            if (!media.canLoadRemotePreview && media.imageUrl == null) {
                Text(
                    text = "暂无预览",
                    modifier = Modifier.padding(top = 3.dp),
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 10.sp,
                )
            }
        }
        media.imageUrl?.let { imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun DownloadProgressOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.60f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.size(96.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    trackColor = Color.White.copy(alpha = 0.20f),
                    strokeWidth = 4.dp,
                    strokeCap = StrokeCap.Round,
                )
                Text("0%", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "正在下载原文件...",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PrivateMediaActionBar(
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailAction("下载", onDownload, Modifier.testTag("private-media.detail.save"))
            DetailAction("删除", onDelete, Modifier.testTag("private-media.detail.delete"))
        }
    }
}

@Composable
private fun BoxScope.ShareSuccessToast() {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 40.dp),
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
    ) {
        Text(
            text = "共享成功",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BoxScope.SavedMediaToast() {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 20.dp, end = 20.dp, bottom = 48.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = "已成功保存到系统相册",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BoxScope.SaveFailedToast(onRetry: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 20.dp, end = 20.dp, bottom = 48.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxSize()
                    .width(4.dp)
                    .background(MaterialTheme.colorScheme.error),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "保存失败",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "网络异常，保存失败",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ErrorToastButton(
                            "重试",
                            onRetry,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary
                        )
                        ErrorToastButton(
                            label = "取消",
                            onClick = onDismiss,
                            container = Color.Transparent,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                            border = BorderStroke(1.dp, Color(0xFFC7D6E0)),
                        )
                    }
                }
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        "关闭",
                        color = Color(0xFFC7D6E0),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorToastButton(
    label: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    border: BorderStroke? = null,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = container,
        shape = RoundedCornerShape(6.dp),
        border = border,
    ) {
        Box(Modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(label, color = content, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun FamilyMediaDetailPage(media: MediaItem?, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F2ED)),
    ) {
        if (media == null) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("共享媒体不存在") }
            return@Box
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 20.dp, bottom = 80.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            FamilyDetailArtwork(media)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
                .clickable(onClick = onBack)
                .semantics { contentDescription = "返回共享相册" },
            contentAlignment = Alignment.Center,
        ) {
            Text("←", color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp)
        }
        androidx.compose.material3.Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(80.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "来自：",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (media.sharedByMe) "我" else "TA",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = media.capturedAt,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.74f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun FamilyDetailArtwork(media: MediaItem) {
    val imageUrl = media.detailImageUrl ?: media.imageUrl
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = media.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DetailAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DetailTopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    "返回"
                )
            }
        },
        actions = { action?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun PageLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
