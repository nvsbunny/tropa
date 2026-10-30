package app.tropa.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Сервер в формате outbound для Xray.
 *
 * Если сервер пришёл из подписки в формате Xray JSON, его исходный outbound лежит
 * в proxy["_xray"] и уходит ядру как есть — так же, как у Happ. Серверы из ссылок
 * (vless://, trojan:// …) и из Clash-подписок переводим из нашего описания.
 */
object XrayOutbound {

    const val RAW = "_xray"

    /** Служебные outbound'ы цепочки (dialerProxy / proxySettings) — с исходными метками. */
    const val CHAIN = "_xray_chain"

    /** На какие outbound'ы ссылается этот: dialerProxy и proxySettings.tag. */
    fun refs(o: JSONObject): List<String> = listOfNotNull(
        o.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")?.takeIf { it.isNotBlank() },
        o.optJSONObject("proxySettings")?.optString("tag")?.takeIf { it.isNotBlank() },
    )

    /**
     * Сервер и его цепочка: первым идёт сам сервер с меткой [tag], за ним служебные
     * outbound'ы с метками «c<tag>-<исходная>». Префикс другой, чтобы балансировщик,
     * который ищет серверы по префиксу «n-», их не выбрал.
     */
    fun buildAll(proxy: Map<String, Any?>, tag: String): List<JSONObject> {
        val raw = proxy[RAW] as? String ?: return listOfNotNull(runCatching { convert(proxy, tag) }.getOrNull())
        return runCatching {
            val chain = (proxy[CHAIN] as? String)?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            val names = chain.associate { it.optString("tag") to "c$tag-${it.optString("tag")}" }
            fun relink(o: JSONObject) = o.apply {
                optJSONObject("streamSettings")?.optJSONObject("sockopt")?.let { s ->
                    names[s.optString("dialerProxy")]?.let { s.put("dialerProxy", it) } ?: s.remove("dialerProxy")
                }
                optJSONObject("proxySettings")?.let { p ->
                    names[p.optString("tag")]?.let { p.put("tag", it) } ?: remove("proxySettings")
                }
            }
            listOf(relink(JSONObject(raw).put("tag", tag))) +
                chain.map { relink(JSONObject(it.toString()).put("tag", names.getValue(it.optString("tag")))) }
        }.getOrDefault(emptyList())
    }

    /** Только сам сервер, без цепочки. null — ядро Xray такой сервер не поддерживает (например, TUIC). */
    fun build(proxy: Map<String, Any?>, tag: String): JSONObject? = buildAll(proxy, tag).firstOrNull()

    private fun s(p: Map<String, Any?>, k: String) = p[k]?.toString().orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun map(p: Map<String, Any?>, k: String) = p[k] as? Map<String, Any?> ?: emptyMap()

    private fun convert(p: Map<String, Any?>, tag: String): JSONObject? {
        val server = s(p, "server")
        val port = s(p, "port").toIntOrNull() ?: return null
        val ob = JSONObject().put("tag", tag)
        when (s(p, "type")) {
            "vless" -> {
                val user = JSONObject().put("id", s(p, "uuid")).put("encryption", s(p, "encryption").ifBlank { "none" })
                s(p, "flow").takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
                ob.put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(user))
                )))
            }
            "vmess" -> {
                val user = JSONObject().put("id", s(p, "uuid")).put("alterId", s(p, "alterId").toIntOrNull() ?: 0)
                    .put("security", s(p, "cipher").ifBlank { "auto" })
                ob.put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(user))
                )))
            }
            "trojan" -> ob.put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", server).put("port", port).put("password", s(p, "password"))
            )))
            "ss" -> ob.put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", server).put("port", port).put("method", s(p, "cipher")).put("password", s(p, "password"))
            )))
            "socks5" -> ob.put("protocol", "socks").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", server).put("port", port).apply {
                    if (s(p, "username").isNotBlank()) put("users", JSONArray().put(JSONObject().put("user", s(p, "username")).put("pass", s(p, "password"))))
                }
            )))
            else -> return null
        }
        ob.put("streamSettings", stream(p))
        return ob
    }

    private fun stream(p: Map<String, Any?>): JSONObject {
        val st = JSONObject()
        val network = s(p, "network").ifBlank { "tcp" }
        val type = s(p, "type")
        val reality = map(p, "reality-opts")
        val tls = p["tls"] == true || type == "trojan"
        val sni = s(p, "servername").ifBlank { s(p, "sni") }
        when {
            reality.isNotEmpty() -> st.put("security", "reality").put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("publicKey", reality["public-key"]?.toString().orEmpty())
                .put("shortId", reality["short-id"]?.toString().orEmpty())
                .put("fingerprint", s(p, "client-fingerprint").ifBlank { "chrome" }))
            tls -> st.put("security", "tls").put("tlsSettings", JSONObject().apply {
                if (sni.isNotBlank()) put("serverName", sni)
                s(p, "client-fingerprint").takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                (p["alpn"] as? List<*>)?.let { put("alpn", JSONArray(it.map { a -> a.toString() })) }
                if (p["skip-cert-verify"] == true) put("allowInsecure", true)
            })
        }
        when (network) {
            "ws" -> {
                val o = map(p, "ws-opts")
                @Suppress("UNCHECKED_CAST")
                val host = (o["headers"] as? Map<String, Any?>)?.get("Host")?.toString().orEmpty()
                if (o["v2ray-http-upgrade"] == true) {
                    st.put("network", "httpupgrade").put("httpupgradeSettings", JSONObject().put("path", o["path"] ?: "/").apply {
                        if (host.isNotBlank()) put("host", host)
                    })
                } else {
                    st.put("network", "ws").put("wsSettings", JSONObject().put("path", o["path"] ?: "/").apply {
                        if (host.isNotBlank()) put("host", host)
                    })
                }
            }
            "grpc" -> st.put("network", "grpc").put("grpcSettings", JSONObject()
                .put("serviceName", map(p, "grpc-opts")["grpc-service-name"]?.toString().orEmpty()))
            "xhttp" -> {
                val o = map(p, "xhttp-opts")
                st.put("network", "xhttp").put("xhttpSettings", JSONObject().put("path", o["path"] ?: "/").apply {
                    o["host"]?.let { put("host", it.toString()) }
                    o["mode"]?.let { put("mode", it.toString()) }
                })
            }
            "h2" -> {
                val o = map(p, "h2-opts")
                st.put("network", "h2").put("httpSettings", JSONObject().put("path", o["path"] ?: "/").apply {
                    (o["host"] as? List<*>)?.let { put("host", JSONArray(it)) }
                })
            }
            "http" -> {
                val o = map(p, "http-opts")
                @Suppress("UNCHECKED_CAST")
                val hosts = (o["headers"] as? Map<String, Any?>)?.get("Host") as? List<*>
                st.put("network", "raw").put("rawSettings", JSONObject().put("header", JSONObject()
                    .put("type", "http")
                    .put("request", JSONObject()
                        .put("path", JSONArray((o["path"] as? List<*>) ?: listOf("/")))
                        .apply { if (hosts != null) put("headers", JSONObject().put("Host", JSONArray(hosts))) })))
            }
            else -> st.put("network", "raw")
        }
        return st
    }
}
