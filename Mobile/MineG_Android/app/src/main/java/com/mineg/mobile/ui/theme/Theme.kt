package com.mineg.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
  primary = MineGColorTokens.BrandPrimary,
  onPrimary = MineGColorTokens.OnBrandPrimary,
  primaryContainer = MineGColorTokens.BrandPrimaryContainer,
  onPrimaryContainer = MineGColorTokens.OnBrandPrimaryContainer,
  secondary = MineGColorTokens.Success,
  onSecondary = Color.White,
  secondaryContainer = MineGColorTokens.SuccessContainer,
  onSecondaryContainer = MineGColorTokens.OnSuccessContainer,
  tertiary = MineGColorTokens.Warning,
  onTertiary = Color.White,
  tertiaryContainer = MineGColorTokens.WarningContainer,
  onTertiaryContainer = MineGColorTokens.OnWarningContainer,
  error = MineGColorTokens.Error,
  onError = Color.White,
  errorContainer = MineGColorTokens.ErrorContainer,
  onErrorContainer = MineGColorTokens.OnErrorContainer,
  background = MineGColorTokens.Background,
  onBackground = MineGColorTokens.TextPrimary,
  surface = MineGColorTokens.Surface,
  onSurface = MineGColorTokens.TextPrimary,
  surfaceVariant = MineGColorTokens.SurfaceContainer,
  onSurfaceVariant = MineGColorTokens.TextSecondary,
  outline = MineGColorTokens.Outline,
  outlineVariant = MineGColorTokens.Divider,
  scrim = Color.Black,
  inverseSurface = Color(0xFF34302B),
  inverseOnSurface = Color(0xFFF8EFE8),
  inversePrimary = Color(0xFFFFB59B),
  surfaceTint = MineGColorTokens.BrandPrimary,
  surfaceBright = MineGColorTokens.Background,
  surfaceDim = Color(0xFFE1D8D2),
  surfaceContainerLowest = MineGColorTokens.Surface,
  surfaceContainerLow = MineGColorTokens.SurfaceLow,
  surfaceContainer = MineGColorTokens.SurfaceContainer,
  surfaceContainerHigh = MineGColorTokens.SurfaceHigh,
  surfaceContainerHighest = Color(0xFFEAE1DA),
)

private val DarkColorScheme = darkColorScheme(
  primary = MineGColorTokens.DarkBrandGradientMiddle,
  onPrimary = Color(0xFF3B0B02),
  primaryContainer = Color(0xFF6B1A10),
  onPrimaryContainer = Color(0xFFFFE3DE),
  secondary = Color(0xFFABD0A9),
  onSecondary = Color(0xFF19331B),
  secondaryContainer = Color(0xFF2E4E30),
  onSecondaryContainer = Color(0xFFDCEBD9),
  tertiary = Color(0xFFF5BD72),
  onTertiary = Color(0xFF3A2500),
  tertiaryContainer = Color(0xFF663C00),
  onTertiaryContainer = MineGColorTokens.WarningContainer,
  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
  errorContainer = Color(0xFF93000A),
  onErrorContainer = MineGColorTokens.ErrorContainer,
  background = MineGColorTokens.DarkBackground,
  onBackground = MineGColorTokens.DarkTextPrimary,
  surface = MineGColorTokens.DarkSurface,
  onSurface = MineGColorTokens.DarkTextPrimary,
  surfaceVariant = MineGColorTokens.DarkSurfaceContainer,
  onSurfaceVariant = MineGColorTokens.DarkTextSecondary,
  outline = MineGColorTokens.DarkOutline,
  outlineVariant = MineGColorTokens.DarkDivider,
  scrim = Color.Black,
  inverseSurface = Color(0xFFFFEDE6),
  inverseOnSurface = Color(0xFF382E2A),
  inversePrimary = MineGColorTokens.BrandPrimaryAction,
  surfaceTint = MineGColorTokens.DarkBrandGradientMiddle,
  surfaceBright = Color(0xFF433732),
  surfaceDim = MineGColorTokens.DarkBackground,
  surfaceContainerLowest = Color(0xFF15100E),
  surfaceContainerLow = MineGColorTokens.DarkSurface,
  surfaceContainer = MineGColorTokens.DarkSurfaceContainer,
  surfaceContainerHigh = MineGColorTokens.DarkSurfaceHigh,
  surfaceContainerHighest = Color(0xFF463A35),
)

private val LightExtendedColors = MineGExtendedColors(
  brandGradient = listOf(
    MineGColorTokens.BrandGradientStart,
    MineGColorTokens.BrandGradientMiddle,
    MineGColorTokens.BrandGradientEnd,
  ),
  brandSelectionAlpha = 0.18f,
  brandPrimaryAction = MineGColorTokens.BrandPrimaryAction,
  brandPrimaryPressed = MineGColorTokens.BrandPrimaryPressed,
  onBrandPrimaryAction = MineGColorTokens.OnBrandPrimaryAction,
  success = MineGColorTokens.Success,
  successContainer = MineGColorTokens.SuccessContainer,
  onSuccessContainer = MineGColorTokens.OnSuccessContainer,
  warning = MineGColorTokens.Warning,
  warningContainer = MineGColorTokens.WarningContainer,
  onWarningContainer = MineGColorTokens.OnWarningContainer,
  info = MineGColorTokens.Info,
  infoContainer = MineGColorTokens.InfoContainer,
  onInfoContainer = MineGColorTokens.OnInfoContainer,
  iconInactive = MineGColorTokens.IconInactive,
  divider = MineGColorTokens.Divider,
)

private val DarkExtendedColors = LightExtendedColors.copy(
  brandGradient = listOf(
    MineGColorTokens.DarkBrandGradientStart,
    MineGColorTokens.DarkBrandGradientMiddle,
    MineGColorTokens.DarkBrandGradientEnd,
  ),
  brandSelectionAlpha = 0.22f,
  brandPrimaryAction = Color(0xFFFF8A73),
  brandPrimaryPressed = MineGColorTokens.DarkBrandGradientMiddle,
  onBrandPrimaryAction = Color(0xFF3B0B02),
  success = Color(0xFFABD0A9),
  successContainer = Color(0xFF2E4E30),
  onSuccessContainer = Color(0xFFDCEBD9),
  warning = Color(0xFFF5BD72),
  warningContainer = Color(0xFF663C00),
  onWarningContainer = MineGColorTokens.WarningContainer,
  info = Color(0xFFA4CAFA),
  infoContainer = Color(0xFF234A70),
  onInfoContainer = MineGColorTokens.InfoContainer,
  iconInactive = Color(0xFF9E918B),
  divider = MineGColorTokens.DarkDivider,
)

private val LocalMineGExtendedColors = staticCompositionLocalOf { LightExtendedColors }

val MaterialTheme.mineGColors: MineGExtendedColors
  @Composable
  @ReadOnlyComposable
  get() = LocalMineGExtendedColors.current

@Composable
fun mineGBrandGradient(alpha: Float = 1f): Brush {
  val colors = MaterialTheme.mineGColors.brandGradient
  return Brush.linearGradient(
    0f to colors[0].copy(alpha = alpha),
    0.52f to colors[1].copy(alpha = alpha),
    1f to colors[2].copy(alpha = alpha),
  )
}

@Composable
fun mineGNavigationSelectionGradient(): Brush =
  mineGBrandGradient(alpha = MaterialTheme.mineGColors.brandSelectionAlpha)

@Composable
fun MineGTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalMineGExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
  ) {
    MaterialTheme(
      colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
      content = content,
    )
  }
}
