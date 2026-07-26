package com.mineg.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * MineG color tokens from Requirement/Prototype/Stitch/THEME.md.
 *
 * Keep brand colors separate from status colors: the warm orange-red palette
 * identifies MineG, while green, amber, red, and blue communicate state.
 */
object MineGColorTokens {
  val BrandGradientStart = Color(0xFFFD7106)
  val BrandGradientMiddle = Color(0xFFFD5033)
  val BrandGradientEnd = Color(0xFFFD374B)
  val BrandPrimary = BrandGradientMiddle
  val BrandPrimaryAction = Color(0xFFD63B26)
  val BrandPrimaryPressed = Color(0xFFB92E24)
  val BrandPrimaryContainer = Color(0xFFFFE3DE)
  val OnBrandPrimary = Color(0xFF1F1B17)
  val OnBrandPrimaryAction = Color.White
  val OnBrandPrimaryContainer = Color(0xFF6B1A10)

  val Background = Color(0xFFFFF8F4)
  val Surface = Color.White
  val SurfaceLow = Color(0xFFFBF2EB)
  val SurfaceContainer = Color(0xFFF5ECE5)
  val SurfaceHigh = Color(0xFFEEE3DC)
  val TextPrimary = Color(0xFF1F1B17)
  val TextSecondary = Color(0xFF6B6260)
  val IconInactive = Color(0xFFAAA4A3)
  val Outline = Color(0xFFB9AFAA)
  val Divider = Color(0xFFE8DDD7)

  val Success = Color(0xFF436444)
  val SuccessContainer = Color(0xFFDCEBD9)
  val OnSuccessContainer = Color(0xFF19331B)
  val Warning = Color(0xFF8A4F00)
  val WarningContainer = Color(0xFFFFE0B2)
  val OnWarningContainer = Color(0xFF3A2500)
  val Error = Color(0xFFBA1A1A)
  val ErrorContainer = Color(0xFFFFDAD6)
  val OnErrorContainer = Color(0xFF93000A)
  val Info = Color(0xFF3A5F86)
  val InfoContainer = Color(0xFFD8E9FF)
  val OnInfoContainer = Color(0xFF173B60)

  val DarkBackground = Color(0xFF1B1513)
  val DarkSurface = Color(0xFF241D1A)
  val DarkSurfaceContainer = Color(0xFF2F2622)
  val DarkSurfaceHigh = Color(0xFF3A302B)
  val DarkTextPrimary = Color(0xFFFFF5F0)
  val DarkTextSecondary = Color(0xFFD8C3B9)
  val DarkOutline = Color(0xFF8E7D75)
  val DarkDivider = Color(0xFF51443E)
  val DarkBrandGradientStart = Color(0xFFFF8A3D)
  val DarkBrandGradientMiddle = Color(0xFFFF6E58)
  val DarkBrandGradientEnd = Color(0xFFFF5B72)
}

@Immutable
data class MineGExtendedColors(
  val brandGradient: List<Color>,
  val brandSelectionAlpha: Float,
  val brandPrimaryAction: Color,
  val brandPrimaryPressed: Color,
  val onBrandPrimaryAction: Color,
  val success: Color,
  val successContainer: Color,
  val onSuccessContainer: Color,
  val warning: Color,
  val warningContainer: Color,
  val onWarningContainer: Color,
  val info: Color,
  val infoContainer: Color,
  val onInfoContainer: Color,
  val iconInactive: Color,
  val divider: Color,
)
