package app.tropa.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Страница метрик ядра Xray на 127.0.0.1: отклики серверов от встроенного
 * наблюдателя и счётчики трафика. Управлять ядром через неё нельзя — только смотреть.
 */
class XrayApi(private val port: Int) : CoreApi {

    private fun vars(timeoutMs: Int = 3000): JSONObject? = runCatching {
        val c = URI("http://127.0.0.1:$port/debug/vars").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode == 200) JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    override fun isAlive(): Boolean = vars(1500) != null

    /** id сервера → отклик в мс, 0 — наблюдатель считает его мёртвым. */
    override fun observatory(): Map<String, Int> {
        val o = vars()?.optJSONObject("observatory") ?: return emptyMap()
        return o.keys().asSequence().filter { it.startsWith("n-") }.associate { tag ->
            val s = o.getJSONObject(tag)
            tag.removePrefix("n-") to if (s.optBoolean("alive")) s.optInt("delay").coerceAtLeast(1) else 0
        }
    }

    /** Суммарно отправлено и получено байт через серверы (без «напрямую»). */
    override fun traffic(): Pair<Long, Long> {
        val out = vars()?.optJSONObject("stats")?.optJSONObject("outbound") ?: return 0L to 0L
        var up = 0L
        var down = 0L
        out.keys().forEach { tag ->
            if (!tag.startsWith("n-")) return@forEach
            val s = out.getJSONObject(tag)
            up += s.optLong("uplink")
            down += s.optLong("downlink")
        }
        return up to down
    }

    /**
     * Какой сервер сейчас выбран. Балансировщик выбирает живой сервер с наименьшим
     * «отклик × цена» — повторяем его расчёт по данным наблюдателя.
     */
    override fun current(plan: XrayConfig.Plan): String? {
        plan.fixed?.let { return it }
        val delays = observatory()
        return plan.costs.entries
            .filter { (delays[it.key] ?: 0) > 0 }
            .minByOrNull { delays.getValue(it.key) * it.value }?.key
            ?: plan.costs.keys.firstOrNull()
    }
}
