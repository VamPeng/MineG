package com.mineg.mobile

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.app.MineGApp
import com.mineg.mobile.app.MineGAppViewModel
import com.mineg.mobile.app.LibraryAccess
import com.mineg.mobile.ui.theme.MineGColorTokens
import com.mineg.mobile.ui.theme.MineGTheme
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
  private val viewModel by viewModels<MineGAppViewModel> { MineGAppViewModel.factory(this) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MineGTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          val state by viewModel.state.collectAsStateWithLifecycle()
          val context = LocalContext.current
          val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onLibraryPermissionResult()
          }
          val requestLibraryAccess = {
            viewModel.markLibraryPermissionRequested()
            if (state.libraryAccess in setOf(LibraryAccess.DENIED, LibraryAccess.LIMITED)) {
              context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
              )
            } else {
              permissionLauncher.launch(
                if (Build.VERSION.SDK_INT >= 33) {
                  arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                  arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
              )
            }
          }
          LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }
          MineGApp(viewModel, requestLibraryAccess)
        }
      }
    }
  }
}

@Composable
private fun MineGAccountApp(viewModel: AccountViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    viewModel.onPermissionResult()
  }
  val requestPermission = {
    viewModel.markPermissionRequestStarted()
    if (state.permissionState in setOf(LibraryPermissionState.DENIED, LibraryPermissionState.LIMITED)) {
      context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
      )
    } else {
      permissionLauncher.launch(
        if (Build.VERSION.SDK_INT >= 33) {
          arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
          arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        },
      )
    }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }
  when (state.screen) {
    AccountScreen.RESTORING -> RestoringScreen()
    AccountScreen.LOGIN -> LoginScreen(state, viewModel)
    AccountScreen.SIGNUP -> SignUpScreen(state, viewModel)
    AccountScreen.REVIEW_PENDING -> ReviewPendingScreen(state, viewModel)
    AccountScreen.PROFILE -> ProfileScreen(state, viewModel)
    AccountScreen.PROFILE_EDIT -> ProfileEditScreen(state, viewModel)
    AccountScreen.PERMISSION -> PermissionScreen(state, viewModel, requestPermission)
    AccountScreen.BACKUP_OVERVIEW -> BackupOverviewScreen(state, viewModel)
    AccountScreen.BACKUP_SETTINGS -> BackupSettingsScreen(state, viewModel)
    AccountScreen.LOCAL_ALBUM -> LocalAlbumScreen(state, viewModel)
    AccountScreen.LEGAL -> LegalScreen(state.legalDocument.orEmpty(), viewModel::closeLegal)
  }
}

@Composable
private fun RestoringScreen() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
      CircularProgressIndicator()
      Text("正在恢复安全会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun AuthFrame(title: String, subtitle: String, semanticId: String, content: @Composable ColumnScope.() -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceContainerLow,
          ),
        ),
      )
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 22.dp, vertical = 42.dp)
      .testTag(semanticId),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
      BrandLockup()
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
      }
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
      ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
      }
    }
  }
}

@Composable
private fun BrandLockup() {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    com.mineg.mobile.app.MineGAssetImage(
      assetPath = "mineg_logo.png",
      contentDescription = "MineG Logo",
      modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)),
    )
    Text("MineG", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 20.sp)
  }
}

