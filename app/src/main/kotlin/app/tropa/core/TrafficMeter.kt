package app.tropa.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сколько прошло через серверы VPN: сегодня, за месяц и всего, как в Happ.
 * Ядро считает байты с момента своего запуска, поэтому здесь складываем приросты между замерами.
 * «Напрямую» (российские сайты, приложения мимо VPN) сюда не входит: это не трафик VPN.
 */
object TrafficMeter {
    data class Stats(val today: Long = 0, val month: Long = 0, val total: Long = 0)

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /** Счётчик ядра при прошлом замере. Ядро перезапустилось — начинаем с нуля. */
    private var last = 0L

    private fun prefs(context: Context) = context.getSharedPreferences("traffic", Context.MODE_PRIVATE)
    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun month() = day().take(6)

    fun load(context: Context) {
        val p = prefs(context)
        _stats.value = Stats(p.getLong("d" + day(), 0), p.getLong("m" + month(), 0), p.getLong("all", 0))
    }

    fun coreStarted() {
        last = 0
    }

    @Synchronized
    fun sample(context: Context, counter: Long) {
        val delta = if (counter >= last) counter - last else counter
        last = counter
        if (delta <= 0) return
        val p = prefs(context)
        val d = "d" + day()
        val m = "m" + month()
        val e = p.edit()
            .putLong(d, p.getLong(d, 0) + delta)
            .putLong(m, p.getLong(m, 0) + delta)
            .putLong("all", p.getLong("all", 0) + delta)
        // Прошлые дни и месяцы старше прошлого не нужны: храним только то, что показываем.
        p.all.keys.filter { (it.startsWith("d") && it != d) || (it.startsWith("m") && it < "m" + (month().toInt() - 1)) }.forEach { e.remove(it) }
        e.apply()
        load(context)
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        _stats.value = Stats()
    }
}
