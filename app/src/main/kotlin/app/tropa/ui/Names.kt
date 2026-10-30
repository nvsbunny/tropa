package app.tropa.ui

/**
 * Имена серверов от провайдеров полны эмодзи, молний и капса. Здесь мы достаём
 * из имени страну (для флага) и показываем чистое название.
 */
object Names {

    fun clean(name: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val keep = Character.isLetterOrDigit(cp) || Character.isSpaceChar(cp) ||
                cp in ".,-+()/:|&_'«»".map { it.code }
            if (keep) sb.appendCodePoint(cp) else sb.append(' ')
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim(' ', '-', '|', ',', ':').ifBlank { name }
    }

    /**
     * Название подписки: значок провайдера в начале («🐾 Котики VPN») оставляем —
     * по нему подписку узнают, как в INCY. Остальное чистим как обычно.
     */
    fun subTitle(title: String): String {
        val t = title.trim()
        val firstWord = t.indexOfFirst { Character.isLetterOrDigit(it) }.let { if (it < 0) t.length else it }
        val lead = t.substring(0, firstWord).filter { !Character.isWhitespace(it) }
        val icon = lead.takeIf { l -> l.isNotEmpty() && l.codePoints().anyMatch { it >= 0x2190 } }
        val rest = clean(t)
        return if (icon != null && rest != t) "$icon $rest" else rest
    }

