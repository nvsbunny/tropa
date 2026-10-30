package app.tropa.core

/**
 * Что приложению нужно от запущенного ядра, какое бы оно ни было (Xray или mihomo):
 * отклики серверов, счётчики трафика и какой сервер сейчас работает.
 */
interface CoreApi {
    fun isAlive(): Boolean

    /** id сервера → отклик в мс, 0 — ядро считает его мёртвым. */
    fun observatory(): Map<String, Int>

    /** Сколько всего отправлено и получено байт через серверы. */
    fun traffic(): Pair<Long, Long>

    /** id сервера, через который сейчас идёт трафик. */
    fun current(plan: XrayConfig.Plan): String?
}

/** Ядра, из которых можно выбрать в «Для опытных». */
enum class CoreKind(val title: String, val binary: String) {
    XRAY("Xray", "libxray.so"),
    MIHOMO("mihomo", "libmihomo.so"),
}
