package app.tropa.widget

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.tropa.BuildConfig
import app.tropa.R
import app.tropa.TropaApp
import app.tropa.core.CoreState
import app.tropa.core.TropaVpnService
import app.tropa.data.Subscription
import app.tropa.ui.Controller
import app.tropa.ui.Controller.Companion.plural
import app.tropa.ui.MainActivity
import app.tropa.ui.Names
import app.tropa.ui.Palette
import app.tropa.ui.modeTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * Три виджета для рабочего стола, чтобы не открывать приложение:
 *  - «Кнопка» (1×1): включить и выключить VPN одним касанием;
 *  - «Подключение» (4×1): то же плюс страна, сервер и отклик; касание по тексту — экран серверов;
 *  - «Подписки» (4×2): сколько дней осталось, трафик и лимит LTE у каждой подписки, кнопка «обновить».
 *
 * Данные берём из того же процесса, что и приложение: статус ядра, подписки, текущий сервер.
 * Обновляются виджеты только когда что-то поменялось (см. [WidgetUpdater]), сами по таймеру не просыпаются.
 */

private fun c(color: Color) = ColorProvider(day = color, night = color)

private val app @Composable get() = LocalContext.current.applicationContext as TropaApp

/** Что делает касание по кнопке включения: выключить, включить или открыть приложение за разрешением. */
private fun toggleAction(context: Context, status: CoreState.Status, hasServers: Boolean): Action {
    val on = status is CoreState.Status.On || status == CoreState.Status.Connecting
    return when {
        on -> actionStartService(
            Intent(context, TropaVpnService::class.java).setAction(TropaVpnService.ACTION_STOP),
            isForegroundService = false,
        )
        // Без разрешения на VPN или без подписок включиться нельзя — открываем приложение.
        !hasServers -> actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 0))
        TropaVpnService.needsPermission(context) -> actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONNECT, true)
        )
        else -> actionStartService(
            Intent(context, TropaVpnService::class.java).setAction(TropaVpnService.ACTION_START),
            isForegroundService = true,
        )
    }
}

private fun openTab(context: Context, tab: Int): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab))

private fun statusTitle(s: CoreState.Status) = when (s) {
    is CoreState.Status.On -> "Подключено"
    CoreState.Status.Connecting -> "Подключаемся…"
    is CoreState.Status.Error -> "Не получилось"
    CoreState.Status.Off -> "Не подключено"
}

@Composable
private fun Label(
    text: String,
    color: Color = Palette.text,
    size: TextUnit = 13.sp,
    bold: Boolean = false,
    lines: Int = 1,
    modifier: GlanceModifier = GlanceModifier,
) = Text(
    text,
    modifier = modifier,
    maxLines = lines,
    style = TextStyle(color = c(color), fontSize = size, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal),
)

@Composable
private fun Icon(res: Int, tint: Color, size: Dp, modifier: GlanceModifier = GlanceModifier) =
    Image(ImageProvider(res), contentDescription = null, colorFilter = ColorFilter.tint(c(tint)), modifier = modifier.size(size))

/** Круглая кнопка питания: голубая, когда VPN включён. */
@Composable
private fun PowerButton(on: Boolean, action: Action, size: Dp) {
    Box(
        GlanceModifier.size(size).cornerRadius(size / 2).background(if (on) Palette.vpn else Palette.s2).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Icon(R.drawable.ic_power, if (on) Palette.vpnInk else Palette.text, size * 0.46f)
    }
}

// ---------- 1. Кнопка ----------

class ButtonWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { Content() }

    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideContent { Content() }

    @Composable
    private fun Content() {
        val context = LocalContext.current
        val a = app
        val status by CoreState.status.collectAsState()
        val subs by a.store.subs.collectAsState()
        val activeId by a.controller.activeId.collectAsState()
        val on = status is CoreState.Status.On
        val busy = status == CoreState.Status.Connecting
        val size = LocalSize.current
        val country = if (on) a.controller.currentNode()?.let { Names.country(it.second.name) } else null
        val action = toggleAction(context, status, subs.any { it.nodes.isNotEmpty() })
        activeId // перерисовать, когда ядро сменило сервер
        Box(
            GlanceModifier.fillMaxSize().cornerRadius(26.dp).background(if (on || busy) Palette.vpn else Palette.s1).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (on || busy) Palette.vpnInk else Palette.text
            if (size.width >= 120.dp) {
                Row(GlanceModifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(R.drawable.ic_power, ink, 26.dp)
                    Spacer(GlanceModifier.width(10.dp))
                    Column {
                        Label(if (on) "Включено" else if (busy) "Включаем…" else "Выключено", ink, 15.sp, bold = true)
                        Label(if (on) (Names.flag(country) + " " + (countryName(country) ?: "VPN")).trim() else "Тропа", ink, 12.sp)
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(R.drawable.ic_power, ink, 26.dp)
                    if (size.height >= 64.dp) {
                        Spacer(GlanceModifier.height(4.dp))
                        Label(if (on) Names.flag(country).ifEmpty { "Вкл" } else if (busy) "…" else "Выкл", ink, 12.sp, bold = true)
                    }
                }
            }
        }
    }
}

