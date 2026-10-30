package app.tropa.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.tropa.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Списки сайтов и geo-файлы, которые обновляются сами, без новой версии Тропы (как XKeen на роутере).
 *
 * В APK лежат версии на день сборки. Раз в три дня по Wi-Fi Тропа скачивает свежие:
 *  - geosite.dat и geoip.dat — оттуда же, откуда их берёт сам Xray (XTLS/Xray-rules-dat), со сверкой SHA-256;
 *  - заблокированные в России сайты и подсети мессенджеров — itdoginfo/allow-domains.
 * Новый файл принимаем, только если он цел и в нём есть то, на что мы опираемся (метки ru, private,
 * category-ru; списки не пустые). Иначе остаётся прежний. Применяются при следующем подключении:
 * рвать работающий VPN ради списков не стоит.
 */
object Lists {

    private const val EVERY = 3 * 24 * 3_600_000L
    private const val RULES = "https://raw.githubusercontent.com/XTLS/Xray-rules-dat/release"
    private const val ITDOG = "https://raw.githubusercontent.com/itdoginfo/allow-domains/main"

    private data class Source(val name: String, val url: String, val checksum: Boolean, val asset: String)

    private val sources = listOf(
        Source("geosite.dat", "$RULES/geosite/geosite.dat", true, "xray/geosite.dat"),
        Source("geoip.dat", "$RULES/geoip/geoip-lite.dat", true, "xray/geoip.dat"),
        Source("blocked.list", "$ITDOG/Russia/inside-raw.lst", false, "rules/blocked.list"),
    ) + listOf("telegram", "meta", "twitter", "discord").map {
        Source("ip-$it.list", "$ITDOG/Subnets/IPv4/$it.lst", false, "rules/ip-$it.list")
    }

    fun dir(context: Context) = File(context.filesDir, "lists").apply { mkdirs() }

    /** Когда списки последний раз обновились (0 — работаем на тех, что в APK). */
    fun updatedAt(context: Context) = context.getSharedPreferences("lists", Context.MODE_PRIVATE).getLong("at", 0)

    /** Свежий файл, если он скачан, иначе null — тогда берём из APK. */
    fun file(context: Context, name: String): File? = File(dir(context), name).takeIf { it.length() > 0 }

    /** Строки списка: скачанного или из APK. */
    fun lines(context: Context, name: String): List<String> {
        val asset = sources.first { it.name == name }.asset
        val text = file(context, name)?.readText() ?: runCatching { context.assets.open(asset).bufferedReader().readText() }.getOrDefault("")
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** Метки geo-файла: из скачанного читаем сами, для APK-версии они выписаны при сборке. */
    fun tags(context: Context, name: String): Set<String> {
        file(context, name)?.let { return GeoTags.read(it.readBytes()) }
        val txt = if (name == "geosite.dat") "xray/geosite-tags.txt" else "xray/geoip-tags.txt"
        return runCatching { context.assets.open(txt).bufferedReader().readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Обновить, если пора. Только по безлимитной сети (Wi-Fi). Возвращает true, если что-то поменялось.
     * Вызывается из VPN-сервиса, который и так просыпается раз в 15 минут ради подписок.
     */
    fun updateIfDue(context: Context, force: Boolean = false): Boolean {
        val prefs = context.getSharedPreferences("lists", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("tried", 0) < EVERY) return false
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val unmetered = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        if (!force && !unmetered) return false
        prefs.edit().putLong("tried", now).apply()
        var changed = false
        var ok = 0
        for (s in sources) {
            runCatching {
                val data = get(s.url)
                if (s.checksum) {
                    val want = String(get("${s.url}.sha256sum")).trim().substringBefore(' ').lowercase()
                    val got = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                    if (want != got) error("SHA-256 не совпал")
                }
                if (!valid(s.name, data)) error("файл без нужных данных")
                val target = File(dir(context), s.name)
                if (!(target.exists() && target.readBytes().contentEquals(data))) {
                    val tmp = File(dir(context), "${s.name}.tmp")
                    tmp.writeBytes(data)
                    tmp.renameTo(target)
                    changed = true
                }
                ok++
            }
        }
        if (ok == sources.size) prefs.edit().putLong("at", now).apply()
        return changed
    }

    private fun valid(name: String, data: ByteArray): Boolean = when (name) {
        "geosite.dat" -> GeoTags.read(data).let { "category-ru" in it && "private" in it }
        "geoip.dat" -> GeoTags.read(data).let { "ru" in it && "private" in it }
        "blocked.list" -> String(data).lines().count { it.isNotBlank() } > 100
        else -> String(data).lines().any { Regex("""^\d+\.\d+\.\d+\.\d+/\d+$""").matches(it.trim()) }
    }

    private fun get(url: String): ByteArray {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Tropa/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) error("сервер ответил ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

/** Метки из geosite.dat / geoip.dat: в protobuf это поле 1 каждой записи списка (как в app/build.gradle.kts). */
object GeoTags {
    fun read(b: ByteArray): Set<String> {
        var i = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val x = b[i++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        val tags = mutableSetOf<String>()
        return runCatching {
            while (i < b.size) {
                val key = varint().toInt()
                val len = varint().toInt()
                val end = i + len
                if (key == (1 shl 3 or 2) && varint().toInt() == (1 shl 3 or 2)) {
                    val n = varint().toInt()
                    tags += String(b, i, n).lowercase()
                }
                i = end
            }
            tags
        }.getOrDefault(tags)
    }
}
