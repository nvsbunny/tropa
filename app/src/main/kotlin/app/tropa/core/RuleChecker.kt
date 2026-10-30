package app.tropa.core

import android.content.Context
import app.tropa.data.RouteMode
import java.net.IDN

/**
 * Отвечает на вопрос «куда пойдёт сайт» по тем же спискам и в том же порядке,
 * что и правила ядра в [XrayConfig.build]. Российские сервисы вне зоны .ru ядро знает
 * по geosite:category-ru, приложению этот файл не разобрать — для них ответ приблизительный.
 */
class RuleChecker(context: Context) {

    data class Verdict(val viaVpn: Boolean, val reason: String)

    private class DomainSet(lines: List<String>) {
        val exact = HashSet<String>()
        val suffix = HashSet<String>()

        init {
            lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach {
                when {
                    it.startsWith("domain-suffix,") -> suffix.add(it.substringAfter(',').substringBefore(',').trimStart('.'))
                    it.startsWith("domain,") -> exact.add(it.substringAfter(',').substringBefore(','))
                    ',' in it -> Unit
                    it.startsWith("+.") -> suffix.add(it.substring(2))
                    it.startsWith(".") -> suffix.add(it.substring(1))
                    // В списке itdoginfo простой домен означает «с поддоменами».
                    else -> suffix.add(it)
                }
            }
        }

        fun matches(host: String): Boolean {
            if (host in exact) return true
            var h = host
            while (true) {
                if (h in suffix) return true
                val dot = h.indexOf('.')
                if (dot < 0) return false
                h = h.substring(dot + 1)
            }
        }
    }

    private val blocked by lazy { load(context, "blocked.list") }

    /** Берём список, который ядро уже обновило, а если его ещё нет — тот, что в APK. */
    private fun load(context: Context, name: String): DomainSet {
        val updated = java.io.File(context.filesDir, "core/rules/$name")
        val lines = runCatching { if (updated.length() > 0) updated.readLines() else null }.getOrNull()
            ?: runCatching { context.assets.open("rules/$name").bufferedReader().readLines() }.getOrDefault(emptyList())
        return DomainSet(lines)
    }

    fun normalize(input: String): String? {
        val host = input.trim().lowercase()
            .substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
            .trimEnd('.')
        if (host.isEmpty() || '.' !in host) return null
        return runCatching { IDN.toASCII(host) }.getOrNull()
    }

    fun check(input: String, mode: RouteMode, custom: Map<String, Boolean> = emptyMap()): Verdict? {
        val host = normalize(input) ?: return null
        custom.entries.firstOrNull { (d, _) -> host == d || host.endsWith(".$d") }?.let { (_, vpn) ->
            return Verdict(vpn, "вы сами добавили этот сайт")
        }
        val isBlocked = blocked.matches(host)
        val tld = host.substringAfterLast('.')
        return when (mode) {
            RouteMode.ALL -> Verdict(true, "включён режим «Всё через VPN»")
            RouteMode.RU_DIRECT -> when {
                isBlocked -> Verdict(true, "сайт в списке заблокированных в России")
                tld == "ru" || tld == "su" || tld == "xn--p1ai" -> Verdict(false, "российский домен")
                else -> Verdict(true, "зарубежный сайт. Если его сервер стоит в России, пойдёт напрямую")
            }
            RouteMode.BLOCKED_ONLY ->
                if (isBlocked) Verdict(true, "сайт в списке заблокированных в России")
                else Verdict(false, "сайт не в списке заблокированных")
            // Правила провайдера опираются на его geosite-файлы, их знает только ядро.
            RouteMode.PROVIDER -> null
        }
    }
}
