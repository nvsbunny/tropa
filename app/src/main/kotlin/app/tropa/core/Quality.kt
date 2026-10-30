package app.tropa.core

import android.content.Context
import app.tropa.data.Node
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Проверка качества серверов, как в проверках качества на роутерах с OpenWrt.
 *
 * Поднимаем отдельный временный Xray: у каждого сервера свой HTTP-вход на 127.0.0.1
 * с одноразовым паролем. Через каждый сервер открываем нужные сервисы и меряем отклик
 * на повторный запрос в уже открытом соединении (медиана трёх). Сервер, на котором
 * что-то не открылось, уходит в конец очереди, даже если у него маленький пинг.
 * Само приложение идёт мимо VPN, поэтому проверка работает и при выключенном VPN.
 */
object Quality {

    data class Check(val key: String, val title: String, val url: String, val ok: (Int) -> Boolean)

    /** Claude: 405 — пускает, 403 «Request not allowed» — выход заблокирован. */
    val checks = listOf(
        Check("google", "Google", "https://www.google.com/generate_204") { it == 204 },
        Check("youtube", "YouTube", "https://www.youtube.com/generate_204") { it == 204 },
        Check("telegram", "Telegram", "http://149.154.167.51/") { it > 0 },
        Check("whatsapp", "WhatsApp", "https://web.whatsapp.com/") { it == 200 },
        Check("claude", "Claude", "https://api.anthropic.com/v1/messages") { it == 405 },
    )

    data class Result(val id: String, val delay: Int, val failed: List<String>) {
        val alive get() = delay > 0
    }

    fun run(context: Context, nodes: List<Node>): List<Result> {
        if (nodes.isEmpty()) return emptyList()
        val dir = File(context.cacheDir, "probe").apply { mkdirs() }
        val user = token()
        val pass = token()
        val ports = nodes.map { freePort() }

        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        val usable = mutableListOf<Int>()
        nodes.forEachIndexed { i, n ->
            val obs = XrayOutbound.buildAll(n.proxy, "o$i").ifEmpty { return@forEachIndexed }
            usable += i
            obs.forEach { outbounds.put(it) }
            inbounds.put(JSONObject().put("tag", "i$i").put("listen", "127.0.0.1").put("port", ports[i]).put("protocol", "http")
                .put("settings", JSONObject().put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("i$i")).put("outboundTag", "o$i"))
        }
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val config = File(dir, "probe.json")
        config.writeText(JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))
            .toString())

        val binary = context.applicationInfo.nativeLibraryDir + "/libxray.so"
        val pb = ProcessBuilder(binary, "run", "-c", config.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(File(dir, "probe.log"))
        pb.environment()["XRAY_LOCATION_ASSET"] = File(context.filesDir, "core").absolutePath
        val proc = pb.start()
        try {
            waitPort(ports[usable.firstOrNull() ?: return emptyList()])
            val pool = Executors.newFixedThreadPool(6)
            try {
                return nodes.indices.map { i ->
                    pool.submit<Result> {
                        if (i !in usable) Result(nodes[i].id, 0, emptyList())
                        else one(nodes[i].id, ProxyHttp(ports[i], user, pass))
                    }
                }.map { it.get() }
            } finally {
                pool.shutdown()
            }
        } finally {
            proc.destroy()
            config.delete()
        }
    }

    private fun one(id: String, http: ProxyHttp): Result {
        val conn = http.open(XrayConfig.TEST_URL) ?: return Result(id, 0, emptyList())
        val delay = conn.use { c ->
            if (c.request() != 204) return Result(id, 0, emptyList())
            val samples = (1..3).mapNotNull {
                val t = System.nanoTime()
                if (c.request() == 204) ((System.nanoTime() - t) / 1_000_000).toInt().coerceAtLeast(1) else null
            }.sorted()
            samples.getOrNull(samples.size / 2) ?: 0
        }
        if (delay == 0) return Result(id, 0, emptyList())
        // Одна случайная неудача не должна ронять сервер: непрошедшее проверяем ещё раз.
        val failed = checks.filter { c -> !pass(http, c) && !pass(http, c) }.map { it.key }
        return Result(id, delay, failed)
    }

    private fun pass(http: ProxyHttp, c: Check): Boolean =
        http.open(c.url)?.use { conn -> c.ok(conn.request()) } ?: false

    /** Очередь: сначала живые без неудач, по отклику; потом с неудачами. */
    fun rank(results: List<Result>): List<String> =
        results.filter { it.alive }.sortedWith(compareBy<Result> { it.failed.size }.thenBy { it.delay }).map { it.id }

    private fun token(): String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitPort(port: Int) {
        repeat(50) {
            if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(100)
        }
    }
}
