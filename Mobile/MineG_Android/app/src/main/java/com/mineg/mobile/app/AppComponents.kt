package com.mineg.mobile.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.NavigationBar
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors
import com.mineg.mobile.ui.theme.mineGNavigationSelectionGradient
import coil3.compose.AsyncImage

@Composable
fun MineGBottomBar(
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
) {
  val inactiveIconFilter = remember {
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
  }
  NavigationBar(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceAround,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      MainTab.entries.forEach { tab ->
        val iconAsset = when (tab) {
          MainTab.PRIVATE_SPACE -> "claude.png"
          MainTab.BACKUP -> "nav_backup.png"
          MainTab.PROFILE -> "nav_profile.png"
        }
        val selected = tab == selectedTab
        Box(
          modifier = Modifier
            .size(width = 60.dp, height = 50.dp)
            .clip(RoundedCornerShape(20.dp))
            .then(if (selected) Modifier.background(mineGNavigationSelectionGradient()) else Modifier)
            .clickable { onSelectTab(tab) }
            .semantics { contentDescription = tab.label },
          contentAlignment = Alignment.Center,
        ) {
          MineGAssetImage(
            assetPath = iconAsset,
            contentDescription = null,
            modifier = Modifier.size(if (selected) 38.dp else 34.dp),
            colorFilter = if (selected) null else inactiveIconFilter,
            alpha = if (selected) 1f else 0.56f,
          )
        }
      }
    }
  }
}

@Composable
fun MineGAssetImage(
  assetPath: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  colorFilter: ColorFilter? = null,
  alpha: Float = 1f,
) {
  val context = LocalContext.current
  val bitmap = remember(assetPath) { PrototypeBitmapCache.load(context, assetPath) }
  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = contentDescription,
      modifier = modifier,
      colorFilter = colorFilter,
      alpha = alpha,
    )
  }
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
    shape = RoundedCornerShape(18.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) { content() }
}

@Composable
fun MediaPlaceholder(
  media: MediaItem,
  modifier: Modifier = Modifier,
  showOwner: Boolean = false,
  onClick: (() -> Unit)? = null,
) {
  val palette = mockPalette(media.colorSeed)
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(Brush.linearGradient(palette))
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
  ) {
    Text(
      text = media.title.take(1),
      modifier = Modifier.align(Alignment.Center),
      color = Color.White.copy(alpha = 0.9f),
      fontSize = 28.sp,
      fontWeight = FontWeight.Bold,
    )
    PrototypeCroppedImage(
      crop = MockVisualAssets.mediaCrops[Math.floorMod(media.colorSeed, MockVisualAssets.mediaCrops.size)],
      contentDescription = media.title,
      modifier = Modifier.matchParentSize(),
    )
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
  trailing: (@Composable () -> Unit)? = null,
) {
  val resolvedContainer = iconContainer ?: MaterialTheme.colorScheme.primaryContainer
  val resolvedTint = iconTint ?: MaterialTheme.colorScheme.onPrimaryContainer
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
      Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(resolvedContainer),
      contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = resolvedTint) }
    Column(Modifier.weight(1f)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    trailing?.invoke()
  }
  HorizontalDivider(color = MaterialTheme.mineGColors.divider)
}

private fun mockPalette(seed: Int): List<Color> {
  val palettes = listOf(
    listOf(Color(0xFFFFB07C), Color(0xFFFD5C55)),
    listOf(Color(0xFF7FA6A0), Color(0xFF355C5A)),
    listOf(Color(0xFFF0B7A4), Color(0xFF9C5B6A)),
    listOf(Color(0xFFF4C87A), Color(0xFFB46B45)),
    listOf(Color(0xFFB7A48B), Color(0xFF66554B)),
    listOf(Color(0xFF9DB9D2), Color(0xFF526B86)),
    listOf(Color(0xFFD6C5B5), Color(0xFF8A6E61)),
    listOf(Color(0xFFB5A5C9), Color(0xFF675577)),
    listOf(Color(0xFFD7A17B), Color(0xFF75493B)),
  )
  return palettes[Math.floorMod(seed, palettes.size)]
}
