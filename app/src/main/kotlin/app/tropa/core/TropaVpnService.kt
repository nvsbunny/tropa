package app.tropa.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.tropa.R
import app.tropa.data.QualityData
import app.tropa.data.QualityInfo
import app.tropa.TropaApp
import app.tropa.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * VPN-сервис: поднимает TUN-интерфейс и запускает ядро (Xray или mihomo) отдельным процессом.
 *
 * Дескриптор TUN передаём ядру как fd 3: временно подставляем его на место
 * stdin нашего процесса, а оболочка дочернего процесса делает «3<&0».
 * Само приложение исключено из VPN, поэтому трафик ядра к серверам не зацикливается.
 */
class TropaVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var process: Process? = null
    @Volatile private var stopping = false
    private var restarts = 0
    /** Сеть, под которую собран конфиг: на мобильной в автовыбор пускаем LTE-входы. */
    @Volatile private var onMobile = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reloadJob: kotlinx.coroutines.Job? = null
    /** Работает root-режим: ядро запущено через su, стоят правила iptables. */
    @Volatile private var rootActive = false

    /**
     * Само приложение исключено из VPN, поэтому его сеть по умолчанию — настоящая:
     * Wi-Fi или мобильная. По ней и решаем.
     */
    private fun isOnMobile(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    /**
     * Пока VPN включён, сервис и так живёт, поэтому подписки обновляем здесь,
     * по интервалу провайдера (обычно час), без отдельной фоновой задачи.
     * Если серверы поменялись — пересобираем конфиг.
     */
    private fun keepSubscriptionsFresh() {
        if (refreshJob?.isActive == true) return
        val store = (application as TropaApp).store
        val fetcher = app.tropa.sub.SubscriptionFetcher(this)
        refreshJob = scope.launch {
            while (true) {
                delay(15 * 60_000L)
                val now = System.currentTimeMillis()
                var changed = false
                store.subs.value.filter { !it.isLocal && now - it.updatedAt > it.updateIntervalHours * 3_600_000L }.forEach { old ->
                    runCatching { fetcher.fetch(old.url, old) }.onSuccess { fresh ->
                        store.upsertSub(fresh)
                        // Некоторые провайдеры каждый раз выдают новые адрес и SNI из пула: это не повод рвать
                        // соединение. Пересобираем, только если поменялся сам список серверов.
                        if (fresh.nodes.map { it.id } != old.nodes.map { it.id }) changed = true
                    }.onFailure { e -> store.upsertSub(old.copy(lastError = e.message.orEmpty())) }
                }
                // Ночная проверка качества: только на Wi-Fi, на зарядке и с выключенным экраном,
                // не чаще раза в сутки — днём и от батареи телефон не грузим.
                val power = getSystemService(android.os.PowerManager::class.java)
                val battery = getSystemService(android.os.BatteryManager::class.java)
                val q = store.quality.value
                val api = CoreState.api
                if (!changed && api != null && !onMobile && battery.isCharging && !power.isInteractive &&
                    now - q.at > 20 * 3_600_000L
                ) {
                    val nodes = store.allNodes().map { it.second }.filter { app.tropa.ui.Names.kind(it.name) == app.tropa.ui.Names.Kind.NORMAL }
                    val results = Quality.run(this@TropaVpnService, nodes)
                    store.saveQuality(QualityData(System.currentTimeMillis(), results.associate { it.id to QualityInfo(it.delay, it.failed) }, Quality.rank(results)))
                    changed = store.settings.value.choice == app.tropa.data.ServerChoice.AUTO
                }
                // Списки сайтов и geo-файлы — раз в три дня по Wi-Fi; вступят в силу при следующем подключении.
                runCatching { Lists.updateIfDue(this@TropaVpnService) }
                // Обновление Тропы: сама проверка — раз в 12 часов, установка — когда телефон не используется.
                runCatching { Updater.auto(this@TropaVpnService) }
                if (changed && !stopping) {
                    stopping = true
                    cleanup()
                    startCore()
                }
            }
        }
    }

    /** Перешли с Wi-Fi на мобильную или обратно — пересобираем выбор серверов. */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (mobile == onMobile || process == null) return
                reloadJob?.cancel()
                reloadJob = scope.launch {
                    // Сеть при переключении «дребезжит»: ждём, пока успокоится.
                    delay(3000)
                    if (isOnMobile() != onMobile && !stopping) {
                        stopping = true
                        cleanup()
                        startCore()
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun unwatchNetwork() {
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCore(CoreState.Status.Off)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NOTIFY -> {
                if (CoreState.status.value is CoreState.Status.On) showConnected()
                return START_STICKY
            }
            ACTION_RELOAD -> {
                // Новые маршруты или серверы: пересобираем туннель и конфигурацию.
                if (wantNotification()) startForegroundCompat(getString(R.string.app_name), "Применяем настройки…")
                scope.launch {
                    stopping = true
                    cleanup()
                    restarts = 0
                    startCore()
                }
            }
            else -> {
                startForegroundCompat(getString(R.string.app_name), "Подключаемся…")
                if (process == null) scope.launch { startCore() }
            }
        }
        return START_STICKY
    }

    private suspend fun startCore() {
        stopping = false
        CoreState.set(CoreState.Status.Connecting)
        val app = application as TropaApp
        val store = app.store
        // IPv6 раздаём, только если он правда работает в текущей сети. Бывает, что адрес IPv6 у Wi-Fi есть,
        // а наружу по нему не пройти (так у роутеров с VPN): тогда DNS отдавал бы ya.ru адрес IPv6,
        // и сайты, которые идут напрямую, не открывались бы.
        val settings = store.settings.value.let { if (it.ipv6 && !ipv6Works()) it.copy(ipv6 = false) else it }
        val nodes = store.allNodes().map { it.second }
        onMobile = isOnMobile()
        if (nodes.isEmpty()) {
            fail("Нет серверов. Добавьте подписку")
            return
        }
        // Root-режим «для опытных»: без VPN, через TPROXY и iptables (см. RootProxy).
        val rootMode = settings.rootMode
        if (rootMode && !RootProxy.hasRoot()) {
            fail("Нет root-доступа. Разрешите Тропе root в Magisk или KernelSU либо выключите root-режим")
            return
        }
        try {
            val workDir = File(filesDir, "core").apply { mkdirs() }
            copyGeo(workDir)

            val pfd = if (rootMode) null else buildTun(settings.directApps)
            tun = pfd
            val port = ServerSocket(0).use { it.localPort }
            val provider = store.subs.value.firstNotNullOfOrNull { s ->
                s.routing.takeIf { it.isNotBlank() }?.let { ProviderRouting.parse(it) }
            }
            val operator = runCatching {
                // При двух SIM нужен оператор той, через которую идёт мобильный интернет.
                val dataSub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                getSystemService(android.telephony.TelephonyManager::class.java).createForSubscriptionId(dataSub).networkOperatorName
            }.getOrNull().orEmpty()
            val quality = store.quality.value
            val ranking = if (quality.fresh) quality.ranking else emptyList()
            val broken = if (quality.fresh) quality.byId.filterValues { it.delay > 0 && it.failed.isNotEmpty() }.keys else emptySet()
            val lists = lists()
            val log = File(workDir, "core.log").apply { writeText("") }
            appendLog(log, "Tropa: IPv6 " + if (settings.ipv6) "включён" else if (store.settings.value.ipv6) "в этой сети не работает — только IPv4" else "выключен в настройках")
            // Ядро для опытных — mihomo, если пользователь его выбрал и оно есть в этой сборке.
            val core = if (!rootMode && settings.core == CoreKind.MIHOMO.name && hasCore(CoreKind.MIHOMO)) CoreKind.MIHOMO else CoreKind.XRAY
            val api: CoreApi
            val plan: XrayConfig.Plan
            if (core == CoreKind.MIHOMO) {
                plan = XrayConfig.plan(store.subs.value, settings, onMobile, operator, ranking, broken)
                val secret = randomSecret()
                File(workDir, "config.yaml").writeText(
                    MihomoConfig.build(store.subs.value, settings, lists, port, secret, TUN_FD, provider, plan)
                )
                launchProcess(pfd!!, workDir, log, core)
                api = MihomoApi(port, secret)
            } else {
                val (config, xrayPlan) = XrayConfig.build(
                    store.subs.value, settings, lists, port, provider, onMobile, operator, ranking = ranking, broken = broken,
                    tun = !rootMode, tproxyPort = if (rootMode) RootProxy.PORT else 0,
                )
                plan = xrayPlan
                File(workDir, "config.json").writeText(config)
                if (rootMode) {
                    appendLog(log, "Tropa: root-режим, TPROXY на порту ${RootProxy.PORT}")
                    RootProxy.start(workDir, applicationInfo.nativeLibraryDir + "/" + core.binary, bypassUids(settings.directApps))
                    rootActive = true
                } else {
                    launchProcess(pfd!!, workDir, log, core)
                }
                api = XrayApi(port)
            }
            repeat(50) {
                if (api.isAlive()) {
                    CoreState.api = api
                    CoreState.core = core
                    CoreState.plan = plan
                    CoreState.set(CoreState.Status.On(System.currentTimeMillis()))
                    showConnected()
                    restarts = 0
                    if (rootMode) watchRoot(log) else watchProcess()
                    watchNetwork()
                    keepSubscriptionsFresh()
                    return
                }
                if (!rootMode && process?.isAlive != true) {
                    fail(coreError(log))
                    return
                }
                delay(200)
            }
            fail("Ядро не запустилось за 10 секунд. " + coreError(log))
        } catch (e: Exception) {
            fail(e.message ?: "Не удалось подключиться")
        }
    }

    /**
     * Работает ли IPv6 для сайтов, которые идут напрямую. Само приложение идёт мимо VPN, поэтому
     * спрашиваем сеть телефона: есть ли у ya.ru адрес IPv6 и отвечает ли по нему сам сайт.
     * Простого соединения мало: роутер с VPN или DNS-перехватом может «принять» его сам.
     */
    private fun ipv6Works(): Boolean = runCatching<Boolean> {
        val v6 = java.net.InetAddress.getAllByName("ya.ru").filterIsInstance<java.net.Inet6Address>().firstOrNull()
            ?: return@runCatching false
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(v6, 80), 1500)
            s.soTimeout = 2000
            s.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: ya.ru\r\nConnection: close\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readLine()?.startsWith("HTTP/") == true
        }
    }.getOrDefault(false)

    private fun buildTun(directApps: Set<String>): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .setBlocking(false)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("172.19.0.2")
            // IPv6 забираем в туннель всегда: если ядру он не нужен, оно его отбросит,
            // а приложения уйдут на IPv4. Иначе IPv6 утёк бы мимо VPN.
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        builder.addDisallowedApplication(packageName)
        directApps.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
        return builder.establish() ?: throw IllegalStateException("Android не дал создать VPN. Проверьте разрешение")
    }

    private fun hasCore(kind: CoreKind) = File(applicationInfo.nativeLibraryDir, kind.binary).exists()

    private fun launchProcess(pfd: ParcelFileDescriptor, workDir: File, log: File, core: CoreKind) {
        val binary = applicationInfo.nativeLibraryDir + "/" + core.binary
        val fd = pfd.fileDescriptor
        clearCloseOnExec(fd)

        val args = when (core) {
            CoreKind.XRAY -> "run -c \"${workDir.absolutePath}/config.json\""
            CoreKind.MIHOMO -> "-d \"${workDir.absolutePath}\" -f \"${workDir.absolutePath}/config.yaml\""
        }
        val pb = ProcessBuilder("/system/bin/sh", "-c", "exec \"$binary\" $args 3<&0")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.environment()["HOME"] = workDir.absolutePath
        pb.environment()["TMPDIR"] = cacheDir.absolutePath
        // Xray на Android берёт дескриптор TUN из этой переменной, geo-файлы — из папки ядра.
        pb.environment()["XRAY_TUN_FD"] = TUN_FD.toString()
        pb.environment()["XRAY_LOCATION_ASSET"] = workDir.absolutePath

        synchronized(stdinLock) {
            val savedStdin = Os.dup(FileDescriptor.`in`)
            try {
                Os.dup2(fd, 0)
                process = pb.start()
            } finally {
                Os.dup2(savedStdin, 0)
                Os.close(savedStdin)
            }
        }
    }

    /** Тропа и приложения «напрямую» в root-режиме идут мимо ядра — по их UID. */
    private fun bypassUids(directApps: Set<String>): List<Int> =
        listOf(android.os.Process.myUid()) + directApps.mapNotNull { pkg -> runCatching { packageManager.getPackageUid(pkg, 0) }.getOrNull() }

    /**
     * Root-режим: ядро живёт отдельно от приложения, поэтому следим за ним по странице статистики.
     * Умерло — снимаем правила (иначе весь трафик уходил бы в пустоту) и сообщаем.
     */
    private fun watchRoot(log: File) {
        scope.launch {
            while (rootActive && !stopping) {
                delay(5000)
                if (!rootActive || stopping) break
                if (CoreState.api?.isAlive() != true) {
                    fail("Ядро в root-режиме остановилось. " + coreError(log))
                    break
                }
            }
        }
    }

    /** Если ядро упало само, перезапускаем его до трёх раз подряд. */
    private fun watchProcess() {
        val p = process ?: return
        scope.launch {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (stopping || p != process) return@launch
            val log = File(filesDir, "core/core.log")
            if (restarts < 3) {
                restarts++
                appendLog(log, "Tropa: ядро завершилось с кодом $code, перезапуск $restarts")
                cleanup()
                delay(1000L * restarts)
                startCore()
            } else {
                fail("Ядро несколько раз упало подряд. " + coreError(log))
            }
        }
    }

    private fun fail(message: String) {
        stopCore(CoreState.Status.Error(message))
        stopSelf()
    }

    private fun cleanup() {
        CoreState.api = null
        if (rootActive) {
            RootProxy.stop(File(filesDir, "core"))
            rootActive = false
        }
        process?.destroy()
        process = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun stopCore(status: CoreState.Status) {
        stopping = true
        unwatchNetwork()
        refreshJob?.cancel()
        refreshJob = null
        cleanup()
        CoreState.set(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        // Другой VPN забрал туннель или пользователь отключил нас в настройках Android.
        stopCore(CoreState.Status.Off)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) stopCore(CoreState.Status.Off)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * geosite.dat и geoip.dat кладём рядом с конфигом: Xray ищет их там.
     * Берём свежие, если Тропа их скачала (см. [Lists]), иначе — из APK.
     */
    private fun copyGeo(dir: File) {
        listOf("geosite.dat", "geoip.dat").forEach { name ->
            val target = File(dir, name)
            val fresh = Lists.file(this, name)
            if (fresh != null) {
                if (target.length() != fresh.length() || target.lastModified() < fresh.lastModified()) fresh.copyTo(target, overwrite = true)
                return@forEach
            }
            val size = assets.openFd("xray/$name").use { it.length }
            if (target.length() == size) return@forEach
            assets.open("xray/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun lists(): XrayConfig.Lists = XrayConfig.Lists(
        blockedDomains = Lists.lines(this, "blocked.list"),
        blockedIps = listOf("telegram", "meta", "twitter", "discord").flatMap { Lists.lines(this, "ip-$it.list") }.distinct(),
        geositeTags = Lists.tags(this, "geosite.dat"),
        geoipTags = Lists.tags(this, "geoip.dat"),
    )

    private fun coreError(log: File): String {
        val lines = runCatching { log.readLines() }.getOrDefault(emptyList())
        val err = lines.lastOrNull { "[Error]" in it || "Failed to start" in it || "failed to" in it || "level=error" in it || "level=fatal" in it || "Parse config error" in it }
            ?: lines.lastOrNull()
        val msg = err?.let { e -> if ("msg=" in e) e.substringAfter("msg=").trim('"', ' ') else e.substringAfter("] ", e).trim() }
        return if (msg.isNullOrBlank()) "Подробности в журнале ядра" else "Ядро: $msg"
    }

    private fun appendLog(log: File, line: String) = runCatching { log.appendText("$line\n") }

    private fun wantNotification() = (application as TropaApp).store.settings.value.notification

    /**
     * Уведомление «Подключено» — по желанию. Без него сервис не останавливается:
     * пока туннель поднят, Android сам держит VPN-сервис как важный (и показывает ключ
     * в строке состояния). Запуск всё равно идёт с уведомлением — этого требует система,
     * — и оно исчезает, как только VPN подключился.
     */
    private fun showConnected() {
        if (wantNotification()) startForegroundCompat("Подключено", "Нажмите, чтобы открыть Тропу")
        else stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundCompat(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Подключение", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, TropaVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val MTU = 9000
        private const val TUN_FD = 3
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "app.tropa.START"
        const val ACTION_STOP = "app.tropa.STOP"
        const val ACTION_RELOAD = "app.tropa.RELOAD"
        const val ACTION_NOTIFY = "app.tropa.NOTIFY"
        private val stdinLock = Any()

        private fun randomSecret(): String =
            ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Есть ли в этой сборке ядро mihomo (его можно собрать без него). */
        /** Нужно ли спросить разрешение на VPN. В root-режиме VPN не используется. */
        fun needsPermission(context: Context): Boolean =
            !(context.applicationContext as TropaApp).store.settings.value.rootMode && VpnService.prepare(context) != null

        fun hasMihomo(context: Context) = File(context.applicationInfo.nativeLibraryDir, CoreKind.MIHOMO.binary).exists()

        /**
         * Снимает флаг FD_CLOEXEC, иначе дескриптор закроется при запуске процесса.
         * До Android 11 Os.fcntlInt скрыт от приложений, поэтому там через рефлексию.
         */
        private fun clearCloseOnExec(fd: FileDescriptor) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val flags = Os.fcntlInt(fd, OsConstants.F_GETFD, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFD, flags and OsConstants.FD_CLOEXEC.inv())
            } else {
                val m = Os::class.java.getMethod(
                    "fcntlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                val flags = m.invoke(null, fd, OsConstants.F_GETFD, 0) as Int
                m.invoke(null, fd, OsConstants.F_SETFD, flags and OsConstants.FD_CLOEXEC.inv())
            }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TropaVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TropaVpnService::class.java).setAction(ACTION_STOP))
        }

        fun notification(context: Context) {
            context.startService(Intent(context, TropaVpnService::class.java).setAction(ACTION_NOTIFY))
        }

        fun reload(context: Context) {
            context.startService(Intent(context, TropaVpnService::class.java).setAction(ACTION_RELOAD))
        }
    }
}
