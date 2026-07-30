@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mineg.mobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGBrandGradient
import com.mineg.mobile.ui.theme.mineGColors

@Composable
fun LoginPage(
  state: AuthUiState,
  onPhoneChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onAgreementChange: (Boolean) -> Unit,
  onLogin: () -> Unit,
  onSignUp: () -> Unit,
  onLegal: (LegalDocument) -> Unit,
) {
  AuthPageFrame(title = "只属于家人的私人相册", subtitle = "照片先在设备端加密，再安全备份。", semanticId = "auth.login") {
    state.message?.let { InlineMessage(it, state.messageIsError) }
    OutlinedTextField(
      value = state.phone,
      onValueChange = onPhoneChange,
      modifier = Modifier.fillMaxWidth().testTag("auth.login.phone"),
      label = { Text("手机号") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      singleLine = true,
      isError = "phone" in state.fieldErrors,
      supportingText = { state.fieldErrors["phone"]?.let { Text(it) } },
      enabled = !state.loading,
    )
    OutlinedTextField(
      value = state.password,
      onValueChange = onPasswordChange,
      modifier = Modifier.fillMaxWidth().testTag("auth.login.password"),
      label = { Text("密码") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      visualTransformation = PasswordVisualTransformation(),
      singleLine = true,
      isError = "password" in state.fieldErrors,
      supportingText = { state.fieldErrors["password"]?.let { Text(it) } },
      enabled = !state.loading,
    )
    Row(
      Modifier.fillMaxWidth().clickable(enabled = !state.loading) { onAgreementChange(!state.agreementAccepted) }
        .testTag("auth.login.agreement"),
      verticalAlignment = Alignment.Top,
    ) {
      Checkbox(
        state.agreementAccepted,
        onCheckedChange = onAgreementChange,
        modifier = Modifier.testTag("auth.login.agreement.checkbox"),
        enabled = !state.loading,
      )
      Column(Modifier.padding(top = 11.dp)) {
        Text("我已阅读并同意", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
          Text("服务协议", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onLegal(LegalDocument.TERMS) })
          Text(" 与 ", color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("隐私政策", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onLegal(LegalDocument.PRIVACY) })
        }
        state.fieldErrors["agreement"]?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
      }
    }
    Button(
      onClick = onLogin,
      enabled = !state.loading,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth.login.submit"),
    ) {
      if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("登录")
    }
    TextButton(
      onClick = onSignUp,
      enabled = !state.loading,
      modifier = Modifier.align(Alignment.CenterHorizontally).testTag("auth.login.openSignup"),
    ) { Text("没有账号？立即注册") }
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.mineGColors.success, modifier = Modifier.size(17.dp))
      Text(" 端到端加密保护", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
  }
}

@Composable
fun SignUpPage(
  state: AuthUiState,
  onBack: () -> Unit,
  onPhoneChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onPasswordConfirmationChange: (String) -> Unit,
  onSubmit: () -> Unit,
) {
  AuthPageFrame(
    title = "创建家庭成员账号",
    subtitle = "提交后需要家庭管理员审核，审核通过前无法浏览媒体。",
    onBack = onBack,
    semanticId = "auth.signup",
  ) {
    OutlinedTextField(
      state.phone,
      onPhoneChange,
      Modifier.fillMaxWidth().testTag("auth.signup.phone"),
      label = { Text("手机号") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      isError = "phone" in state.fieldErrors,
      supportingText = { state.fieldErrors["phone"]?.let { Text(it) } },
      enabled = !state.loading,
    )
    OutlinedTextField(
      state.password,
      onPasswordChange,
      Modifier.fillMaxWidth().testTag("auth.signup.password"),
      label = { Text("密码") },
      visualTransformation = PasswordVisualTransformation(),
      isError = "password" in state.fieldErrors,
      supportingText = { state.fieldErrors["password"]?.let { Text(it) } },
      enabled = !state.loading,
    )
    OutlinedTextField(
      state.passwordConfirmation,
      onPasswordConfirmationChange,
      Modifier.fillMaxWidth().testTag("auth.signup.passwordConfirmation"),
      label = { Text("确认密码") },
      visualTransformation = PasswordVisualTransformation(),
      isError = "passwordConfirmation" in state.fieldErrors,
      supportingText = { state.fieldErrors["passwordConfirmation"]?.let { Text(it) } },
      enabled = !state.loading,
    )
    Button(
      onClick = onSubmit,
      enabled = !state.loading,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth.signup.submit"),
    ) {
      if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("提交注册申请")
    }
  }
}

@Composable
fun ReviewPendingPage(state: AuthUiState, onRefresh: () -> Unit, onBackToLogin: () -> Unit) {
  Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp).testTag("auth.reviewPending"),
    contentAlignment = Alignment.Center,
  ) {
    MineGCard(Modifier.fillMaxWidth()) {
      Column(
        Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        Box(
          Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          if (state.reviewSyncing) CircularProgressIndicator(Modifier.size(34.dp))
          else Text("…", fontWeight = FontWeight.Bold, fontSize = 34.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(if (state.reviewSyncing) "正在同步审核状态" else "申请审核中", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
          "管理员审核并完成家庭密钥授权后，你将进入私人空间。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 22.sp,
        )
        state.message?.let { InlineMessage(it, state.messageIsError) }
        Button(
          onClick = onRefresh,
          enabled = !state.reviewSyncing,
          modifier = Modifier.fillMaxWidth().testTag("auth.reviewPending.refresh"),
        ) { Text("刷新状态") }
        OutlinedButton(onClick = onBackToLogin, modifier = Modifier.fillMaxWidth()) { Text("重新登录") }
      }
    }
  }
}

@Composable
fun PermissionPage(message: String?, onGrant: () -> Unit, onDefer: () -> Unit) {
  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("permission.library")) {
    Column(
      Modifier.fillMaxWidth().blur(18.dp).padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      repeat(4) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          repeat(3) { column ->
            Box(
              Modifier.weight(1f).height(112.dp).clip(RoundedCornerShape(14.dp)).background(
                Brush.linearGradient(
                  listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    if ((row + column) % 2 == 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                  ),
                ),
              ),
            )
          }
        }
      }
    }
    Card(
      modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      shape = RoundedCornerShape(26.dp),
      elevation = CardDefaults.cardElevation(8.dp),
    ) {
      Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(mineGBrandGradient()), contentAlignment = Alignment.Center) {
          Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text("保护整个本地相册", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
          "MineG 需要完整的照片与视频访问权限，才能建立本地索引并自动备份。部分授权不会创建扫描或上传任务。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 22.sp,
        )
        message?.let { InlineMessage(it, true) }
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("继续授权") }
        TextButton(onClick = onDefer, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("暂不开启") }
      }
    }
  }
}