@Composable
private fun LoginScreen(state: AccountUiState, viewModel: AccountViewModel) {
  AuthFrame("欢迎回来", "登录你的私人家庭相册，所有密钥材料只在设备端处理。", "auth.login") {
    AccountMessage(state)
    OutlinedTextField(
      value = state.phone,
      onValueChange = viewModel::updatePhone,
      modifier = Modifier.fillMaxWidth().testTag("auth.login.phone"),
      label = { Text("手机号") },
      placeholder = { Text("11 位中国大陆手机号") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      isError = "phone" in state.fieldErrors,
      supportingText = state.fieldErrors["phone"]?.let { { Text(it) } },
      enabled = !state.loading,
    )
    OutlinedTextField(
      value = state.password,
      onValueChange = viewModel::updatePassword,
      modifier = Modifier.fillMaxWidth().testTag("auth.login.password"),
      label = { Text("密码") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      isError = "password" in state.fieldErrors,
      supportingText = state.fieldErrors["password"]?.let { { Text(it) } },
      enabled = !state.loading,
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = !state.loading) { viewModel.updateAgreement(!state.agreementAccepted) }
        .testTag("auth.login.agreement"),
      verticalAlignment = Alignment.Top,
    ) {
      Checkbox(checked = state.agreementAccepted, onCheckedChange = viewModel::updateAgreement, enabled = !state.loading)
      Column(Modifier.padding(top = 11.dp)) {
        Text("我已阅读并同意", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
          Text("服务协议", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { viewModel.openLegal("terms") })
          Text(" 与 ", color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("隐私政策", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { viewModel.openLegal("privacy") })
        }
        state.fieldErrors["agreement"]?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
      }
    }
    Button(
      onClick = viewModel::submitSignIn,
      enabled = !state.loading,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth.login.submit"),
    ) {
      if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
      else Text("登录")
    }
    TextButton(
      onClick = viewModel::openSignUp,
      enabled = !state.loading,
      modifier = Modifier.align(Alignment.CenterHorizontally).testTag("auth.login.openSignup"),
    ) { Text("没有账号？立即注册") }
  }
}

@Composable
private fun SignUpScreen(state: AccountUiState, viewModel: AccountViewModel) {
  BackHandler { viewModel.openLogin() }
  AuthFrame("创建账号", "注册申请需要管理员审核；通过后还需家庭成员设备完成安全密钥授权。", "auth.signup") {
    AccountMessage(state)
    AccountTextField(state.phone, viewModel::updatePhone, "手机号", "auth.signup.phone", state.fieldErrors["phone"], KeyboardType.Phone, state.loading)
    AccountPasswordField(state.password, viewModel::updatePassword, "密码", "auth.signup.password", state.fieldErrors["password"], state.loading)
    AccountPasswordField(
      state.passwordConfirmation,
      viewModel::updatePasswordConfirmation,
      "确认密码",
      "auth.signup.passwordConfirmation",
      state.fieldErrors["passwordConfirmation"],
      state.loading,
    )
    Button(
      onClick = viewModel::submitSignUp,
      enabled = !state.loading,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth.signup.submit"),
    ) {
      if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
      else Text("提交注册申请")
    }
    TextButton(onClick = { viewModel.openLogin() }, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
      Text("返回登录")
    }
  }
}

@Composable
private fun AccountTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  semanticId: String,
  error: String?,
  keyboardType: KeyboardType,
  loading: Boolean,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth().testTag(semanticId),
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    isError = error != null,
    supportingText = error?.let { { Text(it) } },
    enabled = !loading,
  )
}

@Composable
private fun AccountPasswordField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  semanticId: String,
  error: String?,
  loading: Boolean,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth().testTag(semanticId),
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    isError = error != null,
    supportingText = error?.let { { Text(it) } },
    enabled = !loading,
  )
}

