package app.tropa.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Root-режим «для опытных»: без VPN, через TPROXY и iptables (как box4magisk и XKeen на роутерах).
 *
 * Xray запускается от root и слушает вход TPROXY. Правила в таблице mangle помечают исходящий трафик
 * приложений, маршрут по метке заворачивает его на lo, а TPROXY отдаёт ядру — с исходным адресом
 * назначения, без подмены. Не трогаем:
 *  - трафик самого ядра: у всех его исходящих стоит метка [MARK] (sockopt.mark), иначе была бы петля;
 *  - домашние и служебные сети, кроме DNS: запросы на порт 53 всегда уходят в ядро;
 *  - приложения «напрямую» и саму Тропу — по UID.
 * IPv6 ядро не принимает, поэтому IPv6 приложений закрыт (REJECT) — они сразу переходят на IPv4.
 * Вход ядра слушает 127.0.0.1, а не 0.0.0.0: на 0.0.0.0 Go открывает IPv6-сокет, и прозрачный режим
 * для IPv4-пакетов на нём не срабатывает — соединения молча пропадали (проверено на эмуляторе).
 * Ядро работает отдельно от приложения (nohup): если Android закроет Тропу, интернет не пропадёт.
 * Если ядро умерло — правила снимаем, иначе весь трафик уходил бы в пустоту.
 *
 * nftables на Android по умолчанию нет (только в некоторых сборках), поэтому правила — iptables.
 */
object RootProxy {

    const val PORT = 52345
    /**
     * Метка трафика самого ядра — старший бит. Младшие 16 бит fwmark Android занимает номером сети
     * и по ним выбирает маршрут: метка 255 значила бы «сеть №255», которой нет, и ядро не вышло бы в сеть.
     */
    const val MARK = 0x20000000
    /** Метка «отдать ядру»: её ставит OUTPUT на трафик приложений. */
    private const val ROUTE_MARK = 42
    /**
     * Метка, которую ставит TPROXY. Отдельная, потому что Android (tcp_fwmark_accept) переносит метку
     * входящего SYN на принятый сокет: с общей меткой ответ ядра приложению снова уходил бы в ядро.
     */
    private const val TPROXY_MARK = 43
    private const val TABLE = 2042

