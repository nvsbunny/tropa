package app.tropa

import app.tropa.core.GeoTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ListsTest {

    private val assets = File("src/main/assets/xray")

    /** Метки, которые Тропа читает из скачанного geo-файла, совпадают с выписанными при сборке. */
    @Test
    fun geoTagsMatchBuildTime() {
        for (name in listOf("geosite", "geoip")) {
            val parsed = GeoTags.read(File(assets, "$name.dat").readBytes())
            val built = File(assets, "$name-tags.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            assertEquals(built, parsed)
        }
        assertTrue("category-ru" in GeoTags.read(File(assets, "geosite.dat").readBytes()))
    }

    /** Битый файл не роняет разбор: просто нет меток, и Тропа оставит прежний. */
    @Test
    fun brokenGeoFileGivesNoTags() {
        assertTrue(GeoTags.read(ByteArray(0)).isEmpty())
        assertTrue(GeoTags.read("<html>404</html>".toByteArray()).none { it == "ru" })
    }
}
