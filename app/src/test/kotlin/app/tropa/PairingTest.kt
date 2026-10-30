package app.tropa

import app.tropa.core.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class PairingTest {

    private val subs = listOf(Pairing.Sub("https://example.com/sub/abc", "🐾 Котики VPN"), Pairing.Sub("https://b.example/s/1", "Пример VPN"))

    @Test
    fun qrCodeOnlyForHomeNetwork() {
        val key = ByteArray(32) { it.toByte() }
        val ok = Pairing.Ticket("192.168.1.50", 40123, key).uri()
        val t = Pairing.parse(ok)
        assertNotNull(t)
        assertEquals("192.168.1.50", t!!.host)
        assertEquals(40123, t.port)
        assertTrue(t.key.contentEquals(key))
        // Внешний адрес, имя хоста, короткий ключ, чужая схема — отвергаем: подписки уйдут не туда.
        assertNull(Pairing.parse(ok.replace("192.168.1.50", "8.8.8.8")))
        assertNull(Pairing.parse(ok.replace("192.168.1.50", "evil.example")))
        assertNull(Pairing.parse("tropa://pair?h=10.0.0.2&p=1&k=AAAA"))
        assertNull(Pairing.parse(ok.replace("tropa://", "happ://")))
    }

    @Test
    fun sealOpensOnlyWithSameKey() {
        val key = ByteArray(32) { 7 }
        val sealed = Pairing.seal(key, Pairing.encode(subs))
        assertEquals(subs, Pairing.decode(Pairing.open(key, sealed)!!))
        assertNull(Pairing.open(ByteArray(32) { 8 }, sealed))
        sealed[20] = (sealed[20] + 1).toByte()
        assertNull(Pairing.open(key, sealed))
    }

    @Test
    fun phoneSendsToTvOverNetwork() {
        val r = Pairing.Receiver.start("127.0.0.1")
        var got: List<Pairing.Sub>? = null
        var result: Int? = null
        val tv = thread { result = r.use { it.await(keepGoing = { true }) { list -> got = list; list.size } } }
        // Чужой ключ: телевизор отвечает 403 и ждёт дальше.
        val wrong = runCatching { Pairing.send(r.ticket.copy(key = ByteArray(32)), subs) }
        assertTrue(wrong.isFailure)
        assertEquals(2, Pairing.send(r.ticket, subs))
        tv.join(5000)
        assertEquals(2, result)
        assertEquals(subs, got)
    }
}
