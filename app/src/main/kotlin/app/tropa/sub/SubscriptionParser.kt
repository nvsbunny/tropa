package app.tropa.sub

import app.tropa.data.Node
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.security.MessageDigest

/** Превращает тело подписки (Clash YAML, base64 или список ссылок) в серверы. */
object SubscriptionParser {

    data class Result(
        val nodes: List<Node>,
        val skipped: Int,
        val hint: String = "",
        /** Главная группа провайдера: как он сам выбирает сервер. */
        val groupType: String = "",
        val groupMembers: List<String> = emptyList(),
        /** Маршруты провайдера из Xray JSON, в формате профиля Happ/INCY. */
        val routing: String = "",
        /** Записи-заглушки провайдера (адрес 0.0.0.0, порт 1): это сообщения, а не серверы. */
        val notes: String = "",
    )

    fun parse(body: String, subId: String): Result {
        val text = body.trim().removePrefix("﻿")
        if (text.isEmpty()) return Result(emptyList(), 0, "Подписка пустая")

        if (looksLikeYaml(text)) {
            val root = runCatching { yamlRoot(text) }.getOrNull()
            val proxies = root?.let { yamlProxies(it) }
            if (!proxies.isNullOrEmpty()) {
                val nodes = toNodes(proxies, subId)
                val (type, members) = mainGroup(root, nodes)
                return Result(nodes, 0, groupType = type, groupMembers = members, notes = stubNotes(proxies))
            }
        }

        // Xray JSON: как есть или в base64. Так подписки отдают Happ.
        val json = text.takeIf { it.startsWith("[") || it.startsWith("{") }
            ?: LinkParser.decodeBase64(text)?.trim()?.takeIf { it.startsWith("[") || it.startsWith("{") }
        if (json != null) {
            val parsed = runCatching { XrayJson.parse(json) }.getOrNull()
            if (parsed != null && parsed.proxies.isNotEmpty()) {
                val nodes = toNodes(parsed.proxies, subId)
                // Группа провайдера охватывает все его серверы; LTE и прочее отсеет выбор под сеть.
                return Result(
                    nodes, 0, routing = parsed.routing, notes = stubNotes(parsed.proxies),
                    groupType = parsed.groupType, groupMembers = if (parsed.groupType.isEmpty()) emptyList() else nodes.map { it.id },
                )
            }
        }

        val links = extractLinks(text) ?: extractLinks(LinkParser.decodeBase64(text) ?: "")
        if (links != null) {
            val parsed = links.map { LinkParser.parse(it) }
            val ok = parsed.filterNotNull()
            return Result(toNodes(ok, subId), parsed.count { it == null }, notes = stubNotes(ok))
        }

        val hint = when {
            text.startsWith("happ://crypt") -> "Провайдер зашифровал подписку только для Happ"
            json != null -> "Пришла JSON-конфигурация без поддерживаемых серверов"
            text.startsWith("<") -> "Вместо подписки пришла веб-страница. Проверьте ссылку"
            else -> "Не удалось разобрать подписку"
        }
        return Result(emptyList(), 0, hint)
    }

    /** Ссылки, вставленные вручную: каждая строка — отдельный сервер. */
    fun parseLinks(text: String, subId: String): Result {
        val links = extractLinks(text) ?: extractLinks(LinkParser.decodeBase64(text) ?: "") ?: emptyList()
        val parsed = links.map { LinkParser.parse(it) }
        return Result(toNodes(parsed.filterNotNull(), subId), parsed.count { it == null })
    }

    private fun looksLikeYaml(t: String) = Regex("(?m)^proxies:\\s*$").containsMatchIn(t)

    private fun isLink(s: String) = LinkParser.schemes.any { s.startsWith("$it://", ignoreCase = true) }

