package app.tropa.sub

import org.json.JSONArray
import org.json.JSONObject

/**
 * Подписки в формате Xray JSON: так многие провайдеры отдают серверы Happ.
 * Каждый элемент — полный конфиг Xray: сервер в outbounds (tag "proxy"),
 * маршруты провайдера в routing.rules.
 */
object XrayJson {

    /**
     * [groupType] — как выбирает сам провайдер, если у него есть групповая запись
     * с балансировщиком по обычным серверам («Лучший сервер» — leastLoad,
     * то есть по отклику). Балансировщик только из LTE («Авто-выбор LTE») не в счёт.
     */
    data class Parsed(val proxies: List<Map<String, Any?>>, val routing: String, val groupType: String = "")

    fun parse(text: String): Parsed? {
        val t = text.trim()
        val configs: List<JSONObject> = when {
            t.startsWith("[") -> JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            t.startsWith("{") -> listOf(JSONObject(t))
            else -> return null
        }
        val proxies = configs.mapNotNull { cfg ->
            val outs = cfg.optJSONArray("outbounds") ?: return@mapNotNull null
            val list = (0 until outs.length()).mapNotNull { outs.optJSONObject(it) }
            // Групповые записи («Лучший сервер + ОБХОДЫ БС», «Авто-выбор LTE») — это
            // балансировщик из нескольких серверов. Одним сервером его не описать,
            // а его роль у нас играет выбор «Провайдер».
            val balancers = cfg.optJSONObject("routing")?.optJSONArray("balancers")?.length() ?: 0
            val proxies = list.filter { it.optString("protocol") in supported }
            if (balancers > 0 || proxies.size > 1 && list.none { it.optString("tag") == "proxy" }) return@mapNotNull null
            val ob = list.firstOrNull { it.optString("tag") == "proxy" }
                ?: proxies.firstOrNull()
                ?: return@mapNotNull null
            outbound(ob, cfg.optString("remarks").ifBlank { cfg.optString("name") }, chainOf(ob, list))
        }
        val groupType = configs.firstNotNullOfOrNull { cfg ->
            val balancer = cfg.optJSONObject("routing")?.optJSONArray("balancers")?.optJSONObject(0) ?: return@firstNotNullOfOrNull null
            val outs = cfg.optJSONArray("outbounds") ?: return@firstNotNullOfOrNull null
            val tags = (0 until outs.length()).mapNotNull { outs.optJSONObject(it) }
                .filter { it.optString("protocol") in supported }.map { it.optString("tag") }
            val name = cfg.optString("remarks")
            if (tags.isEmpty() || tags.all { it.contains("lte", true) } || name.contains("lte", true)) return@firstNotNullOfOrNull null
            when (balancer.optJSONObject("strategy")?.optString("type")) {
                "leastLoad", "leastPing" -> "url-test"
                "random", "roundRobin" -> "load-balance"
                else -> "url-test"
            }
        }.orEmpty()
        // Маршруты берём у обычного сервера, а если таких нет — у любой записи.
        val plain = configs.filter { (it.optJSONObject("routing")?.optJSONArray("balancers")?.length() ?: 0) == 0 }
        val routing = (plain + configs).firstNotNullOfOrNull { routingProfile(it) }.orEmpty()
        return Parsed(proxies, routing, groupType)
    }

    private val supported = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2")

