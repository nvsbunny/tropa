package app.tropa

import app.tropa.core.XrayConfig
import app.tropa.data.RouteMode
import app.tropa.data.Settings
import app.tropa.sub.LinkParser
import app.tropa.sub.SubscriptionFetcher
import app.tropa.sub.SubscriptionParser
import app.tropa.ui.Controller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Ключ Reality в правильном формате (32 байта в base64url), иначе ядро не примет конфиг. */
private const val PBK = "nxqrA7RqSlkBsNCW3ufSXov43LuTiA-vx-qsevzLmK8"

class ParserTest {

    private val vlessReality =
        "vless://11111111-2222-3333-4444-555555555555@fi.example.com:443?type=tcp&security=reality&pbk=$PBK&sid=ab12&sni=www.microsoft.com&fp=chrome&flow=xtls-rprx-vision#%F0%9F%87%AB%F0%9F%87%AE%20%D0%A4%D0%B8%D0%BD%D0%BB%D1%8F%D0%BD%D0%B4%D0%B8%D1%8F%20%E2%9A%A1"

    @Test
    fun vlessReality() {
        val p = LinkParser.parse(vlessReality)!!
        assertEquals("vless", p["type"])
        assertEquals("fi.example.com", p["server"])
        assertEquals(443, p["port"])
        assertEquals("xtls-rprx-vision", p["flow"])
        assertEquals(true, p["tls"])
        assertEquals("www.microsoft.com", p["servername"])
        assertNull(p["sni"])
        @Suppress("UNCHECKED_CAST")
        val reality = p["reality-opts"] as Map<String, Any?>
        assertEquals(PBK, reality["public-key"])
        assertEquals("ab12", reality["short-id"])
        assertTrue(p["name"].toString().contains("Финляндия"))
    }

