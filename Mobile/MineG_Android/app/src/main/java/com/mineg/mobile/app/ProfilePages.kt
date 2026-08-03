@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mineg.mobile.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    modifier = Modifier.testTag("profile.home"),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = { ProfileTopBar() },
    bottomBar = { MineGBottomBar(selectedTab, onSelectTab) },
  ) { padding ->
    LazyColumn(
      Modifier.padding(padding).fillMaxSize().testTag("profile.home.list"),
      contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      item {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        ) {
          Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(
              Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(4.dp, MaterialTheme.colorScheme.background, CircleShape),
              contentAlignment = Alignment.Center,
            ) {
              Text(profile.avatarLabel, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
            Column(
              modifier = Modifier.padding(top = 16.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(profile.nickname, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
              Text(profile.maskedPhone, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 22.sp)
            }
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              ProfileEditAction("编辑昵称", Modifier.weight(1f), onEdit)
              ProfileEditAction("修改头像", Modifier.weight(1f), onEdit)
            }
          }
        }
      }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ProfileListGroup(
            rows = listOf(
              ProfileListRow("备份设置", onBackupSettings),
              ProfileListRow("回收站", onRecycleBin),
              ProfileListRow("我分享的", onSharedByMe),
            ),
          )
          ProfileListGroup(
            rows = listOf(
              ProfileListRow("帮助与反馈", onHelp),
              ProfileListRow("退出登录", onLogout, destructive = true, testTag = "profile.home.signOut"),
            ),
          )
        }
      }
    }
  }
}

@Composable
private fun ProfileTopBar() {
  Surface(
    modifier = Modifier.fillMaxWidth().height(64.dp),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
      Text("我的", color = MaterialTheme.colorScheme.onSurface, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun ProfileEditAction(label: String, modifier: Modifier, onClick: () -> Unit) {
  Surface(
    modifier = modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    }
  }
}

private data class ProfileListRow(
  val title: String,
  val onClick: () -> Unit,
  val destructive: Boolean = false,
  val testTag: String? = null,
)

@Composable
private fun ProfileListGroup(rows: List<ProfileListRow>) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
  ) {
    Column {
      rows.forEachIndexed { index, row ->
        ProfileListItem(row)
        if (index != rows.lastIndex) {
          HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
          )
        }
      }
    }
  }
}

@Composable
private fun ProfileListItem(row: ProfileListRow) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .then(if (row.testTag != null) Modifier.testTag(row.testTag) else Modifier)
      .clickable(onClick = row.onClick)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val contentColor = if (row.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Text(
      text = row.title,
      modifier = Modifier.weight(1f),
      color = contentColor,
      fontSize = 17.sp,
      lineHeight = 26.sp,
      fontWeight = FontWeight.Medium,
    )
    Text("›", color = if (row.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, fontSize = 24.sp, lineHeight = 26.sp)
  }
}

@Composable
fun ProfileEditPage(
  profile: UserProfile,
  nickname: String,
  saving: Boolean,
  message: String?,
  onNickname: (String) -> Unit,
  onSave: () -> Unit,
  onAvatar: (Uri) -> Unit,
  onBack: () -> Unit,
) {
  val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    uri?.let(onAvatar)
  }
  Scaffold(topBar = { DetailTopBar("编辑个人资料", onBack) }) { padding ->
    Column(
      Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Box(Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(profile.avatarLabel, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        profile.avatarUrl?.let { AsyncImage(it, profile.nickname, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
      }
      OutlinedButton(
        onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = !saving,
      ) {
        Icon(Icons.Outlined.CameraAlt, null)
        Text(" 选择并裁剪头像")
      }
      OutlinedTextField(
        value = nickname,
        onValueChange = onNickname,
        modifier = Modifier.fillMaxWidth(),
        enabled = !saving,
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
      message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (saving) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Text("保存")
      }
      Text("保存后将更新你的个人资料展示。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
  }
}

@Composable
fun RecycleBinPage(state: RecycleBinUiState, onBack: () -> Unit, onRestore: (String) -> Unit) {
  Scaffold(topBar = { RecycleBinTopBar("回收站", "返回个人中心", onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
    if (state.loadState == PageLoadState.LOADING) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
      }
    } else if (state.loadState == PageLoadState.ERROR) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState("回收站加载失败", state.errorMessage ?: "请检查网络后重试。")
      }
    } else if (state.loadState == PageLoadState.EMPTY || state.items.isEmpty()) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          "暂无删除内容",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 20.sp,
          lineHeight = 28.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          Text(
            "清理前可以恢复。",
            modifier = Modifier.padding(bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
          )
        }
        gridItems(state.items, key = { it.media.id }) { deleted ->
          RecycleMediaTile(deleted, onRestore)
        }
      }
    }
  }
}