    /**
     * Служебные outbound'ы, через которые сервер подключается: dialerProxy и proxySettings.
     * Так провайдеры обходят белые списки — например, сначала «нарезают» пакеты (freedom
     * с fragment), а уже потом идут к серверу. Без этой цепочки сервер не подключится.
     */
    private fun chainOf(ob: JSONObject, list: List<JSONObject>): List<JSONObject> {
        val byTag = list.associateBy { it.optString("tag") }
        val found = LinkedHashMap<String, JSONObject>()
        val queue = ArrayDeque(app.tropa.core.XrayOutbound.refs(ob))
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (t in found || t == ob.optString("tag")) continue
            val o = byTag[t] ?: continue
            found[t] = o
            queue += app.tropa.core.XrayOutbound.refs(o)
        }
        return found.values.toList()
    }

    fun outbound(ob: JSONObject, name: String, chain: List<JSONObject> = emptyList()): Map<String, Any?>? {
        val protocol = ob.optString("protocol")
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val m = linkedMapOf<String, Any?>("name" to name, "udp" to true)
        when (protocol) {
            "vless", "vmess" -> {
                val v = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val user = v.optJSONArray("users")?.optJSONObject(0) ?: return null
                m["type"] = protocol
                m["server"] = v.optString("address")
                m["port"] = v.optInt("port", 443)
                m["uuid"] = user.optString("id")
                if (protocol == "vless") {
                    user.optString("flow").takeIf { it.isNotBlank() }?.let { m["flow"] = it }
                    user.optString("encryption").takeIf { it.isNotBlank() && it != "none" }?.let { m["encryption"] = it }
                } else {
                    m["alterId"] = user.optInt("alterId", 0)
                    m["cipher"] = user.optString("security").ifBlank { "auto" }
                }
            }
            "trojan", "shadowsocks" -> {
                val srv = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                m["type"] = if (protocol == "trojan") "trojan" else "ss"
                m["server"] = srv.optString("address")
                m["port"] = srv.optInt("port", 443)
                m["password"] = srv.optString("password")
                if (protocol == "shadowsocks") m["cipher"] = srv.optString("method")
            }
            else -> {
                // Hysteria и другое, что понимает Xray: описываем только для списка, ядру уйдёт исходник.
                val srv = settings.optJSONArray("servers")?.optJSONObject(0)
                m["type"] = if (protocol.startsWith("hysteria")) "hysteria2" else protocol
                m["server"] = srv?.optString("address") ?: settings.optString("address")
                m["port"] = srv?.optInt("port") ?: settings.optInt("port")
            }
        }
        applyStream(m, stream)
        if (m["type"] == "trojan") {
            m.remove("tls")
            m["servername"]?.let { m["sni"] = it }
            m.remove("servername")
        }
        // Исходный outbound — ядру Xray как есть, без пересказа.
        m[app.tropa.core.XrayOutbound.RAW] = JSONObject(ob.toString()).apply { remove("tag") }.toString()
        if (chain.isNotEmpty()) m[app.tropa.core.XrayOutbound.CHAIN] = JSONArray(chain.map { JSONObject(it.toString()) }).toString()
        return m.takeIf { (it["server"] as? String)?.isNotBlank() == true }
    }

    private fun applyStream(m: MutableMap<String, Any?>, s: JSONObject) {
        val network = s.optString("network", "tcp").lowercase()
        when (s.optString("security")) {
            "reality" -> {
                val r = s.optJSONObject("realitySettings") ?: JSONObject()
                m["tls"] = true
                r.optString("serverName").takeIf { it.isNotBlank() }?.let { m["servername"] = it }
                m["client-fingerprint"] = r.optString("fingerprint").ifBlank { "chrome" }
                m["reality-opts"] = mapOf("public-key" to r.optString("publicKey"), "short-id" to r.optString("shortId"))
            }
            "tls" -> {
                val t = s.optJSONObject("tlsSettings") ?: JSONObject()
                m["tls"] = true
                t.optString("serverName").takeIf { it.isNotBlank() }?.let { m["servername"] = it }
                t.optString("fingerprint").takeIf { it.isNotBlank() }?.let { m["client-fingerprint"] = it }
                t.optJSONArray("alpn")?.let { a -> m["alpn"] = (0 until a.length()).map { a.getString(it) } }
                if (t.optBoolean("allowInsecure")) m["skip-cert-verify"] = true
            }
        }
        when (network) {
            "ws" -> {
                val w = s.optJSONObject("wsSettings") ?: JSONObject()
                val host = w.optString("host").ifBlank { w.optJSONObject("headers")?.optString("Host").orEmpty() }
                m["network"] = "ws"
                m["ws-opts"] = linkedMapOf<String, Any?>("path" to w.optString("path", "/")).apply {
                    if (host.isNotBlank()) put("headers", mapOf("Host" to host))
                }
            }
            "grpc" -> {
                m["network"] = "grpc"
                m["grpc-opts"] = mapOf("grpc-service-name" to (s.optJSONObject("grpcSettings")?.optString("serviceName") ?: ""))
            }
            "xhttp", "splithttp" -> {
                val x = s.optJSONObject("xhttpSettings") ?: s.optJSONObject("splithttpSettings") ?: JSONObject()
                m["network"] = "xhttp"
                m["xhttp-opts"] = linkedMapOf<String, Any?>("path" to x.optString("path", "/")).apply {
                    x.optString("host").takeIf { it.isNotBlank() }?.let { put("host", it) }
                    x.optString("mode").takeIf { it.isNotBlank() }?.let { put("mode", it) }
                }
            }
            "httpupgrade" -> {
                val h = s.optJSONObject("httpupgradeSettings") ?: JSONObject()
                m["network"] = "ws"
                m["ws-opts"] = linkedMapOf<String, Any?>("path" to h.optString("path", "/"), "v2ray-http-upgrade" to true).apply {
                    h.optString("host").takeIf { it.isNotBlank() }?.let { put("headers", mapOf("Host" to it)) }
                }
            }
            else -> m["network"] = "tcp"
        }
    }

    /**
     * Правила маршрутов Xray → профиль в формате Happ/INCY, который понимает
     * [app.tropa.core.ProviderRouting]. Правила по портам и протоколам, кроме
     * блокировки QUIC, пропускаем: у Тропы своя схема DNS.
     */
    private fun routingProfile(cfg: JSONObject): String? {
        val rules = cfg.optJSONObject("routing")?.optJSONArray("rules") ?: return null
        val lists = linkedMapOf(
            "DirectSites" to mutableListOf<String>(), "DirectIp" to mutableListOf(),
            "ProxySites" to mutableListOf(), "ProxyIp" to mutableListOf(),
            "BlockSites" to mutableListOf(), "BlockIp" to mutableListOf(),
        )
        var blockQuic = false
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            // В групповых записях «через VPN» указывает на балансировщик (balancerTag).
            val target = when {
                r.has("balancerTag") -> "Proxy"
                else -> when (r.optString("outboundTag")) {
                    "direct" -> "Direct"
                    "proxy" -> "Proxy"
                    "block" -> "Block"
                    else -> continue
                }
            }
            if (r.has("inboundTag") || r.has("protocol")) continue
            if (r.has("port") || r.has("network")) {
                if (target == "Block" && r.optString("network") == "udp" && r.optString("port") == "443") blockQuic = true
                continue
            }
            r.optJSONArray("domain")?.let { a -> (0 until a.length()).forEach { lists.getValue("${target}Sites") += a.getString(it) } }
            r.optJSONArray("ip")?.let { a -> (0 until a.length()).forEach { lists.getValue("${target}Ip") += a.getString(it) } }
        }
        if (lists.values.all { it.isEmpty() }) return null
        return JSONObject().apply {
            put("Name", cfg.optString("remarks").ifBlank { "Провайдер" })
            put("GlobalProxy", "true")
            if (blockQuic) put("BlockQuic", "true")
            lists.forEach { (k, v) -> put(k, JSONArray(v)) }
        }.toString()
    }
}