    private val privateNets = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
    )

    /** Есть ли root и дал ли его пользователь Тропе (Magisk при первом вызове спросит сам). */
    fun hasRoot(): Boolean = runCatching { su("id", 30).contains("uid=0") }.getOrDefault(false)

    /** Выполнить команду через su; вывод — вместе с ошибками. */
    fun su(cmd: String, timeoutSec: Long = 20): String {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            error("su не ответил за $timeoutSec с")
        }
        reader.join(1000)
        return out.toString()
    }

    fun dir(workDir: File) = File(workDir, "root").apply { mkdirs() }

    /** Запустить ядро и поставить правила. [bypassUids] — Тропа и приложения «напрямую». */
    fun start(workDir: File, binary: String, bypassUids: Collection<Int>) {
        val d = dir(workDir)
        File(d, "start.sh").writeText(startScript(workDir, d, binary, bypassUids))
        File(d, "stop.sh").writeText(stopScript(d))
        val out = su("sh \"${File(d, "start.sh").absolutePath}\"", 40)
        if (!out.lines().any { it.trim() == "OK" }) error(out.lines().lastOrNull { it.isNotBlank() } ?: "правила не встали")
    }

    /** Остановить ядро и снять все правила. Можно вызывать сколько угодно раз. */
    fun stop(workDir: File) {
        val stop = File(dir(workDir), "stop.sh")
        if (!stop.exists()) stop.writeText(stopScript(dir(workDir)))
        runCatching { su("sh \"${stop.absolutePath}\"") }
    }

    fun startScript(workDir: File, d: File, binary: String, bypassUids: Collection<Int>): String {
        val w = workDir.absolutePath
        val pid = File(d, "xray.pid").absolutePath
        val hexPort = "%04X".format(PORT)
        val ipt = "iptables -w 5"
        val ip6 = "ip6tables -w 5"
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Тропа, root-режим: ядро Xray получает трафик через TPROXY. Создано приложением.")
            appendLine("sh \"${File(d, "stop.sh").absolutePath}\" >/dev/null 2>&1")
            appendLine("cd \"$w\"")
            appendLine("XRAY_LOCATION_ASSET=\"$w\" nohup \"$binary\" run -c \"$w/config.json\" >> \"$w/core.log\" 2>&1 &")
            appendLine("echo \$! > \"$pid\"")
            appendLine("i=0")
            appendLine("while [ \$i -lt 50 ]; do grep -qi \":$hexPort \" /proc/net/tcp /proc/net/tcp6 2>/dev/null && break; sleep 0.2; i=\$((i+1)); done")
            appendLine("kill -0 \$(cat \"$pid\") 2>/dev/null || { echo \"ядро не запустилось\"; exit 1; }")
            appendLine("ip rule add fwmark $ROUTE_MARK lookup $TABLE pref 9000 2>/dev/null")
            appendLine("ip rule add fwmark $TPROXY_MARK lookup $TABLE pref 9001 2>/dev/null")
            appendLine("ip route add local 0.0.0.0/0 dev lo table $TABLE 2>/dev/null")
            // Ядру — только то, что пометили мы (приложения этого телефона).
            appendLine("$ipt -t mangle -N TROPA_PRE")
            for (p in listOf("tcp", "udp")) {
                appendLine("$ipt -t mangle -A TROPA_PRE -p $p -m mark --mark $ROUTE_MARK -j TPROXY --on-ip 127.0.0.1 --on-port $PORT --tproxy-mark $TPROXY_MARK")
            }
            appendLine("$ipt -t mangle -A PREROUTING -j TROPA_PRE")
            appendLine("$ipt -t mangle -N TROPA_OUT")
            appendLine("$ipt -t mangle -A TROPA_OUT -m mark --mark $MARK/$MARK -j RETURN")
            appendLine("$ipt -t mangle -A TROPA_OUT -o lo -j RETURN")
            bypassUids.distinct().forEach { appendLine("$ipt -t mangle -A TROPA_OUT -m owner --uid-owner $it -j RETURN") }
            for (p in listOf("udp", "tcp")) appendLine("$ipt -t mangle -A TROPA_OUT -p $p --dport 53 -j MARK --set-mark $ROUTE_MARK")
            privateNets.forEach { appendLine("$ipt -t mangle -A TROPA_OUT -d $it -j RETURN") }
            for (p in listOf("tcp", "udp")) appendLine("$ipt -t mangle -A TROPA_OUT -p $p -j MARK --set-mark $ROUTE_MARK")
            appendLine("$ipt -t mangle -A OUTPUT -j TROPA_OUT")
            // Вход ядра не должен быть доступен из домашней сети.
            appendLine("$ipt -t filter -I INPUT ! -i lo -p tcp --dport $PORT -j DROP")
            appendLine("$ipt -t filter -I INPUT ! -i lo -p udp --dport $PORT -j DROP")
            // IPv6: закрываем для приложений (кроме обходящих), они переходят на IPv4.
            appendLine("$ip6 -t filter -N TROPA6")
            appendLine("$ip6 -t filter -A TROPA6 -o lo -j RETURN")
            appendLine("$ip6 -t filter -A TROPA6 -d fe80::/10 -j RETURN")
            appendLine("$ip6 -t filter -A TROPA6 -m mark --mark $MARK/$MARK -j RETURN")
            bypassUids.distinct().forEach { appendLine("$ip6 -t filter -A TROPA6 -m owner --uid-owner $it -j RETURN") }
            appendLine("$ip6 -t filter -A TROPA6 -j REJECT")
            appendLine("$ip6 -t filter -A OUTPUT -j TROPA6")
            appendLine("$ipt -t mangle -C OUTPUT -j TROPA_OUT && echo OK")
        }
    }

    fun stopScript(d: File): String {
        val pid = File(d, "xray.pid").absolutePath
        val ipt = "iptables -w 5"
        val ip6 = "ip6tables -w 5"
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Тропа, root-режим: снять правила и остановить ядро. Создано приложением.")
            appendLine("[ -f \"$pid\" ] && kill \$(cat \"$pid\") 2>/dev/null")
            appendLine("rm -f \"$pid\"")
            appendLine("while $ipt -t mangle -D OUTPUT -j TROPA_OUT 2>/dev/null; do :; done")
            appendLine("while $ipt -t mangle -D PREROUTING -j TROPA_PRE 2>/dev/null; do :; done")
            appendLine("$ipt -t mangle -F TROPA_OUT 2>/dev/null; $ipt -t mangle -X TROPA_OUT 2>/dev/null")
            appendLine("$ipt -t mangle -F TROPA_PRE 2>/dev/null; $ipt -t mangle -X TROPA_PRE 2>/dev/null")
            for (p in listOf("tcp", "udp")) {
                appendLine("while $ipt -t filter -D INPUT ! -i lo -p $p --dport $PORT -j DROP 2>/dev/null; do :; done")
            }
            appendLine("while $ip6 -t filter -D OUTPUT -j TROPA6 2>/dev/null; do :; done")
            appendLine("$ip6 -t filter -F TROPA6 2>/dev/null; $ip6 -t filter -X TROPA6 2>/dev/null")
            appendLine("while ip rule del fwmark $ROUTE_MARK lookup $TABLE 2>/dev/null; do :; done")
            appendLine("while ip rule del fwmark $TPROXY_MARK lookup $TABLE 2>/dev/null; do :; done")
            appendLine("ip route flush table $TABLE 2>/dev/null")
            appendLine("echo STOPPED")
        }
    }
}