@Composable
private fun RecycleBinTopBar(title: String, backDescription: String, onBack: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().height(64.dp),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .size(40.dp)
          .clip(CircleShape)
          .clickable(onClick = onBack)
          .testTag(backDescription),
        contentAlignment = Alignment.Center,
      ) { Text("←", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp) }
      Text(
        text = title,
        modifier = Modifier.align(Alignment.Center),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun RecycleMediaTile(deleted: DeletedMedia, onRestore: (String) -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surface),
  ) {
    deleted.media.imageUrl?.let { imageUrl ->
      AsyncImage(
        model = imageUrl,
        contentDescription = deleted.media.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize(),
      )
    }
    Box(
      modifier = Modifier
        .matchParentSize()
        .background(
          Brush.verticalGradient(
            colorStops = arrayOf(
              0f to Color.Transparent,
              0.66f to Color.Transparent,
              1f to Color.Black.copy(alpha = 0.60f),
            ),
          ),
        ),
    )
    Surface(
      modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
      color = Color.Black.copy(alpha = 0.40f),
      shape = CircleShape,
    ) {
      Text(
        "已删除 ${deleted.deletedAgo}",
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
      )
    }
    Surface(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(8.dp)
        .size(40.dp)
        .clip(CircleShape)
        .clickable { onRestore(deleted.media.id) },
      color = MaterialTheme.colorScheme.primary,
      shape = CircleShape,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text("恢复", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
      }
    }
  }
}

@Composable
fun RecycleRestoreDialog(media: MediaItem?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      RecycleBinTopBar("恢复媒体", "返回回收站", onDismiss)
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
      ) {
        media?.let { selected ->
          val imageUrl = selected.detailImageUrl ?: selected.imageUrl
          imageUrl?.let {
            AsyncImage(
              model = it,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.matchParentSize().blur(2.dp),
            )
          }
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)))
        Surface(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset(y = (-20).dp),
          color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
          shape = RoundedCornerShape(12.dp),
        ) {
          Column(Modifier.padding(24.dp)) {
            Text("确定恢复此媒体？", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
              "媒体将恢复至私人空间，不会自动重新同步到家庭相册。",
              modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontSize = 15.sp,
              lineHeight = 22.sp,
            )
            Surface(
              modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onConfirm),
              color = MaterialTheme.colorScheme.primary,
              shape = RoundedCornerShape(8.dp),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Text("确认恢复", color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
              }
            }
            Surface(
              modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss),
              color = Color.Transparent,
              shape = RoundedCornerShape(8.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.Medium)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun ProfileLogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.20f)),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
      ) {
        Column {
          Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text("确定退出登录？", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
              "退出后，当前账号尚未完成的备份任务将停止。",
              modifier = Modifier.padding(top = 8.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontSize = 15.sp,
              lineHeight = 22.sp,
              textAlign = TextAlign.Center,
            )
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
          Surface(
            modifier = Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onConfirm),
            color = Color.Transparent,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text("确认退出", color = MaterialTheme.colorScheme.error, fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
            }
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
          Surface(
            modifier = Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onDismiss),
            color = Color.Transparent,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
            }
          }
        }
      }
    }
  }
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
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedbackCategory.entries.drop(3).take(3).forEach { category ->
          FilterChip(category == state.category, onClick = { onCategory(category) }, label = { Text(category.label, fontSize = 12.sp) })
        }
      }
      FilterChip(
        FeedbackCategory.OTHER == state.category,
        onClick = { onCategory(FeedbackCategory.OTHER) },
        label = { Text(FeedbackCategory.OTHER.label) },
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
      Button(
        onClick = onSubmit,
        enabled = !state.submitting,
        modifier = Modifier.fillMaxWidth().height(52.dp),
      ) { Text(if (state.submitting) "提交中…" else "提交反馈") }
      if (state.submitted) {
        Text(
          "反馈已提交，感谢你的帮助。" + state.feedbackId?.let { " 编号：$it" }.orEmpty(),
          color = MaterialTheme.mineGColors.success,
        )
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
