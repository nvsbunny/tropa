package app.tropa.core

import app.tropa.data.RouteMode
import app.tropa.data.Settings
import app.tropa.data.Subscription
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * Конфигурация для второго ядра — mihomo («Для опытных»).
 *
 * Очередь серверов та же, что у Xray ([XrayConfig.plan]): цена 1, 1001, 2001… — порядок,
 * ×1e6 — серверы с неоткрывшимися сервисами, 1e9 — LTE в запас. В mihomo это группы:
 * основная (url-test, если цены равны, иначе fallback по порядку), затем «сломанные»,
 * затем LTE, и общий fallback по ним. Маршруты — из тех же geosite.dat/geoip.dat.
 * Открытых прокси нет: только TUN, управление на 127.0.0.1 со случайным ключом.
 */
object MihomoConfig {

    const val GROUP_PROXY = "PROXY"
    private const val GROUP_MAIN = "MAIN"
    private const val GROUP_BROKEN = "BROKEN"
    private const val GROUP_MOBILE = "MOBILE"

    private fun testGroup(name: String, type: String, members: List<String>) = linkedMapOf<String, Any?>(
        "name" to name, "type" to type, "proxies" to members,
        "url" to XrayConfig.TEST_URL, "interval" to 120, "tolerance" to 40, "timeout" to 4000, "lazy" to false,
    )

    /** Поля, которые нужны только нам, ядру mihomo не отдаём. */
    private fun clean(p: Map<String, Any?>) = LinkedHashMap(p).apply { remove(XrayOutbound.RAW); remove(XrayOutbound.CHAIN) }

    /** mihomo не понимает серверы, у которых нет нужных ему полей (например, Hysteria из Xray JSON). */
    private fun usable(p: Map<String, Any?>): Boolean = when (p["type"]) {
        "vless", "vmess" -> !p["uuid"]?.toString().isNullOrBlank()
        "trojan", "ss", "hysteria2" -> !p["password"]?.toString().isNullOrBlank()
        "socks5" -> true
        else -> false
    }