@Composable
private fun ReviewPendingScreen(state: AccountUiState, viewModel: AccountViewModel) {
  var showLogoutDialog by remember { mutableStateOf(false) }
  Box(
    Modifier.fillMaxSize().padding(24.dp).testTag("auth.reviewPending"),
    contentAlignment = Alignment.Center,
  ) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(
        Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        Box(
          Modifier.size(74.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) { Text("…", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        Text("申请审核中", fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text("管理员通过后，家庭成员设备还会无感完成密钥授权。在此之前，你会继续停留在本页面。", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
        AccountMessage(state)
        Button(
          onClick = { viewModel.refreshReviewStatus() },
          enabled = !state.loading,
          modifier = Modifier.fillMaxWidth().testTag("auth.reviewPending.refresh"),
        ) {
          if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
          else Text("刷新状态")
        }
        OutlinedButton(
          onClick = viewModel::signInAgain,
          enabled = !state.loading,
          modifier = Modifier.fillMaxWidth().testTag("auth.reviewPending.signInAgain"),
        ) { Text("重新登录") }
        TextButton(
          onClick = { showLogoutDialog = true },
          enabled = !state.loading,
          modifier = Modifier.testTag("auth.reviewPending.signOut"),
        ) { Text("退出当前账号", color = MaterialTheme.colorScheme.error) }
      }
    }
  }
  if (showLogoutDialog) {
    SignOutDialog(onDismiss = { showLogoutDialog = false }, onConfirm = viewModel::confirmSignOut)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(state: AccountUiState, viewModel: AccountViewModel) {
  var showLogoutDialog by remember { mutableStateOf(false) }
  Scaffold(
    modifier = Modifier.testTag("profile.home"),
    topBar = {
      TopAppBar(
        title = { Text("个人中心", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
      )
    },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    Column(Modifier.padding(padding).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Box(Modifier.size(58.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            MediaThumbnail(
              uri = state.profile?.avatarUrl,
              modifier = Modifier.fillMaxSize(),
              placeholder = state.profile?.nickname?.take(1).orEmpty(),
              contentDescription = "头像",
            )
          }
          Column {
            Text(state.profile?.nickname.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(state.profile?.maskedPhone.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Spacer(Modifier.weight(1f))
          TextButton(onClick = viewModel::openProfileEdit, modifier = Modifier.testTag("profile.home.edit")) {
            Text("编辑")
          }
        }
      }
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("端到端加密已启用", fontWeight = FontWeight.SemiBold, color = MaterialTheme.mineGColors.success)
          Text("你的私钥不会离开设备。家庭密钥、资料与本地相册索引均已由当前设备安全处理。", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 21.sp)
        }
      }
      Button(onClick = viewModel::openBackup, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("profile.home.openBackup")) {
        Text("本地相册与备份")
      }
      OutlinedButton(onClick = viewModel::openBackupSettings, modifier = Modifier.fillMaxWidth().testTag("profile.home.openBackupSettings")) {
        Text("备份设置")
      }
      OutlinedButton(
        onClick = { showLogoutDialog = true },
        modifier = Modifier.fillMaxWidth().testTag("profile.home.signOut"),
      ) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
    }
  }
  if (showLogoutDialog) SignOutDialog(onDismiss = { showLogoutDialog = false }, onConfirm = viewModel::confirmSignOut)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(state: AccountUiState, viewModel: AccountViewModel) {
  val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    uri?.let(viewModel::updateAvatar)
  }
  BackHandler(onBack = viewModel::backToProfile)
  Scaffold(
    modifier = Modifier.testTag("profile.edit"),
    topBar = {
      TopAppBar(
        title = { Text("编辑个人资料") },
        navigationIcon = { TextButton(onClick = viewModel::backToProfile) { Text("返回") } },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(22.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      AccountMessage(state)
      OutlinedTextField(
        value = state.editingNickname,
        onValueChange = viewModel::updateEditingNickname,
        label = { Text("昵称") },
        supportingText = { Text(state.fieldErrors["nickname"] ?: "2～20 个字符，支持中文、字母、数字、空格、- 和 _") },
        isError = state.fieldErrors["nickname"] != null,
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth().testTag("profile.edit.nickname"),
      )
      Button(
        onClick = viewModel::saveProfile,
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("profile.edit.save"),
      ) {
        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        else Text("保存昵称")
      }
      OutlinedButton(
        onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth().testTag("profile.edit.avatar"),
      ) { Text("选择并裁剪头像") }
      Text(
        "头像会在本机居中裁剪为方形并缩放至不超过 1024×1024，再通过独立资料对象授权上传，不会进入媒体备份。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 21.sp,
      )
    }
  }
}

@Composable
private fun PermissionScreen(state: AccountUiState, viewModel: AccountViewModel, requestPermission: () -> Unit) {
  BackHandler(onBack = viewModel::deferPermission)
  Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("permission.library"),
  ) {
    Column(
      Modifier.fillMaxWidth().blur(18.dp).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      repeat(2) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          repeat(3) {
            Box(
              Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
          }
        }
      }
    }
    Card(
      Modifier.align(Alignment.BottomCenter).padding(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      shape = RoundedCornerShape(24.dp),
      elevation = CardDefaults.cardElevation(8.dp),
    ) {
      Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("保护整个本地相册", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
          "MineG 需要完整照片与视频访问权限，才能分批建立本地索引并自动备份。部分授权、拒绝或受限状态都不会创建扫描或上传任务。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 22.sp,
        )
        Text("当前状态：${permissionLabel(state.permissionState)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        AccountMessage(state)
        Button(
          onClick = requestPermission,
          modifier = Modifier.fillMaxWidth().height(52.dp).testTag("permission.library.request"),
        ) {
          Text(if (state.permissionState in setOf(LibraryPermissionState.DENIED, LibraryPermissionState.LIMITED)) "前往系统设置" else "继续授权")
        }
        TextButton(
          onClick = viewModel::deferPermission,
          modifier = Modifier.align(Alignment.CenterHorizontally).testTag("permission.library.notNow"),
        ) { Text("暂不开启") }
      }
    }
  }
}

private fun permissionLabel(state: LibraryPermissionState): String = when (state) {
  LibraryPermissionState.NOT_DETERMINED -> "尚未决定"
  LibraryPermissionState.FULL -> "完整授权"
  LibraryPermissionState.LIMITED -> "部分授权"
  LibraryPermissionState.RESTRICTED -> "访问受限"
  LibraryPermissionState.DENIED -> "已拒绝"
  LibraryPermissionState.SYSTEM_RESTRICTED -> "系统限制"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupOverviewScreen(state: AccountUiState, viewModel: AccountViewModel) {
  BackHandler(onBack = viewModel::backToProfile)
  Scaffold(
    modifier = Modifier.testTag("backup.overview"),
    topBar = {
      TopAppBar(
        title = { Text("本地相册", fontWeight = FontWeight.Bold) },
        navigationIcon = { TextButton(onClick = viewModel::backToProfile) { Text("返回") } },
        actions = {
          TextButton(onClick = viewModel::openBackupSettings, modifier = Modifier.testTag("backup.overview.settings")) { Text("设置") }
        },
      )
    },
    floatingActionButton = {
      if (!state.backupSettings.autoBackupEnabled) {
        Button(onClick = viewModel::startBackup, modifier = Modifier.testTag("backup.overview.startBackup")) { Text("开始备份") }
      }
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.padding(padding).fillMaxSize(),
      contentPadding = PaddingValues(18.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
          Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              when {
                state.loading -> "正在分批扫描本地媒体…"
                state.scanState?.status?.name == "COMPLETE" -> "本地索引已完成"
                else -> "等待扫描"
              },
              fontWeight = FontWeight.Bold,
            )
            Text(
              "已索引 ${state.scanState?.indexedCount ?: 0} 项 · 自动备份${if (state.backupSettings.autoBackupEnabled) "已开启" else "已关闭"} · ${if (state.backupSettings.allowCellularBackup) "允许移动网络" else "仅 Wi‑Fi"}",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.singleMediaBackup?.let { backup ->
              Text(
                "单媒体加密备份：${backup.state.name} · ${backup.uploadedParts}/${backup.partCount} 分片",
                modifier = Modifier.testTag("backup.overview.singleMediaStatus"),
                style = MaterialTheme.typography.bodySmall,
              )
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            AccountMessage(state)
          }
        }
      }
      item { Text("设备相册", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
      if (!state.loading && state.albums.isEmpty()) {
        item { Text("没有可显示的本地照片或视频。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      items(state.albums, key = { it.platformAlbumRef }) { album ->
        AlbumCard(album, onClick = { viewModel.openLocalAlbum(album) })
      }
      item { Spacer(Modifier.height(70.dp)) }
    }
  }
}

@Composable
private fun AlbumCard(album: LocalAlbum, onClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("backup.overview.album.${album.platformAlbumRef}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      MediaThumbnail(album.coverThumbnailUri, Modifier.size(72.dp))
      Column(Modifier.weight(1f)) {
        Text(album.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("${album.mediaCount} 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text("查看", color = MaterialTheme.colorScheme.primary)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSettingsScreen(state: AccountUiState, viewModel: AccountViewModel) {
  BackHandler(onBack = viewModel::backToBackupOverview)
  Scaffold(
    modifier = Modifier.testTag("backup.settings"),
    topBar = {
      TopAppBar(
        title = { Text("备份设置") },
        navigationIcon = { TextButton(onClick = viewModel::backToBackupOverview) { Text("返回") } },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      SettingSwitch(
        title = "自动备份",
        subtitle = "发现新的本地媒体后自动进入备份流程",
        checked = state.backupSettings.autoBackupEnabled,
        onCheckedChange = viewModel::setAutoBackupEnabled,
        enabled = true,
        semanticId = "backup.settings.autoBackup",
      )
      SettingSwitch(
        title = "允许移动网络备份",
        subtitle = "关闭时只在未计量网络下调度",
        checked = state.backupSettings.allowCellularBackup,
        onCheckedChange = viewModel::setAllowCellularBackup,
        enabled = state.backupSettings.autoBackupEnabled,
        semanticId = "backup.settings.allowCellular",
      )
    }
  }
}

@Composable
private fun SettingSwitch(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  enabled: Boolean,
  semanticId: String,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
    Row(
      Modifier.fillMaxWidth().padding(18.dp).testTag(semanticId),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
      }
      Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalAlbumScreen(state: AccountUiState, viewModel: AccountViewModel) {
  BackHandler(onBack = viewModel::backToBackupOverview)
  Scaffold(
    modifier = Modifier.testTag("backup.album"),
    topBar = {
      TopAppBar(
        title = { Text(state.selectedAlbum?.name ?: "本地相册") },
        navigationIcon = { TextButton(onClick = viewModel::backToBackupOverview) { Text("返回") } },
      )
    },
  ) { padding ->
    if (state.loading) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (state.localMedia.isEmpty()) {
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("此相册暂无可用媒体") }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.padding(padding).fillMaxSize().testTag("backup.album.grid"),
        contentPadding = PaddingValues(3.dp),
      ) {
        gridItems(state.localMedia, key = { it.platformAssetRef }) { media ->
          LocalMediaTile(media)
        }
      }
    }
  }
}

@Composable
private fun LocalMediaTile(media: LocalMedia) {
  Box(Modifier.padding(2.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
    MediaThumbnail(media.thumbnailUri, Modifier.fillMaxSize())
    if (media.durationMs != null) {
      Text(
        "${media.durationMs / 1000}s",
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp),
      )
    }
  }
}

@Composable
private fun MediaThumbnail(
  uri: String?,
  modifier: Modifier = Modifier,
  placeholder: String = "相册",
  contentDescription: String? = null,
) {
  val context = LocalContext.current
  val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
    value = if (uri.isNullOrBlank()) null else withContext(Dispatchers.IO) {
      runCatching {
        if (uri.startsWith("content://")) {
          context.contentResolver.loadThumbnail(Uri.parse(uri), Size(320, 320), null)
        } else {
          val connection = URL(uri).openConnection() as HttpURLConnection
          try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.inputStream.use(BitmapFactory::decodeStream)
          } finally {
            connection.disconnect()
          }
        }
      }.getOrNull()
    }
  }
  if (bitmap == null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
      Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
  } else {
    Image(bitmap = checkNotNull(bitmap).asImageBitmap(), contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Crop)
  }
}

@Composable
private fun SignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("确认退出登录？") },
    text = { Text("退出后将停止当前账号的任务并清除本机登录凭据和内存密钥状态。") },
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("确认退出", color = MaterialTheme.colorScheme.error) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalScreen(document: String, onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val privacy = document == "privacy"
  Scaffold(
    topBar = { TopAppBar(title = { Text(if (privacy) "隐私政策" else "服务协议") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
  ) { padding ->
    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(if (privacy) "MineG 隐私政策" else "MineG 服务协议", fontSize = 25.sp, fontWeight = FontWeight.Bold)
      Text(
        if (privacy) {
          "MineG 仅处理账号准入、加密媒体元数据和实现服务所需的信息。媒体内容在设备端加密；服务端和审核管理员不能读取媒体明文或私钥。访问令牌保存在系统安全存储中，不写入普通数据库或日志。"
        } else {
          "使用 MineG 即表示你同意仅上传本人有权处理的媒体，并妥善保管登录密码。MVP 不提供密码找回或管理员绕过密钥恢复；遗失密码可能导致加密内容无法恢复。"
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 24.sp,
      )
      Spacer(Modifier.height(20.dp))
      Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回登录") }
    }
  }
}

@Composable
private fun AccountMessage(state: AccountUiState) {
  state.message?.let {
    Surface(
      color = if (state.messageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.mineGColors.successContainer,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        it,
        color = if (state.messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.mineGColors.onSuccessContainer,
        modifier = Modifier.padding(14.dp),
      )
    }
  }
}
