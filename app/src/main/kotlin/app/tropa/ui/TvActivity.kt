package app.tropa.ui

import android.graphics.Bitmap
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tropa.TropaApp
import app.tropa.core.CoreState
import app.tropa.core.TropaVpnService
import app.tropa.core.Pairing
import app.tropa.ui.Controller.Companion.plural
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Тропа на телевизоре: управление пультом, без полей ввода.
 *
 * Пульт: стрелки, «ОК», «Назад». Чему научил опыт HD-резки:
 *  - у каждой кнопки видимая рамка фокуса по её скруглению;
 *  - стартовый фокус ставим после первого кадра и в режиме пульта, иначе первый «ОК» только выделяет;
 *  - полей ввода нет: на ТВ они забирают стрелки себе. Подписки приходят с телефона по QR-коду.
 */
class TvActivity : ComponentActivity() {

    private val controller get() = (application as TropaApp).controller

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) controller.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TropaTheme { TvApp() } }
    }

    override fun onStart() {
        super.onStart()
        controller.setVisible(true)
    }

    override fun onStop() {
        controller.setVisible(false)
        super.onStop()
    }

    private fun toggle() {
        if (CoreState.isOn) {
            controller.disconnect()
            return
        }
        val prepare = if (TropaVpnService.needsPermission(this)) VpnService.prepare(this) else null
        if (prepare != null) vpnPermission.launch(prepare) else controller.connect()
    }

    @Composable
    private fun TvApp() {
        val c = controller
        val status by CoreState.status.collectAsState()
        val subs by c.store.subs.collectAsState()
        val delays by c.delays.collectAsState()
        val activeId by c.activeId.collectAsState()
        val settings by c.store.settings.collectAsState()
        val on = status is CoreState.Status.On
        val current = remember(subs, delays, activeId, settings) { c.currentNode() }
        var pairing by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        val first = remember { FocusRequester() }
        val input = LocalInputModeManager.current

        LaunchedEffect(Unit) {
            delay(100)
            input.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
        }
        // Без подписок сразу показываем код: больше на телевизоре делать нечего.
        LaunchedEffect(subs.isEmpty()) { if (subs.isEmpty()) pairing = true }

        Row(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 56.dp, vertical = 28.dp)) {
            Column(Modifier.weight(1f).padding(end = 40.dp)) {
                Text("Тропа", style = Type.heading, color = Palette.muted)
                Text(
                    when (status) {
                        is CoreState.Status.On -> "Подключено"
                        CoreState.Status.Connecting -> "Подключаемся…"
                        is CoreState.Status.Error -> "Не получилось"
                        CoreState.Status.Off -> "Не подключено"
                    },
                    style = Type.hero.copy(fontSize = 52.sp, lineHeight = 60.sp),
                    color = if (on) Palette.vpn else Palette.text,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    when {
                        status is CoreState.Status.Error -> (status as CoreState.Status.Error).message
                        current == null -> "Подписок пока нет — отправьте их с телефона"
                        else -> Names.flag(Names.country(current.second.name)) + " " + Names.clean(current.second.name) +
                            (delays[current.second.id]?.takeIf { it > 0 }?.let { " · $it мс" } ?: "")
                    },
                    style = Type.body.copy(fontSize = 22.sp, lineHeight = 30.sp),
                    color = if (status is CoreState.Status.Error) Palette.danger else Palette.muted,
                    modifier = Modifier.padding(top = 10.dp),
                )

                Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton(
                        if (on || status == CoreState.Status.Connecting) "Отключить" else "Подключить",
                        primary = !on,
                        modifier = Modifier.focusRequester(first),
                        enabled = current != null,
                    ) { toggle() }
                    TvButton(if (pairing) "Скрыть код" else "Добавить с телефона") { pairing = !pairing }
                    if (subs.any { !it.isLocal }) TvButton("Обновить подписки") { c.updateAll() }
                }
                if (message.isNotBlank()) {
                    Text(message, style = Type.body.copy(fontSize = 20.sp), color = Palette.vpn, modifier = Modifier.padding(top = 20.dp))
                }

                Column(Modifier.padding(top = 32.dp)) {
                    subs.filter { !it.isLocal }.take(4).forEach { s ->
                        val days = if (s.expire > 0) ((s.expire * 1000 - System.currentTimeMillis()) / 86_400_000L).toInt() else null
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(Names.subTitle(s.title), style = Type.body.copy(fontSize = 20.sp), modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    days == null -> "без срока"
                                    days < 0 -> "истекла"
                                    else -> "$days ${plural(days, "день", "дня", "дней")}"
                                },
                                style = Type.body.copy(fontSize = 20.sp),
                                color = if (days != null && days < 4) Palette.danger else Palette.muted,
                            )
                        }
                    }
                }
            }

            if (pairing) {
                PairPanel(onDone = { n ->
                    message = if (n > 0) "Получено с телефона: $n ${plural(n, "подписка", "подписки", "подписок")}" else ""
                    pairing = false
                })
            }
        }
    }

    /** QR-код для телефона; ждёт одну передачу и закрывается. */
    @Composable
    private fun PairPanel(onDone: (Int) -> Unit) {
        var ticket by remember { mutableStateOf<Pairing.Ticket?>(null) }
        var error by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val host = withContext(Dispatchers.IO) { Pairing.homeAddress() }
            if (host == null) {
                error = "Телевизор не в домашней сети: подключите Wi-Fi или кабель"
                return@LaunchedEffect
            }
            val ctx = coroutineContext
            val got = withContext(Dispatchers.IO) {
                Pairing.Receiver.start(host).use { r ->
                    ticket = r.ticket
                    r.await(keepGoing = { ctx.isActive }) { list ->
                        list.forEach { controller.addFromText(it.url) }
                        list.size
                    }
                }
            }
            if (got != null) onDone(got)
        }
        Column(
            Modifier.width(340.dp).background(Palette.s1, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Добавить с телефона", style = Type.heading.copy(fontSize = 24.sp))
            val t = ticket
            if (t != null) {
                val qr = remember(t) { qrBitmap(t.uri(), 640) }
                Box(Modifier.padding(top = 14.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(10.dp)) {
                    Image(qr.asImageBitmap(), contentDescription = "QR-код для телефона", modifier = Modifier.size(200.dp))
                }
                Text(
                    "Наведите камеру телефона на код и откройте ссылку — Тропа на телефоне спросит, отправить ли подписки.",
                    style = Type.body.copy(fontSize = 17.sp, lineHeight = 23.sp),
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "Код одноразовый. Подписки придут по домашней сети в зашифрованном виде.",
                    style = Type.caption.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(error.ifBlank { "Готовим код…" }, style = Type.body.copy(fontSize = 18.sp),
                    color = if (error.isNotBlank()) Palette.danger else Palette.muted, modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
}

/** Рамка фокуса для пульта: по скруглению кнопки, с лёгким увеличением. */
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(18.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) 1.06f else 1f, label = "tvFocus")
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = s; scaleY = s }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
}

@Composable
private fun TvButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .tvFocus(shape)
            .background(if (primary && enabled) Palette.vpn else Palette.s2, shape)
            // Без стандартной подсветки: она рисует квадрат под скруглённой кнопкой, фокус показывает рамка.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.bodyStrong.copy(fontSize = 20.sp), color = if (primary && enabled) Palette.vpnInk else if (enabled) Palette.text else Palette.muted)
    }
}

/** QR-код с запасом по краям (quiet zone) — так его быстрее ловит камера. */
fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
