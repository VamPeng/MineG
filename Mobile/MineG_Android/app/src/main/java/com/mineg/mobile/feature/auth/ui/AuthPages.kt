@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

/** Authentication, registration, review-pending and legal-document Compose surfaces. */
package com.mineg.mobile.feature.auth.ui

import com.mineg.mobile.presentation.AuthUiState
import com.mineg.mobile.presentation.LegalDocument
import com.mineg.mobile.ui.component.MineGDrawableImage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineg.mobile.ui.theme.mineGColors

private val AuthBodyStyle = TextStyle(
  fontFamily = FontFamily.SansSerif,
  fontWeight = FontWeight.Normal,
  fontSize = 15.sp,
  lineHeight = 22.sp,
)

private val AuthLabelStyle = TextStyle(
  fontFamily = FontFamily.SansSerif,
  fontWeight = FontWeight.Medium,
  fontSize = 12.sp,
  lineHeight = 16.sp,
  letterSpacing = 0.5.sp,
)

private val AuthHeadlineStyle = TextStyle(
  fontFamily = FontFamily.SansSerif,
  fontWeight = FontWeight.SemiBold,
  fontSize = 20.sp,
  lineHeight = 28.sp,
)

/** Renders login fields, agreement state and submission feedback. */
@Composable
fun LoginPage(
  state: AuthUiState,
  onPhoneChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onLogin: () -> Unit,
  onSignUp: () -> Unit,
) {
  var passwordVisible by rememberSaveable { mutableStateOf(false) }
  val credentialsError = state.fieldErrors["password"]?.contains("手机号或密码错误") == true
  val networkUnavailable = state.messageIsError && state.message?.contains("网络") == true

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(start = 20.dp, top = 48.dp, end = 20.dp)
      .testTag("auth.login"),
    verticalArrangement = Arrangement.SpaceBetween,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.TopCenter) {
      AuthWordmark(Modifier.padding(top = 32.dp))
    }

    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (networkUnavailable) {
          AuthNetworkNotice()
        }
        AuthTextField(
          value = state.phone,
          onValueChange = onPhoneChange,
          placeholder = "请输入手机号",
          modifier = Modifier.fillMaxWidth().testTag("auth.login.phone"),
          keyboardType = KeyboardType.Phone,
          isError = credentialsError,
          errorBorderWidth = 1.dp,
          enabled = !state.loading && !networkUnavailable,
        )
        AuthTextField(
          value = state.password,
          onValueChange = onPasswordChange,
          placeholder = "请输入密码",
          modifier = Modifier.fillMaxWidth().testTag("auth.login.password"),
          keyboardType = KeyboardType.Password,
          password = true,
          passwordVisible = passwordVisible,
          onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
          isError = credentialsError,
          errorBorderWidth = 1.dp,
          enabled = !state.loading && !networkUnavailable,
        )
        if (credentialsError) {
          Text(
            text = "手机号或密码错误",
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.error,
            style = AuthLabelStyle,
          )
        }
      }

      AuthPrimaryButton(
        label = if (state.loading) "登录中…" else "登录",
        onClick = onLogin,
        enabled = !state.loading && !networkUnavailable,
        modifier = Modifier.fillMaxWidth().testTag("auth.login.submit"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("还没有账号？", color = MaterialTheme.colorScheme.onSurfaceVariant, style = AuthBodyStyle)
        Text(
          text = "立即注册",
          modifier = Modifier
            .clickable(enabled = !state.loading && !networkUnavailable, onClick = onSignUp)
            .testTag("auth.login.openSignup"),
          color = MaterialTheme.colorScheme.primary,
          style = AuthBodyStyle.copy(fontWeight = FontWeight.SemiBold),
        )
      }
    }
  }
}

