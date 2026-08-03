package com.mineg.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Authoritative Android mapping of Requirement/Prototype/Stitch/THEME.md. */
object MineGColorTokens {
  val BrandPrimary = Color(0xFF3BAAFF)
  val BrandPrimaryPressed = Color(0xFF2CA4FF)
  val BrandPrimaryContainer = Color(0xFFD9F0FF)
  val OnBrandPrimary = Color.White
  val OnBrandPrimaryContainer = Color(0xFF004B73)

  val Background = Color(0xFFF7FBFF)
  val Surface = Color.White
  val SurfaceLow = Color(0xFFEEF6FB)
  val SurfaceContainer = Color(0xFFE5F0F7)
  val SurfaceHigh = Color(0xFFD7E5EF)
  val TextPrimary = Color(0xFF1B2730)
  val TextSecondary = Color(0xFF52616D)
  val IconInactive = Color(0xFF9AAAB6)
  val Outline = Color(0xFFB9CCD8)
  val Divider = Color(0xFFD7E5EF)

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

  val DarkBackground = Color(0xFF101820)
  val DarkSurface = Color(0xFF17222B)
  val DarkSurfaceContainer = Color(0xFF20303C)
  val DarkSurfaceHigh = Color(0xFF2A3D4B)
  val DarkTextPrimary = Color(0xFFEAF6FF)
  val DarkTextSecondary = Color(0xFFB8CAD6)
  val DarkOutline = Color(0xFF748896)
  val DarkDivider = Color(0xFF304653)
  val DarkBrandPrimary = Color(0xFF6BC4FF)
}

@Immutable
data class MineGExtendedColors(
  val brandSelectionAlpha: Float,
  val brandPrimaryPressed: Color,
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
