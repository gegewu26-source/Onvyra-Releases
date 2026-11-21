package com.constx.onvyra.ui.media

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonArray
import com.constx.onvyra.data.media.MovieCatalogPayloadException
import com.constx.onvyra.data.media.MovieCatalogPayloadFailure
import com.constx.onvyra.data.media.MovieCatalogSource
import com.constx.onvyra.data.media.decodeMovieCatalogPayload
import com.constx.onvyra.data.media.parseMovieCatalogItems
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual Android SAX implementation; no network, provider/plugin or permission changes. */
@RunWith(AndroidJUnit4::class)
class MovieCatalogPayloadAndroidTest {
    @Before fun explicitLabOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allow_catalog_payload_lab") == "true")
        assertTrue("Run XML platform checks only on the dedicated lab emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test fun nativeAndroidParserAcceptsXmlCdataAndKeepsRealPlaybackMetadata() {
        val dollar = '$'
        val xml = "<rss><list><video><id>1</id><name><![CDATA[让子弹飞🙂]]></name><year>2010</year><dl><dd flag=\"hls\"><![CDATA[正片${dollar}https://cdn.example.org/a.m3u8?part=a${dollar}b&token=x%2By]]></dd></dl></video></list></rss>"
        val root = decodeMovieCatalogPayload(xml)
        val item = parseMovieCatalogItems(root.toString(), MovieCatalogSource("fixture", "Fixture", "https://example.org/api")).single()
        assertEquals("让子弹飞🙂", item.title)
        assertEquals("hls", item.lines.single().name)
        assertTrue(item.lines.single().episodes.single().url.endsWith("?part=a${dollar}b&token=x%2By"))
    }

    @Test fun nativeAndroidParserAcceptsRealEmptyCatalogButRejectsMissingList() {
        assertEquals(0, (decodeMovieCatalogPayload("<rss><list recordcount=\"0\"/></rss>")["list"] as JsonArray).size)
        val error = assertThrows(MovieCatalogPayloadException::class.java) { decodeMovieCatalogPayload("<rss><msg>Unknown feed</msg></rss>") }
        assertEquals(MovieCatalogPayloadFailure.INVALID_FORMAT, error.reason)
    }

    @Test fun nativeAndroidParserRejectsDtdAndExternalEntitiesBeforeAnyResolution() {
        val error = assertThrows(MovieCatalogPayloadException::class.java) {
            decodeMovieCatalogPayload("<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"https://example.invalid/must-not-request\">]><rss><list><video><id>1</id><name>&xxe;</name></video></list></rss>")
        }
        assertEquals(MovieCatalogPayloadFailure.INVALID_FORMAT, error.reason)
    }
}
