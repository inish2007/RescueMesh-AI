package com.rescuemesh.ai.ui.theme

import androidx.compose.ui.graphics.Color

// --- Light Theme Palette (Professional Public-Safety System) ---
val LightBackground = Color(0xFFF7F9FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F4F8)
val LightBorder = Color(0xFFE4E9EF)
val LightPrimaryTeal = Color(0xFF0F766E)
val LightEmergencyRed = Color(0xFFB42318)
val LightActionBlue = Color(0xFF175CD3)
val LightWarningAmber = Color(0xFF9A6700)
val LightTextPrimary = Color(0xFF182230)
val LightTextSecondary = Color(0xFF526174)
val LightTextTertiary = Color(0xFF8B949E)

// --- Dark Theme Palette (Restrained, Calm, No Neon) ---
val DarkBackground = Color(0xFF101820)
val DarkSurface = Color(0xFF1A2630)
val DarkSurfaceVariant = Color(0xFF22313E)
val DarkBorder = Color(0xFF2D3B48)
val DarkPrimaryTeal = Color(0xFF56C5B5)
val DarkEmergencyRed = Color(0xFFF97066)
val DarkActionBlue = Color(0xFF53B1FD)
val DarkWarningAmber = Color(0xFFF79009)
val DarkTextPrimary = Color(0xFFF1F5F7)
val DarkTextSecondary = Color(0xFF94A3B8)
val DarkTextTertiary = Color(0xFF64748B)

// --- Backwards Compatible Semantic Aliases ---
val BackgroundDark = DarkBackground
val SurfaceDark = DarkSurface
val SurfaceVariant = DarkSurfaceVariant
val SurfaceElevated = Color(0xFF243442)
val SurfaceBorder = DarkBorder

val TacticalAmber = Color(0xFFFFB300)
val AmberPrimary = TacticalAmber
val AmberLight = Color(0xFFFFD54F)
val AmberDark = Color(0xFFFFA000)
val AmberContainer = Color(0xFFFFF8E1)

val GreenPassable = Color(0xFF56C5B5)
val GreenLight = Color(0xFF34D399)
val GreenContainer = Color(0xFFE6F7F3)

val RedBlocked = Color(0xFFF97066)
val RedLight = Color(0xFFFDA29B)
val RedContainer = Color(0xFFFEE4E2)

val OrangeHazard = Color(0xFFF79009)
val OrangeLight = Color(0xFFFEDF89)
val OrangeContainer = Color(0xFFFEF0C7)

val BlueInfo = LightActionBlue
val BlueLight = Color(0xFF84CAFF)
val BlueContainer = Color(0xFFE0F2FE)

val PurpleConflict = Color(0xFF7A5AF8)
val PurpleLight = Color(0xFFD6BBFB)
val PurpleContainer = Color(0xFFF4EBFF)

val GrayUnknown = Color(0xFF667085)
val GrayLight = Color(0xFFD0D5DD)

val TextPrimary = DarkTextPrimary
val TextSecondary = DarkTextSecondary
val TextTertiary = DarkTextTertiary

val MeshActive = Color(0xFF00BFA5)
val MeshInactive = Color(0xFFE53935)

val PrimaryAmber = TacticalAmber
val StatusOpen = GreenPassable
val StatusPassable = StatusOpen
val StatusClear = StatusOpen
val StatusBlocked = RedBlocked
val StatusHazard = OrangeHazard
val StatusConflicted = PurpleConflict
val StatusUnknown = GrayUnknown
