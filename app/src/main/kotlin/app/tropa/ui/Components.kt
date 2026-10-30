package app.tropa.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------- значки: контурные, толщина 1.8, как в прототипе ----------

object Icons {
    private fun stroke(vararg paths: String, width: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val home = stroke("M4 11 12 4l8 7v9h-5v-6H9v6H4z")
    /** Серверы: два скруглённых блока, у каждого огонёк. */
    val servers: ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        listOf(
            "M6 4h12a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M6 13h12a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        ).forEach {
            addPath(
                PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round,
            )
        }
        // Огоньки — залитые точки, а не штрихи: так они читаются даже в 20 dp.
        addPath(
            PathParser().parsePathString("M8 6.1a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8zM8 15.1a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8z").toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
    val fork = stroke("M4 12h5c3 0 3-6 6-6h5M9 12c3 0 3 6 6 6h5")
    val sliders = stroke("M5 7h14M5 12h14M5 17h14", "M9 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M15 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z")
    val power = stroke("M12 3v8", "M6.3 7.2a8 8 0 1 0 11.4 0", width = 2.2f)
    val chevron = stroke("m9 6 6 6-6 6", width = 2f)
    val shield = stroke("M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6z", width = 2f)
    val arrow = stroke("M5 12h14M13 6l6 6-6 6", width = 2f)
    val plus = stroke("M12 5v14M5 12h14", width = 2f)
    val refresh = stroke("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val trash = stroke("M5 7h14", "M10 7V5h4v2", "M7 7l1 13h8l1-13")
    val paste = stroke("M9 4h6v3H9z", "M8 5H6v15h12V5h-2")
    val message = stroke("M4 5h16v11H8l-4 4z")
    val search = stroke("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4")
    val close = stroke("M6 6l12 12M18 6 6 18", width = 2f)
    val log = stroke("M6 4h12v16H6z", "M9 9h6M9 13h6M9 17h3")
    val lock = stroke("M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
}

@Composable
fun Ico(v: ImageVector, tint: Color = Palette.text, size: Dp = 24.dp, desc: String? = null) =
    Icon(v, contentDescription = desc, tint = tint, modifier = Modifier.size(size))

// ---------- карточки и мелочи ----------

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = Palette.s1, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun Pill(text: String, color: Color = Palette.vpn) {
    Text(
        text,
        style = Type.tiny,
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.caption, color = Palette.muted, modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp))
}

/** Три полоски качества: по задержке, без цифр-загадок. */
@Composable
fun QualityBars(delay: Int) {
    val level = when {
        delay <= 0 -> 0
        delay < 80 -> 3
        delay < 160 -> 2
        else -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(width = 10.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < level) Palette.vpn else Palette.s2)
            )
        }
    }
}

fun delayText(d: Int?): String = when {
    d == null -> ""
    d < 0 -> "UDP"
    d == 0 -> "—"
    else -> "$d мс"
}

// ---------- флаги ----------

private data class FlagSpec(val colors: List<Color>, val vertical: Boolean = false, val cross: Color? = null)

