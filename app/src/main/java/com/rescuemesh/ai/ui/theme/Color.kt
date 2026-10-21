package com.rescuemesh.ai.ui.theme

import androidx.compose.ui.graphics.Color

// --- Dark Surface Palette ---
val BackgroundDark = Color(0xFF0D1117)
val SurfaceDark = Color(0xFF161B22)
val SurfaceVariant = Color(0xFF21262D)
val SurfaceElevated = Color(0xFF2D333B)
val SurfaceBorder = Color(0xFF30363D)

// --- Amber Accent (Rescue Operations) ---
val AmberPrimary = Color(0xFFFFB300)
val AmberLight = Color(0xFFFFE54C)
val AmberDark = Color(0xFFC68400)
val AmberContainer = Color(0xFF3D2E00)

// --- Status Colors ---
val GreenPassable = Color(0xFF4CAF50)
val GreenLight = Color(0xFF81C784)
val GreenContainer = Color(0xFF1B3A1D)

val RedBlocked = Color(0xFFF44336)
val RedLight = Color(0xFFEF9A9A)
val RedContainer = Color(0xFF3D1414)

val OrangeHazard = Color(0xFFFF9800)
val OrangeLight = Color(0xFFFFCC80)
val OrangeContainer = Color(0xFF3D2800)

val BlueInfo = Color(0xFF42A5F5)
val BlueLight = Color(0xFF90CAF9)
val BlueContainer = Color(0xFF0D2B4A)

val PurpleConflict = Color(0xFFAB47BC)
val PurpleLight = Color(0xFFCE93D8)
val PurpleContainer = Color(0xFF2E1530)

val GrayUnknown = Color(0xFF9E9E9E)
val GrayLight = Color(0xFFBDBDBD)

// --- Text ---
val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)
val TextTertiary = Color(0xFF6E7681)

// --- Functional ---
val MeshActive = Color(0xFF00E676)
val MeshInactive = Color(0xFFFF5252)

// --- Aliases for UI Screens ---
val PrimaryAmber = AmberPrimary
val StatusOpen = GreenPassable
val StatusPassable = StatusOpen
val StatusClear = StatusOpen
val StatusBlocked = RedBlocked
val StatusHazard = OrangeHazard
val StatusConflicted = PurpleConflict
val StatusUnknown = GrayUnknown
