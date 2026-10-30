package app.tropa.ui

import androidx.compose.foundation.background
import app.tropa.data.ServerChoice
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tropa.core.CoreState
import app.tropa.data.Node
import app.tropa.data.Subscription
import app.tropa.ui.Controller.Companion.plural

@Composable
fun ServersScreen(c: Controller, onAddSub: () -> Unit) {
    val subs by c.store.subs.collectAsState()
    val settings by c.store.settings.collectAsState()
    val delays by c.delays.collectAsState()
    val activeId by c.activeId.collectAsState()
    val testing by c.testing.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    val quality by c.store.quality.collectAsState()
    val checking by c.checking.collectAsState()

    LaunchedEffect(Unit) { if (delays.isEmpty()) c.testAll() }

    val all = subs.filter { filter == null || it.id == filter }.flatMap { s -> s.nodes.map { s to it } }
    val total = subs.sumOf { it.nodes.size }
    // Лучшие сверху. Не ответившие — в конце, UDP-серверы без проверки — между ними.
    val sorted = all.sortedWith(compareBy { (_, n) ->
        when (val d = delays[n.id]) {
            null -> 50_000
            0 -> 100_000
            -1 -> 40_000
            else -> d
        }
    })
    val kinds = sorted.groupBy { Names.kind(it.second.name) }
    val regular = kinds[Names.Kind.NORMAL].orEmpty()
    val fast = regular.filter { (delays[it.second.id] ?: 0) in 1..99 }
    val good = regular.filter { (delays[it.second.id] ?: 0) in 100..199 }
    val slow = regular.filter { (delays[it.second.id] ?: 0) >= 200 }
    val unknown = regular.filter { delays[it.second.id] == null || delays[it.second.id] == -1 }
    val dead = regular.filter { delays[it.second.id] == 0 }
    val usedId = activeId ?: settings.manualNodeId.takeIf { settings.choice == ServerChoice.MANUAL }
    val providerSub = subs.firstOrNull { it.id == settings.providerSubId } ?: subs.firstOrNull()
    val row = RowOpts(delays, usedId, settings.choice != ServerChoice.MANUAL, settings.advanced, c, quality.byId)

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Серверы", style = Type.title)
                    Text(
                        "$total ${plural(total, "сервер", "сервера", "серверов")} из ${subs.size} ${plural(subs.size, "подписки", "подписок", "подписок")}",
                        style = Type.caption, color = Palette.muted,
                    )
                }
                IconButtonBox(Icons.refresh, if (testing) "Проверяем" else "Проверить все", tint = if (testing) Palette.vpn else Palette.text) {
                    c.testAll()
                }
                IconButtonBox(Icons.plus, "Добавить подписку", tint = Palette.vpn, onClick = onAddSub)
            }
            Spacer(16.dp)
            Panel {
                Text("Кто выбирает сервер", style = Type.bodyStrong)
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Palette.bg).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(ServerChoice.PROVIDER to "Провайдер", ServerChoice.AUTO to "Тропа").forEach { (choice, label) ->
                        val sel = settings.choice == choice
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Palette.s2 else Palette.bg)
                                .clickable { c.setChoice(choice) },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = Type.captionStrong, color = if (sel) Palette.text else Palette.muted) }
                    }
                }
                Text(
                    when (settings.choice) {
                        ServerChoice.PROVIDER -> "Как решил ${providerSub?.title ?: "провайдер"}: " +
                            when (providerSub?.groupType) {
                                null, "" -> "первый живой сервер по его списку."
                                "url-test" -> "самый отзывчивый из его серверов, как его «Лучший сервер»."
                                else -> "его собственная группа серверов."
                            } +
                            " LTE-серверы берём только на мобильном интернете."
                        ServerChoice.AUTO -> (if (quality.fresh) "Тропа берёт лучший по последней проверке качества: открываются ли Google, YouTube, Telegram, WhatsApp и Claude, и какой отклик. "
                            else "Тропа держит самый отзывчивый сервер. Нажмите «Проверить качество», чтобы выбирать ещё и по сервисам. ") +
                            "На Wi-Fi без LTE, на мобильном интернете LTE в запасе."
                        ServerChoice.MANUAL -> "Вы выбрали сервер сами. Чтобы вернуть автоматику, нажмите «Провайдер» или «Тропа»."
                    },
                    style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 10.dp),
                )
                if (settings.choice == ServerChoice.PROVIDER && subs.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        subs.forEach { s -> ChipButton(s.title, s.id == providerSub?.id) { c.setChoice(ServerChoice.PROVIDER, s.id) } }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Palette.s1)
                    .clickable(onClickLabel = "Проверить качество серверов") { c.runQuality() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (checking) "Проверяем серверы…" else "Проверить качество", style = Type.bodyStrong, color = Palette.vpn)
                    Text(
                        if (quality.at > 0) "Последняя проверка: " + java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale.forLanguageTag("ru")).format(java.util.Date(quality.at)) +
                            ". Сама повторяется ночью на зарядке и Wi-Fi."
                        else "Google, YouTube, Telegram, WhatsApp и Claude через каждый сервер. Займёт около минуты.",
                        style = Type.caption, color = Palette.muted,
                    )
                }
                Ico(Icons.chevron, tint = Palette.muted, size = 18.dp)
            }
            if (subs.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChipButton("Все", filter == null) { filter = null }
                    subs.forEach { s -> ChipButton(s.title, filter == s.id) { filter = s.id } }
                }
            }
        }
        group("Задержка до 100 мс", fast, row,
            note = "Задержка — это отклик, а не скорость. Скорость зависит ещё и от загрузки сервера.")
        group("100–200 мс", good, row)
        group("Больше 200 мс", slow, row)
        group(if (delays.isEmpty()) "Ещё не проверены" else "Проверим после подключения", unknown, row)
        group("Не отвечают", dead, row)
        group("Для мобильного интернета (LTE, белые списки)", kinds[Names.Kind.MOBILE].orEmpty(), row,
            note = "Работают только на мобильном интернете и не у всех операторов. На Wi-Fi Тропа их не выбирает.")
        group("Российские серверы", kinds[Names.Kind.RU_EXIT].orEmpty(), row,
            note = "Весь трафик идёт на сервер в России, а заблокированное провайдер сам отправляет за границу. " +
                "Сайты видят российский адрес. В автоматику Тропа их не берёт — выберите вручную, если нужно.")
        item { Spacer(24.dp) }
    }
}