// ---------- 2. Подключение ----------

class ConnectionWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { Content() }

    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideContent { Content() }

    @Composable
    private fun Content() {
        val context = LocalContext.current
        val a = app
        val status by CoreState.status.collectAsState()
        val subs by a.store.subs.collectAsState()
        val settings by a.store.settings.collectAsState()
        val delays by a.controller.delays.collectAsState()
        val activeId by a.controller.activeId.collectAsState()
        activeId
        val on = status is CoreState.Status.On
        val size = LocalSize.current
        val current = a.controller.currentNode()
        val server = current?.let { (sub, node) ->
            val flag = Names.flag(Names.country(node.name))
            val d = delays[node.id]?.takeIf { it > 0 }?.let { " · $it мс" }.orEmpty()
            val who = if (subs.size > 1) " · " + Names.subTitle(sub.title) else ""
            "$flag ${Names.clean(node.name)}$d$who".trim()
        }
        val second = when (status) {
            is CoreState.Status.On -> server ?: "Сервер выбирается"
            CoreState.Status.Connecting -> server ?: "Выбираем сервер"
            is CoreState.Status.Error -> (status as CoreState.Status.Error).message
            CoreState.Status.Off -> if (current == null) "Добавьте подписку в приложении" else server.orEmpty()
        }
        Row(
            GlanceModifier.fillMaxSize().cornerRadius(26.dp).background(Palette.s1).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val btn = (size.height - 20.dp).coerceIn(40.dp, 56.dp)
            PowerButton(on || status == CoreState.Status.Connecting, toggleAction(context, status, current != null), btn)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().clickable(openTab(context, 1))) {
                Label(statusTitle(status), if (on) Palette.vpn else Palette.text, 16.sp, bold = true)
                Label(
                    second,
                    if (status is CoreState.Status.Error) Palette.danger else Palette.muted,
                    13.sp,
                    lines = if (size.height >= 100.dp) 2 else 1,
                )
                if (size.height >= 100.dp) {
                    Spacer(GlanceModifier.height(4.dp))
                    Label(modeTitle(settings.mode), Palette.muted, 12.sp)
                }
            }
            Icon(R.drawable.ic_servers, Palette.muted, 20.dp, GlanceModifier.clickable(openTab(context, 1)))
        }
    }
}

// ---------- 3. Подписки ----------

class SubsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { Content() }

    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideContent { Content() }

    @Composable
    private fun Content() {
        val context = LocalContext.current
        val a = app
        val subs by a.store.subs.collectAsState()
        val updating by a.controller.updating.collectAsState()
        val size = LocalSize.current
        val remote = subs.filter { !it.isLocal }
        Column(GlanceModifier.fillMaxSize().cornerRadius(26.dp).background(Palette.s1).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("Подписки", Palette.text, 15.sp, bold = true, modifier = GlanceModifier.defaultWeight().clickable(openTab(context, 0)))
                Box(GlanceModifier.size(32.dp).cornerRadius(16.dp).clickable(actionRunCallback<RefreshSubs>()), contentAlignment = Alignment.Center) {
                    Icon(R.drawable.ic_refresh, if (updating.isNotEmpty()) Palette.vpn else Palette.muted, 20.dp)
                }
            }
            if (remote.isEmpty()) {
                Spacer(GlanceModifier.height(6.dp))
                Label("Добавьте подписку в приложении", Palette.muted, 13.sp, modifier = GlanceModifier.clickable(openTab(context, 0)))
            }
            // Каждая подписка — две строки, примерно 46 dp.
            val fits = ((size.height - 44.dp) / 46.dp).toInt().coerceAtLeast(1)
            remote.take(fits).forEach { s ->
                Spacer(GlanceModifier.height(6.dp))
                SubRow(s, openTab(context, 0))
            }
        }
    }

    @Composable
    private fun SubRow(s: Subscription, open: Action) {
        val days = if (s.expire > 0) ((s.expire * 1000 - System.currentTimeMillis()) / 86_400_000L).toInt() else null
        Column(GlanceModifier.fillMaxWidth().clickable(open)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(Names.subTitle(s.title), Palette.text, 13.sp, bold = true, modifier = GlanceModifier.defaultWeight())
                Label(
                    when {
                        days == null -> "без срока"
                        days < 0 -> "истекла"
                        else -> "$days ${plural(days, "день", "дня", "дней")}"
                    },
                    if (days != null && days < 4) Palette.danger else Palette.muted,
                    12.sp,
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val lte = if (s.nodes.any { Names.kind(it.name) == Names.Kind.MOBILE }) Names.lteQuota(s.announce) else null
                when {
                    lte != null -> {
                        val (left, total) = lte
                        val share = if (total > 0) (left / total).toFloat() else 0f
                        LinearProgressIndicator(
                            progress = share.coerceIn(0f, 1f),
                            modifier = GlanceModifier.defaultWeight().height(5.dp),
                            color = c(if (share < 0.1f) Palette.danger else Palette.direct),
                            backgroundColor = c(Palette.s2),
                        )
                        Spacer(GlanceModifier.width(10.dp))
                        Label("LTE ${"%.0f".format(left)} из ${"%.0f".format(total)} ГБ", Palette.muted, 12.sp)
                    }
                    s.total > 0 -> {
                        val used = (s.upload + s.download).toFloat() / s.total
                        LinearProgressIndicator(
                            progress = used.coerceIn(0f, 1f),
                            modifier = GlanceModifier.defaultWeight().height(5.dp),
                            color = c(if (used > 0.9f) Palette.danger else Palette.vpn),
                            backgroundColor = c(Palette.s2),
                        )
                        Spacer(GlanceModifier.width(10.dp))
                        Label("${Controller.formatBytes(s.upload + s.download)} из ${Controller.formatBytes(s.total)}", Palette.muted, 12.sp)
                    }
                    else -> Label("Трафик без лимита", Palette.muted, 12.sp)
                }
            }
        }
    }
}

/** Кнопка «обновить» на виджете подписок. */
class RefreshSubs : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as TropaApp).controller.updateAll()
    }
}

private fun countryName(code: String?): String? = code?.let {
    mapOf(
        "FI" to "Финляндия", "SE" to "Швеция", "NL" to "Нидерланды", "DE" to "Германия", "LV" to "Латвия",
        "LT" to "Литва", "EE" to "Эстония", "PL" to "Польша", "FR" to "Франция", "GB" to "Великобритания",
        "US" to "США", "CH" to "Швейцария", "AT" to "Австрия", "CZ" to "Чехия", "NO" to "Норвегия",
        "DK" to "Дания", "IT" to "Италия", "ES" to "Испания", "TR" to "Турция", "KZ" to "Казахстан",
        "JP" to "Япония", "SG" to "Сингапур", "HK" to "Гонконг", "AE" to "ОАЭ", "AM" to "Армения",
        "GE" to "Грузия", "RS" to "Сербия", "RU" to "Россия", "CA" to "Канада",
    )[it]
}

class ButtonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ButtonWidget()
}

class ConnectionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ConnectionWidget()
}

class SubsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SubsWidget()
}

/**
 * Обновляет виджеты, когда меняется то, что на них видно: статус, сервер, отклик, подписки.
 * Своего таймера нет — батарею виджеты не тратят.
 */
object WidgetUpdater {
    private val widgets get() = listOf(ButtonWidget(), ConnectionWidget(), SubsWidget())

    fun start(app: TropaApp) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            combine(
                CoreState.status, app.store.subs, app.store.settings, app.controller.activeId,
                app.controller.delays, app.controller.updating,
            ) { it.toList() }
                .distinctUntilChanged()
                .collectLatest {
                    delay(400) // несколько изменений подряд — одно обновление
                    widgets.forEach { w -> runCatching { w.updateAll(app) } }
                }
        }
        // Живой предпросмотр в списке виджетов (Android 15+). Система ограничивает частоту
        // таких вызовов, поэтому один раз на версию приложения.
        if (Build.VERSION.SDK_INT >= 35) scope.launch {
            val prefs = app.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            val version = BuildConfig.VERSION_CODE
            if (prefs.getInt("previews", 0) == version) return@launch
            val m = GlanceAppWidgetManager(app)
            val ok = listOf(ButtonWidgetReceiver::class, ConnectionWidgetReceiver::class, SubsWidgetReceiver::class)
                .all { runCatching { m.setWidgetPreviews(it) }.isSuccess }
            if (ok) prefs.edit().putInt("previews", version).apply()
        }
    }
}
