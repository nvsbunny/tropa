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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val on = status is CoreState.Status.On
    val current = remember3(subs, delays, activeId, settings) { c.currentNode() }
    val totalNodes = subs.sumOf { it.nodes.size }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Column(Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp)) {
            Text(
                when (status) {
                    is CoreState.Status.On -> "Подключено"
                    CoreState.Status.Connecting -> "Подключаемся"
                    is CoreState.Status.Error -> "Не получилось"
                    CoreState.Status.Off -> "Не подключено"
                },
                style = Type.hero,
                color = if (on) Palette.vpn else Palette.text,
            )
            Text(
                when (status) {
                    is CoreState.Status.On -> current?.let { n -> countryWord(n.second.name)?.let { "Сайты видят $it, а банки — вас" } }
                        ?: "Сайты видят адрес сервера, а банки — вас"
                    CoreState.Status.Connecting -> "Выбираем лучший сервер"
                    is CoreState.Status.Error -> status.message
                    CoreState.Status.Off -> "Сайты видят ваш настоящий адрес"
                },
                style = Type.small,
                color = if (status is CoreState.Status.Error) Palette.danger else Palette.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

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

        Spacer(14.dp)
        RouteSummary(settings.mode, settings.directApps.size + settings.customSites.size, onOpenRoutes)
        if (subs.size > 1) MergeRow(settings.mergeSubs) { c.setMerge(it) }
        Spacer(8.dp)
        Panel {
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Выбрать сервер", onClick = onOpenServers),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Flag(current?.let { Names.country(it.second.name) })
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            current?.let { Names.clean(it.second.name) } ?: "Сервер",
                            style = Type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        when (settings.choice) {
                            ServerChoice.PROVIDER -> Pill("Провайдер")
                            ServerChoice.AUTO -> Pill("Автовыбор")
                            ServerChoice.MANUAL -> Unit
                        }
                    }
                    Text(
                        if (settings.choice == ServerChoice.AUTO && settings.mergeSubs) "Лучший из $totalNodes ${plural(totalNodes, "сервера", "серверов", "серверов")} " +
                            "в ${subs.size} ${plural(subs.size, "подписке", "подписках", "подписках")}"
                        else current?.first?.title ?: "",
                        style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(delayText(current?.let { delays[it.second.id] }), style = Type.bodyStrong)
                Ico(Icons.chevron, tint = Palette.muted, size = 18.dp)
            }
            if (settings.advanced && current != null) {
                Text(
                    Names.protocol(current.second) + ", " + current.second.server + ":" + current.second.port,
                    style = Type.caption, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (on) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("↓ ${Controller.formatSpeed(speed.second)}", style = Type.caption, color = Palette.muted)
                    Text("↑ ${Controller.formatSpeed(speed.first)}", style = Type.caption, color = Palette.muted)
                }
            }
            if (settings.mergeSubs || subs.size == 1) {
                subs.filter { !it.isLocal }.forEach { PlanRow(it) }
                subs.firstOrNull { it.announce.isNotBlank() }?.let { s -> Announce("Сообщение от ${s.title}: ${Names.clean(s.announce)}") }
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
        Text(if (showTitle) s.title else "Трафик", style = Type.caption, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
            Text("Безлимит", style = Type.caption, color = Palette.muted)
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
