package app.tropa

import app.tropa.core.XrayConfig
import app.tropa.data.RouteMode
import app.tropa.data.ServerChoice
import app.tropa.data.Settings
import app.tropa.data.Subscription
import app.tropa.sub.SubscriptionParser
import app.tropa.ui.Names
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChoiceTest {

    private val names = listOf(
        "🇷🇺 Кроме Т-Мобайл, МТС 9", "🇷🇺 Все операторы 6 LTE", "🇷🇺 Москва (Без блокировок)",
        "🇷🇺 Мост - Residental", "🇱🇹 Вильнюс, Литва", "🇫🇮 Хельсинки, Финляндия", "Обходы БС Билайн",
    )

    private val lists = XrayConfig.Lists(listOf("youtube.com"), listOf("91.108.4.0/22"), setOf("category-ru", "private"), setOf("ru", "private"))

    @Test
    fun kinds() {
        assertEquals(Names.Kind.MOBILE, Names.kind(names[0]))
        assertEquals(Names.Kind.MOBILE, Names.kind(names[1]))
        assertEquals(Names.Kind.RU_EXIT, Names.kind(names[2]))
        assertEquals(Names.Kind.RU_EXIT, Names.kind(names[3]))
        assertEquals(Names.Kind.NORMAL, Names.kind(names[4]))
        assertEquals(Names.Kind.NORMAL, Names.kind(names[5]))
        assertEquals(Names.Kind.MOBILE, Names.kind(names[6]))
        // Мост через Россию работает и на Wi-Fi, страна у него — выход.
        assertEquals(Names.Kind.NORMAL, Names.kind("🇷🇺 Мост -> 🇸🇪 Residental"))
        assertEquals("SE", Names.country("🇷🇺 Мост -> 🇸🇪 Residental"))
        assertEquals(Names.Kind.SPECIAL, Names.kind("🇪🇺 Авто-выбор LTE 🏳️"))
        assertEquals(Names.Kind.MOBILE, Names.kind("🇷🇺 Только МТС LTE #14 🏳️"))
    }

    @Test
    fun lteQuota() {
        assertEquals(42.5 to 60.0, Names.lteQuota("Трафик расходуется только на LTE серверах.\n\nОсталось LTE: 42.50 GB / 60.00 GB"))
        assertEquals(0.5 to 10.0, Names.lteQuota("Белые списки: 512 MB / 10 GB"))
        assertEquals(null, Names.lteQuota("Осталось 56 дн"))
    }

    @Test
    fun operators() {
        assertFalse(Names.fitsOperator("Кроме Т-Мобайл, МТС 9", "MTS RUS"))
        assertTrue(Names.fitsOperator("Кроме Т-Мобайл, МТС 9", "Beeline"))
        assertTrue(Names.fitsOperator("Все операторы 6 LTE", "MegaFon"))
        assertTrue(Names.fitsOperator("Обходы БС Билайн", "Beeline"))
        assertFalse(Names.fitsOperator("Обходы БС Билайн", "Tele2"))
        assertTrue(Names.fitsOperator("Кроме МТС", ""))
    }

    private fun sub(id: String = "a", list: List<String> = names): Subscription {
        val links = list.mapIndexed { i, n ->
            "trojan://pw@s$i.$id.example.com:443?sni=s$i.example.com#${java.net.URLEncoder.encode(n, "UTF-8").replace("+", "%20")}"
        }
        val nodes = SubscriptionParser.parseLinks(links.joinToString("\n"), id).nodes
        return Subscription(id = id, url = "https://x", title = id, nodes = nodes)
    }

    private fun nameOf(s: List<Subscription>, id: String) = s.flatMap { it.nodes }.first { it.id == id }.name

    @Test
    fun wifiSkipsLteAndRussianServers() {
        val s = listOf(sub())
        val plan = XrayConfig.plan(s, Settings(choice = ServerChoice.AUTO), onMobile = false, operator = "", ranking = emptyList())
        assertEquals(listOf("🇱🇹 Вильнюс, Литва", "🇫🇮 Хельсинки, Финляндия"), plan.costs.keys.map { nameOf(s, it) })
    }

    @Test
    fun autoPutsFittingLteFirstOnMobile() {
        val s = listOf(sub())
        val normal = XrayConfig.plan(s, Settings(choice = ServerChoice.AUTO), false, "", emptyList()).costs.keys.toList()
        // Очередь с домашнего Wi-Fi на мобильном не действует, обычные серверы — запас по одной цене.
        val plan = XrayConfig.plan(s, Settings(choice = ServerChoice.AUTO), onMobile = true, operator = "MTS RUS", ranking = normal.reversed())
        assertTrue(normal.all { plan.costs[it] == 1001.0 })
        // У МТС не пробуем ни «Кроме Т-Мобайл, МТС», ни сервер только для Билайна.
        assertEquals(listOf("🇷🇺 Все операторы 6 LTE"), plan.costs.filterValues { it == 1.0 }.keys.map { nameOf(s, it) })
    }

    @Test
    fun providerChoicePutsLteFirstOnMobile() {
        val s = listOf(sub())
        val plan = XrayConfig.plan(s, Settings(choice = ServerChoice.PROVIDER), onMobile = true, operator = "MTS RUS", ranking = emptyList())
        assertEquals(listOf("🇷🇺 Все операторы 6 LTE"), plan.costs.filterValues { it == 1.0 }.keys.map { nameOf(s, it) })
        // Обычные серверы остаются запасом и сохраняют порядок провайдера.
        val rest = plan.costs.filterValues { it > 1.0 }.values.toList()
        assertTrue(rest.isNotEmpty() && rest.all { it >= 1000.0 } && rest == rest.sorted())
        // На Wi-Fi LTE в выборе нет.
        val wifi = XrayConfig.plan(s, Settings(choice = ServerChoice.PROVIDER), onMobile = false, operator = "MTS RUS", ranking = emptyList())
        assertTrue(wifi.costs.keys.none { nameOf(s, it).contains("LTE") })
    }

    /** Первый в очереди стал отвечать за 351 мс, а пятый — за 109: берём пятый. */
    @Test
    fun fasterServerBeatsOldRanking() {
        val names = (1..6).map { "🇫🇮 Сервер $it" }
        val s = listOf(sub("big", names))
        val ids = s[0].nodes.map { it.id }
        val costs = XrayConfig.plan(s, Settings(choice = ServerChoice.AUTO), false, "", ids).costs
        assertTrue(109 * costs.getValue(ids[4]) < 351 * costs.getValue(ids[0]))
        // Похожие отклики — держимся очереди, а не прыгаем между серверами.
        assertTrue(100 * costs.getValue(ids[0]) < 99 * costs.getValue(ids[4]))
    }

    @Test
    fun providerOrderAndQualityRanking() {
        val s = listOf(sub())
        val provider = XrayConfig.plan(s, Settings(choice = ServerChoice.PROVIDER), false, "", emptyList())
        // Порядок провайдера: Вильнюс раньше Хельсинки — у него цена меньше.
        val (vilnius, helsinki) = provider.costs.keys.toList()
        assertTrue(provider.costs.getValue(vilnius) < provider.costs.getValue(helsinki))
        // Очередь проверки качества переставляет их для «Тропы».
        val ranked = XrayConfig.plan(s, Settings(choice = ServerChoice.AUTO), false, "", listOf(helsinki, vilnius))
        assertTrue(ranked.costs.getValue(helsinki) < ranked.costs.getValue(vilnius))
    }

    @Test
    fun brokenServersGoLast() {
        val s = listOf(sub())
        val (vilnius, helsinki) = XrayConfig.plan(s, Settings(choice = ServerChoice.PROVIDER), false, "", emptyList()).costs.keys.toList()
        val plan = XrayConfig.plan(s, Settings(choice = ServerChoice.PROVIDER), false, "", emptyList(), broken = setOf(vilnius))
        assertTrue(plan.costs.getValue(helsinki) < plan.costs.getValue(vilnius))
    }

    @Test
    fun manualPinsServer() {
        val s = listOf(sub())
        val id = s[0].nodes[2].id
        val (json, plan) = XrayConfig.build(s, Settings(choice = ServerChoice.MANUAL, manualNodeId = id), lists, 9090, tun = false)
        assertEquals(id, plan.fixed)
        assertFalse(JSONObject(json).getJSONObject("routing").has("balancers"))
    }

    @Test
    fun separateSubscriptions() {
        val first = sub("a")
        val second = sub("b", listOf("🇩🇪 Германия"))
        val plan = XrayConfig.plan(listOf(first, second), Settings(choice = ServerChoice.AUTO, mergeSubs = false, providerSubId = "b"), false, "", emptyList())
        assertEquals(second.nodes.map { it.id }, plan.costs.keys.toList())
        val merged = XrayConfig.plan(listOf(first, second), Settings(choice = ServerChoice.AUTO, mergeSubs = true), false, "", emptyList())
        assertEquals(3, merged.costs.size)
    }

    @Test
    fun clashGroupIsProvidersChoice() {
        val yaml = """
            proxies:
              - {name: A, type: trojan, server: a.example, port: 443, password: p}
              - {name: B, type: trojan, server: b.example, port: 443, password: p}
            proxy-groups:
              - {name: "🚀 Выбор", type: select, proxies: ["⚡ Лучший", A, B]}
              - {name: "⚡ Лучший", type: url-test, proxies: [B, A], url: "https://cp.cloudflare.com"}
        """.trimIndent()
        val r = SubscriptionParser.parse(yaml, "c")
        assertEquals("url-test", r.groupType)
        assertEquals(listOf("B", "A"), r.groupMembers.map { id -> r.nodes.first { it.id == id }.name })
    }

    @Test
    fun xrayJsonPassesThroughAsIs() {
        val json = """[{"remarks":"🇫🇮 Финляндия","outbounds":[
            {"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"fi.example.com","port":443,"users":[{"id":"u","encryption":"none","flow":"xtls-rprx-vision"}]}]},
             "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"fi.example.com","publicKey":"nxqrA7RqSlkBsNCW3ufSXov43LuTiA-vx-qsevzLmK8","shortId":"ab","fingerprint":"edge","spiderX":"/x"}}},
            {"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}],
           "routing":{"rules":[{"network":"udp","port":"443","outboundTag":"block"},{"domain":["domain:provider.example","regexp:.*\\.ru$","geosite:category-ru"],"outboundTag":"direct"},{"ip":["geoip:private"],"outboundTag":"direct"}]}},
          {"remarks":"🚀 Лучший","outbounds":[{"protocol":"vless","tag":"T1","settings":{"vnext":[{"address":"a","port":1,"users":[{"id":"u"}]}]}},{"protocol":"vless","tag":"T2","settings":{"vnext":[{"address":"b","port":2,"users":[{"id":"u"}]}]}}],
           "routing":{"balancers":[{"tag":"B","selector":["T"],"strategy":{"type":"leastLoad"}}],"rules":[]}}]"""
        val r = SubscriptionParser.parse(json, "m")
        assertEquals(1, r.nodes.size)
        assertEquals("url-test", r.groupType)
        assertTrue(r.routing.contains("BlockQuic"))
        val s = Subscription(id = "m", url = "", title = "Провайдер A", nodes = r.nodes, routing = r.routing)
        val p = app.tropa.core.ProviderRouting.parse(r.routing)!!
        val (out, _) = XrayConfig.build(listOf(s), Settings(mode = RouteMode.PROVIDER), lists, 9090, p, tun = false)
        // Исходный outbound ушёл ядру как есть — вместе с полями, которых мы не знаем (spiderX).
        assertTrue(out.contains("\"spiderX\": \"/x\""))
        assertTrue(out.contains("regexp:.*\\\\.ru$"))
        File("build/core-check").mkdirs()
        File("build/core-check/config-XRAY.json").writeText(out)
    }

    /** Root-режим: вход TPROXY вместо TUN, у каждого исходящего ядра — метка, правила её пропускают. */
    @Test
    fun rootModeTproxyConfig() {
        val s = listOf(sub())
        val (out, _) = XrayConfig.build(s, Settings(choice = ServerChoice.AUTO, rootMode = true), lists, 9090, null, tun = false,
            tproxyPort = app.tropa.core.RootProxy.PORT)
        val o = org.json.JSONObject(out)
        val inbound = o.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("dokodemo-door", inbound.getString("protocol"))
        assertEquals("tproxy", inbound.getJSONObject("streamSettings").getJSONObject("sockopt").getString("tproxy"))
        val outs = o.getJSONArray("outbounds")
        for (i in 0 until outs.length()) {
            assertEquals(app.tropa.core.RootProxy.MARK, outs.getJSONObject(i).getJSONObject("streamSettings").getJSONObject("sockopt").getInt("mark"))
        }
        File("build/core-check").mkdirs()
        File("build/core-check/config-TPROXY.json").writeText(out)
        // Скрипт: трафик ядра (метка) и обходящие UID — мимо; DNS — в ядро раньше, чем исключение домашних сетей.
        val sh = app.tropa.core.RootProxy.startScript(File("/w"), File("/w/root"), "/lib/libxray.so", listOf(10123, 10200))
        val lines = sh.lines()
        assertTrue(sh.contains("--mark ${app.tropa.core.RootProxy.MARK}/${app.tropa.core.RootProxy.MARK} -j RETURN"))
        assertTrue(sh.contains("--uid-owner 10123 -j RETURN") && sh.contains("--uid-owner 10200 -j RETURN"))
        assertTrue(lines.indexOfFirst { "--dport 53" in it } < lines.indexOfFirst { "192.168.0.0/16" in it })
        assertTrue(sh.contains("-j TPROXY --on-ip 127.0.0.1 --on-port ${app.tropa.core.RootProxy.PORT}"))
        // Те же скрипты для проверки на эмуляторе с root (adb root): ядро и конфиг в /data/local/tmp/tropa.
        val emu = File("/data/local/tmp/tropa")
        File("build/core-check/root-start.sh").writeText(app.tropa.core.RootProxy.startScript(emu, File(emu, "root"), "/data/local/tmp/tropa/xray", emptyList()))
        File("build/core-check/root-stop.sh").writeText(app.tropa.core.RootProxy.stopScript(File(emu, "root")))
        val stop = app.tropa.core.RootProxy.stopScript(File("/w/root"))
        assertTrue(stop.contains("-D OUTPUT -j TROPA_OUT") && stop.contains("ip route flush table"))
    }

    /** Обход белых списков у провайдеров: сервер подключается через «нарезку» (dialerProxy). */
    @Test
    fun xrayJsonKeepsDialerProxyChain() {
        val json = """[{"remarks":"Все операторы 12 LTE","outbounds":[
            {"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"lte.example.com","port":443,"users":[{"id":"u","encryption":"none"}]}]},
             "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"lte.example.com"},"sockopt":{"dialerProxy":"fragment"}}},
            {"protocol":"freedom","tag":"fragment","settings":{"fragment":{"packets":"tlshello","length":"100-200","interval":"10-20"}}},
            {"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}]},
          {"remarks":"🇫🇮 Финляндия","outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"fi.example.com","port":443,"users":[{"id":"u","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"fi.example.com"}}}]}]"""
        val r = SubscriptionParser.parse(json, "b")
        assertEquals(2, r.nodes.size)
        val s = Subscription(id = "b", url = "", title = "Провайдер B", nodes = r.nodes)
        val (out, _) = XrayConfig.build(listOf(s), Settings(choice = ServerChoice.AUTO), lists, 9090, null, onMobile = true, tun = false)
        val lte = XrayConfig.tag(r.nodes[0].id)
        val o = org.json.JSONObject(out)
        val outs = o.getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val server = outs.first { it.optString("tag") == lte }
        val frag = outs.first { it.optString("tag") == "c$lte-fragment" }
        assertEquals("c$lte-fragment", server.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertTrue(frag.getJSONObject("settings").has("fragment"))
        // Служебный outbound цепочки балансировщик выбрать не должен.
        assertFalse(out.contains("\"c$lte-fragment\"") && o.optJSONObject("routing")?.toString()?.contains("c$lte") == true)
        File("build/core-check").mkdirs()
        File("build/core-check/config-CHAIN.json").writeText(out)
    }

    /** Конфигурации второго ядра — для проверки настоящим mihomo: mihomo -t. */
    @Test
    fun mihomoConfigs() {
        val s = listOf(sub())
        val p = app.tropa.core.ProviderRouting.parse(
            """{"Name":"P","GlobalProxy":"true","DirectSites":["geosite:category-ru","geosite:nonexistent","domain:sber.ru"],"ProxyIp":["91.108.4.0/22"]}"""
        )
        File("build/core-check").mkdirs()
        for (mode in RouteMode.entries) {
            for (mobile in listOf(false, true)) {
                val settings = Settings(mode = mode, choice = ServerChoice.AUTO)
                val plan = XrayConfig.plan(s, settings, mobile, "MegaFon", emptyList(), broken = setOf(s[0].nodes[4].id))
                val yaml = app.tropa.core.MihomoConfig.build(s, settings, lists, 9090, "secret", 3, p, plan)
                assertTrue(!yaml.contains("mixed-port") && !yaml.contains("socks-port") && !yaml.contains("_xray"))
                assertFalse(yaml.contains("nonexistent"))
                File("build/core-check/mihomo-${mode.name}-${if (mobile) "mobile" else "wifi"}.yaml").writeText(yaml)
            }
        }
    }

    /** Технические имена провайдеров — по-человечески, страна по коду в имени. */
    @Test
    fun technicalServerNames() {
        assertEquals("SE", Names.country("SELFSTEAL NODE se-1 SE TCP"))
        assertEquals("Швеция se-1 TCP", Names.serverName("SELFSTEAL NODE se-1 SE TCP"))
        assertEquals("LT", Names.country("SELFSTEAL NODE lt-2 LT GRPC"))
        assertEquals("Литва lt-2 GRPC", Names.serverName("SELFSTEAL NODE lt-2 LT GRPC"))
        assertEquals("CH", Names.country("node ch-1"))
        // Обычные имена не трогаем.
        assertEquals("Швеция GRPC", Names.serverName("Швеция GRPC"))
        assertEquals("Хельсинки, Финляндия", Names.serverName("🇫🇮 Хельсинки, Финляндия"))
        assertEquals("Все операторы 12 LTE", Names.serverName("🇷🇺 ⭐️ Все операторы #12 LTE 🏳️").replace("#", ""))
        assertEquals(null, Names.country("PREMIUM ОБХОД 1"))
        assertEquals("💧 Пример @examplebot", Names.subTitle("💧 Пример 💧 @examplebot"))
    }

    /** Выбрана страна в большой подписке: в очереди только её обычные серверы, с одной ценой. */
    @Test
    fun countryChoice() {
        val names = listOf("SELFSTEAL NODE se-1 SE TCP", "SELFSTEAL NODE se-2 SE GRPC", "SELFSTEAL NODE fi-7 FI GRPC", "tw-1 TW TCP", "🇷🇺 Все операторы LTE")
        val s = sub("big", names)
        val settings = Settings(choice = ServerChoice.COUNTRY, country = "SE", countrySubId = "big")
        val plan = XrayConfig.plan(listOf(s), settings, onMobile = true, operator = "MegaFon", ranking = emptyList())
        assertEquals(null, plan.fixed)
        // Шведские — с ценой 1, остальные обычные (FI, TW) — запасные на случай, если Швеция не отвечает. LTE — нет.
        assertEquals(s.nodes.take(4).map { it.id }.toSet(), plan.costs.keys)
        assertTrue(s.nodes.take(2).all { plan.costs[it.id] == 1.0 })
        assertTrue(s.nodes.drop(2).take(2).all { plan.costs.getValue(it.id) >= 1e9 })
        assertEquals("TW", Names.country("tw-1 TW TCP"))
        assertEquals("Тайвань tw-1 TCP", Names.serverName("tw-1 TW TCP"))
        assertEquals("GB", Names.country("uk-1 UK TCP"))
        assertEquals("Великобритания uk-1 TCP", Names.serverName("uk-1 UK TCP"))
        assertEquals("AI", Names.country("ai-1 AI GRPC"))
        assertEquals("Для нейросетей ai-1 GRPC", Names.serverName("ai-1 AI GRPC"))
    }
}