    /**
     * В подписке ссылки идут по одной на строку, и в имени после # бывают пробелы.
     * Если же ссылки вставили одной строкой через пробел, режем по пробелам.
     */
    private fun extractLinks(t: String): List<String>? {
        val lines = t.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        val byLine = lines.filter(::isLink)
        if (byLine.size > 1 || (byLine.size == 1 && lines.size == 1 && byLine[0].count { it == ' ' } == 0)) return byLine
        val byToken = t.split(Regex("\\s+")).filter(::isLink)
        return when {
            byToken.size > byLine.size -> byToken
            byLine.isNotEmpty() -> byLine
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun yamlRoot(text: String): Map<String, Any?>? {
        val opts = LoaderOptions().apply { codePointLimit = 64 * 1024 * 1024 }
        return Yaml(opts).load<Any?>(text) as? Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun yamlProxies(root: Map<String, Any?>): List<Map<String, Any?>> {
        val list = root["proxies"] as? List<Any?> ?: return emptyList()
        return list.mapNotNull { it as? Map<String, Any?> }
            .filter { it["type"] != null && it["server"] != null && it["name"] != null }
    }

    /**
     * Главная группа провайдера — первая в proxy-groups. Если это «select», его выбор
     * по умолчанию — первый пункт: часто это вложенная группа вроде «Лучший сервер»
     * с проверкой задержки, тогда берём её.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mainGroup(root: Map<String, Any?>, nodes: List<Node>): Pair<String, List<String>> {
        val groups = (root["proxy-groups"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
        if (groups.isEmpty()) return "" to emptyList()
        val byName = groups.associateBy { it["name"]?.toString().orEmpty() }
        val idByName = nodes.associate { it.name to it.id }
        fun members(g: Map<String, Any?>) = (g["proxies"] as? List<Any?>).orEmpty().map { it.toString() }
        fun flatten(name: String, depth: Int = 0): List<String> = when {
            name in idByName -> listOf(idByName.getValue(name))
            depth < 4 && name in byName -> members(byName.getValue(name)).flatMap { flatten(it, depth + 1) }
            else -> emptyList()
        }
        var g = groups.first()
        repeat(4) {
            if (g["type"] != "select") return@repeat
            val first = members(g).firstOrNull { it in byName || it in idByName } ?: return@repeat
            g = byName[first] ?: return "select" to (listOf(idByName.getValue(first)) + flatten(g["name"].toString())).distinct()
        }
        val type = g["type"]?.toString().orEmpty()
        val ids = flatten(g["name"].toString()).distinct()
        return if (ids.isEmpty()) "" to emptyList() else type to ids
    }

    /**
     * id сервера стабилен между обновлениями: он считается из типа и имени.
     * Адрес в id не входит — некоторые провайдеры при каждом скачивании выдают случайный
     * адрес, SNI и shortId из пула, и иначе выбранный сервер терялся бы каждый час.
     */
    private fun toNodes(proxies: List<Map<String, Any?>>, subId: String): List<Node> {
        val seen = HashSet<String>()
        return proxies.mapNotNull { p ->
            val name = p["name"]?.toString().orEmpty()
            val server = p["server"]?.toString().orEmpty()
            val port = p["port"]?.toString()?.toIntOrNull() ?: 0
            val type = p["type"]?.toString().orEmpty()
            if (server.isEmpty() || type.isEmpty() || isStub(server, port)) return@mapNotNull null
            var id = hash("$subId|$type|$name")
            while (!seen.add(id)) id = hash(id)
            Node(id = id, name = name, type = type, server = server, port = port, proxy = p)
        }
    }

    fun isStub(server: String, port: Int) =
        port <= 1 || server == "0.0.0.0" || server.startsWith("127.") || server == "localhost"

    /** Имена заглушек: провайдеры пишут в них сообщения («Продлите подписку» и т. п.). */
    private fun stubNotes(proxies: List<Map<String, Any?>>): String =
        proxies.filter { p ->
            isStub(p["server"]?.toString().orEmpty(), p["port"]?.toString()?.toIntOrNull() ?: 0)
        }.mapNotNull { it["name"]?.toString()?.takeIf(String::isNotBlank) }.joinToString("\n")

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
