package app.tropa.sub

import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * Разбор share-ссылок (vless://, vmess://, trojan://, ss://, hysteria2://, tuic://, socks://)
 * в описание сервера для ядра mihomo. Неизвестные схемы возвращают null.
 */
object LinkParser {

    val schemes = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic", "socks", "socks5")

    fun parse(link: String): Map<String, Any?>? = runCatching {
        val l = link.trim()
        when (l.substringBefore("://").lowercase()) {
            "vless" -> vless(Url(l))
            "vmess" -> vmess(l.substringAfter("://"))
            "trojan" -> trojan(Url(l))
            "ss" -> shadowsocks(l)
            "hysteria2", "hy2" -> hysteria2(Url(l))
            "tuic" -> tuic(Url(l))
            "socks", "socks5" -> socks(Url(l))
            else -> null
        }
    }.getOrNull()

    /** Разобранная ссылка вида scheme://userinfo@host:port/path?query#name. */
    class Url(raw: String) {
        val scheme: String = raw.substringBefore("://").lowercase()
        val name: String
        val userInfo: String
        val host: String
        val port: Int
        val query: Map<String, String>

        init {
            var rest = raw.substringAfter("://")
            name = if ('#' in rest) decode(rest.substringAfterLast('#')).also { rest = rest.substringBeforeLast('#') } else ""
            val q = if ('?' in rest) rest.substringAfter('?').also { rest = rest.substringBefore('?') } else ""
            query = q.split('&').filter { '=' in it }.associate {
                it.substringBefore('=').lowercase() to decode(it.substringAfter('='))
            }
            rest = rest.substringBefore('/')
            userInfo = if ('@' in rest) decode(rest.substringBeforeLast('@')) else ""
            val hostPort = rest.substringAfterLast('@')
            if (hostPort.startsWith("[")) {
                host = hostPort.substring(1, hostPort.indexOf(']'))
                port = hostPort.substringAfter("]:", "443").toIntOrNull() ?: 443
            } else {
                host = hostPort.substringBeforeLast(':')
                port = hostPort.substringAfterLast(':', "443").toIntOrNull() ?: 443
            }
        }

        fun q(vararg keys: String): String? = keys.firstNotNullOfOrNull { query[it]?.takeIf { v -> v.isNotEmpty() } }
    }

    fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    fun decodeBase64(s: String): String? {
        val clean = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.isEmpty()) return null
        val padded = clean.padEnd((clean.length + 3) / 4 * 4, '=')
        return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
            ?: runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull()
    }

    private fun isTrue(v: String?) = v == "1" || v.equals("true", true)

    /** Общая часть для VLESS и Trojan: транспорт, TLS и Reality. */
    private fun applyTransport(m: MutableMap<String, Any?>, u: Url, defaultTls: Boolean) {
        val network = (u.q("type") ?: "tcp").lowercase()
        val security = (u.q("security") ?: if (defaultTls) "tls" else "none").lowercase()
        val headerType = u.q("headertype")
        m["network"] = when (network) {
            "tcp", "raw" -> if (headerType == "http") "http" else "tcp"
            "h2", "http" -> "h2"
            else -> network
        }
        if (security == "tls" || security == "reality") {
            m["tls"] = true
            u.q("sni", "peer", "servername")?.let { m["servername"] = it; m["sni"] = it }
            u.q("fp")?.let { m["client-fingerprint"] = it }
            u.q("alpn")?.let { m["alpn"] = it.split(',').map(String::trim).filter(String::isNotEmpty) }
            if (isTrue(u.q("allowinsecure", "insecure"))) m["skip-cert-verify"] = true
        }
        if (security == "reality") {
            m["reality-opts"] = linkedMapOf<String, Any?>(
                "public-key" to (u.q("pbk") ?: ""),
                "short-id" to (u.q("sid") ?: ""),
            ).also { o -> u.q("pqv")?.let { o["support-x25519mlkem768"] = true } }
            if (m["client-fingerprint"] == null) m["client-fingerprint"] = "chrome"
        }
        val path = u.q("path")
        val host = u.q("host")
        when (m["network"]) {
            "ws" -> m["ws-opts"] = linkedMapOf<String, Any?>().apply {
                put("path", path ?: "/")
                host?.let { put("headers", mapOf("Host" to it)) }
                u.q("ed")?.toIntOrNull()?.let { put("max-early-data", it); put("early-data-header-name", "Sec-WebSocket-Protocol") }
            }
            "grpc" -> m["grpc-opts"] = mapOf("grpc-service-name" to (u.q("servicename") ?: path ?: ""))
            "h2" -> m["h2-opts"] = linkedMapOf<String, Any?>().apply {
                put("path", path ?: "/")
                host?.let { put("host", it.split(',')) }
            }
            "http" -> m["http-opts"] = linkedMapOf<String, Any?>().apply {
                put("path", listOf(path ?: "/"))
                host?.let { put("headers", mapOf("Host" to it.split(','))) }
            }
            "xhttp", "splithttp" -> {
                m["network"] = "xhttp"
                m["xhttp-opts"] = linkedMapOf<String, Any?>().apply {
                    put("path", path ?: "/")
                    host?.let { put("host", it) }
                    u.q("mode")?.let { put("mode", it) }
                }
            }
            "httpupgrade" -> {
                m["network"] = "ws"
                m["ws-opts"] = linkedMapOf<String, Any?>().apply {
                    put("path", path ?: "/")
                    put("v2ray-http-upgrade", true)
                    host?.let { put("headers", mapOf("Host" to it)) }
                }
            }
        }
    }

    private fun base(u: Url, type: String): MutableMap<String, Any?> = linkedMapOf(
        "name" to u.name.ifBlank { "${u.host}:${u.port}" },
        "type" to type,
        "server" to u.host,
        "port" to u.port,
        "udp" to true,
    )

    private fun vless(u: Url): Map<String, Any?> = base(u, "vless").apply {
        put("uuid", u.userInfo)
        u.q("flow")?.let { put("flow", it) }
        u.q("encryption")?.takeIf { it != "none" }?.let { put("encryption", it) }
        u.q("packetencoding")?.let { put("packet-encoding", it) }
        applyTransport(this, u, defaultTls = false)
        // У VLESS имя сервера для TLS называется servername, sni ядру не нужен.
        remove("sni")
    }

    private fun trojan(u: Url): Map<String, Any?> = base(u, "trojan").apply {
        put("password", u.userInfo)
        applyTransport(this, u, defaultTls = true)
        // У Trojan TLS всегда, а имя сервера mihomo берёт из sni.
        remove("tls")
        remove("servername")
    }

    private fun vmess(body: String): Map<String, Any?>? {
        val json = decodeBase64(body.substringBefore('#')) ?: return null
        val o = JSONObject(json)
        val net = o.optString("net", "tcp").lowercase()
        val m = linkedMapOf<String, Any?>(
            "name" to o.optString("ps").ifBlank { o.optString("add") },
            "type" to "vmess",
            "server" to o.optString("add"),
            "port" to (o.optString("port").toIntOrNull() ?: 443),
            "uuid" to o.optString("id"),
            "alterId" to (o.optString("aid").toIntOrNull() ?: 0),
            "cipher" to o.optString("scy").ifBlank { "auto" },
            "udp" to true,
        )
        val host = o.optString("host")
        val path = o.optString("path")
        if (o.optString("tls") == "tls") {
            m["tls"] = true
            o.optString("sni").ifBlank { host }.takeIf { it.isNotEmpty() }?.let { m["servername"] = it }
            o.optString("fp").takeIf { it.isNotEmpty() }?.let { m["client-fingerprint"] = it }
            o.optString("alpn").takeIf { it.isNotEmpty() }?.let { m["alpn"] = it.split(',') }
        }
        when (net) {
            "ws" -> {
                m["network"] = "ws"
                m["ws-opts"] = linkedMapOf<String, Any?>("path" to path.ifBlank { "/" }).apply {
                    if (host.isNotEmpty()) put("headers", mapOf("Host" to host))
                }
            }
            "grpc" -> { m["network"] = "grpc"; m["grpc-opts"] = mapOf("grpc-service-name" to path) }
            "h2", "http" -> {
                m["network"] = "h2"
                m["h2-opts"] = linkedMapOf<String, Any?>("path" to path.ifBlank { "/" }).apply {
                    if (host.isNotEmpty()) put("host", host.split(','))
                }
            }
            else -> if (o.optString("type") == "http") {
                m["network"] = "http"
                m["http-opts"] = linkedMapOf<String, Any?>("path" to listOf(path.ifBlank { "/" })).apply {
                    if (host.isNotEmpty()) put("headers", mapOf("Host" to host.split(',')))
                }
            }
        }
        return m
    }

    private fun shadowsocks(raw: String): Map<String, Any?>? {
        var body = raw.substringAfter("://")
        val name = if ('#' in body) decode(body.substringAfterLast('#')).also { body = body.substringBeforeLast('#') } else ""
        val query = if ('?' in body) body.substringAfter('?').also { body = body.substringBefore('?') } else ""
        body = body.substringBefore('/')
        // Старый формат: всё целиком в base64.
        if ('@' !in body) body = decodeBase64(body) ?: return null
        val user = body.substringBeforeLast('@')
        val hostPort = body.substringAfterLast('@')
        val creds = if (':' in decode(user)) decode(user) else decodeBase64(user) ?: return null
        val u = Url("ss://x@$hostPort")
        val m = linkedMapOf<String, Any?>(
            "name" to name.ifBlank { "${u.host}:${u.port}" },
            "type" to "ss",
            "server" to u.host,
            "port" to u.port,
            "cipher" to creds.substringBefore(':'),
            "password" to creds.substringAfter(':'),
            "udp" to true,
        )
        val plugin = query.split('&').firstOrNull { it.startsWith("plugin=") }?.let { decode(it.substringAfter('=')) }
        if (plugin != null && plugin.startsWith("obfs-local")) {
            val opts = plugin.split(';').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
            m["plugin"] = "obfs"
            m["plugin-opts"] = mapOf("mode" to (opts["obfs"] ?: "http"), "host" to (opts["obfs-host"] ?: ""))
        }
        return m
    }

    private fun hysteria2(u: Url): Map<String, Any?> = base(u, "hysteria2").apply {
        put("password", u.userInfo)
        u.q("sni", "peer")?.let { put("sni", it) }
        if (isTrue(u.q("insecure", "allowinsecure"))) put("skip-cert-verify", true)
        u.q("obfs")?.takeIf { it != "none" }?.let { put("obfs", it) }
        u.q("obfs-password")?.let { put("obfs-password", it) }
        u.q("mport")?.let { put("ports", it) }
        u.q("pinsha256")?.let { put("fingerprint", it) }
        u.q("alpn")?.let { put("alpn", it.split(',')) }
    }

    private fun tuic(u: Url): Map<String, Any?> = base(u, "tuic").apply {
        put("uuid", u.userInfo.substringBefore(':'))
        put("password", u.userInfo.substringAfter(':', ""))
        u.q("sni")?.let { put("sni", it) }
        put("alpn", (u.q("alpn") ?: "h3").split(','))
        u.q("congestion_control")?.let { put("congestion-controller", it) }
        u.q("udp_relay_mode")?.let { put("udp-relay-mode", it) }
        if (isTrue(u.q("allow_insecure", "insecure"))) put("skip-cert-verify", true)
    }

    private fun socks(u: Url): Map<String, Any?> = base(u, "socks5").apply {
        if (u.userInfo.isNotEmpty()) {
            val creds = if (':' in u.userInfo) u.userInfo else decodeBase64(u.userInfo) ?: u.userInfo
            put("username", creds.substringBefore(':'))
            put("password", creds.substringAfter(':', ""))
        }
    }
}
