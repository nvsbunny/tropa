package app.tropa.core

import app.tropa.data.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Проверка серверов, пока VPN выключен: время TCP-соединения до сервера.
 * Это грубая оценка. Когда VPN включён, проверяем по-настоящему, через ядро.
 */
object Ping {
    /** Задержку не измерить без ядра. 0 означает «не ответил». */
    const val UNKNOWN = -1
    private val udpTypes = setOf("hysteria2", "hysteria", "tuic", "wireguard")

    suspend fun tcp(nodes: List<Node>): Map<String, Int> = withContext(Dispatchers.IO) {
        val gate = Semaphore(16)
        coroutineScope {
            nodes.map { n ->
                async {
                    // Hysteria2, TUIC и WireGuard работают поверх UDP: по TCP их не проверить.
                    if (n.type in udpTypes) n.id to UNKNOWN
                    else gate.withPermit { n.id to measure(n.server, n.port) }
                }
            }.awaitAll().toMap()
        }
    }

    private fun measure(host: String, port: Int): Int = runCatching {
        Socket().use { s ->
            val start = System.nanoTime()
            s.connect(InetSocketAddress(host, port), 3000)
            ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
        }
    }.getOrDefault(0)
}
