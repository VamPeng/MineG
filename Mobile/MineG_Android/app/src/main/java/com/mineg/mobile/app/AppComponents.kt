package com.mineg.mobile.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.mineg.mobile.R
import com.mineg.mobile.ui.theme.mineGColors
import com.mineg.mobile.ui.theme.mineGNavigationSelectionColor
import coil3.compose.AsyncImage

@Composable
fun MineGBottomBar(
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
    Column {
      HorizontalDivider(color = Color(0x2E6F7E89), thickness = 1.dp)
      Row(
        modifier = Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        MainTab.entries.forEach { tab ->
          val iconRes = when (tab) {
            MainTab.PRIVATE_SPACE -> R.drawable.cloud
            MainTab.BACKUP -> R.drawable.local_album
            MainTab.PROFILE -> R.drawable.profile
          }
          val selected = tab == selectedTab
          Box(
            modifier = Modifier
              .size(width = 60.dp, height = 50.dp)
              .clip(RoundedCornerShape(18.dp))
              .then(if (selected) Modifier.background(mineGNavigationSelectionColor()) else Modifier)
              .clickable { onSelectTab(tab) }
              .semantics { contentDescription = tab.label },
            contentAlignment = Alignment.Center,
          ) {
            MineGDrawableImage(
              drawableRes = iconRes,
              contentDescription = null,
              modifier = Modifier.size(32.dp),
              colorFilter = ColorFilter.tint(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.mineGColors.iconInactive,
              ),
            )
          }
        }
      }
    }
  }
}

@Composable
fun MineGDrawableImage(
  drawableRes: Int,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  colorFilter: ColorFilter? = null,
  alpha: Float = 1f,
) {
  Image(
    painter = painterResource(drawableRes),
    contentDescription = contentDescription,
    modifier = modifier,
    colorFilter = colorFilter,
    alpha = alpha,
  )
}

@Composable
fun MineGPageTitle(
  title: String,
  subtitle: String? = null,
  action: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    action?.invoke()
  }
}

@Composable
fun MineGCard(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) { content() }
}

@Composable
fun MediaPlaceholder(
  media: MediaItem,
  modifier: Modifier = Modifier,
  showOwner: Boolean = false,
  bottomStartSyncState: LocalMediaSyncState? = null,
  onClick: (() -> Unit)? = null,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
  ) {
    Text(
      text = media.title.take(1),
      modifier = Modifier.align(Alignment.Center),
      color = Color.White.copy(alpha = 0.9f),
      fontSize = 28.sp,
      fontWeight = FontWeight.Bold,
    )
    if (media.imageUrl != null) {
      PrototypeCroppedImage(
        crop = MockVisualAssets.mediaCrops[Math.floorMod(media.colorSeed, MockVisualAssets.mediaCrops.size)],
        contentDescription = media.title,
        modifier = Modifier.matchParentSize(),
      )
    }
    media.imageUrl?.let {
      AsyncImage(
        model = it,
        contentDescription = media.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().matchParentSize(),
      )
    }
    val kindLabel = when (media.kind) {
      MediaKind.PHOTO -> null
      MediaKind.VIDEO -> media.duration ?: "视频"
      MediaKind.GIF -> "GIF"
      MediaKind.LIVE_PHOTO -> "LIVE"
    }
    kindLabel?.let {
      Surface(
        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
        color = Color.Black.copy(alpha = 0.48f),
        shape = RoundedCornerShape(5.dp),
      ) { Text(it, color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
    }
    bottomStartSyncState?.let { syncState ->
      val color = when (syncState) {
        LocalMediaSyncState.UNSYNCED -> Color.Black.copy(alpha = 0.46f)
        LocalMediaSyncState.SYNCING ->
          lerp(MaterialTheme.colorScheme.primary, Color.White, 0.16f).copy(alpha = 0.64f)
        LocalMediaSyncState.FAILED -> Color(0xFFD32F2F)
        LocalMediaSyncState.SYNCED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.80f)
      }
      Surface(
        modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
        color = color,
        shape = RoundedCornerShape(5.dp),
      ) {
        Text(
          syncState.label,
          color = Color.White,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
      }
    }
    if (showOwner) {
      Surface(
        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(12.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Box(
            Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
          ) { Text(media.owner.avatarLabel, fontSize = 8.sp, color = MaterialTheme.colorScheme.onPrimaryContainer) }
          Text(media.owner.nickname, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp)
        }
      }
    }
  }
}

private object PrototypeBitmapCache {
  private val images = mutableMapOf<String, ImageBitmap>()

  @Synchronized
  fun load(context: Context, assetPath: String): ImageBitmap? = images[assetPath] ?: runCatching {
    context.assets.open(assetPath).use(BitmapFactory::decodeStream).asImageBitmap().also { images[assetPath] = it }
  }.getOrNull()
}

@Composable
fun PrototypeCroppedImage(
  crop: MockVisualAssets.Crop,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
) {
  val context = LocalContext.current
  val bitmap = remember(crop.assetPath) { PrototypeBitmapCache.load(context, crop.assetPath) }
  if (bitmap != null) {
    Canvas(modifier = modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription }) {
      drawImage(
        image = bitmap,
        srcOffset = IntOffset(crop.x, crop.y),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
      )
    }
  }
}

@Composable
fun EmptyState(title: String, description: String) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 72.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
fun SettingRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  iconContainer: Color? = null,
  iconTint: Color? = null,
  showIcon: Boolean = true,
  trailing: (@Composable () -> Unit)? = null,
) {
  val resolvedContainer = iconContainer ?: MaterialTheme.colorScheme.primaryContainer
  val resolvedTint = iconTint ?: MaterialTheme.colorScheme.onPrimaryContainer
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (showIcon) {
      Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(resolvedContainer),
        contentAlignment = Alignment.Center,
      ) { Icon(icon, contentDescription = null, tint = resolvedTint) }
    }
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    trailing?.invoke()
  }
  HorizontalDivider(color = MaterialTheme.mineGColors.divider)
}
