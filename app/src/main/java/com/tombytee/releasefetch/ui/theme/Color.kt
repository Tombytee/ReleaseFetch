package com.tombytee.releasefetch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light palette — crisp slate & cobalt developer utility
val LightPrimary = Color(0xFF1D4ED8)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDBEAFE)
val LightOnPrimaryContainer = Color(0xFF0F296D)

val LightSecondary = Color(0xFF475569)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE2E8F0)
val LightOnSecondaryContainer = Color(0xFF1E293B)

val LightTertiary = Color(0xFF059669)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFD1FAE5)
val LightOnTertiaryContainer = Color(0xFF064E3B)

val LightBackground = Color(0xFFF8FAFC)
val LightOnBackground = Color(0xFF0F172A)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF0F172A)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightOnSurfaceVariant = Color(0xFF475569)
val LightOutline = Color(0xFFCBD5E1)
val LightOutlineVariant = Color(0xFFE2E8F0)
val LightError = Color(0xFFDC2626)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFEE2E2)
val LightOnErrorContainer = Color(0xFF7F1D1D)

// Dark palette — deep slate obsidian with luminous cobalt & emerald accents
val DarkPrimary = Color(0xFF60A5FA)
val DarkOnPrimary = Color(0xFF0B1E4A)
val DarkPrimaryContainer = Color(0xFF1E3A8A)
val DarkOnPrimaryContainer = Color(0xFFDBEAFE)

val DarkSecondary = Color(0xFF94A3B8)
val DarkOnSecondary = Color(0xFF1E293B)
val DarkSecondaryContainer = Color(0xFF1E293B)
val DarkOnSecondaryContainer = Color(0xFFE2E8F0)

val DarkTertiary = Color(0xFF34D399)
val DarkOnTertiary = Color(0xFF022C22)
val DarkTertiaryContainer = Color(0xFF064E3B)
val DarkOnTertiaryContainer = Color(0xFFA7F3D0)

val DarkBackground = Color(0xFF0B1120)
val DarkOnBackground = Color(0xFFF1F5F9)
val DarkSurface = Color(0xFF111827)
val DarkOnSurface = Color(0xFFF1F5F9)
val DarkSurfaceVariant = Color(0xFF1E293B)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)
val DarkOutline = Color(0xFF334155)
val DarkOutlineVariant = Color(0xFF1E293B)
val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFF450A0A)
val DarkErrorContainer = Color(0xFF7F1D1D)
val DarkOnErrorContainer = Color(0xFFFECACA)

@Immutable
data class ReleaseSemanticColors(
    val recommendedAccent: Color,
    val recommendedContainer: Color,
    val onRecommendedContainer: Color,
    val warningAccent: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val androidBadge: Color,
    val windowsBadge: Color,
    val macosBadge: Color,
    val linuxBadge: Color,
    val iosBadge: Color,
    val otherBadge: Color,
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
)

val LightReleaseSemanticColors = ReleaseSemanticColors(
    recommendedAccent = Color(0xFF059669),
    recommendedContainer = Color(0xFFECFDF5),
    onRecommendedContainer = Color(0xFF065F46),
    warningAccent = Color(0xFFD97706),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    androidBadge = Color(0xFF10B981),
    windowsBadge = Color(0xFF2563EB),
    macosBadge = Color(0xFF475569),
    linuxBadge = Color(0xFFEA580C),
    iosBadge = Color(0xFF6366F1),
    otherBadge = Color(0xFF64748B),
    heroGradientStart = Color(0xFFEFF6FF),
    heroGradientEnd = Color(0xFFECFDF5),
)

val DarkReleaseSemanticColors = ReleaseSemanticColors(
    recommendedAccent = Color(0xFF34D399),
    recommendedContainer = Color(0xFF052E24),
    onRecommendedContainer = Color(0xFFA7F3D0),
    warningAccent = Color(0xFFFBBF24),
    warningContainer = Color(0xFF451A03),
    onWarningContainer = Color(0xFFFDE68A),
    androidBadge = Color(0xFF34D399),
    windowsBadge = Color(0xFF60A5FA),
    macosBadge = Color(0xFFCBD5E1),
    linuxBadge = Color(0xFFFB923C),
    iosBadge = Color(0xFFA5B4FC),
    otherBadge = Color(0xFF94A3B8),
    heroGradientStart = Color(0xFF111C33),
    heroGradientEnd = Color(0xFF082820),
)

val LocalReleaseColors = staticCompositionLocalOf { LightReleaseSemanticColors }
