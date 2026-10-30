package app.tropa.core

import app.tropa.data.RouteMode
import app.tropa.data.ServerChoice
import app.tropa.data.Settings
import app.tropa.data.Subscription
import app.tropa.ui.Names
import org.json.JSONArray
import org.json.JSONObject

/**
 * Собирает конфигурацию ядра Xray.
 *
 * Серверы называются по своему id («n-<id>»): в разных подписках бывают одинаковые
 * «Финляндия». Открытых локальных прокси нет: трафик приходит через TUN, наружу
 * смотрит только страница метрик на 127.0.0.1 — в ней статистика, не прокси.
 *
 * Выбор сервера — балансировщики Xray leastLoad с «ценой» сервера, как в
 * «Лучшем сервере» у провайдеров: из живых берётся тот, у кого отклик × цена меньше.
 * Цена задаёт очередь: провайдера, проверки качества или LTE «в запас».
 */
object XrayConfig {

    const val BALANCER = "main"
    const val TEST_URL = "https://www.gstatic.com/generate_204"
    private const val DNS_TAG = "dns-query"

    /** Списки, которые лежат в APK. */
    data class Lists(
        val blockedDomains: List<String>,
        val blockedIps: List<String>,
        val geositeTags: Set<String>,
        val geoipTags: Set<String>,
    )

    /** Что выбрано и с какой ценой — по этому приложение понимает, какой сервер сейчас работает. */
    data class Plan(val fixed: String?, val costs: Map<String, Double>)

    fun tag(id: String) = "n-$id"

    /**
     * Очередь выбора. Возвращает null, если сервер закреплён вручную.
     * Цена: обычные по очереди 1, 1001, 2001…; LTE — 1e9, то есть только если обычные легли.
     */
    fun plan(
        subs: List<Subscription>,
        settings: Settings,
        onMobile: Boolean,
        operator: String,
        ranking: List<String>,
        broken: Set<String> = emptySet(),
    ): Plan {
        val everyId = subs.flatMap { s -> s.nodes.map { it.id } }
        if (settings.choice == ServerChoice.MANUAL && settings.manualNodeId in everyId) {
            return Plan(settings.manualNodeId, emptyMap())
        }
        val active = subs.firstOrNull { it.id == settings.providerSubId && it.nodes.isNotEmpty() }
            ?: subs.firstOrNull { it.nodes.isNotEmpty() }
        val scope = when {
            settings.choice == ServerChoice.PROVIDER && active != null -> listOf(active)
            settings.mergeSubs || active == null -> subs
            else -> listOf(active)
        }
        val nodes = scope.flatMap { it.nodes }.filter { n ->
            Names.kind(n.name) != Names.Kind.MOBILE || Names.fitsOperator(n.name, operator)
        }
        val kind = nodes.associate { it.id to Names.kind(it.name) }
        val normal = nodes.filter { kind[it.id] == Names.Kind.NORMAL }.map { it.id }
        val mobile = nodes.filter { kind[it.id] == Names.Kind.MOBILE }.map { it.id }

        val order: List<String> = when {
            settings.choice == ServerChoice.AUTO && ranking.isNotEmpty() ->
                ranking.filter { it in normal } + normal.filter { it !in ranking }
            settings.choice == ServerChoice.PROVIDER && active?.groupType.isNullOrBlank() -> normal // порядок провайдера
            else -> emptyList() // «самый отзывчивый»: у всех одна цена
        }
        val costs = linkedMapOf<String, Double>()
        if (order.isEmpty()) normal.forEach { costs[it] = 1.0 }
        else order.forEachIndexed { i, id -> costs[id] = 1.0 + i * 1000.0 }
        // На этих серверах при последней проверке что-то не открылось (например, Claude
        // на одном из серверов): берём их, только если остальные не отвечают.
        costs.keys.filter { it in broken }.forEach { costs[it] = costs.getValue(it) * 1e6 }
        if (onMobile) mobile.forEach { costs[it] = 1e9 }
        if (costs.isEmpty()) {
            // Обычных нет: берём что есть, лишь бы работало.
            nodes.forEach { costs[it.id] = 1.0 }
        }
        return Plan(null, costs)
    }