    @Test
    fun vlessWsAndXhttp() {
        val ws = LinkParser.parse("vless://id@h.example:8443?type=ws&security=tls&path=%2Fws&host=cdn.example#ws")!!
        assertEquals("ws", ws["network"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("/ws", (ws["ws-opts"] as Map<String, Any?>)["path"])
        val xh = LinkParser.parse("vless://id@h.example:443?type=xhttp&security=reality&pbk=k&sid=1&path=%2Fx&mode=auto#x")!!
        assertEquals("xhttp", xh["network"])
    }

    @Test
    fun vmess() {
        val json = """{"v":"2","ps":"Германия","add":"de.example.com","port":"443","id":"uuid","aid":"0","scy":"auto","net":"ws","host":"de.example.com","path":"/v","tls":"tls","sni":"de.example.com"}"""
        val p = LinkParser.parse("vmess://" + Base64.getEncoder().encodeToString(json.toByteArray()))!!
        assertEquals("vmess", p["type"])
        assertEquals("Германия", p["name"])
        assertEquals("ws", p["network"])
        assertEquals(true, p["tls"])
    }

    @Test
    fun shadowsocksFormats() {
        val sip002 = LinkParser.parse("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:pass".toByteArray()) + "@1.2.3.4:8388#SS")!!
        assertEquals("aes-256-gcm", sip002["cipher"])
        assertEquals("pass", sip002["password"])
        val ss2022 = LinkParser.parse("ss://2022-blake3-aes-128-gcm:a2V5%3D@5.6.7.8:443#2022")!!
        assertEquals("2022-blake3-aes-128-gcm", ss2022["cipher"])
        assertEquals("a2V5=", ss2022["password"])
        val legacy = LinkParser.parse("ss://" + Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:pw@9.9.9.9:1234".toByteArray()) + "#old")!!
        assertEquals(1234, legacy["port"])
    }

    @Test
    fun trojanHysteriaTuic() {
        val t = LinkParser.parse("trojan://pw@t.example:443?sni=t.example&type=grpc&serviceName=svc#T")!!
        assertEquals("t.example", t["sni"])
        assertEquals("grpc", t["network"])
        val h = LinkParser.parse("hy2://auth@h.example:443?sni=h.example&obfs=salamander&obfs-password=x&insecure=1#H")!!
        assertEquals("hysteria2", h["type"])
        assertEquals("salamander", h["obfs"])
        assertEquals(true, h["skip-cert-verify"])
        val tu = LinkParser.parse("tuic://uuid:pw@[2001:db8::1]:443?congestion_control=bbr&alpn=h3&sni=x#TU")!!
        assertEquals("2001:db8::1", tu["server"])
        assertEquals("pw", tu["password"])
    }

    @Test
    fun base64Subscription() {
        val body = Base64.getEncoder().encodeToString(
            "$vlessReality\ntrojan://pw@t.example:443#T\nunknown://x\n".toByteArray()
        )
        val r = SubscriptionParser.parse(body, "s1")
        assertEquals(2, r.nodes.size)
        assertEquals(0, r.skipped)
    }

    @Test
    fun clashYamlSubscription() {
        val yaml = """
            mixed-port: 7890
            proxies:
              - {name: "NL", type: vless, server: nl.example, port: 443, uuid: u, tls: true}
              - name: SE
                type: hysteria2
                server: se.example
                port: 443
                password: p
            proxy-groups: []
        """.trimIndent()
        val r = SubscriptionParser.parse(yaml, "s2")
        assertEquals(listOf("NL", "SE"), r.nodes.map { it.name })
    }

    @Test
    fun webPageGivesHint() {
        val r = SubscriptionParser.parse("<html><body>login</body></html>", "s3")
        assertTrue(r.nodes.isEmpty())
        assertTrue(r.hint.contains("веб-страница"))
    }

    @Test
    fun linksWithSpacesInNames() {
        val r = SubscriptionParser.parseLinks("trojan://pw@a.example:443#Имя с пробелами", "l")
        assertEquals("Имя с пробелами", r.nodes.single().name)
    }

    @Test
    fun headers() {
        val info = SubscriptionFetcher.parseUserinfo("upload=10; download=20; total=1073741824; expire=1798000000")
        assertEquals(1073741824L, info["total"])
        assertEquals("Кошки", SubscriptionFetcher.decodeHeader("base64:" + Base64.getEncoder().encodeToString("Кошки".toByteArray())))
    }

    @Test
    fun deepLinks() {
        assertEquals("https://sub.example/abc", Controller.extractSubscriptionUrl("happ://add/https://sub.example/abc"))
        assertEquals("https://sub.example/abc", Controller.extractSubscriptionUrl("clash://install-config?url=https%3A%2F%2Fsub.example%2Fabc&name=x"))
        assertEquals("https://sub.example/abc", Controller.extractSubscriptionUrl("https://sub.example/abc"))
        assertEquals("https://sub.example/abc", Controller.extractSubscriptionUrl("sub.example/abc"))
        assertNull(Controller.extractSubscriptionUrl(vlessReality))
    }

    @Test
    fun providerRouting() {
        val json = """{"Name":"Пример","GlobalProxy":"true","DirectSites":["domain:sberbank.ru","full:gosuslugi.ru","yandex.ru","geosite:nonexistent","geosite:category-ru"],
            |"DirectIp":["10.0.0.0/8","77.88.8.8"],"ProxySites":["chatgpt.com","keyword:youtube"],"ProxyIp":["91.108.4.0/22"],
            |"BlockSites":["regexp:^ads\\..*"],"BlockIp":[]}""".trimMargin()
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray())
        val decoded = app.tropa.core.ProviderRouting.decode("happ://routing/onadd/$b64")
        assertEquals(json, decoded)
        val p = app.tropa.core.ProviderRouting.parse(decoded)!!
        val rules = app.tropa.core.ProviderRouting.rules(p, "PROXY")
        assertTrue("DOMAIN,gosuslugi.ru,DIRECT" in rules)
        assertTrue("IP-CIDR,77.88.8.8/32,DIRECT,no-resolve" in rules)
        assertTrue("DOMAIN-KEYWORD,youtube,PROXY" in rules)
        assertEquals("MATCH,PROXY", rules.last())
        assertEquals("", app.tropa.core.ProviderRouting.decode("off"))

        val nodes = SubscriptionParser.parseLinks("trojan://pw@t.example:443?sni=t.example#T", "s").nodes
        val (json2, _) = XrayConfig.build(listOf(app.tropa.data.Subscription(id = "s", url = "", title = "T", nodes = nodes)), Settings(mode = RouteMode.PROVIDER), testLists, 9090, p, tun = false)
        File("build/core-check").mkdirs()
        File("build/core-check/config-PROVIDER.json").writeText(json2)
        // Метки, которых нет в geo-файлах, в конфиг не попадают: с ними Xray не запустился бы.
        assertTrue(!json2.contains("geosite:nonexistent"))
    }

    /** Списки как в APK, но короткие — для проверки конфигурации. */
    private val testLists = XrayConfig.Lists(
        blockedDomains = listOf("youtube.com", ".ua"),
        blockedIps = listOf("91.108.4.0/22"),
        geositeTags = setOf("category-ru", "private", "google"),
        geoipTags = setOf("ru", "private", "telegram"),
    )

    /** Пишет пример конфигурации, чтобы проверить её настоящим ядром: xray run -test. */
    @Test
    fun configForCoreCheck() {
        val nodes = SubscriptionParser.parseLinks(
            listOf(
                vlessReality,
                "trojan://pw@t.example:443?sni=t.example#T",
                "hy2://auth@h.example:443?sni=h.example#H",
                "ss://2022-blake3-aes-128-gcm:a2V5a2V5a2V5a2V5a2V5aw%3D%3D@5.6.7.8:443#S",
            ).joinToString("\n"), "s",
        ).nodes
        for (mode in RouteMode.entries.filter { it != RouteMode.PROVIDER }) {
            val (json, plan) = XrayConfig.build(listOf(app.tropa.data.Subscription(id = "s", url = "", title = "T", nodes = nodes)), Settings(mode = mode), testLists, 9090, tun = false)
            assertNotNull(json)
            // Никаких открытых прокси: ни socks, ни http-входов.
            assertTrue(!json.contains("\"socks\"") && !json.contains("\"http\""))
            assertTrue(plan.costs.isNotEmpty())
            File("build/core-check").mkdirs()
            File("build/core-check/config-${mode.name}.json").writeText(json)
        }
    }
}
