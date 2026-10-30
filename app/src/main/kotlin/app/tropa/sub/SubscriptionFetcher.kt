package app.tropa.sub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import app.tropa.BuildConfig
import app.tropa.core.ProviderRouting
import app.tropa.data.Subscription
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/**
 * Скачивает подписку. Панели (Remnawave, Marzban, 3x-ui и др.) отдают разный формат
 * в зависимости от User-Agent, поэтому пробуем несколько клиентов по очереди,
 * пока не получим серверы.
 */
class SubscriptionFetcher(private val context: Context) {

    class FetchError(message: String) : Exception(message)

    private val agents = listOf(
        "clash.meta/1.19.33",
        "Happ/3.10.0",
        "v2rayNG/1.10.20",
    )

    /**
     * Порядок попыток. Сначала «/json»: так многие провайдеры отдают Happ полные конфиги Xray
     * с маршрутами каждого сервера, без него приходит голый список ссылок без правил.
     */
    private fun attempts(url: String): List<Pair<String, String>> {
        val clean = url.trim()
        val base = clean.trimEnd('/')
        val json = if (base.endsWith("/json") || '?' in base) null else "$base/json"
        return buildList {
            if (json != null) add(json to "Happ/3.10.0")
            agents.forEach { add(clean to it) }
        }
    }

    private sealed interface Attempt {
        class Ok(val resp: Response, val parsed: SubscriptionParser.Result) : Attempt
        class Denied(val hint: String) : Attempt
        class Empty(val hint: String) : Attempt
    }

    /**
     * HWID отправляем, только если провайдер без него не пустил: лишнее устройство
     * в его лимите никому не нужно, а многим провайдерам HWID не нужен.
     */
    fun fetch(url: String, existing: Subscription?): Subscription {
        val id = existing?.id ?: UUID.randomUUID().toString()
        var hint = ""
        var withHwid = existing?.sendHwid == true
        while (true) {
            var denied = false
            for ((target, agent) in attempts(url)) {
                when (val a = tryOne(target, agent, withHwid, id, isMain = target == url.trim())) {
                    is Attempt.Ok -> return build(url, id, existing, a.resp, a.parsed, agent).copy(sendHwid = withHwid)
                    is Attempt.Denied -> { denied = true; hint = a.hint }
                    is Attempt.Empty -> if (a.hint.isNotBlank()) hint = a.hint
                }
            }
            if (!denied || withHwid) break
            withHwid = true
        }
        throw FetchError(hint.ifBlank { "В подписке не нашлось серверов" })
    }

    private fun tryOne(target: String, agent: String, hwid: Boolean, id: String, isMain: Boolean): Attempt {
        val resp = runCatching { get(target, agent, hwid) }.getOrElse { e ->
            // Вариант «/json» может просто не существовать у этой панели.
            if (!isMain) return Attempt.Empty("")
            throw FetchError(humanError(e))
        }
        val shortBody = resp.body.length < 2000 && "hwid" in resp.body.lowercase()
        if (resp.code == 401 || resp.code == 403 || (resp.code in 200..299 && shortBody)) {
            return Attempt.Denied("Провайдер не пустил (код ${resp.code}). Возможно, превышен лимит устройств")
        }
        if (resp.code !in 200..299) return Attempt.Empty(if (isMain) "Сервер подписки ответил кодом ${resp.code}" else "")
        val parsed = SubscriptionParser.parse(resp.body, id)
        if (BuildConfig.DEBUG) runCatching {
            java.io.File(context.cacheDir, "sub-$id-${if (isMain) "main" else "json"}.txt").writeText(resp.body)
        }
        // Вместо серверов одни заглушки — это сообщение провайдера («Превышено количество устройств»).
        if (parsed.nodes.isEmpty() && parsed.notes.isNotBlank()) {
            return Attempt.Empty("Провайдер сообщает: " + app.tropa.ui.Names.clean(parsed.notes.lines().joinToString(". ")))
        }
        return if (parsed.nodes.isEmpty()) Attempt.Empty(if (isMain) parsed.hint else "") else Attempt.Ok(resp, parsed)
    }

