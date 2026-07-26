package com.mineg.mobile.app

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors
import com.mineg.mobile.ui.theme.mineGNavigationSelectionGradient

@Composable
fun MineGBottomBar(
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
) {
  NavigationBar(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    tonalElevation = 2.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceAround,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      MainTab.entries.forEach { tab ->
        val icon = when (tab) {
          MainTab.PRIVATE_SPACE -> Icons.Outlined.Lock
          MainTab.FAMILY_ALBUM -> Icons.Outlined.Home
          MainTab.BACKUP -> Icons.Outlined.CloudUpload
          MainTab.PROFILE -> Icons.Outlined.Person
        }
        val selected = tab == selectedTab
        Box(
          modifier = Modifier
            .size(width = 60.dp, height = 50.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(if (selected) Modifier.background(mineGNavigationSelectionGradient()) else Modifier)
            .clickable { onSelectTab(tab) }
            .semantics { contentDescription = tab.label },
          contentAlignment = Alignment.Center,
        ) {
          if (selected) {
            Box(
              Modifier.size(30.dp).clip(CircleShape).background(mineGBrandGradient()),
              contentAlignment = Alignment.Center,
            ) {
              Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(19.dp))
            }
          } else {
            Icon(icon, contentDescription = null, tint = MaterialTheme.mineGColors.iconInactive, modifier = Modifier.size(25.dp))
          }
        }
      }
    }
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
  Column(modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f, fill = true)
        .clip(RoundedCornerShape(10.dp))
        .background(Brush.linearGradient(palette)),
    ) {
      Text(
        text = media.title.take(1),
        modifier = Modifier.align(Alignment.Center),
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
      )
      val kindLabel = when (media.kind) {
        MediaKind.PHOTO -> null
        MediaKind.VIDEO -> media.duration ?: "视频"
        MediaKind.GIF -> "GIF"
        MediaKind.LIVE_PHOTO -> "LIVE"
      }
      kindLabel?.let {
        Surface(
          modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
          color = Color.Black.copy(alpha = 0.42f),
          shape = RoundedCornerShape(6.dp),
        ) { Text(it, color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
      }
      if (media.isShared) {
        Box(
          Modifier.align(Alignment.BottomEnd).padding(6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.mineGColors.success),
        )
      }
    }
    if (showOwner) {
      Spacer(Modifier.height(5.dp))
      Text(
        media.owner.nickname,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
      Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
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
