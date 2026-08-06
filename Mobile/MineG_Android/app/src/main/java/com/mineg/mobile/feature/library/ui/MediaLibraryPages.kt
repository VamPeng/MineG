@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

/** Local, private and shared media browsing/detail Compose surfaces. */
package com.mineg.mobile.feature.library.ui

import com.mineg.mobile.presentation.BackupStatus
import com.mineg.mobile.presentation.BackupUiState
import com.mineg.mobile.presentation.DeletedMedia
import com.mineg.mobile.presentation.LibraryTab
import com.mineg.mobile.presentation.LocalAlbum
import com.mineg.mobile.presentation.LocalMediaSyncState
import com.mineg.mobile.presentation.MainTab
import com.mineg.mobile.presentation.MediaActionState
import com.mineg.mobile.presentation.MediaItem
import com.mineg.mobile.presentation.MediaKind
import com.mineg.mobile.presentation.MediaListScrollPosition
import com.mineg.mobile.presentation.PageLoadState
import com.mineg.mobile.presentation.PrivateSpaceUiState
import com.mineg.mobile.presentation.SharedAlbumUiState
import com.mineg.mobile.platform.logging.MediaLoadLog
import com.mineg.mobile.ui.component.EmptyState
import com.mineg.mobile.ui.component.DetailTopBar
import com.mineg.mobile.ui.component.MediaPlaceholder
import com.mineg.mobile.ui.component.MineGBottomBar
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.mineg.mobile.platform.PrivateThumbnailCacheKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** Renders private/shared tabs, media paging and the root media navigation shell. */
@Composable
fun PrivateSpacePage(
    privateState: PrivateSpaceUiState,
    privatePreviewSources: Map<String, String>,
    sharedState: SharedAlbumUiState,
    selectedLibraryTab: LibraryTab,
    privateMediaListScrollPosition: MediaListScrollPosition,
    sharedMediaListScrollPosition: MediaListScrollPosition,
    selectedTab: MainTab,
    onSelectLibraryTab: (LibraryTab) -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onOpenPrivateMedia: (String) -> Unit,
    onRefreshPrivateMedia: () -> Unit,
    onLoadMorePrivateMedia: () -> Unit,
    onVisiblePrivateMediaChanged: (List<String>) -> Unit,
    onRetryPrivateMediaPreview: (String) -> Unit,
    onPrivateMediaListScrollPositionChanged: (Int, Int) -> Unit,
    onSharedMediaListScrollPositionChanged: (Int, Int) -> Unit,
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
                        privatePreviewSources,
                        privateMediaListScrollPosition,
                        onOpenPrivateMedia,
                        onRefreshPrivateMedia,
                        onLoadMorePrivateMedia,
                        onVisiblePrivateMediaChanged,
                        onRetryPrivateMediaPreview,
                        onPrivateMediaListScrollPositionChanged,
                    )
                    LibraryTab.SHARED -> SharedAlbumContent(
                        state = sharedState,
                        visibleItems = sharedState.items,
                        scrollPosition = sharedMediaListScrollPosition,
                        onOpenMedia = onOpenSharedMedia,
                        onScrollPositionChanged = onSharedMediaListScrollPositionChanged,
                    )
                }
            }
        }
    }
}

/** Renders the private/shared library tab selector. */
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

