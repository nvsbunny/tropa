package app.tropa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer as LSpacer
import app.tropa.core.CoreState
import app.tropa.data.RouteMode
import app.tropa.data.ServerChoice
import app.tropa.data.Subscription
import app.tropa.ui.Controller.Companion.plural

@Composable
fun HomeScreen(
    c: Controller,
    status: CoreState.Status,
    onOpenServers: () -> Unit,
    onOpenRoutes: () -> Unit,
    onAddSub: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val update by app.tropa.core.Updater.state.collectAsState()
    val subs by c.store.subs.collectAsState()
    val settings by c.store.settings.collectAsState()
    val delays by c.delays.collectAsState()
    val activeId by c.activeId.collectAsState()
    val speed by c.speed.collectAsState()
    val traffic by c.traffic.collectAsState()
    var picking by remember { mutableStateOf(false) }
    if (picking) ServerSheet(c) { picking = false }
    val on = status is CoreState.Status.On
    val current = remember3(subs, delays, activeId, settings) { c.currentNode() }
    val totalNodes = subs.sumOf { it.nodes.size }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        StatusRow(status, c.networkLabel())

        if (subs.isEmpty()) {
            Spacer(24.dp)
            Panel {
                Text("Добавьте подписку", style = Type.heading)
                Text(
                    "Вставьте ссылку от вашего VPN-сервиса. Подойдут ссылки для Happ, INCY, Clash и v2rayNG, а также отдельные серверы vless:// и другие.",
                    style = Type.small, color = Palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                PrimaryButton("Добавить подписку", onClick = onAddSub)
            }
            return@Column
        }

        Spacer(10.dp)
        ExitCard(c, current, on, status, delays[current?.second?.id], settings.choice, traffic, speed) { picking = true }

        val rough by c.rough.collectAsState()
        QuickPick(c, subs, settings.favoriteSubId, delays, rough, current?.second?.id, settings.choice, c.onMobileNetwork()) { picking = true }

        (update as? app.tropa.core.Updater.State.Available)?.let { u ->
            Spacer(14.dp)
            Panel(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClickLabel = "Обновить", onClick = onOpenSettings)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Ico(Icons.refresh, tint = Palette.vpn, size = 20.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Вышла Тропа ${u.release.versionName}", style = Type.bodyStrong)
                        Text(u.release.notes.ifBlank { "Нажмите, чтобы обновить" }, style = Type.caption, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Ico(Icons.chevron, tint = Palette.muted, size = 18.dp)
                }
            }
        }

        Spacer(6.dp)
        RouteSummary(settings.mode, settings.directApps.size + settings.customSites.size, onOpenRoutes)
        if (subs.size > 1) MergeRow(settings.mergeSubs) { c.setMerge(it) }
        if (settings.mergeSubs || subs.size == 1) {
            Spacer(8.dp)
            Panel {
                if (settings.advanced && current != null) {
                    Text(
                        Names.protocol(current.second) + ", " + current.second.server + ":" + current.second.port,
                        style = Type.caption, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                subs.filter { !it.isLocal }.forEach { PlanRow(it) }
                subs.firstOrNull { it.announce.isNotBlank() }?.let { s -> Announce("Сообщение от ${Names.subTitle(s.title)}: ${Names.clean(s.announce)}") }
            }
        }
        // Подписки раздельно, как в Happ и INCY: своя карточка у каждой, нажатие делает её рабочей.
        if (!settings.mergeSubs && subs.size > 1) {
            val activeSub = subs.firstOrNull { it.id == settings.providerSubId } ?: subs.first()
            subs.forEach { sub -> SubCard(sub, sub.id == activeSub.id) { c.setActiveSub(sub.id) } }
        }
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Добавить подписку", onClick = onAddSub)
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Ico(Icons.plus, tint = Palette.vpn, size = 18.dp)
            Text("Добавить ещё подписку", style = Type.captionStrong, color = Palette.vpn)
        }
        LSpacer(Modifier.height(24.dp))
    }
}

/** Строка сверху: состояние точкой и словом, справа — сеть (LTE и оператор или Wi-Fi). */
@Composable
private fun StatusRow(status: CoreState.Status, network: String) {
    val (word, dot) = when (status) {
        is CoreState.Status.On -> "ПОДКЛЮЧЕНО" to Palette.vpnOn
        CoreState.Status.Connecting -> "ПОДКЛЮЧАЕМСЯ" to Palette.brand
        is CoreState.Status.Error -> "НЕ ПОЛУЧИЛОСЬ" to Palette.danger
        CoreState.Status.Off -> "НЕ ПОДКЛЮЧЕНО" to Palette.muted
    }
    Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
            Text(word, style = Type.tiny.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(network, style = Type.tiny, color = Palette.muted)
        }
        if (status is CoreState.Status.Error) Text(status.message, style = Type.caption, color = Palette.danger, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Тёмная карточка выхода: через какую страну сейчас интернет, сервер, отклик, трафик.
 * Когда страна сменилась (выбрали другую или подключились), название собирается из знаков,
 * а фоном на несколько секунд проступает флаг из знаков; потом он оседает и карточка снова тихая.
 */
@Composable
private fun ExitCard(
    c: Controller,
    current: Pair<Subscription, app.tropa.data.Node>?,
    on: Boolean,
    status: CoreState.Status,
    delay: Int?,
    choice: ServerChoice,
    traffic: app.tropa.core.TrafficMeter.Stats,
    speed: Pair<Long, Long>,
    onPick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val node = current?.second
    val code = node?.let { Names.country(it.name) }
    val lte = node != null && Names.kind(node.name) == Names.Kind.MOBILE
    // У LTE-обхода флаг в названии — это вход (Россия, Евросоюз), а страна выхода неизвестна.
    val exitKnown = !lte || (code != null && code != "RU" && Names.countryName(code) != null)
    val title = when {
        node == null -> "Сервер"
        !exitKnown -> "LTE-обход"
        else -> Names.countryName(code) ?: Names.serverName(node.name)
    }
    // Флаг проявляется один раз на каждую новую страну, пока VPN включён.
    var reveal by remember { mutableStateOf(0) }
    var flagOn by remember { mutableStateOf(false) }
    val revealKey = node?.id
    LaunchedEffect(revealKey, on) {
        if (on && revealKey != null && revealKey != c.lastRevealed) {
            c.lastRevealed = revealKey
            reveal++
            flagOn = true
        }
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.card)
            .clickable(onClickLabel = "Выбрать страну или сервер", onClick = onPick)
    ) {
        // У LTE-обхода страна выхода неизвестна (флаг в названии — это вход): флага не рисуем.
        if (flagOn && exitKnown) {
            SymbolFlag(code, Modifier.matchParentSize()) { flagOn = false }
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.35f to androidx.compose.ui.graphics.Color.Transparent, 1f to Palette.card.copy(alpha = 0.92f))))
        }
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (on) "ВЫХОД В ИНТЕРНЕТ" else "БУДЕТ ВЫХОД", style = Type.tiny, color = Palette.cardMuted, modifier = Modifier.weight(1f))
                val chip = when (choice) {
                    ServerChoice.PROVIDER -> "ПРОВАЙДЕР"
                    ServerChoice.AUTO -> "АВТО"
                    ServerChoice.COUNTRY -> "СТРАНА"
                    ServerChoice.MANUAL -> "ВРУЧНУЮ"
                }
                Text(chip, style = Type.tiny, color = Palette.cardText, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.cardChip).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            ScrambleText(title, key = reveal, play = reveal > 0, style = Type.hero.copy(fontSize = androidx.compose.ui.unit.TextUnit(38f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(44f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Palette.cardText, modifier = Modifier.padding(top = 8.dp))
            if (node != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lte) Text("LTE", style = Type.tiny, color = Palette.cardText, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.vpn).padding(horizontal = 6.dp, vertical = 2.dp))
                    Text(
                        Names.serverName(node.name) + " · " + Names.subTitle(current.first.title),
                        style = Type.caption, color = Palette.cardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(delayText(delay).ifEmpty { "—" }, style = Type.title, color = Palette.cardText)
                    if (on) Text(
                        "↓ ${Controller.formatSpeed(speed.second)}   ↑ ${Controller.formatSpeed(speed.first)}",
                        style = Type.caption, color = Palette.cardMuted, maxLines = 1, modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        "Сегодня ${Controller.formatBytes(traffic.today)} · месяц ${Controller.formatBytes(traffic.month)}",
                        style = Type.caption, color = Palette.cardMuted, maxLines = 1,
                    )
                }
                Text(
                    "Скорость ↗", style = Type.captionStrong, color = Palette.cardText,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.cardChip)
                        .clickable(onClickLabel = "Замерить скорость на сайте") {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.speedtest.net/")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * Быстрый выбор под карточкой: вкладки провайдеров (провайдер по умолчанию — первым)
 * и по пять серверов. На Wi-Fi — самые отзывчивые, на мобильной сети — LTE-серверы.
 * Нажали строку — этот сервер выбран; остальное — в шторке «Все серверы».
 */
@Composable
private fun QuickPick(
    c: Controller,
    subs: List<Subscription>,
    favorite: String,
    delays: Map<String, Int>,
    rough: Set<String>,
    currentId: String?,
    choice: ServerChoice,
    mobile: Boolean,
    onMore: () -> Unit,
) {
    val tabs = remember(subs, favorite) { c.providerTabs() }
    if (tabs.isEmpty()) return
    var tabId by remember(favorite) { mutableStateOf(tabs.first().id) }
    val sub = tabs.firstOrNull { it.id == tabId } ?: tabs.first()
    val picks = remember(sub, delays, mobile) { c.quickPicks(sub, mobile) }
    val lte = picks.any { Names.kind(it.second.name) == Names.Kind.MOBILE }
    // Сменилась сеть (Wi-Fi ↔ LTE) — меряем отклик заново: на мобильной меряются и LTE-серверы.
    LaunchedEffect(mobile) { c.testAll() }

    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 22.dp), verticalAlignment = Alignment.Bottom) {
        Text(if (lte) "LTE-серверы" else "Страны", style = Type.heading, modifier = Modifier.weight(1f))
        Text(if (lte) "для мобильной сети" else "лучшие по отклику", style = Type.caption, color = Palette.muted)
    }
    if (tabs.size > 1) {
        Row(
            Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { s -> ChipButton(Names.shortSub(s.title), s.id == sub.id) { tabId = s.id } }
        }
    }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.s1)) {
        if (picks.isEmpty()) {
            Text("У провайдера нет подходящих серверов", style = Type.small, color = Palette.muted, modifier = Modifier.padding(16.dp))
        }
        picks.forEachIndexed { i, (_, node) ->
            if (i > 0) androidx.compose.material3.HorizontalDivider(color = Palette.line.copy(alpha = 0.6f), thickness = 1.dp, modifier = Modifier.padding(start = 60.dp))
            val d = delays[node.id]
            val picked = node.id == currentId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(onClickLabel = "Выбрать этот сервер") { c.selectNode(node.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Flag(Names.country(node.name), size = 28.dp)
                // Заголовок уже говорит «LTE-серверы»: пометку LTE/4G из названия убираем, чтобы влез номер.
                val name = Names.serverName(node.name).let { n ->
                    if (!lte) n else n.replace(Regex("""\(?LTE(?:/4G)?\)?""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s{2,}"""), " ").trim().ifEmpty { n }
                }
                Text(
                    name, style = if (picked) Type.bodyStrong else Type.body,
                    color = if (d == 0) Palette.muted else Palette.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (picked) Pill(if (choice == ServerChoice.MANUAL) "выбран" else "сейчас")
                Text(
                    (if (node.id in rough) "≈ " else "") + delayText(d),
                    style = Type.captionStrong, color = if (d == 0) Palette.muted else Palette.text,
                )
            }
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Открыть все серверы", onClick = onMore)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Все серверы и автовыбор", style = Type.captionStrong, color = Palette.vpn)
        Ico(Icons.chevron, tint = Palette.vpn, size = 16.dp)
    }
}

/** Переключатель «Объединять подписки». */
@Composable
private fun MergeRow(merge: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Объединять подписки", style = Type.small)
            Text(
                if (merge) "Автоматика выбирает из серверов всех подписок" else "Работает одна подписка — выберите её ниже",
                style = Type.caption, color = Palette.muted,
            )
        }
        androidx.compose.material3.Switch(
            checked = merge,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.vpn, checkedThumbColor = Palette.vpnInk),
        )
    }
}

