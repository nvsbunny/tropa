package app.tropa.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Связь со вторым ядром mihomo: его REST API на 127.0.0.1, закрытый случайным ключом. */
class MihomoApi(private val port: Int, private val secret: String) : CoreApi {

    private fun get(path: String, timeoutMs: Int = 3000): JSONObject? = runCatching {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer $secret")
        try {
            if (c.responseCode in 200..299) JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    override fun isAlive(): Boolean = get("/version", 1500) != null

    /** Последний замер каждого сервера из истории проверок групп. */
    override fun observatory(): Map<String, Int> {
        val proxies = get("/proxies")?.optJSONObject("proxies") ?: return emptyMap()
        return proxies.keys().asSequence().mapNotNull { name ->
            val p = proxies.getJSONObject(name)
            val history = p.optJSONArray("history") ?: return@mapNotNull null
            if (history.length() == 0 || p.has("all")) return@mapNotNull null
            name to history.getJSONObject(history.length() - 1).optInt("delay")
        }.toMap()
    }

    override fun traffic(): Pair<Long, Long> {
        val c = get("/connections") ?: return 0L to 0L
        return c.optLong("uploadTotal") to c.optLong("downloadTotal")
    }

    /** Спускаемся по группам от PROXY до конкретного сервера. */
    override fun current(plan: XrayConfig.Plan): String? {
        plan.fixed?.let { return it }
        var name = MihomoConfig.GROUP_PROXY
        repeat(5) {
            val now = get("/proxies/$name")?.optString("now")?.takeIf { it.isNotBlank() } ?: return name.takeIf { it != MihomoConfig.GROUP_PROXY }
            name = now
        }
        return name
    }
}