/** Renders account creation and delegates field behavior to [SignUpForm]. */
@Composable
fun SignUpPage(
  state: AuthUiState,
  onBack: () -> Unit,
  onPhoneChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onPasswordConfirmationChange: (String) -> Unit,
  onSubmit: () -> Unit,
) {
  val phoneError = state.fieldErrors["phone"]?.let {
    if (it.contains("已注册")) "该手机号已注册" else "手机号格式不正确"
  }
  val confirmationError = state.fieldErrors["passwordConfirmation"]?.let { "两次密码不一致" }
  val duplicatePhone = phoneError == "该手机号已注册"
  val hasFieldError = phoneError != null || confirmationError != null
  val showSubmitFailure = state.messageIsError && state.message != null && !hasFieldError
  val centerForm = hasFieldError || showSubmitFailure
  var passwordVisible by rememberSaveable { mutableStateOf(false) }
  var failureVisible by rememberSaveable(state.message) { mutableStateOf(showSubmitFailure) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .testTag("auth.signup"),
  ) {
    Column(Modifier.fillMaxSize()) {
      AuthTopBar(onBack)
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 40.dp),
        contentAlignment = if (centerForm) Alignment.Center else Alignment.TopCenter,
      ) {
        SignUpForm(
          state = state,
          phoneError = phoneError,
          confirmationError = confirmationError,
          duplicatePhone = duplicatePhone,
          showTitle = hasFieldError,
          submissionFailure = showSubmitFailure,
          passwordVisible = passwordVisible,
          onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
          onPhoneChange = onPhoneChange,
          onPasswordChange = onPasswordChange,
          onPasswordConfirmationChange = onPasswordConfirmationChange,
          onSubmit = onSubmit,
        )
      }
    }
    if (failureVisible) {
      AuthSubmitFailureBanner(onDismiss = { failureVisible = false })
    }
  }
}

/** Renders validated sign-up fields and the primary registration action. */
@Composable
private fun SignUpForm(
  state: AuthUiState,
  phoneError: String?,
  confirmationError: String?,
  duplicatePhone: Boolean,
  showTitle: Boolean,
  submissionFailure: Boolean,
  passwordVisible: Boolean,
  onPasswordVisibilityChange: () -> Unit,
  onPhoneChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onPasswordConfirmationChange: (String) -> Unit,
  onSubmit: () -> Unit,
) {
  val denseFields = duplicatePhone || confirmationError != null || submissionFailure
  val fieldHeight = if (denseFields) 58.dp else 50.dp
  val submitHeight = if (denseFields) 60.dp else 54.dp
  val cardColor = if (submissionFailure || duplicatePhone) Color.White.copy(alpha = 0.70f) else MaterialTheme.colorScheme.surface
  val cardBorder = when {
    submissionFailure -> Color.White
    showTitle -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f)
  }
  val fieldContainer = if (showTitle || submissionFailure) {
    MaterialTheme.colorScheme.surfaceContainer
  } else {
    MaterialTheme.colorScheme.surfaceContainerLow
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = cardColor,
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 24.dp),
    ) {
      if (showTitle) {
        Text(
          text = "创建新账号",
          modifier = Modifier.fillMaxWidth(),
          color = MaterialTheme.colorScheme.onSurface,
          style = AuthHeadlineStyle,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (duplicatePhone) 24.dp else 40.dp))
      }
      SignUpField(
        label = "手机号",
        value = state.phone,
        onValueChange = onPhoneChange,
        placeholder = "请输入手机号码",
        modifier = Modifier.testTag("auth.signup.phone"),
        keyboardType = KeyboardType.Phone,
        containerColor = fieldContainer,
        height = fieldHeight,
        errorText = phoneError,
        errorBorderWidth = if (phoneError != null) 2.dp else 0.dp,
      )
      Spacer(Modifier.height(18.dp))
      SignUpField(
        label = "设置密码",
        value = state.password,
        onValueChange = onPasswordChange,
        placeholder = "建议8位以上数字或字母",
        modifier = Modifier.testTag("auth.signup.password"),
        keyboardType = KeyboardType.Password,
        password = true,
        passwordVisible = passwordVisible,
        onPasswordVisibilityChange = if (phoneError != null) onPasswordVisibilityChange else null,
        containerColor = fieldContainer,
        height = fieldHeight,
      )
      Spacer(Modifier.height(18.dp))
      SignUpField(
        label = "确认密码",
        value = state.passwordConfirmation,
        onValueChange = onPasswordConfirmationChange,
        placeholder = "请再次输入密码",
        modifier = Modifier.testTag("auth.signup.passwordConfirmation"),
        keyboardType = KeyboardType.Password,
        password = true,
        containerColor = fieldContainer,
        height = fieldHeight,
        errorText = confirmationError,
        errorBorderWidth = if (confirmationError != null) 2.dp else 0.dp,
        errorFill = confirmationError != null,
        labelIsError = confirmationError != null,
      )
      Spacer(Modifier.height(15.dp))
      Text(
        text = "注册后需要审核。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = AuthBodyStyle.copy(fontSize = 14.sp, lineHeight = 20.sp),
      )
      Spacer(Modifier.height(16.dp))
      AuthPrimaryButton(
        label = if (state.loading) "正在连接…" else "提交注册",
        onClick = onSubmit,
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth().testTag("auth.signup.submit"),
        height = submitHeight,
        shape = RoundedCornerShape(8.dp),
      )
    }
  }
}

