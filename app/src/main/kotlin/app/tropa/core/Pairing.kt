package app.tropa.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * «Отправить на ТВ»: телефон передаёт подписки телевизору по домашней сети, без чужих серверов.
 *
 * Телевизор показывает QR-код tropa://pair?h=<адрес в сети>&p=<порт>&k=<одноразовый ключ> и ждёт
 * одну передачу. Телефон сканирует код и отправляет подписки, зашифрованные этим ключом (AES-GCM).
 * Ключ есть только на экране телевизора, поэтому подслушать или подменить передачу в сети нельзя.
 * Отправляем только на адреса домашней сети: QR-код с внешним адресом телефон отвергнет.
 */
object Pairing {

    data class Ticket(val host: String, val port: Int, val key: ByteArray) {
        fun uri() = "tropa://pair?h=$host&p=$port&k=${b64.encodeToString(key)}"
    }

    /** Подписка для передачи: ссылка и название (название — только чтобы показать, что пришло). */
    data class Sub(val url: String, val title: String)

    private const val AAD = "tropa-pair-v1"
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val random = SecureRandom()

    fun parse(uri: String): Ticket? = runCatching {
        val u = URI(uri.trim())
        if (u.scheme != "tropa" || u.host != "pair") return null
        val q = u.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val host = q["h"] ?: return null
        val key = Base64.getUrlDecoder().decode(q["k"] ?: return null)
        if (key.size != 32 || !isHomeAddress(host)) return null
        Ticket(host, q["p"]?.toInt()?.takeIf { it in 1..65535 } ?: return null, key)
    }.getOrNull()

    /** Только IPv4 домашней сети: 10.x, 172.16–31.x, 192.168.x. */
    fun isHomeAddress(host: String): Boolean {
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) return false
        val a = InetAddress.getByName(host)
        return a is Inet4Address && a.isSiteLocalAddress
    }

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(AAD.toByteArray())
        return nonce + c.doFinal(plain)
    }

    /** null — ключ не тот или данные испорчены. */
    fun open(key: ByteArray, data: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        c.updateAAD(AAD.toByteArray())
        c.doFinal(data, 12, data.size - 12)
    }.getOrNull()

    fun encode(subs: List<Sub>): ByteArray = JSONObject()
        .put("v", 1)
        .put("subs", JSONArray(subs.map { JSONObject().put("url", it.url).put("title", it.title) }))
        .toString().toByteArray()

    fun decode(bytes: ByteArray): List<Sub> {
        val a = JSONObject(String(bytes)).getJSONArray("subs")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Sub(it.getString("url"), it.optString("title")) }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
    }

    // ---------- телефон ----------

    /** Отправить подписки на телевизор. Возвращает, сколько он принял. */
    fun send(ticket: Ticket, subs: List<Sub>): Int {
        val body = b64.encodeToString(seal(ticket.key, encode(subs))).toByteArray()
        val c = URI("http://${ticket.host}:${ticket.port}/pair").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5_000
        c.readTimeout = 15_000
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = (if (code == 200) c.inputStream else c.errorStream)?.use { it.bufferedReader().readText() }.orEmpty()
        c.disconnect()
        if (code != 200) error(if (code == 403) "Код устарел — обновите его на телевизоре" else "Телевизор ответил $code")
        return JSONObject(text).optInt("count")
    }

    // ---------- телевизор ----------

    /** Адрес телевизора в домашней сети (Wi-Fi или кабель), без VPN-интерфейсов. */
    fun homeAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
        .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    /** Ждёт одну передачу подписок. Закрыть — перестать ждать. */
    class Receiver private constructor(private val server: ServerSocket, val ticket: Ticket) : Closeable {

        /**
         * Принимает запросы, пока не придёт правильно зашифрованная передача или [keepGoing] не скажет «хватит».
         * [onSubs] получает подписки и возвращает, сколько принято. null — передачи не было.
         */
        fun await(keepGoing: () -> Boolean, onSubs: (List<Sub>) -> Int): Int? {
            server.soTimeout = 1000
            var attempts = 0
            while (keepGoing() && attempts < 10) {
                val s = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                s.use { sock ->
                    sock.soTimeout = 10_000
                    val input = sock.getInputStream().buffered()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n") && head.length < 8192) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                    }
                    val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length.coerceAtMost(1 shl 20))
                    var read = 0
                    while (read < body.size) {
                        val n = input.read(body, read, body.size - read)
                        if (n < 0) break
                        read += n
                    }
                    val subs = runCatching { open(ticket.key, Base64.getUrlDecoder().decode(String(body, 0, read).trim())) }
                        .getOrNull()?.let { runCatching { decode(it) }.getOrNull() }
                    val (code, answer) = if (head.startsWith("POST /pair ") && subs != null) {
                        200 to JSONObject().put("count", onSubs(subs)).toString()
                    } else {
                        attempts++
                        403 to "{}"
                    }
                    val bytes = answer.toByteArray()
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 $code ${if (code == 200) "OK" else "Forbidden"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                    if (code == 200) return JSONObject(answer).getInt("count")
                }
            }
            return null
        }

        override fun close() = runCatching { server.close() }.let { }

        companion object {
            fun start(host: String): Receiver {
                val server = ServerSocket()
                server.bind(InetSocketAddress(InetAddress.getByName(host), 0))
                val key = ByteArray(32).also { random.nextBytes(it) }
                return Receiver(server, Ticket(host, server.localPort, key))
            }
        }
    }
}
