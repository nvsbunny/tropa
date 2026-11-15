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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.layout.wrapContentWidth
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

/** Кривая «ease» из CSS: так движется выключатель в образце. */
val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** Резкая кривая для «щелчков»: части кнопки, перекат подписи. */
val Snap = CubicBezierEasing(0.83f, 0f, 0.17f, 1f)

// ---------- ползунок подключения ----------

/**
 * Выключатель VPN по образцу (Shoh2008, uiverse.io, MIT): серый ободок, вдавленная цветная полоса
 * и выпуклая светлая ручка. Надписи стоят на месте: «ВЫКЛ» справа на красном, «ВКЛ» слева на синем.
 * При нажатии ручка переезжает и закрывает одну надпись, открывая другую. Движение — 0,25 с, «ease».
 */
@Composable
fun ConnectSwitch(on: Boolean, busy: Boolean, onToggle: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Пока подключаемся — открытая надпись мягко мигает.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(busy) {
        if (busy) while (true) { pulse.animateTo(0.35f, tween(450)); pulse.animateTo(1f, tween(450)) }
        else pulse.animateTo(1f, tween(150))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Ободок: серая рамка, сверху темнее, снизу светлее — как у настоящей клавиши в корпусе.
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .shadow(2.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Brush.verticalGradient(if (Palette.dark) listOf(Color(0xFF0C0C0C), Color(0xFF1A1A19), Color(0xFF2E2D2B)) else listOf(Color(0xFF8E8E8E), Color(0xFFBDBDBD), Color(0xFFE2E2E2))))
                .padding(3.dp)
                .semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(on)
                    contentDescription = "VPN"
                    stateDescription = if (busy) "подключаемся" else if (on) "включён" else "выключен"
                }
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().clip(CircleShape)) {
                // Ручка — прежнего размера, полоса — во всю ширину экрана.
                val knobW = 129.dp
                val maxPx = with(density) { (maxWidth - knobW).toPx() }
                val knobPx = with(density) { knobW.toPx() }
                val x = remember { Animatable(if (on) maxPx else 0f) }
                LaunchedEffect(on, maxPx) { x.animateTo(if (on) maxPx else 0f, tween(250, easing = CssEase)) }

                Canvas(Modifier.matchParentSize()) {
                    // Граница синего и красного всегда под ручкой.
                    val seam = x.value + knobPx / 2
                    drawRect(Palette.vpnOn, size = Size(seam, size.height))
                    drawRect(Palette.vpnOff, topLeft = Offset(seam, 0f), size = Size(size.width - seam, size.height))
                    // Вдавленность: тень сверху, свет снизу, тёмный край по кругу.
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.42f), 0.3f to Color.Black.copy(alpha = 0.10f), 0.65f to Color.Transparent, 1f to Color.White.copy(alpha = 0.14f)))
                    drawRoundRect(Color.Black.copy(alpha = 0.30f), cornerRadius = CornerRadius(size.height / 2), style = Stroke(2.dp.toPx()))
                }
                // Надписи на месте, по центру своей цветной части; ручка их закрывает.
                val textStyle = Type.heading.copy(
                    letterSpacing = TextUnit(2f, TextUnitType.Sp),
                    shadow = Shadow(Color.Black.copy(alpha = 0.30f), Offset(0f, 2f), 4f),
                )
                // Каждая надпись видна только по свою сторону от ручки: ручка наезжает и закрывает её.
                Box(
                    Modifier.matchParentSize().drawWithContent { clipRect(right = x.value) { this@drawWithContent.drawContent() } }
                        .padding(end = knobW),
                    contentAlignment = Alignment.Center,
                ) { Text("ВКЛ", style = textStyle, color = Color.White, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = pulse.value }) }
                Box(
                    Modifier.matchParentSize().drawWithContent { clipRect(left = x.value + knobPx) { this@drawWithContent.drawContent() } }
                        .padding(start = knobW),
                    contentAlignment = Alignment.Center,
                ) { Text("ВЫКЛ", style = textStyle, color = Color.White, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = pulse.value }) }
                // Ручка: выпуклая, во всю высоту полосы, с бликом сверху и тенью на цветную часть.
                Box(
                    Modifier
                        .offset { IntOffset(x.value.roundToInt(), 0) }
                        .width(knobW)
                        .fillMaxHeight()
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        // В тёмной теме ручка графитовая — того же цвета, что карточка в светлой.
                        .background(Brush.verticalGradient(if (Palette.dark) listOf(Color(0xFF4A4945), Color(0xFF353431), Color(0xFF232323)) else listOf(Color(0xFFFDFDFD), Color(0xFFEFEFEF), Color(0xFFD2D2D2))))
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(if (Palette.dark) Color.Black.copy(alpha = 0.6f) else Color(0xFF9A9A9A).copy(alpha = 0.55f), cornerRadius = r, style = Stroke(1.dp.toPx()))
                        val i = 3.dp.toPx()
                        drawRoundRect(
                            Brush.verticalGradient(0f to Color.White.copy(alpha = if (Palette.dark) 0.28f else 1f), 0.5f to Color.White.copy(alpha = 0f)),
                            topLeft = Offset(i, i), size = Size(size.width - 2 * i, size.height - 2 * i),
                            cornerRadius = CornerRadius((size.height - 2 * i) / 2), style = Stroke(1.5.dp.toPx()),
                        )
                        drawRoundRect(Brush.verticalGradient(0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.18f)), cornerRadius = r, style = Stroke(2.dp.toPx()))
                        // Тени по краям: закруглённые концы ручки темнее, середина светлая — ручка выглядит объёмной.
                        drawRoundRect(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.16f), 0.2f to Color.Transparent,
                                0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f),
                            ),
                            cornerRadius = r,
                        )
                    }
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
                                    scope.launch { x.animateTo(if (target) maxPx else 0f, tween(250, easing = CssEase)) }
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
            }
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
            .height(60.dp)
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
        // Под подпись место есть всегда: появляется она прозрачностью и сдвигом, высота меню не меняется.
        val shown by animateFloatAsState(if (selected) 1f else 0f, tween(260, easing = Settle), label = "label")
        Text(
            label, style = Type.tiny, color = tint, maxLines = 1,
            modifier = Modifier.padding(top = 3.dp).graphicsLayer { alpha = shown; translationY = (1f - shown) * 6f * density },
        )
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