    private fun build(
        url: String, id: String, existing: Subscription?, resp: Response, parsed: SubscriptionParser.Result, agent: String,
    ): Subscription {
        var h = resp.headers
        // Маршруты провайдеры часто отдают только «своему» клиенту, поэтому
        // если их нет, спрашиваем ещё раз от имени Happ и берём только заголовки.
        if (h["routing"] == null && parsed.routing.isEmpty() && !agent.startsWith("Happ")) {
            runCatching { get(url, "Happ/3.10.0", existing?.sendHwid == true) }.getOrNull()?.takeIf { it.code in 200..299 }?.let { happ ->
                val r = ProviderRouting.decode(happ.headers["routing"]).ifEmpty { ProviderRouting.findInBody(happ.body) }
                if (r.isNotEmpty()) h = h + ("routing" to r)
            }
        }
        val info = parseUserinfo(h["subscription-userinfo"])
        return Subscription(
            id = id,
            url = url,
            title = existing?.title?.takeIf { it.isNotBlank() && existing.title != hostOf(url) }
                ?: decodeHeader(h["profile-title"])?.takeIf { it.isNotBlank() }
                ?: fileName(h["content-disposition"])
                ?: hostOf(url),
            nodes = parsed.nodes,
            upload = info["upload"] ?: 0,
            download = info["download"] ?: 0,
            total = info["total"] ?: 0,
            expire = info["expire"] ?: 0,
            updatedAt = System.currentTimeMillis(),
            updateIntervalHours = h["profile-update-interval"]?.trim()?.toIntOrNull()?.coerceIn(1, 168) ?: 12,
            announce = listOf(decodeHeader(h["announce"]).orEmpty(), parsed.notes).filter { it.isNotBlank() }.joinToString("\n"),
            supportUrl = h["support-url"].orEmpty(),
            groupType = parsed.groupType,
            groupMembers = parsed.groupMembers,
            routing = ProviderRouting.decode(h["routing"]).ifEmpty { ProviderRouting.findInBody(resp.body) }
                .ifEmpty { parsed.routing }
                .ifEmpty { if (h["routing"]?.trim().equals("off", true)) "" else existing?.routing.orEmpty() },
        )
    }

    private class Response(val code: Int, val body: String, val headers: Map<String, String>)

    private fun get(url: String, agent: String, hwid: Boolean): Response {
        val conn = URI(url.trim()).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", agent)
        conn.setRequestProperty("Accept", "*/*")
        if (hwid) {
            // Remnawave и похожие панели ограничивают число устройств по этим заголовкам.
            conn.setRequestProperty("x-hwid", hwid())
            conn.setRequestProperty("x-device-os", "Android")
            conn.setRequestProperty("x-ver-os", Build.VERSION.RELEASE)
            conn.setRequestProperty("x-device-model", Build.MODEL)
            conn.setRequestProperty("x-app-version", BuildConfig.VERSION_NAME)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val headers = conn.headerFields.filterKeys { it != null }
                .mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
            return Response(code, body, headers)
        } finally {
            conn.disconnect()
        }
    }

    @SuppressLint("HardwareIds")
    private fun hwid(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "tropa"
        return MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun parseUserinfo(v: String?): Map<String, Long> =
            v.orEmpty().split(';').mapNotNull { part ->
                val k = part.substringBefore('=').trim().lowercase()
                val n = part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong()
                if (k.isEmpty() || n == null) null else k to n
            }.toMap()

        /** Заголовки бывают в виде «base64:…» — так провайдеры передают кириллицу. */
        fun decodeHeader(v: String?): String? {
            val s = v?.trim() ?: return null
            return if (s.startsWith("base64:", true)) LinkParser.decodeBase64(s.substring(7)) else s
        }

        fun fileName(cd: String?): String? {
            cd ?: return null
            val star = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            return (star?.let { LinkParser.decode(it) } ?: plain)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        }

        fun hostOf(url: String): String = runCatching { URI(url.trim()).host }.getOrNull() ?: "Подписка"

        fun humanError(e: Throwable): String = when (e) {
            is java.net.UnknownHostException -> "Не удалось найти адрес подписки. Проверьте интернет"
            is java.net.SocketTimeoutException -> "Сервер подписки не отвечает"
            is javax.net.ssl.SSLException -> "Ошибка защищённого соединения с сервером подписки"
            is java.net.ConnectException -> "Сервер подписки недоступен"
            is IllegalArgumentException, is java.net.URISyntaxException -> "Неверная ссылка на подписку"
            else -> e.message ?: "Не удалось загрузить подписку"
        }
    }
}
