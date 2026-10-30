package app.tropa.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import app.tropa.BuildConfig
import app.tropa.R
import app.tropa.TropaApp
import app.tropa.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Обновления Тропы без магазина приложений.
 *
 * Описание последней версии лежит в update.json рядом с APK (см. tools/release.sh):
 * номер версии, что нового и для каждой архитектуры — ссылка, размер и SHA-256.
 * Скачанный файл сверяем по SHA-256 и по ключу подписи, ставим через PackageInstaller.
 * Первую установку пользователь подтверждает сам; дальше Тропа — «владелец» своих
 * обновлений, и Android 12+ ставит их без вопросов.
 *
 * Отдельной фоновой задачи нет: проверяем при открытии приложения и из VPN-сервиса,
 * который и так просыпается раз в 15 минут ради подписок.
 */
object Updater {

    data class Release(val versionCode: Int, val versionName: String, val notes: String, val url: String, val sha256: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val at: Long) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private const val CHECK_EVERY = 6 * 3_600_000L
    private const val AUTO_EVERY = 12 * 3_600_000L
    const val ACTION_STATUS = "app.tropa.UPDATE_STATUS"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Последняя известная версия из кэша — чтобы баннер был виден сразу после запуска. */
    fun restore(context: Context) {
        if (_state.value != State.Idle) return
        val p = prefs(context)
        val r = p.getString("release", null)?.let { runCatching { parseRelease(JSONObject(it)) }.getOrNull() }
        _state.value = when {
            r != null && r.versionCode > BuildConfig.VERSION_CODE -> State.Available(r)
            p.getLong("checkedAt", 0) > 0 -> State.UpToDate(p.getLong("checkedAt", 0))
            else -> State.Idle
        }
    }

    /** Проверить, есть ли версия новее. [force] — по кнопке, без оглядки на «недавно проверяли». */
    suspend fun check(context: Context, force: Boolean = false, every: Long = CHECK_EVERY): Release? = withContext(Dispatchers.IO) {
        restore(context)
        val p = prefs(context)
        val busy = _state.value is State.Downloading || _state.value is State.Installing
        if (busy || (!force && System.currentTimeMillis() - p.getLong("checkedAt", 0) < every)) {
            return@withContext (_state.value as? State.Available)?.release
        }
        _state.value = State.Checking
        runCatching {
            val json = JSONObject(get(BuildConfig.UPDATE_URL))
            val r = parse(json) ?: error("Нет сборки для этого телефона")
            val now = System.currentTimeMillis()
            p.edit().putLong("checkedAt", now).putString("release", releaseJson(r).toString()).apply()
            if (r.versionCode > BuildConfig.VERSION_CODE) {
                _state.value = State.Available(r)
                r
            } else {
                _state.value = State.UpToDate(now)
                null
            }
        }.getOrElse { e ->
            _state.value = State.Failed("Не удалось проверить: ${e.message ?: "нет связи"}")
            null
        }
    }

    /** Скачать и поставить. Вызывается по кнопке «Обновить» и из автообновления. */
    suspend fun update(context: Context, r: Release) = withContext(Dispatchers.IO) {
        runCatching {
            // Описание могло устареть (выпуск пересобрали) — перед скачиванием берём свежее.
            val fresh = runCatching { parse(JSONObject(get(BuildConfig.UPDATE_URL))) }.getOrNull()
                ?.takeIf { it.versionCode >= r.versionCode } ?: r
            val file = download(context, fresh)
            install(context, file, fresh)
        }.onFailure { e -> _state.value = State.Failed(e.message ?: "Не удалось обновить", r) }
    }

    /**
     * Автообновление из VPN-сервиса: проверка раз в 12 часов, скачивание только по Wi-Fi
     * (или другой безлимитной сети). Ставим молча, если Android это разрешает и экран погашен:
     * установка на полминуты рвёт VPN, днём это мешало бы. Иначе — уведомление.
     */
    suspend fun auto(context: Context) {
        val app = context.applicationContext as TropaApp
        if (!app.store.settings.value.autoUpdate) return
        val r = check(context, every = AUTO_EVERY) ?: return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val unmetered = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val screenOff = !context.getSystemService(android.os.PowerManager::class.java).isInteractive
        if (unmetered && screenOff && canInstallSilently(context)) {
            update(context, r)
        } else if (prefs(context).getInt("notified", 0) != r.versionCode) {
            notifyAvailable(context, r)
            prefs(context).edit().putInt("notified", r.versionCode).apply()
        }
    }