private class RowOpts(
    val delays: Map<String, Int>,
    val usedId: String?,
    val auto: Boolean,
    val advanced: Boolean,
    val c: Controller,
    val quality: Map<String, app.tropa.data.QualityInfo> = emptyMap(),
)

private fun androidx.compose.foundation.lazy.LazyListScope.group(
    title: String,
    list: List<Pair<Subscription, Node>>,
    o: RowOpts,
    note: String? = null,
) {
    if (list.isEmpty()) return
    val delays = o.delays
    val usedId = o.usedId
    val auto = o.auto
    val advanced = o.advanced
    val c = o.c
    item(key = "h-$title") {
        SectionLabel(title)
        if (note != null) Text(note, style = Type.caption, color = Palette.muted, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    }
    items(list, key = { it.second.id }) { (sub, node) ->
        val d = delays[node.id]
        val used = node.id == usedId
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .clickable(onClickLabel = "Выбрать этот сервер") { c.selectNode(node.id) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Flag(Names.country(node.name))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            Names.clean(node.name), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (d == 0) Palette.muted else Palette.text,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (used) Pill(if (auto) "сейчас" else "выбран")
                    }
                    Text(
                        if (advanced) "${sub.title}, ${Names.protocol(node)}" else sub.title,
                        style = Type.caption, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // Что не открылось через этот сервер при последней проверке.
                    o.quality[node.id]?.failed?.takeIf { it.isNotEmpty() }?.let { failed ->
                        val names = failed.mapNotNull { k -> app.tropa.core.Quality.checks.firstOrNull { it.key == k }?.title }
                        Text("Не открывается: " + names.joinToString(", "), style = Type.caption, color = Palette.danger)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(delayText(d), style = Type.bodyStrong, color = if (d == 0) Palette.muted else Palette.text)
                    if (d != null && d > 0) Box(Modifier.padding(top = 5.dp)) { QualityBars(d) }
                }
            }
            HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), thickness = 1.dp)
        }
    }
}