    /** Флаг-эмодзи по ISO-коду страны: «FI» → 🇫🇮. */
    fun flag(code: String?): String {
        if (code == null || code.length != 2) return ""
        return code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /**
     * Для чего сервер. Провайдеры подписывают входы для обхода белых списков
     * («LTE», «Все операторы», «Кроме МТС») и серверы с российским выходом
     * («Москва»). Ни те, ни другие на Wi-Fi не нужны. Мосты через Россию
     * («🇷🇺 Мост -> 🇸🇪») работают и на Wi-Fi — это обычные серверы, страна у них по выходу.
     * Служебные — торрент-серверы, игровые и групповые записи провайдера
     * («Авто-выбор», «Лучший сервер»): в автоматику не берём, вручную выбрать можно.
     */
    enum class Kind { NORMAL, MOBILE, RU_EXIT, SPECIAL }

    // Слово целиком без \b: в регулярках Android \b не понимает кириллицу, а флаг (?U) не поддерживается.
    private const val NB = "(?<![\\p{L}\\p{N}])"
    private const val NA = "(?![\\p{L}\\p{N}])"

    private val mobileWords = Regex(
        "$NB(lte|4g|5g|бс)$NA|оператор|мобильн|белы[еймх] спис|whitelist|т-мобайл|${NB}мтс$NA|билайн|мегафон|теле2|tele2|yota|йота",
        RegexOption.IGNORE_CASE,
    )
    private val specialWords = Regex("torrent|торрент|игров|gaming|${NB}game|авто-?выбор|лучший|best server", RegexOption.IGNORE_CASE)

    fun kind(name: String): Kind = when {
        specialWords.containsMatchIn(name) -> Kind.SPECIAL
        mobileWords.containsMatchIn(name) -> Kind.MOBILE
        country(name) == "RU" -> Kind.RU_EXIT
        else -> Kind.NORMAL
    }

    /** Операторы, которые провайдеры упоминают в названиях LTE-серверов, и как их зовёт Android. */
    private val operators = mapOf(
        "мтс" to Regex("mts|мтс", RegexOption.IGNORE_CASE),
        "билайн" to Regex("beeline|билайн|vimpelcom", RegexOption.IGNORE_CASE),
        "мегафон" to Regex("megafon|мегафон", RegexOption.IGNORE_CASE),
        "т-мобайл" to Regex("t-mobile|т-мобайл|tinkoff|t-bank|т-банк", RegexOption.IGNORE_CASE),
        "теле2" to Regex("tele2|теле2|\bt2\b|\bт2\b", RegexOption.IGNORE_CASE),
        "йота" to Regex("yota|йота", RegexOption.IGNORE_CASE),
    )

    /**
     * Подходит ли LTE-сервер оператору. «Кроме Т-Мобайл, МТС» — не для этих операторов,
     * «Только МТС» или «МТС» без «кроме» — только для МТС. Без упоминаний — для всех.
     */
    fun fitsOperator(name: String, operator: String): Boolean {
        if (operator.isBlank()) return true
        val lower = name.lowercase()
        val mentioned = operators.filterKeys { it in lower }
        if (mentioned.isEmpty()) return true
        val ours = mentioned.values.any { it.containsMatchIn(operator) }
        return if ("кроме" in lower || "except" in lower) !ours else ours
    }

    /**
     * Остаток LTE-трафика из сообщения провайдера: «Осталось LTE: 42.5 GB / 60.00 GB».
     * У обычного интернета безлимит, а обходы белых списков провайдеры считают отдельно.
     * Возвращает остаток и лимит в ГБ.
     */
    fun lteQuota(text: String): Pair<Double, Double>? {
        val m = Regex(
            "(?i)(?:lte|4g|белы\\S* спис\\S*|обход\\S*)[^0-9]{0,30}([0-9]+(?:[.,][0-9]+)?)\\s*(gb|гб|mb|мб)\\s*/\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(gb|гб|mb|мб)"
        ).find(text.lowercase()) ?: return null
        fun gb(v: String, unit: String) = v.replace(',', '.').toDouble() / if (unit.lowercase() in setOf("mb", "мб")) 1024.0 else 1.0
        val (left, u1, total, u2) = m.destructured
        return gb(left, u1) to gb(total, u2)
    }

    /** «VLESS, Reality, TCP» — для режима «Для опытных». */
    fun protocol(n: app.tropa.data.Node): String {
        val p = n.proxy
        val parts = mutableListOf(
            when (n.type) {
                "ss" -> "Shadowsocks"
                "hysteria2" -> "Hysteria2"
                "socks5" -> "SOCKS5"
                else -> n.type.uppercase()
            }
        )
        if (p["reality-opts"] != null) parts += "Reality" else if (p["tls"] == true) parts += "TLS"
        (p["network"] as? String)?.takeIf { it != "tcp" }?.let { parts += it.uppercase() }
        (p["flow"] as? String)?.takeIf { it.isNotBlank() }?.let { parts += "Vision" }
        return parts.joinToString(", ")
    }

    /** ISO-код страны из флага-эмодзи или из названия страны или города. */
    fun country(name: String): String? {
        flagEmoji(name)?.let { return it }
        val lower = name.lowercase()
        return words.entries.firstOrNull { (word, _) -> Regex("(^|[^\\p{L}])$word").containsMatchIn(lower) }?.value
    }

    /**
     * Последний флаг в имени: в «🇷🇺 Мост -> 🇸🇪 Residental» вход в России,
     * а выход — в Швеции, и сайты видят именно выход.
     */
    private fun flagEmoji(s: String): String? {
        val cps = s.codePoints().toArray()
        var found: String? = null
        var i = 0
        while (i < cps.size - 1) {
            val a = cps[i] - 0x1F1E6
            val b = cps[i + 1] - 0x1F1E6
            if (a in 0..25 && b in 0..25) {
                found = "${'A' + a}${'A' + b}"
                i += 2
            } else {
                i++
            }
        }
        return found
    }

    private val words: Map<String, String> = linkedMapOf(
        "финлянд" to "FI", "хельсинк" to "FI", "finland" to "FI", "helsinki" to "FI",
        "швеци" to "SE", "стокгольм" to "SE", "sweden" to "SE", "stockholm" to "SE",
        "нидерланд" to "NL", "голланд" to "NL", "амстердам" to "NL", "netherlands" to "NL", "amsterdam" to "NL",
        "германи" to "DE", "франкфурт" to "DE", "берлин" to "DE", "germany" to "DE", "frankfurt" to "DE",
        "латви" to "LV", "рига" to "LV", "latvia" to "LV", "riga" to "LV",
        "литв" to "LT", "вильнюс" to "LT", "lithuania" to "LT", "vilnius" to "LT",
        "эстони" to "EE", "таллин" to "EE", "estonia" to "EE", "tallinn" to "EE",
        "польш" to "PL", "варшав" to "PL", "poland" to "PL", "warsaw" to "PL",
        "франци" to "FR", "париж" to "FR", "france" to "FR", "paris" to "FR",
        "великобритан" to "GB", "англи" to "GB", "лондон" to "GB", "united kingdom" to "GB", "london" to "GB",
        "сша" to "US", "америк" to "US", "нью-йорк" to "US", "usa" to "US", "united states" to "US",
        "швейцари" to "CH", "цюрих" to "CH", "switzerland" to "CH",
        "австри" to "AT", "вена" to "AT", "austria" to "AT",
        "чехи" to "CZ", "прага" to "CZ", "czech" to "CZ",
        "норвеги" to "NO", "осло" to "NO", "norway" to "NO",
        "дани" to "DK", "копенгаген" to "DK", "denmark" to "DK",
        "итали" to "IT", "милан" to "IT", "italy" to "IT",
        "испани" to "ES", "мадрид" to "ES", "spain" to "ES",
        "турци" to "TR", "стамбул" to "TR", "turkey" to "TR", "istanbul" to "TR",
        "казахстан" to "KZ", "алматы" to "KZ", "kazakhstan" to "KZ",
        "россия" to "RU", "москв" to "RU", "russia" to "RU", "moscow" to "RU",
        "япони" to "JP", "токио" to "JP", "japan" to "JP",
        "сингапур" to "SG", "singapore" to "SG",
        "гонконг" to "HK", "hong kong" to "HK",
        "оаэ" to "AE", "дубай" to "AE", "uae" to "AE", "dubai" to "AE",
        "филиппин" to "PH", "philippines" to "PH",
        "канад" to "CA", "canada" to "CA",
        "бельги" to "BE", "belgium" to "BE",
        "армени" to "AM", "ереван" to "AM", "armenia" to "AM",
        "груз" to "GE", "тбилиси" to "GE", "georgia" to "GE",
        "сербия" to "RS", "белград" to "RS", "serbia" to "RS",
        "молдов" to "MD", "кишин" to "MD", "moldova" to "MD",
        "румыни" to "RO", "romania" to "RO",
        "венгри" to "HU", "будапешт" to "HU", "hungary" to "HU",
        "болгари" to "BG", "bulgaria" to "BG",
        "израил" to "IL", "israel" to "IL",
        "индия" to "IN", "india" to "IN",
    )
}
