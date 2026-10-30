import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Ядро — официальный Xray (как у Happ и INCY): провайдеры делают серверы
 * под Xray, и часть Reality-серверов другим ядрам не открывается. Берём закреплённую
 * версию с релизов XTLS и сверяем SHA-256 из их файла .dgst.
 *
 * geosite.dat и geoip.dat — из того же релиза: на них ссылаются правила провайдеров.
 * Список меток из этих файлов выписываем в assets, чтобы приложение не подсунуло ядру
 * неизвестную метку (с ней Xray не запустится).
 * Список заблокированного в России и подсети мессенджеров — от itdoginfo/allow-domains.
 */
val xrayVersion = "v26.10.29"
val xrayAssets = mapOf(
    "arm64-v8a" to ("Xray-android-arm64-v8a.zip" to "1be0ea7dc4fd967b7dd784d9d8281aab19da3bfc5d78c2da7dcb7d690e25299f"),
    "x86_64" to ("Xray-android-amd64.zip" to "7fda59f39c06308c82c3c873590febcfd8acb2db29431eea1459d86408d44618"),
)
val itdog = "https://raw.githubusercontent.com/itdoginfo/allow-domains/main"
val rulesUrls = mapOf("blocked.list" to "$itdog/Russia/inside-raw.lst") +
    listOf("telegram", "meta", "twitter", "discord").associate { "ip-$it.list" to "$itdog/Subnets/IPv4/$it.lst" }

fun download(url: String, dest: File) {
    dest.parentFile.mkdirs()
    var target = url
    repeat(5) {
        val c = URI(target).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        val code = c.responseCode
        if (code in 300..399) {
            target = c.getHeaderField("Location")
            c.disconnect()
            return@repeat
        }
        if (code != 200) throw GradleException("Загрузка $url: HTTP $code")
        c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        c.disconnect()
        return
    }
    throw GradleException("Слишком много перенаправлений: $url")
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

/** Метки из geosite.dat / geoip.dat: в protobuf это поле 1 каждой записи списка. */
fun geoTags(file: File): List<String> {
    val b = file.readBytes()
    var i = 0
    fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            val x = b[i++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return r
            shift += 7
        }
    }
    val tags = mutableListOf<String>()
    while (i < b.size) {
        val key = varint().toInt()
        val len = varint().toInt()
        val end = i + len
        if (key == (1 shl 3 or 2)) {
            // Внутри записи: поле 1 — строка с меткой.
            if (varint().toInt() == (1 shl 3 or 2)) {
                val n = varint().toInt()
                tags += String(b, i, n).lowercase()
            }
        }
        i = end
    }
    return tags
}

