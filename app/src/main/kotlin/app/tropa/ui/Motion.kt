package app.tropa.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Одна кривая движения на всё приложение: быстрый старт, мягкая посадка. */
val Settle = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Резкая кривая для «щелчков»: части кнопки, перекат подписи. */
val Snap = CubicBezierEasing(0.83f, 0f, 0.17f, 1f)

// ---------- ползунок подключения ----------

/**
 * Длинный выключатель: вдавленная дорожка и светлая ручка с надписью «VPN включён / выключен».
 * Сбоку от ручки видно цвет состояния: синий — включён, красный — выключен; при перетаскивании
 * граница цветов едет вместе с ручкой. Нажатие в любом месте переключает, ручку можно и сдвинуть.
 */
@Composable
fun ConnectSwitch(on: Boolean, busy: Boolean, onToggle: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(CircleShape)
            .background(Palette.track)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(on)
                contentDescription = "VPN"
                stateDescription = if (on) "включён" else "выключен"
            }
    ) {
        val pad = 4.dp
        val knobW = maxWidth * 0.66f
        val maxPx = with(density) { (maxWidth - knobW - pad * 2).toPx() }
        val x = remember { Animatable(if (on) maxPx else 0f) }
        LaunchedEffect(on, maxPx) { x.animateTo(if (on) maxPx else 0f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
        val knobPx = with(density) { (knobW + pad * 2).toPx() }

        // Цвет состояния только на открытой части дорожки: слева от ручки синий, справа красный.
        // Под ручкой и в зазоре вокруг неё — сама дорожка, без каймы.
        Canvas(Modifier.matchParentSize()) {
            drawRect(Palette.vpnOn, size = Size(x.value, size.height))
            drawRect(Palette.vpnOff, topLeft = Offset(x.value + knobPx, 0f), size = Size((size.width - x.value - knobPx).coerceAtLeast(0f), size.height))
            // Вдавленность: тень сверху и блик снизу, как у настоящего выключателя.
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.45f to Color.Transparent, 1f to Color.White.copy(alpha = 0.18f)))
        }
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(on) {
                    detectTapGestures {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle(!on)
                    }
                }
                .pointerInput(on, maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = x.value > maxPx / 2
                            scope.launch { x.animateTo(if (target) maxPx else 0f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
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
        val lamp by animateColorAsState(if (on) Palette.vpnOn else Color(0xFFB9B5AB), tween(400), label = "lamp")
        val glow by animateFloatAsState(if (on || busy) 1f else 0f, tween(500), label = "glow")
        Row(
            Modifier
                .padding(pad)
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .width(knobW)
                .fillMaxHeight()
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFFCFBF8), Color(0xFFE9E5DC)))),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(9.dp).graphicsLayer { alpha = if (busy) 0.5f + 0.5f * glow else 1f }
                    .shadow((6 * glow).dp, CircleShape, ambientColor = lamp, spotColor = lamp)
                    .clip(CircleShape).background(lamp)
            )
            Text(
                when {
                    busy -> "Подключаемся…"
                    on -> "VPN включён"
                    else -> "VPN выключен"
                },
                // Ручка всегда светлая, как настоящая клавиша: текст тёмный в любой теме.
                style = Type.bodyStrong, color = Color(0xFF232323), modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

// ---------- нижнее меню ----------

/**
 * Пункт нижнего меню: у выбранного значок подпрыгивает, а под ним появляется подпись;
 * выбранный — фирменным оранжевым. [glyph] рисует значок с учётом цвета и выбора.
 */
@Composable
fun NavItem(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onCenter: (Offset) -> Unit,
    onClick: () -> Unit,
    glyph: @Composable (Color, Boolean) -> Unit,
) {
    val tint by animateColorAsState(if (selected) Palette.brand else Palette.muted, tween(250), label = "tint")
    val hop = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
            hop.animateTo(-7f, tween(140, easing = Settle))
            hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Column(
        modifier
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(16.dp))
            .semantics { this.selected = selected; contentDescription = label }
            .onGloballyPositioned { c -> onCenter(c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f)) }
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(26.dp).graphicsLayer { translationY = hop.value * density }, contentAlignment = Alignment.Center) {
            glyph(tint, selected)
        }
        AnimatedVisibility(
            selected,
            enter = fadeIn(tween(220)) + expandVertically(tween(260, easing = Settle)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
        ) {
            Text(label, style = Type.tiny, color = tint, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Обычный значок пункта меню. */
fun navIcon(icon: ImageVector): @Composable (Color, Boolean) -> Unit = { tint, _ -> Ico(icon, tint = tint, size = 22.dp) }

/**
 * Значок «Настройки»: три полоски, которые при выборе складываются в перевёрнутый «плей»
 * (треугольник остриём влево) — по мотивам переключателя vinodjangid07 с uiverse.io (MIT).
 */
@Composable
fun SettingsGlyph(tint: Color, selected: Boolean) {
    val p by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 380f), label = "morph")
    // Как в оригинале: полоски складываются в треугольник остриём вверх, а весь значок
    // поворачивается на четверть оборота — остриё смотрит влево.
    Canvas(Modifier.size(22.dp).graphicsLayer { rotationZ = -90f * p }) {
        val s = size.width / 24f
        fun lerp(a: Float, b: Float) = a + (b - a) * p
        // Полоски (3,6)-(21,6), (3,12)-(21,12), (3,18)-(21,18) → стороны (4,18)-(12,4), (12,4)-(20,18), основание (4,18)-(20,18).
        val bars = listOf(
            floatArrayOf(3f, 6f, 21f, 6f, 4f, 18f, 12f, 4f),
            floatArrayOf(3f, 12f, 21f, 12f, 12f, 4f, 20f, 18f),
            floatArrayOf(3f, 18f, 21f, 18f, 4f, 18f, 20f, 18f),
        )
        bars.forEach { b ->
            drawLine(
                tint,
                Offset(lerp(b[0], b[4]) * s, lerp(b[1], b[5]) * s),
                Offset(lerp(b[2], b[6]) * s, lerp(b[3], b[7]) * s),
                strokeWidth = 2.2f * s, cap = StrokeCap.Round,
            )
        }
    }
}

// ---------- текст и флаг из знаков ----------

private const val NOISE = "АБВГДЕЖЗИКЛМНОПРСТУФХ0123456789#%&@"

/**
 * Слово собирается из случайных знаков слева направо за [durationMs].
 * Играет, когда меняется [key] и [play] = true; иначе просто показывает текст.
 */
@Composable
fun ScrambleText(text: String, key: Any?, play: Boolean, style: TextStyle, color: Color, modifier: Modifier = Modifier, durationMs: Int = 1100) {
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(key, text, play) {
        if (!play) { shown = text; return@LaunchedEffect }
        val start = System.nanoTime()
        while (true) {
            val k = (System.nanoTime() - start) / 1e6f / durationMs
            if (k >= 1f) break
            shown = buildString {
                text.forEachIndexed { i, ch -> append(if (ch == ' ' || k * text.length > i) ch else NOISE.random()) }
            }
            delay(45)
        }
        shown = text
    }
    Text(shown, style = style, color = color, maxLines = 1, modifier = modifier)
}

/**
 * Флаг страны выхода, сложенный из знаков. Сетка знаков берёт цвет флага в своей точке:
 * на тёмном — плотные знаки, на светлом — лёгкие. Сначала знаки идут шумом и оседают во флаг,
 * флаг держится, чтобы его разглядеть, потом строки оседают вниз и гаснут.
 * [onDone] — когда флаг ушёл и карточка снова обычная.
 */
@Composable
fun SymbolFlag(code: String?, modifier: Modifier, onDone: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val t = remember(code) { Animatable(0f) }
    val total = 5200f // мс: сборка ~1,6 с, флаг держится ~2,4 с, оседание ~1,2 с
    LaunchedEffect(code) {
        t.snapTo(0f)
        t.animateTo(total, tween(total.toInt(), easing = LinearEasing))
        onDone()
    }
    val dense = "@#%&8BWMQ$"
    val airy = ".:-+=~^'"
    val glyphStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = Onest)
    val cache = remember { HashMap<Char, androidx.compose.ui.text.TextLayoutResult>() }
    fun layout(c: Char) = cache.getOrPut(c) { measurer.measure(c.toString(), glyphStyle) }
    val seed = remember(code) { kotlin.random.Random(code.hashCode()) }
    val settle = remember(code) { FloatArray(2000) { seed.nextFloat() * 1300f } }

    Canvas(modifier) {
        val ms = t.value
        val cw = 8.6.dp.toPx()
        val ch = 12.5.dp.toPx()
        val cols = (size.width / cw).toInt()
        val rows = (size.height / ch).toInt()
        val frame = (ms / 70f).toInt()
        val dropStart = 4000f
        for (r in 0 until rows) {
            // Оседание: строка за строкой сверху вниз уезжает вниз и гаснет.
            val dropK = ((ms - dropStart - r * 45f) / 520f).coerceIn(0f, 1f)
            if (dropK >= 1f) continue
            val dy = dropK * dropK * ch * 2.2f
            val rowAlpha = 1f - dropK
            for (cI in 0 until cols) {
                val i = r * cols + cI
                val fx = (cI + 0.5f) / cols
                val fy = (r + 0.5f) / rows
                val target = flagColorAt(code, fx, fy) ?: Color(0xFF8D897F)
                val done = ms > settle[i % settle.size] + 200f
                val noisy = !done && ms > settle[i % settle.size] - 260f
                val colr: Color
                val glyph: Char
                if (done) {
                    colr = target
                    val lum = target.red * 0.3f + target.green * 0.59f + target.blue * 0.11f
                    glyph = if (lum < 0.55f) dense[(i * 7) % dense.length] else airy[(i * 3) % airy.length]
                } else if (noisy) {
                    colr = Color(0xFF6B6A68)
                    glyph = NOISE[((i * 31 + frame * 17) and 0x7fffffff) % NOISE.length]
                } else continue
                drawText(layout(glyph), color = colr.copy(alpha = 0.78f * rowAlpha), topLeft = Offset(cI * cw, r * ch + dy))
            }
        }
    }
}

// ---------- переход кругом ----------

/** Круг из точки нажатия: новый экран раскрывается из пункта меню, который нажали. */
class CircleReveal(private val center: Offset, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply { addOval(Rect(center, radius)) })
}

fun revealRadius(center: Offset, size: Size): Float =
    listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        .maxOf { hypot(it.x - center.x, it.y - center.y) }
