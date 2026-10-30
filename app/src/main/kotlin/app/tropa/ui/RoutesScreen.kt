package app.tropa.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tropa.data.RouteMode
import app.tropa.data.Settings
import app.tropa.ui.Controller.Companion.plural

private val modeNotes = mapOf(
    RouteMode.ALL to "Весь трафик идёт через VPN. Приложения и сайты, которые вы пустили напрямую, всё равно идут мимо.",
    RouteMode.RU_DIRECT to "Российские сайты идут напрямую, остальное через VPN. Самый удобный режим на каждый день.",
    RouteMode.BLOCKED_ONLY to "Через VPN идёт только то, что заблокировано в России. Остальное напрямую: так быстрее и экономнее.",
    RouteMode.PROVIDER to "Маршруты настроил ваш провайдер, как в Happ и INCY. Ваши приложения и сайты всё равно важнее.",
)

@Composable
fun RoutesScreen(c: Controller) {
    val settings by c.store.settings.collectAsState()
    val subs by c.store.subs.collectAsState()
    val providerName = subs.firstOrNull { it.routing.isNotBlank() }?.title
    var apps by remember { mutableStateOf<List<Controller.AppInfo>?>(null) }
    var showSites by rememberSaveable { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { apps = c.installedApps() }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        item {
            Text("Маршруты", style = Type.title, modifier = Modifier.padding(top = 14.dp))
            Text("Что идёт через VPN, а что напрямую", style = Type.caption, color = Palette.muted)
            Spacer(16.dp)
            ModeSelector(settings.mode, providerName) { c.setMode(it) }
            Text(
                modeNotes.getValue(settings.mode),
                style = Type.caption, color = Palette.muted,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp).heightIn(min = 36.dp),
            )
            Row(Modifier.padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("Приложения ${settings.directApps.size.takeIf { it > 0 }?.let { "· $it" } ?: ""}".trim(), !showSites) { showSites = false }
                ChipButton("Сайты ${settings.customSites.size.takeIf { it > 0 }?.let { "· $it" } ?: ""}".trim(), showSites) { showSites = true }
            }
        }
        if (showSites) sitesSection(c, settings, site, { site = it })
        else appsSection(c, settings, apps, query, { query = it })
        item { Spacer(24.dp) }
    }
}

@Composable
private fun ModeSelector(mode: RouteMode, providerName: String?, onSelect: (RouteMode) -> Unit) {
    @Composable
    fun option(m: RouteMode, label: String, modifier: Modifier) {
        val sel = mode == m
        Box(
            modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (sel) Palette.s2 else Color.Transparent)
                .semantics { role = Role.RadioButton; selected = sel }
                .clickable { onSelect(m) }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = Type.captionStrong, color = if (sel) Palette.text else Palette.muted, textAlign = TextAlign.Center)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.s1).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            option(RouteMode.ALL, "Всё через VPN", Modifier.weight(1f))
            option(RouteMode.RU_DIRECT, "Россия напрямую", Modifier.weight(1f))
            option(RouteMode.BLOCKED_ONLY, "Только блоки", Modifier.weight(1f))
        }
        // Провайдер прислал свои маршруты: даём выбрать их одним касанием.
        if (providerName != null) option(RouteMode.PROVIDER, "Как у провайдера: $providerName", Modifier.fillMaxWidth())
    }
}

// ---------- приложения ----------

private fun LazyListScope.appsSection(
    c: Controller,
    settings: Settings,
    apps: List<Controller.AppInfo>?,
    query: String,
    onQuery: (String) -> Unit,
) {
    val installedRu = apps.orEmpty().filter { it.pkg in Controller.ruApps && it.pkg !in settings.directApps }
    // Сначала те, что идут напрямую, чтобы их было видно сразу.
    val filtered = apps.orEmpty()
        .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.pkg in settings.directApps }

    item {
        if (installedRu.isNotEmpty() && !settings.ruHintDismissed && settings.mode != RouteMode.BLOCKED_ONLY) {
            val names = installedRu.take(2).joinToString(", ") { it.label }
            val more = installedRu.size - 2
            Panel(Modifier.padding(top = 10.dp), color = Palette.direct.copy(alpha = 0.13f).over(Palette.s1)) {
                Text(
                    names + (if (more > 0) " и ещё $more ${plural(more, "приложение", "приложения", "приложений")}" else "") +
                        " замечают включённый VPN или зарубежный адрес и могут отказаться работать. " +
                        "Если пустить их напрямую, они VPN не увидят. С маршрутами провайдера это не спорит: " +
                        "такие приложения просто идут мимо туннеля.",
                    style = Type.small,
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Пустить напрямую", color = Palette.direct, ink = Palette.directInk) {
                        c.setDirectMany(installedRu.map { it.pkg })
                    }
                    Box(
                        Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).clickable { c.dismissRuHint() }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Не нужно", style = Type.bodyStrong, color = Palette.muted) }
                }
            }
        }
        Text(
            "Приложения «напрямую» идут мимо VPN целиком, как будто его нет.",
            style = Type.caption, color = Palette.muted, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 10.dp),
        )
        Field(query, onQuery, "Найти приложение", "Поиск приложения", leading = true)
        if (apps == null) Text("Загружаем список…", style = Type.caption, color = Palette.muted, modifier = Modifier.padding(16.dp))
    }
    items(filtered, key = { it.pkg }) { app ->
        Column {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(app.label, app.icon)
                Text(app.label, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                RouteToggle(app.pkg in settings.directApps) { c.setDirect(app.pkg, it) }
            }
            HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), thickness = 1.dp)
        }
    }
}