val fetchCore by tasks.registering {
    group = "build"
    description = "Скачивает ядро Xray, его geo-файлы и списки заблокированных сайтов"
    val jniDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
    // Ядро mihomo — только в сборке «mihomo» (её исходники лежат в src/mihomo).
    val mihomoJniDir = layout.projectDirectory.dir("src/mihomo/jniLibs").asFile
    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val cacheDir = rootProject.layout.projectDirectory.dir(".core-cache").asFile
    outputs.dir(jniDir)
    outputs.dir(mihomoJniDir)
    outputs.dir(assetsDir)
    doLast {
        xrayAssets.forEach { (abi, pair) ->
            val (name, expected) = pair
            val zip = File(cacheDir, "$xrayVersion-$name")
            if (!zip.exists() || sha256(zip) != expected) {
                logger.lifecycle("Tropa: качаем Xray $xrayVersion для $abi")
                download("https://github.com/XTLS/Xray-core/releases/download/$xrayVersion/$name", zip)
            }
            val actual = sha256(zip)
            if (actual != expected) {
                zip.delete()
                throw GradleException("Контрольная сумма Xray не совпала для $abi: $actual")
            }
            val target = File(jniDir, "$abi/libxray.so")
            ZipFile(zip).use { z ->
                // Сверяем по CRC из архива, а не по размеру: у соседних версий Xray он может совпасть.
                val entry = z.getEntry("xray")
                val same = target.exists() && target.length() == entry.size &&
                    CRC32().apply { update(target.readBytes()) }.value == entry.crc
                if (!same) {
                    target.parentFile.mkdirs()
                    z.getInputStream(z.getEntry("xray")).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
                // geo-файлы одинаковые во всех сборках: берём из arm64.
                if (abi == "arm64-v8a") {
                    listOf("geosite.dat", "geoip.dat").forEach { dat ->
                        val out = File(assetsDir, "xray/$dat")
                        out.parentFile.mkdirs()
                        z.getInputStream(z.getEntry(dat)).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
        }
        // Второе ядро mihomo — только для сборки «mihomo»: собирается tools/build-mihomo.sh.
        // Нет файлов — сборка «mihomo» выйдет без него (tropaRelease такую не выпустит).
        val sums = File(cacheDir, "SHA256SUMS").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { Regex("^([0-9a-f]{64})\\s+\\*?(\\S+)$").find(it.trim())?.destructured }
            ?.associate { (hash, file) -> file to hash }.orEmpty()
        mapOf("arm64-v8a" to "mihomo-android-arm64", "x86_64" to "mihomo-android-amd64").forEach { (abi, name) ->
            val src = File(cacheDir, name)
            File(jniDir, "$abi/libmihomo.so").delete() // раньше лежало в общей папке — в сборку «xray» не берём
            val target = File(mihomoJniDir, "$abi/libmihomo.so")
            val expected = sums[name]
            if (expected == null || !src.exists() || sha256(src) != expected) {
                target.delete()
                logger.lifecycle("Tropa: ядра mihomo для $abi нет — только Xray")
                return@forEach
            }
            target.parentFile.mkdirs()
            if (!target.exists() || target.length() != src.length()) src.copyTo(target, overwrite = true)
        }
        File(assetsDir, "xray/geosite-tags.txt").writeText(geoTags(File(assetsDir, "xray/geosite.dat")).joinToString("\n"))
        File(assetsDir, "xray/geoip-tags.txt").writeText(geoTags(File(assetsDir, "xray/geoip.dat")).joinToString("\n"))
        val rules = File(assetsDir, "rules").apply { mkdirs() }
        listOf("ru-domains.list", "ru-ip.mrs").forEach { File(rules, it).delete() }
        rulesUrls.forEach { (name, url) ->
            val file = File(rules, name)
            if (!file.exists() || file.length() == 0L) {
                logger.lifecycle("Tropa: качаем список $name")
                download(url, file)
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchCore) }

android {
    namespace = "app.tropa"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tropa"
        // Android 9+: с него работает смена ключа подписи (APK Signature Scheme v3).
        minSdk = 28
        targetSdk = 37
        versionCode = providers.gradleProperty("tropa.versionCode").get().toInt()
        versionName = providers.gradleProperty("tropa.versionName").get()
        // Откуда Тропа узнаёт о новых версиях. Для проверки можно подменить: -Ptropa.updateUrl=…
        buildConfigField("String", "UPDATE_URL", "\"" + providers.gradleProperty("tropa.updateUrl").get() + "\"")
    }

    // Две сборки из одного кода: «xray» — для всех, «mihomo» — плюс второе ядро «для опытных».
    // Пакет и ключ одни: одну можно поставить поверх другой, настройки сохранятся.
    flavorDimensions += "core"
    productFlavors {
        create("xray") {
            dimension = "core"
            isDefault = true
        }
        create("mihomo") {
            dimension = "core"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Без подписи: релиз подписывает задача tropaRelease своим ключом (см. ниже).
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Код в APK сжатым: так обновление качается на ~17 МБ меньше (иначе с Android 9 он лежит как есть).
    packaging {
        dex { useLegacyPackaging = true }
    }

    // geo-файлы читаем через openFd, поэтому в APK они лежат без сжатия.
    androidResources {
        noCompress += "dat"
    }

    packaging {
        // Ядро запускается как отдельный процесс, поэтому .so должен лежать распакованным.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.snakeyaml)
    implementation(libs.glance.appwidget)
    // QR-код на телевизоре (только рисование, без камеры).
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // В юнит-тестах org.json из Android — пустые заглушки, нужна настоящая реализация.
    testImplementation("org.json:json:20240303")
}

/*
 * Релиз: собираем без подписи и подписываем своим ключом из папки signing/ (в сборку не попадает,
 * её нужно хранить отдельно — без ключа обновить Тропу у пользователей будет нельзя).
 * lineage.bin — запись «отладочный ключ передал права ключу Тропы»: благодаря ей новая версия
 * ставится поверх старых сборок, подписанных отладочным ключом, без переустановки.
 */
tasks.register("tropaRelease") {
    group = "tropa"
    description = "Собирает релиз и подписывает его ключом Тропы"
    dependsOn("assembleRelease")
    val signing = rootProject.file("signing")
    val apkDir = layout.buildDirectory.dir("outputs/apk")
    val outDir = layout.buildDirectory.dir("outputs/tropa")
    val sdk = androidComponents.sdkComponents.sdkDirectory
    val version = android.defaultConfig.versionName
    doLast {
        val props = Properties().apply { File(signing, "keystore.properties").inputStream().use { load(it) } }
        val buildTools = File(sdk.get().asFile, "build-tools").listFiles()!!.filter { File(it, "lib/apksigner.jar").exists() }.maxBy { it.name }
        val javaBin = File(System.getProperty("java.home"), "bin/java").path
        val out = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        // Только свежие сборки двух вариантов: outputs/apk/<xray|mihomo>/release/app-<вариант>-<abi>-release-unsigned.apk.
        // Итог: Tropa-<версия>-<abi>.apk и Tropa-<версия>-<abi>-mihomo.apk.
        listOf("xray", "mihomo").flatMap { flavor ->
            File(apkDir.get().asFile, "$flavor/release").listFiles { f -> f.name.endsWith("-release-unsigned.apk") }.orEmpty().map { flavor to it }
        }.forEach { (flavor, apk) ->
            val abi = apk.name.removePrefix("app-$flavor-").removeSuffix("-release-unsigned.apk")
            if (flavor == "mihomo" && ZipFile(apk).use { z -> z.getEntry("lib/$abi/libmihomo.so") == null }) {
                throw GradleException("В сборке mihomo для $abi нет ядра: сначала tools/build-mihomo.sh")
            }
            val target = File(out, "Tropa-$version-$abi" + (if (flavor == "mihomo") "-mihomo" else "") + ".apk")
            fun run(vararg args: String) {
                val p = ProcessBuilder(javaBin, "--enable-native-access=ALL-UNNAMED", "-jar", File(buildTools, "lib/apksigner.jar").path, *args)
                    .redirectErrorStream(true).apply { environment()["TROPA_KS_PASS"] = props.getProperty("storePassword") }.start()
                val text = p.inputStream.bufferedReader().readText()
                if (p.waitFor() != 0) throw GradleException("apksigner: $text")
            }
            run(
                "sign", "--ks", File(signing, props.getProperty("storeFile")).path, "--ks-key-alias", props.getProperty("keyAlias"),
                "--ks-pass", "env:TROPA_KS_PASS", "--key-pass", "env:TROPA_KS_PASS",
                "--lineage", File(signing, "lineage.bin").path, "--rotation-min-sdk-version", "28",
                // Только схема v3: она есть с Android 9 и одна умеет смену ключа. Старые схемы
                // v1/v2 пришлось бы подписывать прежним (отладочным) ключом.
                "--v1-signing-enabled", "false", "--v2-signing-enabled", "false", "--v4-signing-enabled", "false",
                "--out", target.path, apk.path,
            )
            run("verify", target.path)
            println("Подписано: ${target.name}")
        }
    }
}
