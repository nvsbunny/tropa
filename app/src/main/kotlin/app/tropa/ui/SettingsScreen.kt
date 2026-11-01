package app.tropa.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tropa.BuildConfig
import app.tropa.data.Subscription
import app.tropa.ui.Controller.Companion.plural
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(c: Controller, onAddSub: () -> Unit, onSendToTv: () -> Unit = {}) {
    val context = LocalContext.current
    val subs by c.store.subs.collectAsState()
    val settings by c.store.settings.collectAsState()
    val updating by c.updating.collectAsState()
    var confirmDelete by remember { mutableStateOf<Subscription?>(null) }
    var showLog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        Text("Настройки", style = Type.title, modifier = Modifier.padding(top = 14.dp))

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            SectionLabel("Подписки", Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onAddSub).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Ico(Icons.plus, tint = Palette.vpn, size = 18.dp)
                Text("Добавить", style = Type.captionStrong, color = Palette.vpn)
            }
        }
        if (subs.isEmpty()) {
            Panel { Text("Подписок пока нет.", style = Type.small, color = Palette.muted) }
        }
        subs.forEach { s ->
            Panel(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = Type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subLine(s), style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (!s.isLocal) {
                        IconButtonBox(Icons.refresh, "Обновить подписку", tint = if (s.url in updating) Palette.vpn else Palette.text) {
                            c.updateSub(s.id)
                        }
                        Spacer(0.dp)
                    }
                    Row(Modifier.padding(start = 8.dp)) {
                        IconButtonBox(Icons.trash, "Удалить подписку", tint = Palette.muted) { confirmDelete = s }
                    }
                }
                if (s.lastError.isNotBlank()) {
                    Text("Последнее обновление: ${s.lastError}", style = Type.caption, color = Palette.danger, modifier = Modifier.padding(top = 8.dp))
                }
                if (s.announce.isNotBlank()) {
                    Text(s.announce, style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        SectionLabel("Защита")
        Panel {
            SettingRow(
                "Не пускать в интернет без VPN",
                "Откроем настройки Android. Включите там «Постоянная VPN» и «Блокировать соединения без VPN» для Тропы.",
            ) {
                context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Уведомление «Подключено»", style = Type.body)
                    Text(
                        "Без него VPN работает так же, а включён ли он — видно по ключу в строке состояния, плитке в шторке и виджету.",
                        style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = settings.notification,
                    onCheckedChange = { c.setNotification(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.vpn, checkedThumbColor = Palette.vpnInk),
                )
            }
        }

        SectionLabel("Для опытных")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Подробности", style = Type.body)
                    Text("Протоколы и адреса серверов, журнал ядра, IPv6. Обычному пользователю не нужно.", style = Type.caption, color = Palette.muted)
                }
                Switch(
                    checked = settings.advanced,
                    onCheckedChange = { c.setAdvanced(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.vpn, checkedThumbColor = Palette.vpnInk),
                )
            }
            if (settings.advanced) {
                HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("IPv6", style = Type.body)
                        Text("Выключите, если с ним сайты открываются медленно.", style = Type.caption, color = Palette.muted)
                    }
                    Switch(
                        checked = settings.ipv6,
                        onCheckedChange = { c.setIpv6(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.vpn, checkedThumbColor = Palette.vpnInk),
                    )
                }
                if (c.hasMihomo()) {
                    HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 10.dp))
                    Text("Ядро", style = Type.body)
                    Text(
                        "Xray — как у Happ и INCY: провайдеры настраивают серверы под него. mihomo умеет TUIC и приёмы Clash, " +
                            "но часть серверов с Reality через него не подключается. Серверы, маршруты и выбор — те же.",
                        style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        app.tropa.core.CoreKind.entries.forEach { k ->
                            ChipButton(k.title + if (k == app.tropa.core.CoreKind.XRAY) " (обычно)" else "", settings.core == k.name) { c.setCore(k) }
                        }
                    }
                }
                HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Root-режим (TPROXY)", style = Type.body)
                        Text(
                            "Для телефонов с root (Magisk, KernelSU): без VPN, трафик заворачивают правила iptables, ядро Xray принимает его через TPROXY. " +
                                "Приложения «напрямую» идут мимо, IPv6 закрыт. Ядро — только Xray. Без root не включится.",
                            style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = settings.rootMode,
                        onCheckedChange = { c.setRootMode(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.vpn, checkedThumbColor = Palette.vpnInk),
                    )
                }
                HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 10.dp))
                SettingRow("Журнал ядра", "Последние сообщения ядра: пригодятся, если сервер не подключается.") { showLog = true }
            }
        }

        SectionLabel("Телевизор")
        Panel {
            SettingRow(
                "Отправить на телевизор",
                "Откройте Тропу на телевизоре — там будет QR-код. Откроем камеру: наведите её на код и нажмите ссылку. Если камера показала только текст, скопируйте его и вставьте в Тропе через «+».",
            ) { onSendToTv() }
        }

        SectionLabel("Обновления")
        UpdatesPanel(c, settings.autoUpdate)

        SectionLabel("О приложении")
        Panel {
            Text("Тропа ${BuildConfig.VERSION_NAME}", style = Type.body)
            Text(
                "Ядро Xray 26.10.31 — то же, что у Happ и INCY" + (if (c.hasMihomo()) ", для опытных — ещё mihomo 1.19.33" else "") + ". Приложение не собирает данные и не содержит трекеров. В сеть ходит только к вашим подпискам и серверам и раз в несколько часов на GitHub — проверить, вышла ли новая версия. Открытых локальных прокси нет: наружу смотрит только страница статистики ядра на самом телефоне.",
                style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(24.dp)
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Palette.s1,
            title = { Text("Удалить «${s.title}»?", style = Type.heading) },
            text = { Text("Серверы этой подписки пропадут из списка. Вернуть можно, добавив ссылку заново.", style = Type.small, color = Palette.muted) },
            confirmButton = {
                TextButton(onClick = { c.removeSub(s.id); confirmDelete = null }) { Text("Удалить", color = Palette.danger, style = Type.bodyStrong) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Оставить", color = Palette.text, style = Type.bodyStrong) }
            },
        )
    }

    if (showLog) {
        val text = remember {
            runCatching { File(context.filesDir, "core/core.log").readLines().takeLast(200).joinToString("\n") }
                .getOrDefault("").ifBlank { "Журнал пуст: ядро ещё не запускалось." }
        }
        AlertDialog(
            onDismissRequest = { showLog = false },
            containerColor = Palette.s1,
            title = { Text("Журнал ядра", style = Type.heading) },
            text = {
                Text(
                    text,
                    style = Type.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                    color = Palette.muted,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Закрыть", color = Palette.text, style = Type.bodyStrong) } },
        )
    }
}

@Composable
private fun UpdatesPanel(c: Controller, auto: Boolean) {
    val context = LocalContext.current
    val state by app.tropa.core.Updater.state.collectAsState()
    val resumes by c.resumes.collectAsState()
    // Разрешение выдают в настройках Android — перепроверяем при каждом возврате в Тропу.
    val canInstall = remember(resumes) { app.tropa.core.Updater.canInstall(context) }
    val time = SimpleDateFormat("d.MM HH:mm", Locale.forLanguageTag("ru"))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Тропа ${BuildConfig.VERSION_NAME}", style = Type.body)
                val (line, danger) = when (val s = state) {
                    app.tropa.core.Updater.State.Idle -> "Ещё не проверяли" to false
                    app.tropa.core.Updater.State.Checking -> "Проверяем…" to false
                    is app.tropa.core.Updater.State.UpToDate -> "Последняя версия · проверено ${time.format(Date(s.at))}" to false
                    is app.tropa.core.Updater.State.Available -> "Вышла версия ${s.release.versionName}" + (if (s.release.notes.isNotBlank()) ": ${s.release.notes}" else "") to false
                    is app.tropa.core.Updater.State.Downloading -> "Скачиваем ${s.release.versionName}… ${(s.progress * 100).toInt()}%" to false
                    is app.tropa.core.Updater.State.Installing -> "Устанавливаем. VPN переподключится сам" to false
                    is app.tropa.core.Updater.State.Failed -> s.message to true
                }
                Text(line, style = Type.caption, color = if (danger) Palette.danger else Palette.muted, modifier = Modifier.padding(top = 2.dp))
            }
            val available = state is app.tropa.core.Updater.State.Available || (state as? app.tropa.core.Updater.State.Failed)?.release != null
            when {
                available && !canInstall -> ChipButton("Разрешить", false) {
                    // Один раз: «Установка неизвестных приложений» → Тропа.
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                available -> ChipButton("Обновить", true) { c.installUpdate() }
                state is app.tropa.core.Updater.State.Downloading || state is app.tropa.core.Updater.State.Installing ||
                    state == app.tropa.core.Updater.State.Checking -> Unit
                else -> ChipButton("Проверить", false) { c.checkUpdates(force = true) }
            }
        }
        if (state is app.tropa.core.Updater.State.Available && !canInstall) {
            Text(
                "Один раз разрешите Тропе устанавливать приложения — это нужно только для её собственных обновлений.",
                style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 8.dp),
            )
        }
        val listsAt by c.listsAt.collectAsState()
        Text(
            "Списки сайтов и стран: " + (if (listsAt > 0) "обновлены ${time.format(Date(listsAt))}" else "из версии приложения") +
                ". Обновляются сами раз в три дня по Wi-Fi.",
            style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 8.dp),
        )
        HorizontalDivider(color = Palette.line.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Обновлять автоматически", style = Type.body)
                Text(
                    "Проверяем дважды в сутки, скачиваем по Wi-Fi и ставим, когда телефоном не пользуются. VPN включится обратно сам.",
                    style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = auto,
                onCheckedChange = { c.setAutoUpdate(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.vpn, checkedThumbColor = Palette.vpnInk),
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = Type.body)
            Text(sub, style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Ico(Icons.chevron, tint = Palette.muted, size = 18.dp)
    }
}

private fun subLine(s: Subscription): String {
    val parts = mutableListOf("${s.nodes.size} ${plural(s.nodes.size, "сервер", "сервера", "серверов")}")
    if (s.total > 0) parts += "${Controller.formatBytes(s.upload + s.download)} из ${Controller.formatBytes(s.total)}"
    if (s.expire > 0) parts += "до " + SimpleDateFormat("d MMMM", Locale.forLanguageTag("ru")).format(Date(s.expire * 1000))
    if (s.updatedAt > 0) parts += "обновлена " + SimpleDateFormat("d.MM HH:mm", Locale.forLanguageTag("ru")).format(Date(s.updatedAt))
    return parts.joinToString(", ")
}
