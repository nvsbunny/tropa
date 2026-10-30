package app.tropa.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.tropa.core.CoreState
import app.tropa.core.Ping
import app.tropa.core.RuleChecker
import app.tropa.core.TropaVpnService
import app.tropa.data.Node
import app.tropa.data.RouteMode
import app.tropa.data.ServerChoice
import app.tropa.data.Store
import app.tropa.data.Subscription
import app.tropa.sub.LinkParser
import app.tropa.sub.SubscriptionFetcher
import app.tropa.sub.SubscriptionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Вся логика экранов: подписки, проверки серверов, выбор, маршруты. */
class Controller(private val context: Context, val store: Store) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fetcher = SubscriptionFetcher(context)
    val checker = RuleChecker(context)

    /** id сервера → задержка в мс. 0 — не ответил, [Ping.UNKNOWN] — не проверить без ядра. */
    private val _delays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val delays: StateFlow<Map<String, Int>> = _delays.asStateFlow()

    /** Какой сервер сейчас используется (по данным ядра). */
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _speed = MutableStateFlow(0L to 0L)
    val speed: StateFlow<Pair<Long, Long>> = _speed.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    val updating: StateFlow<Set<String>> = _updating.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var liveJob: Job? = null
    private var reloadJob: Job? = null

    init {
        // Отклик из свежей проверки качества точнее грубого TCP-пинга — начинаем с него.
        store.quality.value.takeIf { it.fresh }?.let { q ->
            _delays.value = q.byId.mapValues { it.value.delay }
        }
        scope.launch {
            CoreState.status.collect { s ->
                if (s is CoreState.Status.On) startLive() else stopLive()
                if (s is CoreState.Status.Error) _messages.tryEmit(s.message)
            }
        }
        refreshStale()
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    // ---------- подписки ----------

    /**
     * Принимает что угодно: ссылку на подписку, ссылку из другого клиента
     * (happ://add/…, clash://install-config?url=…) или сами ссылки на серверы.
     */
    fun addFromText(raw: String, onDone: (Boolean) -> Unit = {}) {
        val text = raw.trim()
        val url = extractSubscriptionUrl(text)
        if (url != null) {
            addSubscription(url, onDone)
            return
        }
        val local = store.subs.value.firstOrNull { it.isLocal }
        val id = local?.id ?: UUID.randomUUID().toString()
        val parsed = SubscriptionParser.parseLinks(text, id)
        if (parsed.nodes.isEmpty()) {
            say(if (text.startsWith("happ://crypt")) "Эта ссылка зашифрована только для Happ" else "Не нашли здесь ни подписки, ни серверов")
            onDone(false)
            return
        }
        val merged = (local?.nodes.orEmpty() + parsed.nodes).distinctBy { it.id }
        store.upsertSub(
            Subscription(id = id, url = "", title = "Свои серверы", nodes = merged, updatedAt = System.currentTimeMillis())
        )
        say("Добавлено серверов: ${parsed.nodes.size}")
        afterServersChanged()
        onDone(true)
    }

    private fun addSubscription(url: String, onDone: (Boolean) -> Unit) {
        val existing = store.subs.value.firstOrNull { it.url == url }
        scope.launch {
            _updating.value = _updating.value + url
            val result = withContext(Dispatchers.IO) { runCatching { fetcher.fetch(url, existing) } }
            _updating.value = _updating.value - url
            result.onSuccess { sub ->
                store.upsertSub(sub)
                say("«${sub.title}»: серверов ${sub.nodes.size}")
                afterServersChanged()
                onDone(true)
            }.onFailure { e ->
                say(e.message ?: "Не удалось добавить подписку")
                onDone(false)
            }
        }
    }

    fun updateSub(id: String) {
        val sub = store.subs.value.firstOrNull { it.id == id } ?: return
        if (sub.isLocal) return
        scope.launch {
            _updating.value = _updating.value + sub.url
            val result = withContext(Dispatchers.IO) { runCatching { fetcher.fetch(sub.url, sub) } }
            _updating.value = _updating.value - sub.url
            result.onSuccess {
                store.upsertSub(it)
                say("«${it.title}» обновлена")
                afterServersChanged()
            }.onFailure { e ->
                store.upsertSub(sub.copy(lastError = e.message.orEmpty()))
                say("«${sub.title}»: ${e.message}")
            }
        }
    }

    /** Обновляет подписки, у которых истёк интервал, который назначил провайдер. */
    fun refreshStale() {
        val now = System.currentTimeMillis()
        store.subs.value.filter { !it.isLocal && now - it.updatedAt > it.updateIntervalHours * 3_600_000L }
            .forEach { updateSub(it.id) }
    }

    fun removeSub(id: String) {
        store.removeSub(id)
        afterServersChanged()
    }

    private fun afterServersChanged() {
        if (CoreState.isOn) scheduleReload() else testAll()
    }

    // ---------- серверы ----------

    /** Само приложение идёт мимо VPN, поэтому его сеть по умолчанию — настоящая. */
    fun onMobileNetwork(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun testAll() {
        if (_testing.value) return
        val mobile = onMobileNetwork()
        val nodes = store.allNodes().map { it.second }
            .filter { mobile || Names.kind(it.name) != Names.Kind.MOBILE }
        if (nodes.isEmpty()) return
        scope.launch {
            _testing.value = true
            val api = CoreState.api
            val result = withContext(Dispatchers.IO) {
                // Точнее всего — наблюдатель ядра (при включённом VPN) и свежая проверка качества:
                // обе меряют отклик через сам сервер. Остальным — время TCP-соединения.
                val observed = api?.observatory().orEmpty().filterValues { it > 0 }
                val checked = store.quality.value.takeIf { it.fresh }?.byId?.mapValues { it.value.delay }.orEmpty()
                val known = observed + checked
                Ping.tcp(nodes.filter { it.id !in known }) + known
            }
            _delays.value = result
            _testing.value = false
        }
    }

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /**
     * Проверка качества по кнопке: сервисы и отклик через каждый обычный сервер.
     * Идёт через отдельный временный Xray, поэтому работает и при выключенном VPN.
     */
    fun runQuality() {
        if (_checking.value) return
        // На мобильном интернете проверяем и LTE-серверы: здесь они могут понадобиться.
        val kinds = if (onMobileNetwork()) setOf(Names.Kind.NORMAL, Names.Kind.MOBILE) else setOf(Names.Kind.NORMAL)
        val nodes = store.allNodes().map { it.second }.filter { Names.kind(it.name) in kinds }
        scope.launch {
            _checking.value = true
            val results = withContext(Dispatchers.IO) { runCatching { app.tropa.core.Quality.run(context, nodes) }.getOrDefault(emptyList()) }
            store.saveQuality(
                app.tropa.data.QualityData(
                    System.currentTimeMillis(),
                    results.associate { it.id to app.tropa.data.QualityInfo(it.delay, it.failed) },
                    app.tropa.core.Quality.rank(results),
                )
            )
            _delays.value = _delays.value + results.associate { it.id to it.delay }
            _checking.value = false
            val bad = results.count { it.alive && it.failed.isNotEmpty() }
            say("Проверено серверов: ${results.size}. С проблемами: $bad")
            if (CoreState.isOn) scheduleReload()
        }
    }

    /** «Как у провайдера» или «Автовыбор Тропы». Для другой подписки провайдера — пересборка. */
    fun setChoice(choice: ServerChoice, providerSubId: String? = null) {
        val changedSub = providerSubId != null && providerSubId != store.settings.value.providerSubId
        store.updateSettings { it.copy(choice = choice, providerSubId = providerSubId ?: it.providerSubId) }
        // Xray выбирает сервер по конфигурации, поэтому новый выбор — это быстрый перезапуск ядра.
        if (CoreState.isOn) scheduleReload()
    }

    fun selectNode(id: String) {
        store.updateSettings { it.copy(choice = ServerChoice.MANUAL, manualNodeId = id) }
        if (CoreState.isOn) scheduleReload()
    }

    /** Сервер, который показываем на главной: текущий по данным ядра или лучший по проверке. */
    fun currentNode(): Pair<Subscription, Node>? {
        val all = store.allNodes()
        if (all.isEmpty()) return null
        val s = store.settings.value
        if (s.choice == ServerChoice.MANUAL) all.firstOrNull { it.second.id == s.manualNodeId }?.let { return it }
        _activeId.value?.let { id -> all.firstOrNull { it.second.id == id }?.let { return it } }
        // VPN выключен: показываем, что будет выбрано. LTE, мосты и служебные серверы
        // на Wi-Fi не выбираются, поэтому и здесь их не предлагаем.
        val d = _delays.value
        val activeSub = store.subs.value.firstOrNull { it.id == s.providerSubId } ?: store.subs.value.firstOrNull()
        val inScope = if (s.mergeSubs) all else all.filter { it.first.id == activeSub?.id }.ifEmpty { all }
        val usable = inScope.filter { Names.kind(it.second.name) == Names.Kind.NORMAL }.ifEmpty { inScope }
        val alive = usable.filter { (d[it.second.id] ?: 1) != 0 }.ifEmpty { usable }
        if (s.choice == ServerChoice.AUTO) {
            return alive.filter { (d[it.second.id] ?: 0) > 0 }.minByOrNull { d[it.second.id]!! } ?: alive.first()
        }
        val sub = store.subs.value.firstOrNull { it.id == s.providerSubId } ?: store.subs.value.firstOrNull()
        // Серверы, где при последней проверке что-то не открылось, ядро берёт последними — здесь тоже.
        val q = store.quality.value
        val broken = if (q.fresh) q.byId.filterValues { it.failed.isNotEmpty() }.keys else emptySet()
        val ok = alive.filter { it.second.id !in broken && it.first.id == sub?.id }.ifEmpty { alive }
        // Своя группа провайдера «по отклику» (как «Лучший сервер») — самый быстрый.
        if (sub?.groupType == "url-test") {
            return ok.filter { (d[it.second.id] ?: 0) > 0 }.minByOrNull { d[it.second.id]!! } ?: ok.first()
        }
        val order = sub?.nodes?.map { it.id }.orEmpty()
        return order.firstNotNullOfOrNull { id -> ok.firstOrNull { it.second.id == id } } ?: ok.first()
    }

    // ---------- маршруты ----------

    fun setMode(mode: RouteMode) {
        if (store.settings.value.mode == mode) return
        store.updateSettings { it.copy(mode = mode) }
        if (CoreState.isOn) scheduleReload()
    }

    fun setDirect(pkg: String, direct: Boolean) {
        store.updateSettings {
            it.copy(directApps = if (direct) it.directApps + pkg else it.directApps - pkg)
        }
        if (CoreState.isOn) scheduleReload()
    }

    fun setDirectMany(pkgs: Collection<String>) {
        store.updateSettings { it.copy(directApps = it.directApps + pkgs, ruHintDismissed = true) }
        if (CoreState.isOn) scheduleReload()
    }

    /** Добавляет или меняет свой сайт. Возвращает false, если это не похоже на адрес. */
    fun setSite(input: String, viaVpn: Boolean): Boolean {
        val host = checker.normalize(input) ?: return false
        store.updateSettings { it.copy(customSites = it.customSites + (host to viaVpn)) }
        if (CoreState.isOn) scheduleReload()
        return true
    }

    fun removeSite(host: String) {
        store.updateSettings { it.copy(customSites = it.customSites - host) }
        if (CoreState.isOn) scheduleReload()
    }

    fun dismissRuHint() = store.updateSettings { it.copy(ruHintDismissed = true) }

    /** Объединять подписки или работать с одной выбранной. */
    fun setMerge(enabled: Boolean) {
        store.updateSettings { it.copy(mergeSubs = enabled) }
        if (CoreState.isOn) scheduleReload()
    }

    /** Нажали на карточку подписки на главной: дальше работаем с ней. */
    fun setActiveSub(id: String) {
        if (store.settings.value.providerSubId == id) return
        store.updateSettings {
            it.copy(providerSubId = id, choice = if (it.choice == ServerChoice.MANUAL) ServerChoice.PROVIDER else it.choice)
        }
        if (CoreState.isOn) scheduleReload()
    }

    /** Ядро для опытных. Новое ядро — перезапуск, если VPN включён. */
    fun setCore(kind: app.tropa.core.CoreKind) {
        if (store.settings.value.core == kind.name) return
        store.updateSettings { it.copy(core = kind.name) }
        if (CoreState.isOn) scheduleReload()
    }

    fun hasMihomo() = TropaVpnService.hasMihomo(context)

    /** Показывать ли уведомление «Подключено». Сервис применяет сразу, без переподключения. */
    fun setNotification(enabled: Boolean) {
        store.updateSettings { it.copy(notification = enabled) }
        if (CoreState.isOn) TropaVpnService.notification(context)
    }

    /** Обновить все подписки сразу (кнопка на виджете). */
    fun updateAll() = store.subs.value.filter { !it.isLocal }.forEach { updateSub(it.id) }

    /**
     * Открыт ли экран приложения. Без него скорость не считаем, а текущий сервер
     * спрашиваем у ядра реже: только для виджетов, батарею не тратим.
     */
    private val visible = MutableStateFlow(false)

    fun setVisible(v: Boolean) {
        visible.value = v
    }

    fun isVisible() = visible.value

    /** Растёт при каждом возврате в приложение: экраны перепроверяют разрешения, выданные в настройках Android. */
    private val _resumes = MutableStateFlow(0)
    val resumes: StateFlow<Int> = _resumes.asStateFlow()

    fun onResumed() {
        _resumes.value++
    }

    // ---------- телевизор ----------

    /** Подписки для отправки на ТВ (свои серверы без ссылки не передать). */
    fun subsForTv() = store.subs.value.filter { !it.isLocal && it.url.isNotBlank() }

    fun sendToTv(ticket: app.tropa.core.Pairing.Ticket) {
        val subs = subsForTv().map { app.tropa.core.Pairing.Sub(it.url, it.title) }
        if (subs.isEmpty()) {
            say("Нечего отправлять: добавьте подписку")
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { app.tropa.core.Pairing.send(ticket, subs) } }
            r.onSuccess { n -> say("Телевизор получил $n ${plural(n, "подписку", "подписки", "подписок")}") }
                .onFailure { e -> say("Не отправилось: ${e.message ?: "телевизор не ответил"}. Телефон и ТВ должны быть в одной сети") }
        }
    }

    // ---------- обновления ----------

    private val _listsAt = MutableStateFlow(app.tropa.core.Lists.updatedAt(context))
    /** Когда списки сайтов и geo-файлы последний раз обновились (0 — те, что в приложении). */
    val listsAt: StateFlow<Long> = _listsAt.asStateFlow()

    /** [force] — по кнопке «Проверить»: заодно сразу обновляем списки сайтов. */
    fun checkUpdates(force: Boolean = false) {
        scope.launch { app.tropa.core.Updater.check(context, force) }
        if (force) scope.launch(Dispatchers.IO) {
            val changed = runCatching { app.tropa.core.Lists.updateIfDue(context, force = true) }.getOrDefault(false)
            _listsAt.value = app.tropa.core.Lists.updatedAt(context)
            if (changed) say("Списки сайтов обновлены — вступят в силу при следующем подключении")
        }
    }

    /** Скачать и поставить новую версию, о которой уже знаем. */
    fun installUpdate() {
        val s = app.tropa.core.Updater.state.value
        val r = (s as? app.tropa.core.Updater.State.Available)?.release ?: (s as? app.tropa.core.Updater.State.Failed)?.release ?: return
        scope.launch { app.tropa.core.Updater.update(context, r) }
    }

    fun setAutoUpdate(enabled: Boolean) = store.updateSettings { it.copy(autoUpdate = enabled) }

    fun setAdvanced(enabled: Boolean) = store.updateSettings { it.copy(advanced = enabled) }

    /** Root-режим: сначала спрашиваем root (Magisk покажет окно), без него не включаем. */
    fun setRootMode(enabled: Boolean) {
        if (!enabled) {
            store.updateSettings { it.copy(rootMode = false) }
            if (CoreState.isOn) scheduleReload()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { app.tropa.core.RootProxy.hasRoot() }
            if (!ok) {
                say("Root-доступа нет: на этом телефоне root-режим не заработает")
                return@launch
            }
            store.updateSettings { it.copy(rootMode = true) }
            if (CoreState.isOn) scheduleReload()
        }
    }

    fun setIpv6(enabled: Boolean) {
        store.updateSettings { it.copy(ipv6 = enabled) }
        if (CoreState.isOn) scheduleReload()
    }

    /** Несколько переключений подряд применяем одним перезапуском. */
    private fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(900)
            TropaVpnService.reload(context)
        }
    }

    // ---------- подключение ----------

    fun connect() {
        if (store.allNodes().isEmpty()) {
            say("Сначала добавьте подписку")
            return
        }
        TropaVpnService.start(context)
    }

    fun disconnect() = TropaVpnService.stop(context)

    private fun startLive() {
        liveJob?.cancel()
        liveJob = scope.launch {
            launch(Dispatchers.IO) {
                // Поток скорости обрывается при перезапуске ядра — переподключаемся.
                while (isActive) {
                    visible.first { it }
                    // Скорость — разница счётчиков ядра за секунду.
                    val api = CoreState.api ?: break
                    val (up0, down0) = api.traffic()
                    delay(1000)
                    val (up1, down1) = api.traffic()
                    _speed.value = if (visible.value) (up1 - up0).coerceAtLeast(0) to (down1 - down0).coerceAtLeast(0) else 0L to 0L
                }
            }
            launch(Dispatchers.IO) {
                delay(1500)
                testAll()
                while (isActive) {
                    refreshActive()
                    delay(if (visible.value) 5000 else 30_000)
                }
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        _speed.value = 0L to 0L
        _activeId.value = null
    }

    private fun refreshActive() {
        val api = CoreState.api ?: return
        val s = store.settings.value
        _activeId.value = CoreState.plan?.let { api.current(it) } ?: s.manualNodeId.takeIf { s.choice == ServerChoice.MANUAL }
    }

    // ---------- приложения ----------

    data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

    private var appsCache: List<AppInfo>? = null

    /** Приложения со значком на рабочем столе — с их настоящими иконками, чтобы узнавать без чтения. */
    suspend fun installedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        appsCache?.let { return@withContext it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val size = (40 * context.resources.displayMetrics.density).toInt()
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val icon = runCatching {
                    val d = ri.loadIcon(pm)
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    d.setBounds(0, 0, size, size)
                    d.draw(canvas)
                    bmp.asImageBitmap()
                }.getOrNull()
                AppInfo(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), icon)
            }
            .sortedBy { it.label.lowercase() }
            .also { appsCache = it }
    }

    companion object {
        /** Банки, госсервисы и маркетплейсы, которые не любят зарубежные адреса. */
        val ruApps = setOf(
            "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.tinkoff.mb", "ru.vtb24.mobilebanking.android",
            "ru.alfabank.mobile.android", "ru.raiffeisen.rmobile", "ru.gazprombank.android.mobilebank.app",
            "ru.psbank.mobile", "ru.sovcombank.halvacard", "ru.ozon.fintech.finance", "ru.mw",
            "ru.rostel", "ru.gosuslugi.pos", "ru.nalog.www", "ru.fns.lkfl", "ru.mos.app", "ru.mos.polls",
            "ru.ozon.app.android", "com.wildberries.ru", "ru.beru.android", "ru.yandex.market", "com.avito.android",
            "com.vkontakte.android", "ru.ok.android", "ru.mail.mailapp", "ru.yandex.taxi", "ru.yandex.yandexmaps",
            "ru.yandex.searchplugin", "com.yandex.browser", "ru.kinopoisk", "ru.mts.mymts", "ru.megafon.mlk",
            "ru.beeline.services", "ru.tele2.mytele2", "ru.dublgis.dgismobile", "ru.rzd.pass", "ru.hh.android",
            "ru.oneme.app", "ru.vk.store", "com.vk.vkvideo", "ru.yandex.music",
        )

        fun extractSubscriptionUrl(text: String): String? {
            val t = text.trim()
            if (LinkParser.schemes.any { t.startsWith("$it://", true) }) return null
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
                return t.lines().first().trim()
            }
            // clash://install-config?url=…, v2rayng://install-sub?url=…, sing-box://import-remote-profile?url=…
            Regex("[?&]url=([^&#\\s]+)").find(t)?.let { m ->
                val u = LinkParser.decode(m.groupValues[1])
                if (u.startsWith("http", true)) return u
            }
            // happ://add/https://…, incy://add/…, tropa://add/…
            val idx = t.indexOf("http", ignoreCase = true)
            if (idx > 0 && t.substring(0, idx).contains("://")) {
                val u = LinkParser.decode(t.substring(idx)).substringBefore(' ')
                if (u.startsWith("http://", true) || u.startsWith("https://", true)) return u
            }
            // Ссылка, вставленная без протокола: «example.com/sub/abc».
            if (Regex("^[\\w.-]+\\.[a-z]{2,}/\\S+$", RegexOption.IGNORE_CASE).matches(t)) return "https://$t"
            return null
        }

        fun formatBytes(b: Long): String = when {
            b >= 1L shl 40 -> "%.1f ТБ".format(b / (1L shl 40).toDouble())
            b >= 1L shl 30 -> "%.1f ГБ".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.0f МБ".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.0f КБ".format(b / (1L shl 10).toDouble())
            else -> "$b Б"
        }

        fun formatSpeed(bps: Long): String = when {
            bps >= 1_000_000 -> "%.1f Мбит/с".format(bps * 8 / 1_000_000.0)
            bps >= 1_000 -> "%.0f кбит/с".format(bps * 8 / 1_000.0)
            else -> "0 кбит/с"
        }

        fun plural(n: Int, one: String, few: String, many: String): String {
            val m10 = n % 10
            val m100 = n % 100
            return when {
                m10 == 1 && m100 != 11 -> one
                m10 in 2..4 && m100 !in 12..14 -> few
                else -> many
            }
        }
    }
}