private val flags: Map<String, FlagSpec> = mapOf(
    "FI" to FlagSpec(listOf(Color(0xFFF4F6F8)), cross = Color(0xFF2F5BAA)),
    "SE" to FlagSpec(listOf(Color(0xFF2E68B0)), cross = Color(0xFFF2C230)),
    "NO" to FlagSpec(listOf(Color(0xFFC8303A)), cross = Color(0xFF233F7D)),
    "DK" to FlagSpec(listOf(Color(0xFFC8303A)), cross = Color(0xFFF4F6F8)),
    "NL" to FlagSpec(listOf(Color(0xFFB23A3A), Color(0xFFF4F6F8), Color(0xFF2B4A93))),
    "DE" to FlagSpec(listOf(Color(0xFF1E1E1E), Color(0xFFC93333), Color(0xFFE8B828))),
    "LV" to FlagSpec(listOf(Color(0xFF8E2C34), Color(0xFF8E2C34), Color(0xFFF4F6F8), Color(0xFF8E2C34), Color(0xFF8E2C34))),
    "LT" to FlagSpec(listOf(Color(0xFFE3B52C), Color(0xFF2E7B47), Color(0xFFB83333))),
    "EE" to FlagSpec(listOf(Color(0xFF3473B8), Color(0xFF1E1E1E), Color(0xFFF4F6F8))),
    "PL" to FlagSpec(listOf(Color(0xFFF4F6F8), Color(0xFFCC3344))),
    "RU" to FlagSpec(listOf(Color(0xFFF4F6F8), Color(0xFF2E55A4), Color(0xFFC8303A))),
    "AT" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFFF4F6F8), Color(0xFFC8303A))),
    "HU" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFFF4F6F8), Color(0xFF3B7A47))),
    "BG" to FlagSpec(listOf(Color(0xFFF4F6F8), Color(0xFF2E8A5A), Color(0xFFC8303A))),
    "AM" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFF2E55A4), Color(0xFFE8A628))),
    "FR" to FlagSpec(listOf(Color(0xFF2B4A93), Color(0xFFF4F6F8), Color(0xFFC8303A)), vertical = true),
    "IT" to FlagSpec(listOf(Color(0xFF2E8A4C), Color(0xFFF4F6F8), Color(0xFFC8303A)), vertical = true),
    "BE" to FlagSpec(listOf(Color(0xFF1E1E1E), Color(0xFFE8C028), Color(0xFFC8303A)), vertical = true),
    "RO" to FlagSpec(listOf(Color(0xFF2B4A93), Color(0xFFE8C028), Color(0xFFC8303A)), vertical = true),
    "MD" to FlagSpec(listOf(Color(0xFF2B4A93), Color(0xFFE8C028), Color(0xFFC8303A)), vertical = true),
    "IE" to FlagSpec(listOf(Color(0xFF2E8A4C), Color(0xFFF4F6F8), Color(0xFFE88A38)), vertical = true),
    "UA" to FlagSpec(listOf(Color(0xFF2E68B0), Color(0xFFF2C230))),
    "CH" to FlagSpec(listOf(Color(0xFFC8303A)), cross = Color(0xFFF4F6F8)),
    "JP" to FlagSpec(listOf(Color(0xFFF4F6F8))),
    "TR" to FlagSpec(listOf(Color(0xFFC8303A))),
    "SG" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFFF4F6F8))),
    "ID" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFFF4F6F8))),
    "AE" to FlagSpec(listOf(Color(0xFF2E8A4C), Color(0xFFF4F6F8), Color(0xFF1E1E1E))),
    "KZ" to FlagSpec(listOf(Color(0xFF3AA8C8))),
    "ES" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFFE8C028), Color(0xFFE8C028), Color(0xFFC8303A))),
    "US" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFFF4F6F8), Color(0xFFC8303A), Color(0xFFF4F6F8), Color(0xFF2B4A93))),
    "GB" to FlagSpec(listOf(Color(0xFF233F7D)), cross = Color(0xFFC8303A)),
    "CZ" to FlagSpec(listOf(Color(0xFFF4F6F8), Color(0xFFC8303A))),
    "HK" to FlagSpec(listOf(Color(0xFFC8303A))),
    "CA" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFFF4F6F8), Color(0xFFC8303A)), vertical = true),
    "EU" to FlagSpec(listOf(Color(0xFF2B4A93))),
    "GE" to FlagSpec(listOf(Color(0xFFF4F6F8)), cross = Color(0xFFC8303A)),
    "RS" to FlagSpec(listOf(Color(0xFFC8303A), Color(0xFF2B4A93), Color(0xFFF4F6F8))),
)

/** Упрощённый круглый флаг. Неизвестная страна — кружок с кодом. */
@Composable
fun Flag(code: String?, size: Dp = 30.dp) {
    val spec = code?.let { flags[it] }
    if (spec == null) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Palette.s2),
            contentAlignment = Alignment.Center,
        ) {
            Text(code ?: "", style = Type.tiny, color = Palette.muted)
        }
        return
    }
    Canvas(Modifier.size(size).clip(CircleShape)) {
        val n = spec.colors.size
        spec.colors.forEachIndexed { i, c ->
            if (spec.vertical) {
                drawRect(c, topLeft = Offset(this.size.width * i / n, 0f), size = this.size.copy(width = this.size.width / n + 1))
            } else {
                drawRect(c, topLeft = Offset(0f, this.size.height * i / n), size = this.size.copy(height = this.size.height / n + 1))
            }
        }
        spec.cross?.let { c ->
            val w = this.size.width
            val h = this.size.height
            val nordic = code != "CH" && code != "GB" && code != "GE"
            val cx = if (nordic) w * 0.40f else w * 0.5f
            val t = w * 0.16f
            drawRect(c, topLeft = Offset(cx - t / 2, 0f), size = this.size.copy(width = t))
            drawRect(c, topLeft = Offset(0f, h / 2 - t / 2), size = this.size.copy(height = t))
        }
        if (code == "JP") drawCircle(Color(0xFFC8303A), radius = this.size.minDimension * 0.22f)
        if (code == "TR") drawCircle(Color(0xFFF4F6F8), radius = this.size.minDimension * 0.18f)
    }
}

// ---------- полоса подключения ----------

/**
 * Полоса внизу экрана: нажатие в любом месте или свайп кружка вправо.
 * Для TalkBack это обычный переключатель.
 */
