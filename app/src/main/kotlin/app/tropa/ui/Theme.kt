package app.tropa.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.tropa.R

/** Набор цветов одной темы. */
class Colors(
    val bg: Color, val s1: Color, val s2: Color, val line: Color, val text: Color, val muted: Color,
    val vpn: Color, val vpnInk: Color, val direct: Color, val directInk: Color, val danger: Color,
    val brand: Color, val card: Color, val cardText: Color, val cardMuted: Color, val cardChip: Color,
    val track: Color, val vpnOn: Color, val vpnOff: Color,
) {
    fun all() = listOf(bg, s1, s2, line, text, muted, vpn, vpnInk, direct, directInk, danger, brand, card, cardText, cardMuted, cardChip, track, vpnOn, vpnOff)
}

/** Светлая: слоновая кость и графит, как у Content Architecture; оранжевый — только выбор. */
val LightColors = Colors(
    bg = Color(0xFFF1EEE7), s1 = Color(0xFFE8E4DA), s2 = Color(0xFFDDD8CC), line = Color(0xFFD2CCBF),
    text = Color(0xFF232323), muted = Color(0xFF5B5A56),
    vpn = Color(0xFF2F6FDB), vpnInk = Color(0xFFFFFFFF), direct = Color(0xFFB7791F), directInk = Color(0xFFFFFFFF),
    danger = Color(0xFFD7392B), brand = Color(0xFFF26A1B),
    card = Color(0xFF232323), cardText = Color(0xFFF1EEE7), cardMuted = Color(0xFFA9A59C), cardChip = Color(0xFF3A3A3A),
    track = Color(0xFFD9D4C9), vpnOn = Color(0xFF4F8DF0), vpnOff = Color(0xFFF0584A),
)

/** Тёмная — зеркальная: фон графитовый, карточка цвета слоновой кости. */
val DarkColors = Colors(
    bg = Color(0xFF1F1F1E), s1 = Color(0xFF2A2927), s2 = Color(0xFF353431), line = Color(0xFF42403B),
    text = Color(0xFFF1EEE7), muted = Color(0xFFA9A59C),
    vpn = Color(0xFF7FA9F7), vpnInk = Color(0xFF0E1B33), direct = Color(0xFFE5B567), directInk = Color(0xFF2B1E06),
    danger = Color(0xFFF07A6E), brand = Color(0xFFFF7A2E),
    card = Color(0xFFF1EEE7), cardText = Color(0xFF232323), cardMuted = Color(0xFF5B5A56), cardChip = Color(0xFFDDD8CC),
    track = Color(0xFF3A3936), vpnOn = Color(0xFF4F8DF0), vpnOff = Color(0xFFF0584A),
)

/**
 * Цвета приложения. Тема переключается на лету: [dark] — состояние Compose, экраны перерисуются сами.
 * Синий — VPN включён, красный — выключен, охра — напрямую, оранжевый — выбор.
 */
object Palette {
    var dark by mutableStateOf(false)
    private val cur get() = if (dark) DarkColors else LightColors
    val bg: Color get() = cur.bg
    val s1: Color get() = cur.s1
    val s2: Color get() = cur.s2
    val line: Color get() = cur.line
    val text: Color get() = cur.text
    val muted: Color get() = cur.muted
    val vpn: Color get() = cur.vpn
    val vpnInk: Color get() = cur.vpnInk
    val direct: Color get() = cur.direct
    val directInk: Color get() = cur.directInk
    val danger: Color get() = cur.danger
    /** Фирменный оранжевый. */
    val brand: Color get() = cur.brand
    /** Карточка выхода на главной — контрастная к фону. */
    val card: Color get() = cur.card
    val cardText: Color get() = cur.cardText
    val cardMuted: Color get() = cur.cardMuted
    val cardChip: Color get() = cur.cardChip
    /** Выключатель: дорожка и цвета состояний. */
    val track: Color get() = cur.track
    val vpnOn: Color get() = cur.vpnOn
    val vpnOff: Color get() = cur.vpnOff

    /** Тот же цвет в светлой и в тёмной теме — для виджетов, которые рисует система днём и ночью. */
    fun day(c: Color): Color = LightColors.all().getOrNull(DarkColors.all().indexOf(c)) ?: c
    fun night(c: Color): Color = DarkColors.all().getOrNull(LightColors.all().indexOf(c)) ?: c
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
        colorScheme = if (Palette.dark) darkColorScheme(
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
        ) else lightColorScheme(
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
        // Цвет текста по умолчанию на всё приложение.
        CompositionLocalProvider(LocalContentColor provides Palette.text, content = content)
    }
}
