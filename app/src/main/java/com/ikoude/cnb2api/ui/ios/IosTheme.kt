package com.ikoude.cnb2api.ui.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** iOS 系统色板：浅色/深色两套，尽量贴近 UIKit 语义色。 */
data class IosPalette(
    val isDark: Boolean,
    val bg: Color,
    val groupedBg: Color,
    val card: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val gray: Color,
    val gray4: Color,
)

private val LightPalette = IosPalette(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    groupedBg = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF8E8E93),
    tertiaryLabel = Color(0xFFC7C7CC),
    separator = Color(0xFFD1D1D6),
    fill = Color(0xFFE5E5EA),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    gray = Color(0xFF8E8E93),
    gray4 = Color(0xFFD1D1D6),
)

private val DarkPalette = IosPalette(
    isDark = true,
    bg = Color(0xFF000000),
    groupedBg = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF98989F),
    tertiaryLabel = Color(0xFF48484A),
    separator = Color(0xFF38383A),
    fill = Color(0xFF2C2C2E),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    gray = Color(0xFF8E8E93),
    gray4 = Color(0xFF3A3A3C),
)

val LocalIos = staticCompositionLocalOf { LightPalette }

/** 可选主题色（键 → 颜色）。 */
val IosAccentColors: Map<String, Color> = linkedMapOf(
    "blue" to Color(0xFF007AFF),
    "green" to Color(0xFF34C759),
    "purple" to Color(0xFFAF52DE),
    "orange" to Color(0xFFFF9500),
    "pink" to Color(0xFFFF2D55),
    "graphite" to Color(0xFF8E8E93),
)

/** 设置页展示用的「键 → 中文名」。 */
val IosAccentNames: List<Pair<String, String>> = listOf(
    "blue" to "蓝",
    "green" to "绿",
    "purple" to "紫",
    "orange" to "橙",
    "pink" to "粉",
    "graphite" to "石墨",
)


object IosText {
    fun largeTitle() = TextStyle(fontFamily = FontFamily.Default, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    fun title() = TextStyle(fontFamily = FontFamily.Default, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    fun headline() = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    fun body() = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, fontWeight = FontWeight.Normal)
    fun subheadline() = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    fun footnote() = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.5.sp, fontWeight = FontWeight.Normal)
    fun caption() = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, fontWeight = FontWeight.Normal)
    fun tab() = TextStyle(fontFamily = FontFamily.Default, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    fun mono() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
fun IosTheme(
    appearance: String = "system",
    accent: String = "blue",
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val base = if (dark) DarkPalette else LightPalette
    val accentColor = if (accent.startsWith("#")) {
        try { Color(android.graphics.Color.parseColor(accent)) } catch (e: Exception) { base.blue }
    } else {
        IosAccentColors[accent] ?: base.blue
    }
    val p = base.copy(blue = accentColor)
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.blue, background = p.bg, surface = p.card,
            onPrimary = Color.White, onBackground = p.label, onSurface = p.label,
            error = p.red, outline = p.separator, surfaceVariant = p.fill,
        )
    } else {
        lightColorScheme(
            primary = p.blue, background = p.bg, surface = p.card,
            onPrimary = Color.White, onBackground = p.label, onSurface = p.label,
            error = p.red, outline = p.separator, surfaceVariant = p.fill,
        )
    }
    CompositionLocalProvider(LocalIos provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