    /** Разрешено ли Тропе ставить приложения («Установка неизвестных приложений»). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Ставит ли Android обновление без вопросов: Тропа сама себя и устанавливала. */
    private fun canInstallSilently(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !canInstall(context)) return false
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        } else null
        return installer == context.packageName
    }

    private fun download(context: Context, r: Release): File {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Tropa-${r.versionName}.apk")
        _state.value = State.Downloading(r, 0f)
        val c = open(r.url)
        val digest = MessageDigest.getInstance("SHA-256")
        val total = r.size.takeIf { it > 0 } ?: c.contentLengthLong
        var done = 0L
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var last = 0f
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    val p = if (total > 0) done.toFloat() / total else 0f
                    if (p - last >= 0.01f) { last = p; _state.value = State.Downloading(r, p) }
                }
            }
        }
        c.disconnect()
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(r.sha256, ignoreCase = true)) {
            file.delete()
            error("Файл скачался с ошибкой, попробуйте ещё раз")
        }
        if (!signedByUs(context, file, r)) {
            file.delete()
            error("Файл подписан чужим ключом — ставить не будем")
        }
        return file
    }

    /**
     * Та же ли это Тропа: имя пакета, номер версии и ключ подписи. Ключ Тропы мог смениться
     * (так было при переходе с отладочного), поэтому сравниваем с историей ключей из APK.
     */
    private fun signedByUs(context: Context, file: File, r: Release): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        if (apk.packageName != context.packageName || apk.longVersionCode != r.versionCode.toLong()) return false
        fun certs(info: android.content.pm.SigningInfo?): Set<String> = info?.let { s ->
            (if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory).orEmpty()
                .map { it.toCharsString() }.toSet()
        }.orEmpty()
        val ours = certs(pm.getPackageInfo(context.packageName, flags).signingInfo)
        val theirs = certs(apk.signingInfo)
        return ours.isNotEmpty() && ours.any { it in theirs }
    }

    private fun install(context: Context, file: File, r: Release) {
        _state.value = State.Installing(r)
        // Установка перезапускает приложение и рвёт VPN — после неё включим его обратно.
        prefs(context).edit().putBoolean("restartVpn", CoreState.isOn).apply()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("tropa.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    internal fun onStatus(context: Context, intent: Intent) {
        val r = (_state.value as? State.Installing)?.release
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android просит подтверждения: открываем его окно, а если Тропа не на экране — уведомление.
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val app = context.applicationContext as TropaApp
                if (app.controller.isVisible()) context.startActivity(confirm)
                else notify(context, "Обновление Тропы готово", "Нажмите, чтобы установить", PendingIntent.getActivity(context, 3, confirm, PendingIntent.FLAG_IMMUTABLE))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // приложение перезапустится, дальше — onReplaced
            else -> {
                prefs(context).edit().putBoolean("restartVpn", false).apply()
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _state.value = State.Failed(if (msg.isNullOrBlank()) "Установка отменена" else "Не установилось: $msg", r)
            }
        }
    }

    /** Новая версия встала: убираем скачанный файл и, если VPN был включён, включаем его снова. */
    internal fun onReplaced(context: Context) {
        File(context.cacheDir, "update").deleteRecursively()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        val p = prefs(context)
        val restart = p.getBoolean("restartVpn", false)
        p.edit().putBoolean("restartVpn", false).remove("release").apply()
        if (restart && !TropaVpnService.needsPermission(context)) TropaVpnService.start(context)
    }

    private fun notifyAvailable(context: Context, r: Release) {
        val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 3)
        notify(context, "Вышла Тропа ${r.versionName}", r.notes.ifBlank { "Нажмите, чтобы обновить" },
            PendingIntent.getActivity(context, 4, open, PendingIntent.FLAG_IMMUTABLE))
    }

    private fun notify(context: Context, title: String, text: String, action: PendingIntent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(action)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    // ---------- описание версии ----------

    /**
     * Сборка под этот телефон: первая архитектура из поддерживаемых, для которой есть APK, —
     * и та же сборка, что стоит сейчас («xray» или «mihomo», у второй ключ с «-mihomo»).
     */
    private fun parse(o: JSONObject): Release? {
        val apks = o.optJSONObject("apks") ?: return null
        val suffix = if (BuildConfig.FLAVOR == "mihomo") "-mihomo" else ""
        val abi = Build.SUPPORTED_ABIS.firstOrNull { apks.has(it + suffix) } ?: return null
        val a = apks.getJSONObject(abi + suffix)
        return Release(o.getInt("versionCode"), o.getString("versionName"), o.optString("notes"),
            a.getString("url"), a.getString("sha256"), a.optLong("size"))
    }

    private fun releaseJson(r: Release) = JSONObject().put("versionCode", r.versionCode).put("versionName", r.versionName)
        .put("notes", r.notes).put("url", r.url).put("sha256", r.sha256).put("size", r.size)

    private fun parseRelease(o: JSONObject) = Release(o.getInt("versionCode"), o.getString("versionName"), o.optString("notes"),
        o.getString("url"), o.getString("sha256"), o.optLong("size"))

    private fun open(url: String): HttpURLConnection {
        // GitHub отдаёт файлы через перенаправление на другой адрес — проходим его сами.
        var target = url
        repeat(6) {
            val c = URI(target).toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Tropa/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code in 300..399) {
                target = URI(target).resolve(c.getHeaderField("Location")).toString()
                c.disconnect()
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                error("сервер ответил $code")
            }
            return c
        }
        error("слишком много перенаправлений")
    }

    private fun get(url: String): String = open(url).let { c -> c.inputStream.use { it.bufferedReader().readText() }.also { c.disconnect() } }
}

/** Итог установки обновления и событие «Тропа обновилась». */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Updater.ACTION_STATUS -> Updater.onStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updater.onReplaced(context)
        }
    }
}
