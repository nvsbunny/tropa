package app.tropa.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Всё состояние приложения в одном JSON-файле во внутренней папке.
 * Подписок и настроек немного, поэтому база данных здесь не нужна.
 */
class Store(context: Context) {
    private val file = File(context.filesDir, "state.json")

    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val qualityFile = File(context.filesDir, "quality.json")
    private val _quality = MutableStateFlow(QualityData())
    val quality: StateFlow<QualityData> = _quality.asStateFlow()

    fun saveQuality(q: QualityData) {
        _quality.value = q
        runCatching {
            qualityFile.writeText(JSONObject().apply {
                put("at", q.at)
                put("ranking", JSONArray(q.ranking))
                put("byId", JSONObject().apply {
                    q.byId.forEach { (id, i) -> put(id, JSONObject().put("delay", i.delay).put("failed", JSONArray(i.failed))) }
                })
            }.toString())
        }
    }

    private fun loadQuality() {
        if (!qualityFile.exists()) return
        val o = JSONObject(qualityFile.readText())
        val by = o.optJSONObject("byId") ?: JSONObject()
        _quality.value = QualityData(
            at = o.optLong("at"),
            ranking = o.optJSONArray("ranking")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            byId = by.keys().asSequence().associateWith { k ->
                val i = by.getJSONObject(k)
                QualityInfo(i.optInt("delay"), i.optJSONArray("failed")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
            },
        )
    }

    init {
        runCatching { load() }
        runCatching { loadQuality() }
    }

    fun allNodes(): List<Pair<Subscription, Node>> =
        _subs.value.flatMap { s -> s.nodes.map { s to it } }

    fun upsertSub(sub: Subscription) {
        _subs.update { list ->
            val i = list.indexOfFirst { it.id == sub.id }
            if (i >= 0) list.toMutableList().also { it[i] = sub } else list + sub
        }
        save()
    }

    fun removeSub(id: String) {
        _subs.update { list -> list.filterNot { it.id == id } }
        save()
    }

    fun updateSettings(block: (Settings) -> Settings) {
        _settings.update(block)
        save()
    }

    @Synchronized
    private fun save() {
        val root = JSONObject()
        root.put("settings", JSONObject().apply {
            val s = _settings.value
            put("mode", s.mode.name)
            put("directApps", JSONArray(s.directApps.toList()))
            put("customSites", JSONObject().apply { s.customSites.forEach { (k, v) -> put(k, v) } })
            put("choice", s.choice.name)
            put("providerSubId", s.providerSubId)
            put("mergeSubs", s.mergeSubs)
            put("manualNodeId", s.manualNodeId)
            put("ipv6", s.ipv6)
            put("ruHintDismissed", s.ruHintDismissed)
            put("advanced", s.advanced)
            put("core", s.core)
            put("notification", s.notification)
            put("autoUpdate", s.autoUpdate)
            put("rootMode", s.rootMode)
        })
        root.put("subs", JSONArray().apply {
            _subs.value.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("url", s.url); put("title", s.title)
                    put("upload", s.upload); put("download", s.download); put("total", s.total)
                    put("expire", s.expire); put("updatedAt", s.updatedAt)
                    put("updateIntervalHours", s.updateIntervalHours)
                    put("announce", s.announce); put("supportUrl", s.supportUrl)
                    put("lastError", s.lastError)
                    put("routing", s.routing)
                    put("groupType", s.groupType)
                    put("sendHwid", s.sendHwid)
                    put("groupMembers", JSONArray(s.groupMembers))
                    put("nodes", JSONArray().apply {
                        s.nodes.forEach { n ->
                            put(JSONObject().apply {
                                put("id", n.id); put("name", n.name); put("type", n.type)
                                put("server", n.server); put("port", n.port)
                                put("proxy", toJson(n.proxy))
                            })
                        }
                    })
                })
            }
        })
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        val root = JSONObject(file.readText())
        root.optJSONObject("settings")?.let { o ->
            _settings.value = Settings(
                mode = runCatching { RouteMode.valueOf(o.optString("mode")) }.getOrDefault(RouteMode.RU_DIRECT),
                directApps = o.optJSONArray("directApps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    ?: emptySet(),
                customSites = o.optJSONObject("customSites")?.let { j ->
                    j.keys().asSequence().associateWith { j.optBoolean(it) }
                } ?: emptyMap(),
                choice = runCatching { ServerChoice.valueOf(o.optString("choice")) }.getOrDefault(ServerChoice.PROVIDER),
                providerSubId = o.optString("providerSubId"),
                mergeSubs = o.optBoolean("mergeSubs", true),
                manualNodeId = o.optString("manualNodeId"),
                ipv6 = o.optBoolean("ipv6", true),
                ruHintDismissed = o.optBoolean("ruHintDismissed", false),
                advanced = o.optBoolean("advanced", false),
                core = o.optString("core", "XRAY").ifBlank { "XRAY" },
                notification = o.optBoolean("notification", true),
                autoUpdate = o.optBoolean("autoUpdate", true),
                rootMode = o.optBoolean("rootMode", false),
            )
        }
        val arr = root.optJSONArray("subs") ?: return
        _subs.value = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val nodes = o.optJSONArray("nodes") ?: JSONArray()
            Subscription(
                id = o.getString("id"), url = o.optString("url"), title = o.optString("title"),
                upload = o.optLong("upload"), download = o.optLong("download"), total = o.optLong("total"),
                expire = o.optLong("expire"), updatedAt = o.optLong("updatedAt"),
                updateIntervalHours = o.optInt("updateIntervalHours", 12),
                announce = o.optString("announce"), supportUrl = o.optString("supportUrl"),
                lastError = o.optString("lastError"),
                routing = o.optString("routing"),
                groupType = o.optString("groupType"),
                sendHwid = o.optBoolean("sendHwid", false),
                groupMembers = o.optJSONArray("groupMembers")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                nodes = (0 until nodes.length()).map { j ->
                    val n = nodes.getJSONObject(j)
                    @Suppress("UNCHECKED_CAST")
                    Node(
                        id = n.getString("id"), name = n.optString("name"), type = n.optString("type"),
                        server = n.optString("server"), port = n.optInt("port"),
                        proxy = fromJson(n.getJSONObject("proxy")) as Map<String, Any?>,
                    )
                },
            )
        }
    }

    companion object {
        fun toJson(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            else -> value
        }

        fun fromJson(value: Any?): Any? = when (value) {
            JSONObject.NULL, null -> null
            is JSONObject -> linkedMapOf<String, Any?>().apply {
                value.keys().forEach { k -> put(k, fromJson(value.get(k))) }
            }
            is JSONArray -> (0 until value.length()).map { fromJson(value.get(it)) }
            else -> value
        }
    }
}