    fun build(
        subs: List<Subscription>,
        settings: Settings,
        lists: XrayConfig.Lists,
        port: Int,
        secret: String,
        tunFd: Int,
        provider: ProviderRouting.Profile?,
        plan: XrayConfig.Plan,
    ): String {
        val mode = if (settings.mode == RouteMode.PROVIDER && provider == null) RouteMode.RU_DIRECT else settings.mode
        val nodes = subs.flatMap { it.nodes }.filter { usable(it.proxy) }
        val ids = nodes.map { it.id }.toSet()
        val proxies = nodes.map { n -> clean(n.proxy).apply { put("name", n.id) } }

        val groups = mutableListOf<Map<String, Any?>>()
        val fixed = plan.fixed?.takeIf { it in ids }
        val costs = plan.costs.filterKeys { it in ids }
        val proxyMembers = if (fixed != null || costs.isEmpty()) {
            listOfNotNull(fixed) + ids.filter { it != fixed }
        } else {
            val main = costs.filterValues { it < 1e6 }
            val broken = costs.filterValues { it >= 1e6 && it < 1e9 }.keys.toList()
            val mobile = costs.filterValues { it >= 1e9 }.keys.toList()
            val tiers = mutableListOf<String>()
            if (main.isNotEmpty()) {
                val same = main.values.distinct().size == 1
                groups += testGroup(GROUP_MAIN, if (same) "url-test" else "fallback", main.entries.sortedBy { it.value }.map { it.key })
                tiers += GROUP_MAIN
            }
            if (broken.isNotEmpty()) { groups += testGroup(GROUP_BROKEN, "url-test", broken); tiers += GROUP_BROKEN }
            if (mobile.isNotEmpty()) { groups += testGroup(GROUP_MOBILE, "url-test", mobile); tiers += GROUP_MOBILE }
            tiers
        }
        groups += if (fixed == null && proxyMembers.size > 1 && costs.isNotEmpty()) testGroup(GROUP_PROXY, "fallback", proxyMembers)
        else linkedMapOf("name" to GROUP_PROXY, "type" to "select", "proxies" to proxyMembers.ifEmpty { listOf("DIRECT") })

        val config = linkedMapOf<String, Any?>(
            "allow-lan" to false,
            "mode" to "rule",
            "log-level" to "warning",
            "ipv6" to settings.ipv6,
            "external-controller" to "127.0.0.1:$port",
            "secret" to secret,
            "unified-delay" to true,
            "tcp-concurrent" to true,
            "find-process-mode" to "off",
            // Те же geosite.dat и geoip.dat, что и у Xray: формат у них общий.
            "geodata-mode" to true,
            "geodata-loader" to "memconservative",
            "geo-auto-update" to false,
            "profile" to mapOf("store-selected" to false, "store-fake-ip" to true),
            "sniffer" to mapOf(
                "enable" to true, "parse-pure-ip" to true,
                "sniff" to mapOf(
                    "HTTP" to mapOf("ports" to listOf(80, "8080-8880"), "override-destination" to true),
                    "TLS" to mapOf("ports" to listOf(443, 8443)),
                    "QUIC" to mapOf("ports" to listOf(443, 8443)),
                ),
            ),
            "dns" to dns(settings, mode),
            "proxies" to proxies,
            "proxy-groups" to groups,
            "rules" to rules(mode, settings, lists, provider),
            "tun" to mapOf(
                "enable" to true, "stack" to "gvisor", "file-descriptor" to tunFd,
                "auto-route" to false, "auto-detect-interface" to false,
                "dns-hijack" to listOf("any:53", "tcp://any:53"), "mtu" to TropaVpnService.MTU,
            ),
        )
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
            width = 4096
        }
        return Yaml(opts).dump(config)
    }

    private fun dns(settings: Settings, mode: RouteMode): Map<String, Any?> {
        val ru = listOf("77.88.8.8", "77.88.8.1")
        val viaProxy = listOf("https://1.1.1.1/dns-query#$GROUP_PROXY", "https://8.8.8.8/dns-query#$GROUP_PROXY")
        return linkedMapOf<String, Any?>(
            "enable" to true,
            "ipv6" to settings.ipv6,
            "enhanced-mode" to "fake-ip",
            "fake-ip-range" to "198.18.0.1/16",
            "fake-ip-filter" to listOf("+.lan", "+.local", "+.msftconnecttest.com", "+.msftncsi.com", "connectivitycheck.gstatic.com"),
            "default-nameserver" to ru + "1.1.1.1",
            "proxy-server-nameserver" to ru + "1.1.1.1",
            "nameserver" to viaProxy,
        ).apply {
            if (mode != RouteMode.ALL) put("nameserver-policy", mapOf("geosite:category-ru" to ru, "+.ru" to ru, "+.su" to ru, "+.xn--p1ai" to ru))
        }
    }

    private fun rules(mode: RouteMode, settings: Settings, lists: XrayConfig.Lists, provider: ProviderRouting.Profile?): List<String> = buildList {
        add("GEOIP,private,DIRECT,no-resolve")
        listOf("connectivitycheck.gstatic.com", "connectivitycheck.android.com", "clients3.google.com", "captive.apple.com", "msftconnecttest.com")
            .forEach { add("DOMAIN-SUFFIX,$it,DIRECT") }
        add("DOMAIN-SUFFIX,assets-proxy.anthropic.com,$GROUP_PROXY")
        settings.customSites.forEach { (d, vpn) -> add("DOMAIN-SUFFIX,$d,${if (vpn) GROUP_PROXY else "DIRECT"}") }
        fun blocked() {
            lists.blockedDomains.forEach { add("DOMAIN-SUFFIX,${it.trimStart('.')},$GROUP_PROXY") }
            lists.blockedIps.forEach { add("IP-CIDR,$it,$GROUP_PROXY,no-resolve") }
        }
        when (mode) {
            RouteMode.ALL -> add("MATCH,$GROUP_PROXY")
            RouteMode.RU_DIRECT -> {
                blocked()
                listOf("ru", "su", "xn--p1ai").forEach { add("DOMAIN-SUFFIX,$it,DIRECT") }
                if ("category-ru" in lists.geositeTags) add("GEOSITE,category-ru,DIRECT")
                add("GEOIP,ru,DIRECT")
                add("MATCH,$GROUP_PROXY")
            }
            RouteMode.BLOCKED_ONLY -> {
                blocked()
                add("MATCH,DIRECT")
            }
            RouteMode.PROVIDER -> {
                val p = provider!!
                // Метки, которых нет в наших geo-файлах, отсеиваем так же, как для Xray.
                fun ok(e: String) = ProviderRouting.xrayEntry(e, lists) != null
                addAll(ProviderRouting.rules(p.copy(direct = p.direct.filter(::ok), proxy = p.proxy.filter(::ok), block = p.block.filter(::ok)), GROUP_PROXY, geo = true))
            }
        }
    }
}