@Composable
private fun Announce(text: String) {
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ico(Icons.message, tint = Palette.muted, size = 18.dp)
        Text(text, style = Type.caption, color = Palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Карточка одной подписки: срок, трафик, сообщение провайдера. Рабочая выделена рамкой. */
@Composable
private fun SubCard(s: Subscription, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) Palette.vpn.copy(alpha = 0.10f).compositeOver(Palette.s1) else Palette.s1)
            .border(1.5.dp, if (active) Palette.vpn else Palette.s1, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = "Сделать рабочей", onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Names.subTitle(s.title), style = Type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (active) Pill("Работает")
        }
        Text(
            "${s.nodes.size} ${plural(s.nodes.size, "сервер", "сервера", "серверов")}",
            style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 2.dp),
        )
        if (!s.isLocal) PlanRow(s, showTitle = false)
        if (s.announce.isNotBlank()) Announce(Names.clean(s.announce))
    }
}

private fun androidx.compose.ui.graphics.Color.compositeOver(bg: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val a = alpha
    return androidx.compose.ui.graphics.Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

/** Одна строка вместо развилки: какой режим маршрутов и сколько исключений. */
@Composable
private fun RouteSummary(mode: RouteMode, exceptions: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Открыть маршруты", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Ico(Icons.fork, tint = Palette.muted, size = 20.dp)
        Text(
            modeTitle(mode) + if (exceptions > 0) ", исключений: $exceptions" else "",
            style = Type.small, color = Palette.muted, modifier = Modifier.weight(1f),
        )
        Ico(Icons.chevron, tint = Palette.muted, size = 18.dp)
    }
}