// ---------- свои сайты ----------

private fun LazyListScope.sitesSection(c: Controller, settings: Settings, site: String, onSite: (String) -> Unit) {
    item {
        Text(
            "Свои сайты важнее режима. Например, рабочий портал можно пустить напрямую, а нужный зарубежный сайт — через VPN.",
            style = Type.caption, color = Palette.muted, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 10.dp),
        )
        Field(site, onSite, "Адрес сайта, например example.com", "Адрес сайта", uri = true)
        // Пока адрес вводят, показываем, куда он идёт сейчас: так видно, нужно ли правило.
        val now = c.checker.check(site, settings.mode, settings.customSites)
        if (now != null) {
            Text(
                "Сейчас: " + (if (now.viaVpn) "через VPN" else "напрямую") + ", " + now.reason + ".",
                style = Type.caption, color = Palette.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Через VPN", modifier = Modifier.weight(1f)) { if (c.setSite(site, true)) onSite("") }
            PrimaryButton("Напрямую", color = Palette.direct, ink = Palette.directInk, modifier = Modifier.weight(1f)) {
                if (c.setSite(site, false)) onSite("")
            }
        }
        if (settings.customSites.isEmpty()) {
            Text(
                "Своих сайтов пока нет.",
                style = Type.caption, color = Palette.muted, modifier = Modifier.padding(start = 4.dp, top = 18.dp),
            )
        } else {
            SectionLabel("Ваши сайты")
        }
    }
    items(settings.customSites.entries.sortedBy { it.key }.toList(), key = { it.key }) { (host, viaVpn) ->
        Column {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (viaVpn) Palette.vpn else Palette.direct))
                Text(host, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                RouteToggle(!viaVpn) { direct -> c.setSite(host, !direct) }
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Убрать сайт") { c.removeSite(host) },
                    contentAlignment = Alignment.Center,
                ) { Ico(Icons.close, tint = Palette.muted, size = 18.dp, desc = "Убрать $host") }
            }
            HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), thickness = 1.dp)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, desc: String, leading: Boolean = false, uri: Boolean = false) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Type.body.copy(color = Palette.text),
        cursorBrush = SolidColor(Palette.vpn),
        keyboardOptions = if (uri) KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done) else KeyboardOptions.Default,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.s1)
            .border(BorderStroke(1.5.dp, if (uri) Palette.line else Color.Transparent), RoundedCornerShape(14.dp))
            .semantics { contentDescription = desc }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (leading) Ico(Icons.search, tint = Palette.muted, size = 18.dp)
                Box {
                    if (value.isEmpty()) Text(placeholder, style = Type.body, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        },
    )
}

/** Настоящий значок приложения, а если Android его не отдал — квадрат с первой буквой. */
@Composable
private fun AppIcon(label: String, icon: androidx.compose.ui.graphics.ImageBitmap?) {
    if (icon != null) {
        androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
        return
    }
    val hues = listOf(0xFF3C6F9E, 0xFF8A4E9E, 0xFF3E8A5C, 0xFF9E6A2E, 0xFF9E3F4A, 0xFF3F8A8A, 0xFF5D6B74)
    val color = Color(hues[(label.hashCode() and 0x7fffffff) % hues.size])
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(label.take(1).uppercase(), style = Type.bodyStrong, color = Color.White)
    }
}

/** Два положения: через VPN (щит) и напрямую (стрелка). */
@Composable
private fun RouteToggle(direct: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.s1).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(false to Icons.shield, true to Icons.arrow).forEach { (isDirect, icon) ->
            val sel = direct == isDirect
            val bg = when {
                !sel -> Color.Transparent
                isDirect -> Palette.direct
                else -> Palette.vpn
            }
            Box(
                Modifier
                    .size(width = 48.dp, height = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(bg)
                    .semantics { role = Role.RadioButton; selected = sel; contentDescription = if (isDirect) "Напрямую" else "Через VPN" }
                    .clickable { onChange(isDirect) },
                contentAlignment = Alignment.Center,
            ) {
                Ico(icon, tint = if (!sel) Palette.muted else if (isDirect) Palette.directInk else Palette.vpnInk, size = 18.dp)
            }
        }
    }
}

private fun Color.over(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}