/** Renders one labeled registration field with field-scoped error copy. */
@Composable
private fun SignUpField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier,
  keyboardType: KeyboardType,
  containerColor: Color,
  height: androidx.compose.ui.unit.Dp,
  password: Boolean = false,
  passwordVisible: Boolean = false,
  onPasswordVisibilityChange: (() -> Unit)? = null,
  errorText: String? = null,
  errorBorderWidth: androidx.compose.ui.unit.Dp = 0.dp,
  errorFill: Boolean = false,
  labelIsError: Boolean = false,
) {
  Column {
    Text(
      text = label,
      modifier = Modifier.padding(horizontal = 4.dp),
      color = if (labelIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      style = AuthLabelStyle,
    )
    Spacer(Modifier.height(8.dp))
    AuthTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = placeholder,
      modifier = modifier.fillMaxWidth(),
      keyboardType = keyboardType,
      password = password,
      passwordVisible = passwordVisible,
      onPasswordVisibilityChange = onPasswordVisibilityChange,
      containerColor = containerColor,
      height = height,
      shape = RoundedCornerShape(8.dp),
      isError = errorText != null,
      errorBorderWidth = errorBorderWidth,
      errorFill = errorFill,
    )
    errorText?.let {
      Spacer(Modifier.height(4.dp))
      Text(
        text = it,
        modifier = Modifier.padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.error,
        style = AuthLabelStyle,
      )
    }
  }
}

/** Renders approval-pending state with refresh and account-switch actions. */
@Composable
fun ReviewPendingPage(state: AuthUiState, onRefresh: () -> Unit, onBackToLogin: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .testTag("auth.reviewPending"),
  ) {
    AuthTopBar(onBackToLogin)
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 40.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("注册申请待审核", color = MaterialTheme.colorScheme.onSurface, style = AuthHeadlineStyle)
        Text(
          text = "审核通过后即可使用 MineG。",
          modifier = Modifier.padding(top = 8.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = AuthBodyStyle,
        )
        Column(
          modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          AuthPrimaryButton(
            label = if (state.reviewSyncing) "正在刷新…" else "刷新状态",
            onClick = onRefresh,
            enabled = !state.reviewSyncing,
            modifier = Modifier.fillMaxWidth().testTag("auth.reviewPending.refresh"),
          )
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(46.dp)
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onBackToLogin),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "退出当前账号",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = AuthBodyStyle.copy(fontWeight = FontWeight.Medium),
            )
          }
        }
      }
    }
  }
}

/** Explains device-library access and offers grant or deferred entry. */
@Composable
fun PermissionPage(message: String?, onGrant: () -> Unit, onDefer: () -> Unit) {
  Column(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 40.dp)
      .testTag("permission.library"),
    verticalArrangement = Arrangement.Center,
  ) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "允许 MineG 访问你的相册",
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
      )
      Text(
        "开启完整相册权限后，MineG 才能读取并备份照片和视频。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        textAlign = TextAlign.Center,
      )
    }
    Spacer(Modifier.height(40.dp))
    PermissionActionButton(
      label = "继续授权",
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      onClick = onGrant,
    )
    Spacer(Modifier.height(16.dp))
    PermissionActionButton(
      label = "暂不开启",
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      onClick = onDefer,
    )
  }
}

