/** Material theme composition backed by the MineG design-token mapping. */
package com.mineg.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
  surfaceVariant = Color(0xFFCDDFe9),
  onSurfaceVariant = MineGColorTokens.TextSecondary,
  outline = MineGColorTokens.Outline,
  outlineVariant = MineGColorTokens.Divider,
  scrim = Color.Black,
  inverseSurface = Color(0xFF26343F),
  inverseOnSurface = Color(0xFFEAF6FF),
  inversePrimary = Color(0xFFB5E4FF),
  surfaceTint = MineGColorTokens.BrandPrimary,
  surfaceBright = MineGColorTokens.Background,
  surfaceDim = MineGColorTokens.SurfaceHigh,
  surfaceContainerLowest = MineGColorTokens.Surface,
  surfaceContainerLow = MineGColorTokens.SurfaceLow,
  surfaceContainer = MineGColorTokens.SurfaceContainer,
  surfaceContainerHigh = MineGColorTokens.SurfaceHigh,
  surfaceContainerHighest = Color(0xFFCDDFe9),
)

private val DarkColorScheme = darkColorScheme(
  primary = MineGColorTokens.DarkBrandPrimary,
  onPrimary = Color(0xFF00344F),
  primaryContainer = Color(0xFF004B73),
  onPrimaryContainer = MineGColorTokens.BrandPrimaryContainer,
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
  inverseSurface = Color(0xFFEAF6FF),
  inverseOnSurface = Color(0xFF26343F),
  inversePrimary = MineGColorTokens.BrandPrimary,
  surfaceTint = MineGColorTokens.DarkBrandPrimary,
  surfaceBright = MineGColorTokens.DarkSurfaceHigh,
  surfaceDim = MineGColorTokens.DarkBackground,
  surfaceContainerLowest = Color(0xFF0B1116),
  surfaceContainerLow = MineGColorTokens.DarkSurface,
  surfaceContainer = MineGColorTokens.DarkSurfaceContainer,
  surfaceContainerHigh = MineGColorTokens.DarkSurfaceHigh,
  surfaceContainerHighest = MineGColorTokens.DarkSurfaceHigh,
)

private val LightExtendedColors = MineGExtendedColors(
  brandSelectionAlpha = 0.16f,
  brandPrimaryPressed = MineGColorTokens.BrandPrimaryPressed,
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
  brandSelectionAlpha = 0.22f,
  brandPrimaryPressed = Color(0xFF55B8F5),
  success = Color(0xFFABD0A9),
  successContainer = Color(0xFF2E4E30),
  onSuccessContainer = Color(0xFFDCEBD9),
  warning = Color(0xFFF5BD72),
  warningContainer = Color(0xFF663C00),
  onWarningContainer = MineGColorTokens.WarningContainer,
  info = Color(0xFFA4CAFA),
  infoContainer = Color(0xFF234A70),
  onInfoContainer = Color(0xFFD8E9FF),
  iconInactive = Color(0xFF91A6B5),
  divider = MineGColorTokens.DarkDivider,
)

private val LocalMineGExtendedColors = staticCompositionLocalOf { LightExtendedColors }

private val MineGTypography = Typography(
  displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 40.sp),
  headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 40.sp),
  headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
  headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
  titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
  titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 26.sp),
  titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
  bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
  bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
  bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
  labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
  labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
  labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

private val MineGShapes = Shapes(
  extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
  small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
  medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
  large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
  extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
)

val MaterialTheme.mineGColors: MineGExtendedColors
  @Composable @ReadOnlyComposable get() = LocalMineGExtendedColors.current

/** Returns the navigation selection color for the active theme. */
@Composable
fun mineGNavigationSelectionColor(): Color =
  MaterialTheme.colorScheme.primary.copy(alpha = MaterialTheme.mineGColors.brandSelectionAlpha)

/** Applies MineG colors, typography and shapes to [content]. */
@Composable
fun MineGTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalMineGExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors) {
    MaterialTheme(
      colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
      typography = MineGTypography,
      shapes = MineGShapes,
      content = content,
    )
  }
}