@Composable
fun LegalPage(document: LegalDocument, onBack: () -> Unit) {
  val privacy = document == LegalDocument.PRIVACY
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (privacy) "隐私政策" else "服务协议") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
      Text(if (privacy) "MineG 隐私政策" else "MineG 服务协议", fontSize = 26.sp, fontWeight = FontWeight.Bold)
      Text(
        if (privacy) {
          "MineG 仅处理账号准入、加密媒体元数据和实现服务所需的信息。原文件、缩略图和预览均在设备端加密，服务端与管理员无法读取媒体明文或私钥。"
        } else {
          "使用 MineG 即表示你同意仅上传本人有权处理的媒体，并妥善保管登录凭据。MVP 不提供密码找回；遗失密码可能导致加密内容无法恢复。"
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 25.sp,
      )
      Text("当前为产品页面占位文案，正式发布前需要接入完整法务版本。", color = MaterialTheme.mineGColors.warning)
      Spacer(Modifier.height(20.dp))
      Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
  }
}

@Composable
private fun AuthPageFrame(
  title: String,
  subtitle: String,
  onBack: (() -> Unit)? = null,
  semanticId: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  Box(
    Modifier.fillMaxSize().background(
      Brush.verticalGradient(
        listOf(
          MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
          MaterialTheme.colorScheme.background,
          MaterialTheme.colorScheme.surfaceContainerLow,
        ),
      ),
    ).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 34.dp).testTag(semanticId),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        MineGAssetImage(
          assetPath = "mineg_logo.png",
          contentDescription = "MineG Logo",
          modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)),
        )
        Text("  MineG", fontWeight = FontWeight.Bold, fontSize = 21.sp)
      }
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 29.sp)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
      }
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(4.dp),
      ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp), content = content)
      }
    }
  }
}

@Composable
fun RestoringPage(message: String? = null) {
  Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("auth.restoring"),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
      CircularProgressIndicator()
      Text(message ?: "正在恢复安全会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun InlineMessage(message: String, isError: Boolean) {
  Surface(
    color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    shape = RoundedCornerShape(12.dp),
  ) {
    Text(
      message,
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    )
  }
}