/** Renders one permission-screen action with the requested visual priority. */
@Composable
private fun PermissionActionButton(
  label: String,
  containerColor: Color,
  contentColor: Color,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    color = containerColor,
    shape = RoundedCornerShape(12.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, color = contentColor, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** Renders the selected terms or privacy document. */
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
          "MineG 处理账号准入、媒体元数据和实现服务所需的信息。媒体上传与加载不做客户端应用层加密，通过 HTTPS/TLS 传输并保存在私有云存储中；审核管理页面不提供媒体浏览能力，云资源高权限访问受审批和审计约束。"
        } else {
          "使用 MineG 即表示你同意仅上传本人有权处理的媒体，并妥善保管登录凭据。MVP 不提供密码找回；媒体通过公网 ECS 和私有云存储提供服务。"
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

/** Renders process/session restoration progress and optional recovery copy. */
@Composable
fun RestoringPage(message: String? = null) {
  val darkTheme = isSystemInDarkTheme()
  val startupBackground = if (darkTheme) MaterialTheme.colorScheme.background else Color.White
  Box(
    Modifier.fillMaxSize().background(startupBackground).testTag("auth.restoring"),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
      MineGDrawableImage(
        drawableRes = com.mineg.mobile.R.drawable.mineg_logo_blue,
        contentDescription = "MineG Logo",
        modifier = Modifier.width(240.dp).height(68.dp),
      )
      CircularProgressIndicator(Modifier.size(26.dp), color = Color(0xFF2D92F4), strokeWidth = 2.dp)
      Text(message ?: "正在恢复会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Renders the authentication wordmark. */
@Composable
private fun AuthWordmark(modifier: Modifier = Modifier) {
  Text(
    text = "MineG",
    modifier = modifier,
    color = MaterialTheme.colorScheme.primary,
    style = TextStyle(
      fontFamily = FontFamily.SansSerif,
      fontWeight = FontWeight.ExtraBold,
      fontSize = 24.sp,
      lineHeight = 32.sp,
    ),
  )
}

/** Renders a minimal authentication back bar. */
@Composable
private fun AuthTopBar(onBack: () -> Unit) {
  Box(Modifier.fillMaxWidth().height(72.dp)) {
    Box(
      modifier = Modifier
        .padding(start = 20.dp)
        .size(40.dp)
        .align(Alignment.CenterStart)
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Text("←", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp, lineHeight = 24.sp)
    }
    AuthWordmark(Modifier.align(Alignment.Center))
  }
}

/** Displays the authentication network/privacy notice. */
@Composable
private fun AuthNetworkNotice() {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    shape = RoundedCornerShape(12.dp),
  ) {
    Text(
      text = "网络连接不可用，请检查网络设置",
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = AuthLabelStyle,
    )
  }
}

/** Displays dismissible submission failure guidance. */
@Composable
private fun AuthSubmitFailureBanner(onDismiss: () -> Unit) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 8.dp),
    color = MaterialTheme.colorScheme.errorContainer,
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.10f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "网络提交失败，请重试",
        modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = AuthLabelStyle,
      )
      Text(
        text = "关闭",
        modifier = Modifier
          .clip(RoundedCornerShape(20.dp))
          .clickable(onClick = onDismiss)
          .padding(4.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = AuthLabelStyle,
      )
    }
  }
}

/** Renders the full-width primary authentication action. */
@Composable
private fun AuthPrimaryButton(
  label: String,
  onClick: () -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  height: androidx.compose.ui.unit.Dp = 56.dp,
  shape: RoundedCornerShape = RoundedCornerShape(12.dp),
) {
  Box(
    modifier = modifier
      .height(height)
      .clip(shape)
      .background(MaterialTheme.colorScheme.primary)
      .alpha(if (enabled) 1f else 0.50f)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      color = MaterialTheme.colorScheme.onPrimary,
      style = AuthHeadlineStyle,
    )
  }
}

/** Renders one common authentication text field and validation message. */
@Composable
private fun AuthTextField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier,
  keyboardType: KeyboardType,
  password: Boolean = false,
  passwordVisible: Boolean = false,
  onPasswordVisibilityChange: (() -> Unit)? = null,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  height: androidx.compose.ui.unit.Dp = 56.dp,
  shape: RoundedCornerShape = RoundedCornerShape(12.dp),
  isError: Boolean = false,
  errorBorderWidth: androidx.compose.ui.unit.Dp = 1.dp,
  errorFill: Boolean = false,
  enabled: Boolean = true,
) {
  var focused by remember { mutableStateOf(false) }
  val borderWidth = when {
    isError -> errorBorderWidth
    focused -> 2.dp
    else -> 0.dp
  }
  val borderColor = when {
    isError -> MaterialTheme.colorScheme.error
    focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    else -> Color.Transparent
  }
  val fill = if (isError && errorFill) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f) else containerColor
  val hasVisibilityAction = password && onPasswordVisibilityChange != null

  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier
      .height(height)
      .clip(shape)
      .background(fill)
      .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, borderColor, shape) else Modifier)
      .onFocusChanged { focused = it.isFocused },
    enabled = enabled,
    singleLine = true,
    textStyle = AuthBodyStyle.copy(color = MaterialTheme.colorScheme.onSurface),
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    decorationBox = { innerTextField ->
      Box(Modifier.fillMaxSize()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.CenterStart)
            .padding(start = 16.dp, end = if (hasVisibilityAction) 64.dp else 16.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (value.isEmpty()) {
            Text(
              text = placeholder,
              color = MaterialTheme.colorScheme.outline.copy(alpha = 0.60f),
              style = AuthBodyStyle,
            )
          }
          innerTextField()
        }
        if (hasVisibilityAction) {
          Text(
            text = if (passwordVisible) "隐藏" else "显示",
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onPasswordVisibilityChange)
              .padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outline,
            style = AuthLabelStyle,
          )
        }
      }
    },
  )
}

/** Displays compact success or error copy next to an authentication action. */
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