fun modeTitle(mode: RouteMode) = when (mode) {
    RouteMode.ALL -> "Всё через VPN"
    RouteMode.RU_DIRECT -> "Российские сайты напрямую"
    RouteMode.BLOCKED_ONLY -> "Через VPN только заблокированное"
    RouteMode.PROVIDER -> "Маршруты от провайдера"
}

@Composable
private fun PlanRow(s: Subscription, showTitle: Boolean = true) {
    val days = if (s.expire > 0) ((s.expire * 1000 - System.currentTimeMillis()) / 86_400_000L).toInt() else null
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (showTitle) Names.shortSub(s.title) else "Трафик", style = Type.caption, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (s.total > 0) {
            val used = (s.upload + s.download).toFloat() / s.total
            LinearProgressIndicator(
                progress = { used.coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (used > 0.9f) Palette.danger else Palette.muted,
                trackColor = Palette.s2,
                drawStopIndicator = {},
            )
        } else {
            // Провайдер сообщает расход и у безлимита (как показывает Happ): это по всем устройствам подписки.
            val used = s.upload + s.download
            Text(if (used > 0) "Безлимит · ${Controller.formatBytes(used)}" else "Безлимит", style = Type.caption, color = Palette.muted)
        }
        Box {
            Text(
                when {
                    days == null -> "Без срока"
                    days < 0 -> "Истекла"
                    else -> "$days ${plural(days, "день", "дня", "дней")}"
                },
                style = Type.caption,
                color = if (days != null && days < 4) Palette.danger else Palette.muted,
            )
        }
    }
    LteRow(s)
}

