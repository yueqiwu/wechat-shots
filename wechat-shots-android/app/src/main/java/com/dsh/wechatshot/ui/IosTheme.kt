package com.dsh.wechatshot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * iOS 风格配色：使用 Apple 系统色系（System Blue / Green / Orange），
 * 刻意避开 Material 默认的紫/青主色。深浅色两套。
 */
@Immutable
data class IosColors(
    val dark: Boolean,
    val background: Color,
    val backgroundTop: Color,
    val card: Color,
    val cardBorder: Color,
    val fill: Color,
    val fillStrong: Color,
    val separator: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accent2: Color,
    val accentSoft: Color,
    val green: Color,
    val greenSoft: Color,
    val orange: Color,
    val orange2: Color,
    val orangeSoft: Color,
    val red: Color,
    val redSoft: Color,
    val tabBar: Color,
    val glassBorder: Color,
)

internal val LightIosColors = IosColors(
    dark = false,
    background = Color(0xFFF2F2F7),
    backgroundTop = Color(0xFFEAF1FF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0x0D000000),
    fill = Color(0x1F767680),
    fillStrong = Color(0x2E767680),
    separator = Color(0x293C3C43),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0x993C3C43),
    textTertiary = Color(0x663C3C43),
    accent = Color(0xFF007AFF),
    accent2 = Color(0xFF5AC8FA),
    accentSoft = Color(0x1A007AFF),
    green = Color(0xFF34C759),
    greenSoft = Color(0x1F34C759),
    orange = Color(0xFFFF9500),
    orange2 = Color(0xFFFF6B22),
    orangeSoft = Color(0x1FFF9500),
    red = Color(0xFFFF3B30),
    redSoft = Color(0x1FFF3B30),
    tabBar = Color(0xF2F9F9F9),
    glassBorder = Color(0x14000000),
)

internal val DarkIosColors = IosColors(
    dark = true,
    background = Color(0xFF000000),
    backgroundTop = Color(0xFF0A1220),
    card = Color(0xFF1C1C1E),
    cardBorder = Color(0x1AFFFFFF),
    fill = Color(0x2E767680),
    fillStrong = Color(0x3D767680),
    separator = Color(0x3D545458),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0x99EBEBF5),
    textTertiary = Color(0x66EBEBF5),
    accent = Color(0xFF0A84FF),
    accent2 = Color(0xFF64D2FF),
    accentSoft = Color(0x260A84FF),
    green = Color(0xFF30D158),
    greenSoft = Color(0x2630D158),
    orange = Color(0xFFFF9F0A),
    orange2 = Color(0xFFFF6B22),
    orangeSoft = Color(0x26FF9F0A),
    red = Color(0xFFFF453A),
    redSoft = Color(0x26FF453A),
    tabBar = Color(0xF21C1C1E),
    glassBorder = Color(0x1FFFFFFF),
)

val LocalIosColors = staticCompositionLocalOf { LightIosColors }

/** 统一的 iOS 字体阶梯（系统字体，负字距，符合 SF 的观感）。 */
object IosType {
    private val family = FontFamily.Default
    private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = spacing.sp,
    )

    val largeTitle = style(34, 41, FontWeight.Bold, 0.37)
    val navTitle = style(17, 22, FontWeight.SemiBold, -0.4)
    val cardTitle = style(19, 24, FontWeight.Bold, -0.4)
    val headline = style(17, 22, FontWeight.SemiBold, -0.4)
    val body = style(15, 21, FontWeight.Normal, -0.2)
    val callout = style(14, 20, FontWeight.Normal, -0.1)
    val footnote = style(13, 18, FontWeight.Normal, 0.0)
    val caption = style(11, 13, FontWeight.Normal, 0.1)
    val button = style(17, 22, FontWeight.SemiBold, -0.4)
}

private fun iosTypography(): Typography = Typography(
    headlineLarge = IosType.largeTitle,
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.35.sp,
    ),
    titleLarge = IosType.navTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = IosType.headline,
    titleSmall = IosType.body.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = IosType.headline,
    bodyMedium = IosType.body,
    bodySmall = IosType.footnote,
    labelLarge = IosType.body.copy(fontWeight = FontWeight.Medium),
    labelMedium = IosType.footnote.copy(fontWeight = FontWeight.Medium),
    labelSmall = IosType.caption,
)

@Composable
private fun IosColors.toMaterialColorScheme(): androidx.compose.material3.ColorScheme = if (dark) {
    darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = green,
        onSecondary = Color.White,
        secondaryContainer = greenSoft,
        onSecondaryContainer = green,
        tertiary = orange,
        onTertiary = Color.White,
        tertiaryContainer = orangeSoft,
        onTertiaryContainer = orange,
        background = background,
        onBackground = textPrimary,
        surface = card,
        onSurface = textPrimary,
        surfaceVariant = fill,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        inverseSurface = Color(0xFFE5E5EA),
        inverseOnSurface = Color(0xFF1C1C1E),
        error = red,
        onError = Color.White,
        errorContainer = redSoft,
        onErrorContainer = red,
        outline = separator,
        outlineVariant = separator,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = green,
        onSecondary = Color.White,
        secondaryContainer = greenSoft,
        onSecondaryContainer = green,
        tertiary = orange,
        onTertiary = Color.White,
        tertiaryContainer = orangeSoft,
        onTertiaryContainer = orange,
        background = background,
        onBackground = textPrimary,
        surface = card,
        onSurface = textPrimary,
        surfaceVariant = fill,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        inverseSurface = Color(0xFF1C1C1E),
        inverseOnSurface = Color.White,
        error = red,
        onError = Color.White,
        errorContainer = redSoft,
        onErrorContainer = red,
        outline = separator,
        outlineVariant = separator,
        scrim = Color.Black,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
fun IosAppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkIosColors else LightIosColors
    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
    CompositionLocalProvider(LocalIosColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            typography = iosTypography(),
            content = content,
        )
    }
}
