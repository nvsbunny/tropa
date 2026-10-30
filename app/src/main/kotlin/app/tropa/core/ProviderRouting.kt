package app.tropa.core

import app.tropa.sub.LinkParser
import org.json.JSONObject

/**
 * Маршруты, которые провайдер присылает для Happ и INCY: base64-JSON со списками
 * DirectSites / ProxySites / BlockSites и т. п. Мы переводим их в правила mihomo,
 * чтобы режим «Как у провайдера» работал так же, как в его «родном» клиенте.
 */
object ProviderRouting {

    data class Profile(
        val name: String,
        val globalProxy: Boolean,
        val geoipUrl: String,
        val geositeUrl: String,
        val direct: List<String>,
        val proxy: List<String>,
        val block: List<String>,
        /** Провайдер режет QUIC (UDP 443), чтобы браузеры шли по TCP. */
        val blockQuic: Boolean = false,
    ) {
        /** Свои geosite/geoip-файлы есть только у профилей Happ/INCY; у Xray JSON их нет. */
        val hasGeoFiles get() = geositeUrl.isNotBlank() || geoipUrl.isNotBlank()
    }

    /** Принимает заголовок routing, ссылку happ://routing/… или голый base64. Пустая строка — нет профиля. */
    fun decode(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.equals("off", true)) return ""
        Regex("^[a-z0-9-]+://routing/(?:on)?add/", RegexOption.IGNORE_CASE).find(s)?.let { s = s.substring(it.range.last + 1) }
        if (s.startsWith("{")) return s.takeIf { isProfile(it) }.orEmpty()
        val json = LinkParser.decodeBase64(LinkParser.decode(s)) ?: return ""
        return json.takeIf { isProfile(it) }.orEmpty()
    }

    /** Ищет профиль маршрутов в теле подписки: некоторые провайдеры кладут его отдельной строкой. */
    fun findInBody(body: String): String =
        body.lineSequence().map { it.trim() }
            .firstOrNull { Regex("^[a-z0-9-]+://routing/", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?.let { decode(it) }.orEmpty()

    private fun isProfile(json: String) = runCatching {
        val o = JSONObject(json)
        listOf("DirectSites", "ProxySites", "DirectIp", "ProxyIp", "BlockSites", "BlockIp").any { o.has(it) }
    }.getOrDefault(false)

    fun parse(json: String): Profile? = runCatching {
        val o = JSONObject(json)
        fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it).trim() } }.orEmpty()
            .filter { it.isNotEmpty() }
        Profile(
            name = o.optString("Name", "Провайдер"),
            globalProxy = o.optString("GlobalProxy", "true").equals("true", true),
            geoipUrl = o.optString("Geoipurl"),
            geositeUrl = o.optString("Geositeurl"),
            direct = list("DirectSites") + list("DirectIp"),
            proxy = list("ProxySites") + list("ProxyIp"),
            block = list("BlockSites") + list("BlockIp"),
            blockQuic = o.optString("BlockQuic").equals("true", true),
        )
    }.getOrNull()

    /**
     * Профиль → правила mihomo. Сначала блок, потом VPN, потом напрямую.
     * [geo] — у ядра есть geosite/geoip-файлы (у Тропы они есть у обоих ядер).
     */
    fun rules(p: Profile, proxyGroup: String, geo: Boolean = p.hasGeoFiles): List<String> = buildList {
        if (p.blockQuic) add("AND,((NETWORK,UDP),(DST-PORT,443)),REJECT")
        p.block.forEach { rule(it, "REJECT", geo)?.let(::add) }
        p.proxy.forEach { rule(it, proxyGroup, geo)?.let(::add) }
        p.direct.forEach { rule(it, "DIRECT", geo)?.let(::add) }
        add("MATCH,${if (p.globalProxy) proxyGroup else "DIRECT"}")
    }.distinct()

    /**
     * Запись профиля для правила Xray. Синтаксис у профилей Happ/INCY и так Xray-овский,
     * поэтому почти всё идёт как есть. Метки geosite/geoip берём, только если они есть
     * в наших geo-файлах: с неизвестной меткой ядро не запустится.
     * Вторая часть пары — true, если это IP-правило.
     */
    fun xrayEntry(entry: String, lists: XrayConfig.Lists): Pair<String, Boolean>? {
        val e = entry.trim()
        return when {
            e.isEmpty() || e.startsWith("ext:") -> null
            e.startsWith("geosite:") -> e.takeIf { e.substringAfter(':').lowercase() in lists.geositeTags }?.let { it to false }
            e.startsWith("geoip:") -> e.takeIf { e.substringAfter(':').lowercase() in lists.geoipTags }?.let { it to true }
            e.startsWith("domain:") || e.startsWith("full:") || e.startsWith("keyword:") || e.startsWith("regexp:") -> e to false
            cidr.matches(e) || (':' in e && e.any { it.isDigit() } && e.none { it.isLetter() && it !in "abcdefABCDEF" }) -> e to true
            else -> e to false
        }
    }

    fun usesGeo(p: Profile) = p.hasGeoFiles &&
        (p.direct + p.proxy + p.block).any { it.startsWith("geosite:") || it.startsWith("geoip:") }

    /**
     * Без файлов провайдера geosite/geoip-теги заменяем своими списками:
     * неизвестный ядру тег сломал бы весь конфиг.
     */
    private fun ownGeo(entry: String, target: String): String? {
        val tag = entry.substringAfter(':').lowercase()
        return when {
            entry.startsWith("geosite:") && (tag == "ru" || tag == "category-ru" || tag.startsWith("category-gov-ru")) ->
                "RULE-SET,ru-domains,$target"
            entry.startsWith("geosite:") && tag.startsWith("ru-blocked") -> "RULE-SET,blocked,$target"
            entry.startsWith("geoip:") && tag == "ru" -> "RULE-SET,ru-ip,$target"
            else -> null // private уже обработан общими правилами, остальное без файлов не понять
        }
    }

    /** «regexp:.*\.ru$» — частый случай «вся доменная зона», его переводим в суффикс. */
    private val zoneRegex = Regex("""^\.\*\\\.([a-z0-9-]+)\$$""")

    private val cidr = Regex("^[0-9.]+(/\\d+)?$")

    fun rule(entry: String, target: String, hasGeoFiles: Boolean = true): String? {
        val e = entry.trim()
        if (!hasGeoFiles && (e.startsWith("geosite:") || e.startsWith("geoip:"))) return ownGeo(e, target)
        return when {
            e.startsWith("geosite:") -> "GEOSITE,${e.substringAfter(':')},$target"
            e.startsWith("geoip:") -> "GEOIP,${e.substringAfter(':')},$target"
            e.startsWith("full:") -> "DOMAIN,${e.substringAfter(':')},$target"
            e.startsWith("domain:") -> "DOMAIN-SUFFIX,${e.substringAfter(':')},$target"
            e.startsWith("keyword:") -> "DOMAIN-KEYWORD,${e.substringAfter(':')},$target"
            e.startsWith("regexp:") -> zoneRegex.find(e.substringAfter(':'))?.let { "DOMAIN-SUFFIX,${it.groupValues[1]},$target" }
            cidr.matches(e) -> "IP-CIDR,${if ('/' in e) e else "$e/32"},$target,no-resolve"
            ':' in e && e.any { it.isDigit() } -> "IP-CIDR6,${if ('/' in e) e else "$e/128"},$target,no-resolve"
            '.' in e -> "DOMAIN-SUFFIX,$e,$target"
            else -> null
        }
    }
}
