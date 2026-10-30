package app.tropa.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.tropa.R

/**
 * Цвет несёт смысл: голубой — трафик идёт через VPN, охра — напрямую.
 * Фон тёмный петролевый, а не почти-чёрный.
 */
object Palette {
    val bg = Color(0xFF152027)
    val s1 = Color(0xFF1C2A32)
    val s2 = Color(0xFF243641)
    val line = Color(0xFF30475A)
    val text = Color(0xFFECF1EE)
    val muted = Color(0xFF9DB0B8)
    val vpn = Color(0xFF8CC8FF)
    val vpnInk = Color(0xFF0E2333)
    val direct = Color(0xFFE5B567)
    val directInk = Color(0xFF2B1E06)
    val danger = Color(0xFFE58A7E)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun onest(weight: Int) = Font(
    R.font.onest,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Onest = FontFamily(onest(400), onest(500), onest(600), onest(700))

private fun style(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = Onest,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
    fontFeatureSettings = "tnum",
)

/** Шкала шрифтов: 12 / 13 / 15 / 16 / 20 / 26 / 32. */
object Type {
    val hero = style(32, 700, 38, -0.6)
    val title = style(26, 700, 32, -0.4)
    val heading = style(20, 600, 26)
    val body = style(16, 500, 22)
    val bodyStrong = style(16, 600, 22)
    val small = style(15, 400, 21)
    val caption = style(13, 400, 18)
    val captionStrong = style(13, 600, 18)
    val tiny = style(12, 600, 16)
}

@Composable
fun TropaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.vpn,
            onPrimary = Palette.vpnInk,
            secondary = Palette.direct,
            onSecondary = Palette.directInk,
            background = Palette.bg,
            onBackground = Palette.text,
            surface = Palette.bg,
            onSurface = Palette.text,
            surfaceVariant = Palette.s1,
            onSurfaceVariant = Palette.muted,
            surfaceContainer = Palette.s1,
            surfaceContainerHigh = Palette.s2,
            surfaceContainerHighest = Palette.s2,
            outline = Palette.line,
            outlineVariant = Palette.line,
            error = Palette.danger,
        ),
        typography = Typography(
            bodyLarge = Type.body,
            bodyMedium = Type.small,
            bodySmall = Type.caption,
            titleLarge = Type.heading,
            titleMedium = Type.bodyStrong,
            labelLarge = Type.bodyStrong,
            labelMedium = Type.captionStrong,
        ),
    ) {
        // Без Surface текст по умолчанию чёрный: задаём светлый цвет на всё приложение.
        CompositionLocalProvider(LocalContentColor provides Palette.text, content = content)
    }
}
