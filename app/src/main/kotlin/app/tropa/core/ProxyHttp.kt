package app.tropa.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Минимальный HTTP-клиент через локальный HTTP-прокси с паролем (вход временного Xray).
 * Стандартный HttpURLConnection не умеет надёжно передавать пароль прокси для https,
 * поэтому CONNECT и запросы пишем руками.
 */
class ProxyHttp(private val port: Int, user: String, pass: String) {

    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    /** Открытое соединение до сайта: в нём можно делать несколько запросов подряд. */
    inner class Conn(val host: String, val path: String, private val socket: Socket, private val https: Boolean) : AutoCloseable {
        private val input = BufferedInputStream(socket.getInputStream())

        /** Делает запрос и возвращает код ответа (0 — ошибка). */
        fun request(): Int = runCatching {
            val target = if (https) path else "http://$host$path"
            val proxyAuth = if (https) "" else "Proxy-Authorization: $auth\r\n"
            socket.getOutputStream().write(
                "GET $target HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\n${proxyAuth}Connection: keep-alive\r\n\r\n".toByteArray()
            )
            val (code, headers) = readHead(input)
            skipBody(input, headers)
            code
        }.getOrDefault(0)

        override fun close() = runCatching { socket.close() }.let { }
    }

    fun open(url: String, timeoutMs: Int = 6000): Conn? = runCatching {
        val u = URI(url)
        val https = u.scheme == "https"
        val host = u.host
        val path = (u.rawPath.ifBlank { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
        val s = Socket()
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress("127.0.0.1", port), 2000)
        if (!https) return@runCatching Conn(host, path, s, false)
        s.getOutputStream().write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\nProxy-Authorization: $auth\r\n\r\n".toByteArray())
        val (code, _) = readHead(BufferedInputStream(s.getInputStream()))
        if (code != 200) {
            s.close()
            return@runCatching null
        }
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, 443, true) as SSLSocket
        tls.soTimeout = timeoutMs
        tls.startHandshake()
        Conn(host, path, tls, true)
    }.getOrNull()

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) throw java.io.EOFException()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readHead(i: InputStream): Pair<Int, Map<String, String>> {
        val status = readLine(i)
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(i)
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        return code to headers
    }

    /** Дочитываем тело, чтобы следующий запрос в том же соединении был чистым. */
    private fun skipBody(i: InputStream, h: Map<String, String>) {
        val len = h["content-length"]?.toLongOrNull()
        when {
            len != null -> { var left = len; while (left > 0) { val n = i.skip(left); if (n <= 0) { if (i.read() < 0) break; left-- } else left -= n } }
            h["transfer-encoding"]?.contains("chunked") == true -> while (true) {
                val size = readLine(i).substringBefore(';').trim().toLong(16)
                var left = size + 2
                while (left > 0) { val n = i.skip(left); if (n <= 0) { if (i.read() < 0) return; left-- } else left -= n }
                if (size == 0L) return
            }
        }
    }
}