@Composable
fun ConnectBar(on: Boolean, busy: Boolean, label: String, onToggle: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumb = 52.dp
    val bg by animateColorAsState(if (on) Palette.vpn.copy(alpha = 0.18f).compositeOver(Palette.s1) else Palette.s1, tween(350), label = "bg")
    val thumbBg by animateColorAsState(if (on) Palette.vpn else Palette.s2, tween(350), label = "thumb")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(1f, if (busy) 1.12f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(CircleShape)
            .background(bg)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(on)
                contentDescription = "Подключение"
                stateDescription = label
            }
    ) {
        val maxPx = with(density) { (maxWidth - thumb - 16.dp).toPx() }
        val x = remember { Animatable(if (on) maxPx else 0f) }
        LaunchedEffect(on, maxPx) { x.animateTo(if (on) maxPx else 0f, tween(350)) }

        Box(
            Modifier
                .matchParentSize()
                .clickable(onClickLabel = if (on) "Отключить" else "Подключить") {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(!on)
                }
                .pointerInput(on, maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = x.value > maxPx / 2
                            scope.launch { x.animateTo(if (target) maxPx else 0f, tween(200)) }
                            if (target != on) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggle(target)
                            }
                        },
                        onDragCancel = { scope.launch { x.animateTo(if (on) maxPx else 0f) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { x.snapTo((x.value + dx).coerceIn(0f, maxPx)) }
                    }
                }
        )
        Text(
            label,
            style = Type.bodyStrong,
            color = if (on) Palette.text else Palette.muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = if (on) 16.dp else 64.dp, end = if (on) 64.dp else 16.dp),
        )
        Box(
            Modifier
                .padding(start = 8.dp)
                .align(Alignment.CenterStart)
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .size(thumb * pulseScale)
                .clip(CircleShape)
                .background(thumbBg),
            contentAlignment = Alignment.Center,
        ) {
            Ico(Icons.power, tint = if (on) Palette.vpnInk else Palette.text)
        }
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

// ---------- развилка маршрута ----------

/**
 * Главный образ приложения: от телефона линия расходится на «через VPN»
 * и «напрямую». Когда VPN включён, по линиям бежит пунктир.
 */
@Composable
fun RouteFork(on: Boolean, vpnTitle: String, vpnSub: String, directTitle: String, directSub: String) {
    val flow = rememberInfiniteTransition(label = "flow")
    val phase by flow.animateFloat(0f, -36f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Box(Modifier.fillMaxWidth().height(172.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val y0 = size.height / 2
            val yTop = 38.dp.toPx()
            val yBot = size.height - 38.dp.toPx()
            val nodeX = 12.dp.toPx()
            val splitX = 64.dp.toPx()
            val curveEnd = 120.dp.toPx()
            val endX = w - 8.dp.toPx()
            val stroke = 3.dp.toPx()
            drawLine(Palette.muted, Offset(nodeX, y0), Offset(splitX, y0), stroke, StrokeCap.Round)
            fun branch(y: Float) = Path().apply {
                moveTo(splitX, y0)
                cubicTo((splitX + curveEnd) / 2, y0, (splitX + curveEnd) / 2, y, curveEnd, y)
                lineTo(endX - 6.dp.toPx(), y)
            }
            val effect = if (on) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()), phase.dp.toPx() / 2)
            else PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()))
            drawPath(branch(yTop), if (on) Palette.vpn else Palette.line, style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = effect))
            drawPath(branch(yBot), if (on) Palette.direct else Palette.line, style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = effect))
            drawCircle(Palette.bg, 8.dp.toPx(), Offset(nodeX, y0))
            drawCircle(Palette.muted, 8.dp.toPx(), Offset(nodeX, y0), style = Stroke(stroke))
            drawCircle(if (on) Palette.vpn else Palette.line, 6.dp.toPx(), Offset(endX, yTop))
            drawCircle(if (on) Palette.direct else Palette.line, 6.dp.toPx(), Offset(endX, yBot))
        }
        // Линии идут на 38 dp и 134 dp: заголовок над линией, пояснение под ней.
        @Composable
        fun label(text: String, top: Dp, strong: Boolean) = Text(
            text,
            style = if (strong) Type.bodyStrong else Type.caption,
            color = if (strong) Palette.text else Palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 126.dp, end = 18.dp).offset(y = top),
        )
        label(vpnTitle, 8.dp, true)
        label(vpnSub, 47.dp, false)
        label(directTitle, 104.dp, true)
        label(directSub, 143.dp, false)
        Text("Телефон", style = Type.caption, color = Palette.muted, modifier = Modifier.align(Alignment.CenterStart).padding(top = 40.dp))
    }
}

@Composable
fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Type.captionStrong.copy(fontSize = Type.small.fontSize),
        color = if (selected) Palette.bg else Palette.text,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Palette.text else Palette.s1)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun PrimaryButton(text: String, color: Color = Palette.vpn, ink: Color = Palette.vpnInk, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.bodyStrong, color = ink)
    }
}

@Composable
fun IconButtonBox(icon: ImageVector, desc: String, tint: Color = Palette.text, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.s1)
            .clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(icon, tint = tint, size = 20.dp, desc = desc)
    }
}

@Composable
fun Spacer(h: Dp) = Box(Modifier.height(h).width(1.dp))
