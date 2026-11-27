package com.yaytif.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.yaytif.app.domain.MarketConfig

private val DarkColorScheme = darkColorScheme(
    primary = YaytifEmerald,
    onPrimary = Color(0xFF022C22),
    primaryContainer = YaytifEmeraldContainerDark,
    onPrimaryContainer = YaytifEmeraldContainerLight,
    secondary = YaytifEmeraldLight,
    onSecondary = Color(0xFF022C22),
    background = SlateBackgroundDark,
    onBackground = SlateOnSurfaceDark,
    surface = SlateSurfaceDark,
    onSurface = SlateOnSurfaceDark,
    surfaceVariant = SlateSurfaceVariantDark,
    onSurfaceVariant = SlateOnSurfaceVariantDark,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorContainerLight
)

private val LightColorScheme = lightColorScheme(
    primary = YaytifEmerald,
    onPrimary = Color.White,
    primaryContainer = YaytifEmeraldContainerLight,
    onPrimaryContainer = YaytifEmeraldContainerDark,
    secondary = YaytifEmeraldDark,
    onSecondary = Color.White,
    background = SlateBackgroundLight,
    onBackground = SlateOnSurfaceLight,
    surface = SlateSurfaceLight,
    onSurface = SlateOnSurfaceLight,
    surfaceVariant = SlateSurfaceVariantLight,
    onSurfaceVariant = SlateOnSurfaceVariantLight,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorContainerDark
)

@Composable
fun YaytifTheme(
    localeTag: String? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val systemDirection = LocalLayoutDirection.current
    val resolvedDirection = when {
        localeTag != null && MarketConfig.isRtlLocale(localeTag) -> LayoutDirection.Rtl
        localeTag != null -> LayoutDirection.Ltr
        else -> systemDirection
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalLayoutDirection provides resolvedDirection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YaytifTypography,
            content = content
        )
    }
}
