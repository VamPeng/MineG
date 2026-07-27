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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors
import coil3.compose.AsyncImage

@Composable
fun ProfilePage(
  profile: UserProfile,
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
  onEdit: () -> Unit,
  onBackupSettings: () -> Unit,
  onRecycleBin: () -> Unit,
  onSharedByMe: () -> Unit,
  onHelp: () -> Unit,
  onLogout: () -> Unit,
) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
  ) { padding ->
    LazyColumn(
      Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Row(
          Modifier.fillMaxWidth().height(48.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          MineGAssetImage("mineg_logo.png", "MineG Logo", Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
          Text("  MineG", color = MaterialTheme.mineGColors.success, fontWeight = FontWeight.Bold, fontSize = 25.sp)
        }
      }
      item {
        MineGCard(Modifier.fillMaxWidth()) {
          Column(
            Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Box(
              Modifier.size(88.dp).clip(CircleShape).background(mineGBrandGradient()),
              contentAlignment = Alignment.Center,
            ) {
              Text(profile.avatarLabel, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 34.sp)
              PrototypeCroppedImage(MockVisualAssets.profileAvatar, Modifier.matchParentSize(), profile.nickname)
              profile.avatarUrl?.let {
                AsyncImage(it, profile.nickname, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
              }
              Box(
                Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(MaterialTheme.mineGColors.success),
                contentAlignment = Alignment.Center,
              ) { Icon(Icons.Outlined.CameraAlt, "修改头像", modifier = Modifier.size(18.dp), tint = Color.White) }
            }
            Text(profile.nickname, fontWeight = FontWeight.Medium, fontSize = 19.sp)
            Text(profile.maskedPhone, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp)) {
                Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(17.dp))
                Text(" 编辑昵称", fontSize = 13.sp)
              }
              OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp)) {
                Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(17.dp))
                Text(" 修改头像", fontSize = 13.sp)
              }
            }
          }
        }
      }
      item {
        MineGCard(Modifier.fillMaxWidth()) {
          SettingRow(Icons.Outlined.CloudUpload, "备份设置", "管理照片自动上传与同步频率", onBackupSettings, MaterialTheme.mineGColors.successContainer, MaterialTheme.mineGColors.success) { Icon(Icons.Outlined.ChevronRight, null) }
          SettingRow(Icons.Outlined.DeleteOutline, "回收站", "查看并恢复已删除的照片和视频", onRecycleBin, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.mineGColors.success) { Icon(Icons.Outlined.ChevronRight, null) }
          SettingRow(Icons.Outlined.Share, "我分享的", "查看我共享给家人的照片和视频", onSharedByMe, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.mineGColors.warning) { Icon(Icons.Outlined.ChevronRight, null) }
        }
      }
      item {
        MineGCard(Modifier.fillMaxWidth()) {
          SettingRow(Icons.AutoMirrored.Outlined.HelpOutline, "帮助与反馈", "常见问题与问题反馈", onHelp, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant) { Icon(Icons.Outlined.ChevronRight, null) }
        }
      }
      item {
        TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error)
          Text(" 退出登录", color = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}

