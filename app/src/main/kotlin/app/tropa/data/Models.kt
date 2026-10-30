package app.tropa.data

/** Один сервер из подписки. [proxy] — готовое описание для ядра mihomo. */
data class Node(
    val id: String,
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val proxy: Map<String, Any?>,
)

data class Subscription(
    val id: String,
    /** Пусто у «своих серверов», добавленных ссылками вручную. */
    val url: String,
    val title: String,
    val nodes: List<Node>,
    val upload: Long = 0,
    val download: Long = 0,
    /** 0 — безлимит. */
    val total: Long = 0,
    /** Unix-время окончания в секундах, 0 — без срока. */
    val expire: Long = 0,
    val updatedAt: Long = 0,
    val updateIntervalHours: Int = 12,
    val announce: String = "",
    val supportUrl: String = "",
    val lastError: String = "",
    /** Маршруты от провайдера в формате Happ/INCY (JSON), если он их прислал. */
    val routing: String = "",
    /**
     * Как провайдер сам выбирает сервер: тип его главной группы (url-test, fallback,
     * load-balance, select) и её серверы. Пусто — берём порядок его списка.
     */
    val groupType: String = "",
    val groupMembers: List<String> = emptyList(),
    /** Провайдер пускает только с HWID: запомнили после первого отказа. */
    val sendHwid: Boolean = false,
) {
    val isLocal get() = url.isEmpty()
}

enum class RouteMode { ALL, RU_DIRECT, BLOCKED_ONLY, PROVIDER }

/** Итог проверки качества: отклик и какие сервисы не открылись, по id сервера. */
data class QualityInfo(val delay: Int, val failed: List<String>)

data class QualityData(
    val at: Long = 0,
    val byId: Map<String, QualityInfo> = emptyMap(),
    /** Очередь серверов от лучшего к худшему. */
    val ranking: List<String> = emptyList(),
) {
    /** Данным больше трёх суток — не опираемся на них, провайдеры меняют серверы. */
    val fresh get() = ranking.isNotEmpty() && System.currentTimeMillis() - at < 72 * 3_600_000L
}

/** Кто выбирает сервер: провайдер (его группа или порядок списка), Тропа по задержке или человек. */
enum class ServerChoice { PROVIDER, AUTO, MANUAL }

data class Settings(
    val mode: RouteMode = RouteMode.RU_DIRECT,
    /** Пакеты приложений, которые идут мимо VPN. */
    val directApps: Set<String> = emptySet(),
    /** Свои сайты: домен → true, если через VPN, false — напрямую. Важнее режима. */
    val customSites: Map<String, Boolean> = emptyMap(),
    val choice: ServerChoice = ServerChoice.PROVIDER,
    /** Чей выбор использовать в режиме «Как у провайдера». Пусто — первая подписка. */
    val providerSubId: String = "",
    /**
     * Объединять подписки: автоматика берёт серверы из всех сразу. Если выключить,
     * работает только выбранная подписка (providerSubId), как в Happ и INCY.
     */
    val mergeSubs: Boolean = true,
    /** id сервера, выбранного вручную. */
    val manualNodeId: String = "",
    val ipv6: Boolean = true,
    /** Подсказку про российские приложения показываем, пока её не приняли или не закрыли. */
    val ruHintDismissed: Boolean = false,
    /** «Для опытных»: протоколы, адреса серверов, журнал ядра, IPv6. */
    val advanced: Boolean = false,
    /** Ядро для опытных: «XRAY» (по умолчанию) или «MIHOMO». */
    val core: String = "XRAY",
    /** Уведомление «Подключено» в шторке. VPN и так виден по значку ключа в строке состояния. */
    val notification: Boolean = true,
    /** Ставить обновления Тропы самой (по Wi-Fi, когда экран погашен). */
    val autoUpdate: Boolean = true,
    /** Root-режим «для опытных»: TPROXY и iptables вместо VPN. */
    val rootMode: Boolean = false,
)