/** Renders private-media loading, error, empty and paged-grid states. */
@Composable
private fun PrivateSpaceContent(
    state: PrivateSpaceUiState,
    previewSources: Map<String, String>,
    scrollPosition: MediaListScrollPosition,
    onOpenMedia: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onVisibleMediaChanged: (List<String>) -> Unit,
    onRetryPreview: (String) -> Unit,
    onScrollPositionChanged: (Int, Int) -> Unit,
) {
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = scrollPosition.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = scrollPosition.firstVisibleItemScrollOffset,
    )
    val currentOnVisibleMediaChanged by rememberUpdatedState(onVisibleMediaChanged)
    val currentOnScrollPositionChanged by rememberUpdatedState(onScrollPositionChanged)
    val mediaIds = state.items.map(MediaItem::id)
    LaunchedEffect(gridState, mediaIds) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.map { it.index }
        }
            .distinctUntilChanged()
            .collectLatest { visibleIndices ->
                // 暂时停用“等待滚动停止后再加载”，可见窗口变化时立即调度预览。
                onVisibleMediaChanged(privateMediaPreviewWindow(mediaIds, visibleIndices))
            }
    }
    DisposableEffect(gridState) {
        onDispose {
            currentOnVisibleMediaChanged(emptyList())
            currentOnScrollPositionChanged(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }
    }
    LaunchedEffect(gridState, state.items.size, state.fullyLoaded) {
        if (state.fullyLoaded || state.loadState != PageLoadState.CONTENT) return@LaunchedEffect
        val footerVisible = snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.any { it.key == PRIVATE_MEDIA_PAGING_FOOTER_KEY }
        }.first { it }
        if (shouldAutoLoadMedia(footerVisible, state.loadingMore, state.fullyLoaded)) {
            onLoadMore()
        }
    }
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
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("private-media.grid"),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items, key = MediaItem::id, contentType = { "private-media" }) { media ->
                PrivateMediaThumbnail(
                    media = previewSources[media.id]?.let { media.copy(imageUrl = it) } ?: media,
                    previewLoading = media.id in state.previewLoadingIds,
                    previewUnavailable = media.id in state.previewUnavailableIds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    onClick = { onOpenMedia(media.id) },
                    onRetryPreview = { onRetryPreview(media.id) },
                )
            }
            item(key = PRIVATE_MEDIA_PAGING_FOOTER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = onLoadMore,
                    enabled = !state.loadingMore && !state.fullyLoaded,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("private-media.grid.load-more"),
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(mediaPagingLabel(state.items.size, state.fullyLoaded))
                }
            }
            if (!state.loadingMore && state.errorMessage != null && !state.fullyLoaded) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        state.errorMessage,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Builds accessible paging status copy for a media grid. */
internal fun mediaPagingLabel(itemCount: Int, fullyLoaded: Boolean): String =
    if (fullyLoaded) "已加载全部媒体" else "加载更多（已加载$itemCount）"

/** Decides whether the visible grid position should request another page. */
internal fun shouldAutoLoadMedia(
    footerVisible: Boolean,
    loadingMore: Boolean,
    fullyLoaded: Boolean,
): Boolean = footerVisible && !loadingMore && !fullyLoaded

/** Selects a bounded, scroll-direction-aware preview prefetch window. */
internal fun privateMediaPreviewWindow(
    mediaIds: List<String>,
    visibleIndices: List<Int>,
    columns: Int = 3,
): List<String> {
    val valid = visibleIndices.filter { it in mediaIds.indices }
    if (valid.isEmpty()) return emptyList()
    val first = valid.min()
    val last = valid.max()
    val visibleCount = (last - first + 1).coerceAtLeast(columns)
    val aheadCount = visibleCount.coerceAtMost(columns * 2)
    val start = (first - columns).coerceAtLeast(0)
    val endExclusive = (last + 1 + aheadCount).coerceAtMost(mediaIds.size)
    return mediaIds.subList(start, endExclusive)
}