    fun build(
        subs: List<Subscription>,
        settings: Settings,
        lists: Lists,
        metricsPort: Int,
        provider: ProviderRouting.Profile? = null,
        onMobile: Boolean = false,
        operator: String = "",
        ranking: List<String> = emptyList(),
        tun: Boolean = true,
        broken: Set<String> = emptySet(),
        /** Root-режим: вход TPROXY на этом порту вместо TUN (см. [RootProxy]). */
        tproxyPort: Int = 0,
    ): Pair<String, Plan> {
        val mode = if (settings.mode == RouteMode.PROVIDER && provider == null) RouteMode.RU_DIRECT else settings.mode
        val plan = plan(subs, settings, onMobile, operator, ranking, broken)

        val outbounds = JSONArray()
        val built = HashSet<String>()
        subs.flatMap { it.nodes }.forEach { n ->
            val obs = XrayOutbound.buildAll(n.proxy, tag(n.id))
            if (obs.isNotEmpty()) {
                obs.forEach { outbounds.put(it) }
                built += n.id
            }
        }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))

        // Куда идёт «через VPN»: закреплённый сервер или балансировщик.
        val fixed = plan.fixed?.takeIf { it in built }
        val costs = plan.costs.filterKeys { it in built }
        fun proxy(o: JSONObject): JSONObject =
            if (fixed != null || costs.isEmpty()) o.put("outboundTag", fixed?.let(::tag) ?: "direct")
            else o.put("balancerTag", BALANCER)

        val rules = JSONArray()
        fun rule(build: JSONObject.() -> Unit) = rules.put(JSONObject().put("type", "field").apply(build))

        // DNS: собственные запросы ядра — первыми, иначе запрос к 77.88.8.8:53 попал бы под правило
        // «порт 53 → DNS ядра» и ушёл по кругу. Запросы приложений — во встроенный DNS ядра.
        rule { put("inboundTag", JSONArray().put(DNS_TAG)); put("ip", JSONArray().put("77.88.8.8").put("77.88.8.1")); put("outboundTag", "direct") }
        rule { put("inboundTag", JSONArray().put(DNS_TAG)); proxy(this) }
        rule { put("port", "53"); put("network", "udp,tcp"); put("outboundTag", "dns-out") }
        rule { put("ip", JSONArray().put("geoip:private")); put("outboundTag", "direct") }
        rule { put("domain", JSONArray().put("geosite:private")); put("outboundTag", "direct") }
        // Проверки интернета Android, Apple и Windows — напрямую: часть серверов отвечает на них 403.
        rule {
            put("domain", JSONArray(listOf(
                "domain:connectivitycheck.gstatic.com", "domain:connectivitycheck.android.com", "domain:clients3.google.com",
                "domain:captive.apple.com", "domain:msftconnecttest.com", "domain:msftncsi.com",
            )))
            put("outboundTag", "direct")
        }
        // Без этого домена Claude пишет «Can't reach Claude», а в российских списках его нет.
        rule { put("domain", JSONArray().put("domain:assets-proxy.anthropic.com")); proxy(this) }
        rule { put("protocol", JSONArray().put("bittorrent")); put("outboundTag", "direct") }

        // Свои сайты пользователя важнее любого режима.
        val ownVpn = settings.customSites.filterValues { it }.keys.map { "domain:$it" }
        val ownDirect = settings.customSites.filterValues { !it }.keys.map { "domain:$it" }
        if (ownDirect.isNotEmpty()) rule { put("domain", JSONArray(ownDirect)); put("outboundTag", "direct") }
        if (ownVpn.isNotEmpty()) rule { put("domain", JSONArray(ownVpn)); proxy(this) }

        val blockedDomains = lists.blockedDomains.map { "domain:${it.trimStart('.')}" }
        fun blocked() {
            if (blockedDomains.isNotEmpty()) rule { put("domain", JSONArray(blockedDomains)); proxy(this) }
            if (lists.blockedIps.isNotEmpty()) rule { put("ip", JSONArray(lists.blockedIps)); proxy(this) }
        }
        val ruTags = listOf("geosite:category-ru").filter { it.substringAfter(':') in lists.geositeTags }
        when (mode) {
            RouteMode.ALL -> rule { put("network", "tcp,udp"); proxy(this) }
            RouteMode.RU_DIRECT -> {
                blocked()
                rule { put("domain", JSONArray(listOf("domain:ru", "domain:su", "domain:xn--p1ai") + ruTags)); put("outboundTag", "direct") }
                rule { put("ip", JSONArray().put("geoip:ru")); put("outboundTag", "direct") }
                rule { put("network", "tcp,udp"); proxy(this) }
            }
            RouteMode.BLOCKED_ONLY -> {
                blocked()
                rule { put("network", "tcp,udp"); put("outboundTag", "direct") }
            }
            RouteMode.PROVIDER -> {
                val p = provider!!
                if (p.blockQuic) rule { put("network", "udp"); put("port", "443"); put("outboundTag", "block") }
                fun entries(list: List<String>) = list.mapNotNull { ProviderRouting.xrayEntry(it, lists) }
                val (blockD, blockI) = entries(p.block).partition { !it.second }
                val (proxyD, proxyI) = entries(p.proxy).partition { !it.second }
                val (directD, directI) = entries(p.direct).partition { !it.second }
                if (blockD.isNotEmpty()) rule { put("domain", JSONArray(blockD.map { it.first })); put("outboundTag", "block") }
                if (blockI.isNotEmpty()) rule { put("ip", JSONArray(blockI.map { it.first })); put("outboundTag", "block") }
                if (proxyD.isNotEmpty()) rule { put("domain", JSONArray(proxyD.map { it.first })); proxy(this) }
                if (proxyI.isNotEmpty()) rule { put("ip", JSONArray(proxyI.map { it.first })); proxy(this) }
                if (directD.isNotEmpty()) rule { put("domain", JSONArray(directD.map { it.first })); put("outboundTag", "direct") }
                if (directI.isNotEmpty()) rule { put("ip", JSONArray(directI.map { it.first })); put("outboundTag", "direct") }
                rule { put("network", "tcp,udp"); if (p.globalProxy) proxy(this) else put("outboundTag", "direct") }
            }
        }

        val routing = JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", rules)
        if (fixed == null && costs.isNotEmpty()) {
            val selector = JSONArray(costs.keys.map(::tag))
            routing.put("balancers", JSONArray().put(JSONObject()
                .put("tag", BALANCER)
                .put("selector", selector)
                .put("fallbackTag", tag(costs.keys.first()))
                .put("strategy", JSONObject().put("type", "leastLoad").put("settings", JSONObject()
                    .put("expected", 1)
                    .put("maxRTT", "4s")
                    .put("tolerance", 0.01)
                    .put("costs", JSONArray(costs.map { (id, v) -> JSONObject().put("match", tag(id)).put("value", v) }))))))
        }

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("metrics", JSONObject().put("tag", "metrics").put("listen", "127.0.0.1:$metricsPort"))
            .put("stats", JSONObject())
            .put("policy", JSONObject().put("system", JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
            .put("dns", dns(settings, mode))
            .put("outbounds", outbounds)
            .put("routing", routing)
        if (fixed == null && costs.isNotEmpty()) {
            config.put("observatory", JSONObject()
                .put("subjectSelector", JSONArray(costs.keys.map(::tag)))
                .put("probeUrl", TEST_URL)
                .put("probeInterval", "2m")
                .put("enableConcurrency", true))
        }
        if (tproxyPort > 0) {
            // Всё, что ядро отправляет само, помечаем: правила iptables такой трафик не перехватывают.
            for (i in 0 until outbounds.length()) {
                val o = outbounds.getJSONObject(i)
                val stream = o.optJSONObject("streamSettings") ?: JSONObject().also { o.put("streamSettings", it) }
                val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
                sockopt.put("mark", RootProxy.MARK)
            }
            config.put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "tproxy")
                .put("listen", "127.0.0.1")
                .put("port", tproxyPort)
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("network", "tcp,udp").put("followRedirect", true))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tproxy", "tproxy")))
                .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic"))))))
        } else if (tun) {
            config.put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "tun")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "tropa").put("MTU", TropaVpnService.MTU))
                .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic"))))))
        }
        return config.toString(2) to plan
    }

    private fun dns(settings: Settings, mode: RouteMode): JSONObject {
        val ru = JSONObject().put("address", "77.88.8.8")
            .put("domains", JSONArray(listOf("domain:ru", "domain:su", "domain:xn--p1ai", "geosite:category-ru")))
            .put("skipFallback", true)
        val servers = JSONArray()
        if (mode != RouteMode.ALL) servers.put(ru)
        servers.put("https://1.1.1.1/dns-query").put("https://8.8.8.8/dns-query")
        return JSONObject()
            .put("tag", DNS_TAG)
            .put("servers", servers)
            .put("queryStrategy", if (settings.ipv6) "UseIP" else "UseIPv4")
    }
}
