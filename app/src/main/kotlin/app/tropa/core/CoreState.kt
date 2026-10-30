package app.tropa.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние подключения, общее для сервиса, экранов и плитки в шторке. */
object CoreState {
    sealed interface Status {
        data object Off : Status
        data object Connecting : Status
        data class On(val since: Long) : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Ядро запущено и отвечает: здесь его страница метрик. */
    @Volatile var api: CoreApi? = null

    /** Какое ядро сейчас запущено. */
    @Volatile var core: CoreKind = CoreKind.XRAY

    /** Очередь выбора сервера, с которой запущено ядро. */
    @Volatile var plan: XrayConfig.Plan? = null

    fun set(s: Status) {
        _status.value = s
    }

    val isOn get() = _status.value is Status.On || _status.value is Status.Connecting
}