/** Renders one private-media thumbnail including retry and availability state. */
@Composable
private fun PrivateMediaThumbnail(
    media: MediaItem,
    previewLoading: Boolean,
    previewUnavailable: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRetryPreview: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
//            .border(1.dp, Color(0xB8C7D6E0), shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        PrivateMediaArtwork(
            media = media,
            previewLoading = previewLoading,
            previewUnavailable = previewUnavailable,
            onRetry = onRetryPreview,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal enum class MediaItemImageState { LOADING, FAILED, SUCCESS }

internal enum class MediaItemVisualState { LOADING, FAILED, SUCCESS, UNAVAILABLE }

/** Derives visual placeholder/retry state from media and preview source availability. */
internal fun mediaItemVisualState(
    imageUrl: String?,
    canLoadRemotePreview: Boolean,
    previewLoading: Boolean,
    previewUnavailable: Boolean,
    imageState: MediaItemImageState,
): MediaItemVisualState = when {
    previewUnavailable -> MediaItemVisualState.FAILED
    previewLoading -> MediaItemVisualState.LOADING
    imageUrl != null -> when (imageState) {
        MediaItemImageState.LOADING -> MediaItemVisualState.LOADING
        MediaItemImageState.FAILED -> MediaItemVisualState.FAILED
        MediaItemImageState.SUCCESS -> MediaItemVisualState.SUCCESS
    }
    canLoadRemotePreview -> MediaItemVisualState.LOADING
    else -> MediaItemVisualState.UNAVAILABLE
}

/** Renders private-media artwork or its deterministic fallback state. */
@Composable
private fun PrivateMediaArtwork(
    media: MediaItem,
    previewLoading: Boolean,
    previewUnavailable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var imageState by remember(media.id, media.imageUrl) {
        mutableStateOf(MediaItemImageState.LOADING)
    }
    val imageModel = remember(context, media.owner.id, media.id, media.imageUrl) {
        media.imageUrl?.let { imageUrl ->
            ImageRequest.Builder(context)
                .data(imageUrl)
                .memoryCacheKey(PrivateThumbnailCacheKeys.memoryKey(media.owner.id, media.id))
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }
    }
    val visualState = mediaItemVisualState(
        imageUrl = media.imageUrl,
        canLoadRemotePreview = media.canLoadRemotePreview,
        previewLoading = previewLoading,
        previewUnavailable = previewUnavailable,
        imageState = imageState,
    )
    val background = when (visualState) {
        MediaItemVisualState.LOADING -> MaterialTheme.colorScheme.surfaceContainerLow
        MediaItemVisualState.FAILED -> MaterialTheme.colorScheme.errorContainer
        MediaItemVisualState.SUCCESS -> MaterialTheme.colorScheme.surface
        MediaItemVisualState.UNAVAILABLE -> MaterialTheme.colorScheme.surfaceContainer
    }
    Box(
        modifier = modifier
            .background(background)
            .testTag("private-media.item.state.${visualState.name.lowercase()}")
            .semantics {
                stateDescription = when (visualState) {
                    MediaItemVisualState.LOADING -> "媒体加载中"
                    MediaItemVisualState.FAILED -> "媒体加载失败"
                    MediaItemVisualState.SUCCESS -> "媒体加载成功"
                    MediaItemVisualState.UNAVAILABLE -> "媒体暂无预览"
                }
            },
    ) {
        imageModel?.let { request ->
            AsyncImage(
                model = request,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onLoading = {
                    imageState = MediaItemImageState.LOADING
                    MediaLoadLog.trace(
                        "artwork loading media=${MediaLoadLog.mediaRef(media.id)} kind=${media.kind}",
                    )
                },
                onSuccess = {
                    imageState = MediaItemImageState.SUCCESS
                    MediaLoadLog.trace(
                        "artwork ready media=${MediaLoadLog.mediaRef(media.id)} kind=${media.kind}",
                    )
                },
                onError = {
                    imageState = MediaItemImageState.FAILED
                    MediaLoadLog.warning(
                        "artwork failed media=${MediaLoadLog.mediaRef(media.id)} kind=${media.kind}",
                    )
                },
            )
        }
        when (visualState) {
            MediaItemVisualState.LOADING -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "加载中",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            MediaItemVisualState.FAILED -> Column(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onRetry)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    "加载失败",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "轻触重试",
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                )
            }

            MediaItemVisualState.UNAVAILABLE -> Text(
                text = "暂无预览",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )

            MediaItemVisualState.SUCCESS -> Unit
        }
        if (visualState == MediaItemVisualState.SUCCESS) {
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
}

/** Renders shared-feed loading, error, empty and media-grid states. */
@Composable
private fun SharedAlbumContent(
    state: SharedAlbumUiState,
    visibleItems: List<MediaItem>,
    onOpenMedia: (String) -> Unit,
    scrollPosition: MediaListScrollPosition = MediaListScrollPosition(),
    onScrollPositionChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = scrollPosition.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = scrollPosition.firstVisibleItemScrollOffset,
    )
    var scrollPositionRestored by remember(scrollPosition) { mutableStateOf(false) }
    val currentOnScrollPositionChanged by rememberUpdatedState(onScrollPositionChanged)
    LaunchedEffect(state.loadState, visibleItems, scrollPosition, scrollPositionRestored) {
        if (!scrollPositionRestored && state.loadState == PageLoadState.CONTENT && visibleItems.isNotEmpty()) {
            val itemCount = visibleItems
                .groupBy(MediaItem::dateGroup)
                .values
                .sumOf { media -> 1 + (media.size + 2) / 3 }
            val targetIndex = scrollPosition.firstVisibleItemIndex.coerceAtMost(itemCount - 1)
            listState.scrollToItem(
                index = targetIndex,
                scrollOffset = if (targetIndex == scrollPosition.firstVisibleItemIndex) {
                    scrollPosition.firstVisibleItemScrollOffset
                } else {
                    0
                },
            )
            scrollPositionRestored = true
        }
    }
    DisposableEffect(listState) {
        onDispose {
            currentOnScrollPositionChanged(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
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
                item(key = "shared-date-$group") {
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
                            SharedMediaTile(
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

/** Renders one shared-media tile with owner and media metadata. */
@Composable
private fun SharedMediaTile(
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
            MediaKind.LIVE_PHOTO -> SharedMediaTag(
                "LIVE",
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            )

            MediaKind.GIF -> SharedMediaTag("GIF", Modifier
                .align(Alignment.TopStart)
                .padding(4.dp))
            MediaKind.VIDEO -> SharedMediaTag(
                label = "▶  ${media.duration ?: "视频"}",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )

            MediaKind.PHOTO -> Unit
        }
        SharedMediaOwnerTag(media, Modifier
            .align(Alignment.BottomStart)
            .padding(4.dp))
    }
}

/** Renders a compact shared-media metadata tag. */
@Composable
private fun SharedMediaTag(label: String, modifier: Modifier = Modifier) {
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

/** Renders the owner badge for media shared by another account. */
@Composable
private fun SharedMediaOwnerTag(media: MediaItem, modifier: Modifier = Modifier) {
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

/** Renders one tab action with selected-state styling. */
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

/** Renders the current account's explicitly shared media. */
@Composable
fun SharedByMePage(
    state: SharedAlbumUiState,
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

/** Renders shared-by-me navigation and title. */
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

/** Renders aggregate backup status, album progress and primary actions. */
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

/** Renders the active backup state, progress and scheduler messaging. */
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

/** Renders a non-progress backup status card. */
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

/** Renders actions available for the current backup status. */
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

/** Renders one backup status action. */
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

/** Maps backup state to title, description and visual treatment. */
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

/** Renders one album's backup counts and navigation action. */
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

/** Renders the primary action that enables automatic backup. */
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

/** Renders automatic and cellular backup preferences. */
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

/** Renders one labeled backup preference with supporting copy. */
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

/** Renders a themed backup preference switch. */
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

/** Renders backup-settings navigation and title. */
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

/** Renders a local album, paged media and per-item backup state. */
@Composable
fun LocalAlbumPage(
    album: LocalAlbum?,
    state: BackupUiState,
    onUpload: (String) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, album?.id, state.localMedia.size, state.localMediaFullyLoaded) {
        if (album == null || state.localMediaInitialLoading || state.localMediaFullyLoaded ||
            state.localMediaErrorMessage != null) return@LaunchedEffect
        val footerVisible = snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.any { it.key == LOCAL_MEDIA_PAGING_FOOTER_KEY }
        }.first { it }
        if (shouldAutoLoadMedia(footerVisible, state.localMediaLoadingMore, state.localMediaFullyLoaded)) {
            onLoadMore()
        }
    }
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
                when {
                    state.localMediaInitialLoading && state.localMedia.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    state.localMedia.isEmpty() && state.localMediaErrorMessage != null -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        EmptyState("本地媒体加载失败", state.localMediaErrorMessage)
                        TextButton(onClick = onLoadMore) { Text("重新加载") }
                    }

                    state.localMedia.isEmpty() && state.localMediaFullyLoaded -> Box(
                        Modifier.fillMaxSize(),
                    ) { EmptyState("相册为空", "这个本地相册中暂时没有媒体。") }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
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
                        item(key = LOCAL_MEDIA_PAGING_FOOTER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(
                                onClick = onLoadMore,
                                enabled = !state.localMediaInitialLoading &&
                                    !state.localMediaLoadingMore && !state.localMediaFullyLoaded,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .testTag("local-media.grid.load-more"),
                            ) {
                                if (state.localMediaLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(mediaPagingLabel(state.localMedia.size, state.localMediaFullyLoaded))
                            }
                        }
                        if (!state.localMediaLoadingMore && state.localMediaErrorMessage != null &&
                            !state.localMediaFullyLoaded) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    state.localMediaErrorMessage,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Renders private-media detail, verified artwork and owner actions. */
@Composable
fun PrivateMediaDetailPage(
    media: MediaItem?,
    actionState: MediaActionState,
    showDeleteConfirmation: Boolean,
    onLoadPreview: () -> Unit,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetrySave: () -> Unit,
    onDismissAction: () -> Unit,
) {
    LaunchedEffect(media?.id, media?.detailImageUrl, media?.imageUrl, media?.canLoadRemotePreview) {
        if (media != null && media.detailImageUrl == null && media.imageUrl == null && media.canLoadRemotePreview) {
            onLoadPreview()
        }
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
            PrivateMediaActionBar(media?.isShared == true, onDownload, onShare, onDelete)
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

/** Renders private-media detail navigation. */
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

/** Renders private detail artwork plus loading, retry and error overlays. */
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

/** Renders the best available local or remote artwork for a media model. */
@Composable
private fun MediaArtwork(media: MediaItem, modifier: Modifier = Modifier) {
    val placeholderColor = when (media.kind) {
        MediaKind.VIDEO -> Color(0xFF485C70)
        MediaKind.GIF -> Color(0xFF526273)
        MediaKind.LIVE_PHOTO -> Color(0xFF465C67)
        MediaKind.PHOTO -> Color(0xFF627485)
    }
    val artworkUrl = detailArtworkSource(media)
    var imageLoaded by remember(artworkUrl) { mutableStateOf(false) }
    Box(modifier.background(placeholderColor)) {
        if (!imageLoaded) {
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
                if (!media.canLoadRemotePreview && artworkUrl == null) {
                    Text(
                        text = "暂无预览",
                        modifier = Modifier.padding(top = 3.dp),
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
        artworkUrl?.let { imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onSuccess = {
                    imageLoaded = true
                    MediaLoadLog.trace(
                        "artwork ready media=${MediaLoadLog.mediaRef(media.id)} kind=${media.kind}",
                    )
                },
                onError = {
                    imageLoaded = false
                    MediaLoadLog.warning(
                        "artwork failed media=${MediaLoadLog.mediaRef(media.id)} kind=${media.kind}",
                    )
                },
            )
        }
    }
}

/** Selects the preferred detail source without triggering I/O. */
internal fun detailArtworkSource(media: MediaItem): String? =
    media.detailImageUrl ?: media.imageUrl

private const val PRIVATE_MEDIA_PAGING_FOOTER_KEY = "private-media-paging-footer"
private const val LOCAL_MEDIA_PAGING_FOOTER_KEY = "local-media-paging-footer"

/** Renders a blocking save-to-album progress overlay. */
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

/** Renders download, share and delete actions for owned private media. */
@Composable
private fun PrivateMediaActionBar(
    isShared: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
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
            DetailAction(
                if (isShared) "取消共享" else "共享",
                onShare,
                Modifier.testTag(if (isShared) "private-media.detail.unshare" else "private-media.detail.share"),
            )
            DetailAction("删除", onDelete, Modifier.testTag("private-media.detail.delete"))
        }
    }
}

/** Displays transient confirmation after sharing succeeds. */
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

/** Displays transient confirmation after system-album save succeeds. */
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

/** Displays retry/dismiss actions after system-album save fails. */
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

/** Renders one compact action inside an error toast. */
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

/** Renders shared-media detail with view-only actions. */
@Composable
fun SharedMediaDetailPage(media: MediaItem?, onLoadPreview: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(media?.id, media?.imageUrl, media?.canLoadRemotePreview) {
        if (media != null && media.imageUrl == null && media.canLoadRemotePreview) onLoadPreview()
    }
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
            SharedDetailArtwork(media)
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

/** Renders shared detail artwork from its verified preview source. */
@Composable
private fun SharedDetailArtwork(media: MediaItem) {
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

/** Renders a compact text action in a detail bar. */
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

/** Renders full-page indeterminate loading. */
@Composable
private fun PageLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
