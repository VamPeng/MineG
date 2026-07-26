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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors

@Composable
fun ProfilePage(
  profile: UserProfile,
  selectedTab: MainTab,
  onSelectTab: (MainTab) -> Unit,
  onEdit: () -> Unit,
  onBackupSettings: () -> Unit,
  onRecycleBin: () -> Unit,
  onFamilyAlbum: () -> Unit,
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
      item { MineGPageTitle("MineG", "我的个人中心") }
      item {
        MineGCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
              Modifier.size(92.dp).clip(CircleShape).background(mineGBrandGradient()),
              contentAlignment = Alignment.Center,
            ) {
              Text(profile.avatarLabel, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 34.sp)
              Box(
                Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
              ) { Icon(Icons.Outlined.CameraAlt, "修改头像", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            Text(profile.nickname, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(profile.maskedPhone, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onEdit) {
              Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(17.dp))
              Text(" 编辑资料")
            }
          }
        }
      }
      item {
        MineGCard(Modifier.fillMaxWidth()) {
          SettingRow(Icons.Outlined.CloudUpload, "备份设置", "管理自动备份与移动网络", onBackupSettings) { Icon(Icons.Outlined.ChevronRight, null) }
          SettingRow(Icons.Outlined.DeleteOutline, "回收站", "查看并恢复已删除媒体", onRecycleBin) { Icon(Icons.Outlined.ChevronRight, null) }
          SettingRow(Icons.Outlined.FamilyRestroom, "家庭共享空间", "浏览家庭成员主动共享的回忆", onFamilyAlbum) { Icon(Icons.Outlined.ChevronRight, null) }
          SettingRow(Icons.AutoMirrored.Outlined.HelpOutline, "帮助与反馈", "常见问题与简短反馈表单", onHelp) { Icon(Icons.Outlined.ChevronRight, null) }
        }
      }
      item {
        MineGCard(Modifier.fillMaxWidth()) {
          Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Security, null, tint = MaterialTheme.mineGColors.success)
            Column {
              Text("端到端加密 · 守护家人回忆", fontWeight = FontWeight.SemiBold)
              Text("媒体密钥不会提供给后台管理员", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
          }
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
      Text("Mock 阶段修改会保存在内存状态中，重启 App 后恢复默认数据。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
      LazyColumn(
        Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          MineGCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Icon(Icons.Outlined.Security, null, tint = MaterialTheme.mineGColors.success)
              Text("恢复后媒体保持私有，不会自动恢复原家庭共享状态。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        items(state.items, key = { it.media.id }) { deleted ->
          MineGCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              MediaPlaceholder(deleted.media, Modifier.size(82.dp))
              Column(Modifier.weight(1f)) {
                Text(deleted.media.title, fontWeight = FontWeight.SemiBold)
                Text("已删除 ${deleted.deletedAgo}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
              }
              IconButtonLike(onClick = { onRestore(deleted.media.id) })
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
        Text("反馈已保存在 Mock 状态中，正式接口接入后会在这里提交。", color = MaterialTheme.mineGColors.success)
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