@Composable
fun ProfileEditPage(profile: UserProfile, onNickname: (String) -> Unit, onBack: () -> Unit) {
  Scaffold(topBar = { DetailTopBar("编辑个人资料", onBack) }) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Box(Modifier.size(112.dp).clip(CircleShape).background(mineGBrandGradient()), contentAlignment = Alignment.Center) {
        Text(profile.avatarLabel, color = MaterialTheme.colorScheme.onPrimary, fontSize = 38.sp, fontWeight = FontWeight.Black)
        PrototypeCroppedImage(MockVisualAssets.profileAvatar, Modifier.matchParentSize(), profile.nickname)
        profile.avatarUrl?.let { AsyncImage(it, profile.nickname, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
      }
      OutlinedButton(onClick = {}) {
        Icon(Icons.Outlined.CameraAlt, null)
        Text(" 选择并裁剪头像")
      }
      OutlinedTextField(
        value = profile.nickname,
        onValueChange = onNickname,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("昵称") },
        supportingText = { Text("2～20 个字符，支持中文、字母、数字、空格、- 和 _") },
      )
      OutlinedTextField(
        value = profile.maskedPhone,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
        label = { Text("手机号不可修改") },
      )
      Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存") }
      Text("保存后将更新你的个人资料展示。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
  }
}

@Composable
fun RecycleBinPage(state: RecycleBinUiState, onBack: () -> Unit, onRestore: (String) -> Unit) {
  Scaffold(topBar = { DetailTopBar("回收站", onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
    if (state.loadState == PageLoadState.LOADING) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
      }
    } else if (state.loadState == PageLoadState.ERROR) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState("回收站加载失败", "请检查网络后重试。")
      }
    } else if (state.loadState == PageLoadState.EMPTY || state.items.isEmpty()) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState("暂无删除内容", "移入回收站的私人媒体会一直保留，直到独立运维流程人工清理。")
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          MineGCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Icon(Icons.Outlined.Security, null, tint = MaterialTheme.mineGColors.warning)
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("私密存储提示", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                  "回收站内的照片和视频仅你个人可见，内容会一直保留，直到由运维人员人工永久清理。",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 13.sp,
                )
              }
            }
          }
        }
        gridItems(state.items, key = { it.media.id }) { deleted ->
          Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) {
            MediaPlaceholder(deleted.media, Modifier.matchParentSize())
            androidx.compose.material3.Surface(
              modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
              color = Color.Black.copy(alpha = 0.56f),
              shape = RoundedCornerShape(10.dp),
            ) {
              Text(
                "已删除 ${deleted.deletedAgo}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
            Box(
              Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp).clip(CircleShape)
                .background(MaterialTheme.mineGColors.success).clickable { onRestore(deleted.media.id) },
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Outlined.Restore, "恢复", tint = Color.White)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun IconButtonLike(onClick: () -> Unit) {
  Box(
    Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { Icon(Icons.Outlined.Restore, "恢复", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
}

@Composable
fun HelpFeedbackPage(
  state: FeedbackUiState,
  onBack: () -> Unit,
  onCategory: (FeedbackCategory) -> Unit,
  onDescription: (String) -> Unit,
  onContact: (String) -> Unit,
  onSubmit: () -> Unit,
) {
  Scaffold(topBar = { DetailTopBar("帮助与反馈", onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("常见问题", fontWeight = FontWeight.Bold, fontSize = 20.sp)
      MineGCard(Modifier.fillMaxWidth()) {
        FaqItem("为什么需要完整相册权限？", "完整权限用于分批扫描历史媒体。部分授权状态不会创建备份任务。")
        FaqItem("家人可以下载我的照片吗？", "不能。家庭相册只提供只读浏览，原文件保存仅属于媒体所有者。")
        FaqItem("删除会影响手机里的照片吗？", "不会。移入 MineG 回收站不会删除设备本地媒体。")
      }
      Text("提交反馈", fontWeight = FontWeight.Bold, fontSize = 20.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedbackCategory.entries.take(3).forEach { category ->
          FilterChip(category == state.category, onClick = { onCategory(category) }, label = { Text(category.label, fontSize = 12.sp) })
        }
      }
      FilterChip(
        FeedbackCategory.SUGGESTION == state.category,
        onClick = { onCategory(FeedbackCategory.SUGGESTION) },
        label = { Text(FeedbackCategory.SUGGESTION.label) },
      )
      OutlinedTextField(
        state.description,
        onDescription,
        Modifier.fillMaxWidth().height(140.dp),
        label = { Text("问题描述") },
        isError = state.errorMessage != null,
        supportingText = { state.errorMessage?.let { Text(it) } },
      )
      OutlinedTextField(state.contact, onContact, Modifier.fillMaxWidth(), label = { Text("联系方式（可选）") })
      Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("提交反馈") }
      if (state.submitted) {
        Text("反馈已提交，感谢你的帮助。", color = MaterialTheme.mineGColors.success)
      }
      Text("不会自动附带媒体、访问令牌、密钥或完整手机号。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
  }
}

@Composable
private fun FaqItem(question: String, answer: String) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Text(question, fontWeight = FontWeight.SemiBold)
    Text(answer, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
  }
}