/**
 * Лимит на обходы белых списков: обычный интернет у провайдеров безлимитный,
 * а LTE-серверы считаются отдельно. Показываем, только если у подписки есть LTE-серверы.
 */
@Composable
private fun LteRow(s: Subscription) {
    if (s.nodes.none { Names.kind(it.name) == Names.Kind.MOBILE }) return
    val quota = Names.lteQuota(s.announce)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LTE, белые списки", style = Type.caption, color = Palette.muted, modifier = Modifier.weight(1f))
        if (quota == null) {
            Text("лимит провайдер не сообщает", style = Type.caption, color = Palette.muted)
        } else {
            val (left, total) = quota
            val share = if (total > 0) (left / total).toFloat() else 0f
            LinearProgressIndicator(
                progress = { share.coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (share < 0.1f) Palette.danger else Palette.direct,
                trackColor = Palette.s2,
                drawStopIndicator = {},
            )
            Text(
                "осталось ${"%.0f".format(left)} из ${"%.0f".format(total)} ГБ",
                style = Type.caption,
                color = if (share < 0.1f) Palette.danger else Palette.muted,
            )
        }
    }
}

/** «Финляндию», «Швецию»: винительный падеж для «через …» и «сайты видят …». */
private fun countryWord(name: String): String? {
    val code = Names.country(name) ?: return null
    return mapOf(
        "FI" to "Финляндию", "SE" to "Швецию", "NL" to "Нидерланды", "DE" to "Германию", "LV" to "Латвию",
        "LT" to "Литву", "EE" to "Эстонию", "PL" to "Польшу", "FR" to "Францию", "GB" to "Великобританию",
        "US" to "США", "CH" to "Швейцарию", "AT" to "Австрию", "CZ" to "Чехию", "NO" to "Норвегию",
        "DK" to "Данию", "IT" to "Италию", "ES" to "Испанию", "TR" to "Турцию", "KZ" to "Казахстан",
        "JP" to "Японию", "SG" to "Сингапур", "HK" to "Гонконг", "AE" to "ОАЭ", "AM" to "Армению",
        "GE" to "Грузию", "RS" to "Сербию", "RU" to "Россию",
    )[code]
}

@Composable
private fun <T> remember3(vararg keys: Any?, calc: () -> T): T =
    androidx.compose.runtime.remember(*keys) { calc() }
