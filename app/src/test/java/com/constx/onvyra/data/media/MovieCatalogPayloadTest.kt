package com.constx.onvyra.data.media

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MovieCatalogPayloadTest {
    private val source = MovieCatalogSource("xml_fixture", "XML Fixture", "https://example.org/api.php/provide/vod/at/xml/")
    private val dollar = '$'

    @Test fun aNonEmptyUnrecognizedJsonListIsNotARealEmptyCatalog() {
        val failure = assertThrows(MovieCatalogPayloadException::class.java) {
            decodeMovieCatalogPayload("""{"list":[{}]}""")
        }
        assertEquals(MovieCatalogPayloadFailure.INVALID_FORMAT, failure.reason)
        val validMixed = decodeMovieCatalogPayload("""{"list":[{}, {"id":"1","name":"影片"}]}""")
        assertEquals(2, (validMixed["list"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun realEmptyJsonListsRemainValidIncludingZeroBasedSuccessProviders() {
        listOf("""{"list":[]}""", """{"code":1,"msg":"数据列表","list":[]}""",
            """{"code":200,"list":[]}""", """{"code":0,"msg":"success","list":[]}""").forEach {
            assertEquals(0, (decodeMovieCatalogPayload(it)["list"] as JsonArray).size)
        }
    }

    @Test fun suggestJsonKeepsItsRealIdentifierAndUnicodeName() {
        val root = decodeMovieCatalogPayload("\uFEFF  " + """{"list":[{"id":8136,"name":"让子弹飞🙂"}]}""")
        val item = (root["list"] as JsonArray).single() as JsonObject
        assertEquals("8136", item["id"]!!.jsonPrimitive.content)
        assertEquals("让子弹飞🙂", item["name"]!!.jsonPrimitive.content)
    }

    @Test fun explicitProviderErrorsAreNeverNoResultsAndDoNotExposeTheirMessage() {
        listOf("""{"code":0,"msg":"remote-secret: 参数错误","list":[]}""",
            """{"code":403,"msg":"remote-secret"}""", """{"success":false,"list":[]}""",
            """{"error":{"message":"remote-secret"},"list":[]}""",
            """{"status":"error","list":[]}""").forEach { body ->
            val error = assertThrows(MovieCatalogPayloadException::class.java) { decodeMovieCatalogPayload(body) }
            assertEquals(MovieCatalogPayloadFailure.API_ERROR, error.reason)
            assertFalse(error.message.orEmpty().contains("remote-secret"))
        }
    }

    @Test fun missingOrMalformedListCannotMasqueradeAsEmptyCatalog() {
        listOf("""{"code":1,"msg":"ok"}""", """{"list":null}""", """{"list":{}}""",
            """{"list":["wrong schema"]}""", "[]", "{broken", "<html><body>Verify you are human</body></html>").forEach(::assertInvalid)
    }

    @Test fun xmlSearchMetadataRequestsRealDetailsInsteadOfInventingAPlaybackLine() {
        val root = decodeMovieCatalogPayload("""
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="5.1"><list page="1" pagecount="2" pagesize="20" recordcount="25"><video>
            <id>8136</id><name><![CDATA[让子弹飞 & 拾荒法师🙂]]></name><type id="1">电影</type><year>2010</year>
            <note><![CDATA[高清中字]]></note></video></list></rss>
        """.trimIndent())
        val item = parseMovieCatalogItems(root.toString(), source).single()
        assertEquals("8136", item.id)
        assertEquals("让子弹飞 & 拾荒法师🙂", item.title)
        assertEquals(2010, item.year)
        assertEquals("电影", item.category)
        assertEquals("高清中字", item.remarks)
        assertTrue(item.lines.isEmpty())
        assertTrue(item.detailUrl!!.contains("ids=8136"))
        assertEquals("25", root["total"]!!.jsonPrimitive.content)
    }

    @Test fun xmlDetailsPreserveSeparateFlagsEpisodeNamesAndSignedDollarUrls() {
        val root = decodeMovieCatalogPayload("""
            <rss><list><video><id>8136</id><name>让子弹飞</name><year>2010</year><time>127分钟</time><dl>
            <dd flag="web"><![CDATA[正片${dollar}https://example.org/page?a=1&b=2]]></dd>
            <dd flag="hls"><![CDATA[第1集${dollar}https://cdn.example.org/1.m3u8?token=x%2By&part=a${dollar}b#第2集${dollar}https://cdn.example.org/2.m3u8]]></dd>
            </dl></video></list></rss>
        """.trimIndent())
        val item = parseMovieCatalogItems(root.toString(), source).single()
        assertEquals(2, item.lines.size)
        assertEquals(listOf("web", "hls"), item.lines.map { it.name })
        assertEquals(listOf("第1集", "第2集"), item.lines[1].episodes.map { it.name })
        assertTrue(item.lines[1].episodes.first().url.contains("token=x%2By&part=a${dollar}b"))
        assertEquals(listOf(1, 2), item.lines[1].episodes.map { it.index })
        assertEquals(7620.0, item.durationSeconds!!, .001)
    }

    @Test fun standardXmlEntitiesAreDecodedWithoutLoadingExternalContent() {
        val root = decodeMovieCatalogPayload("<rss><list><video><id>1</id><name>A &amp; B &#x1F642;</name><dl><dd flag=\"hls\">正片${dollar}https://cdn.example.org/a.m3u8?a=1&amp;b=2</dd></dl></video></list></rss>")
        val item = parseMovieCatalogItems(root.toString(), source).single()
        assertEquals("A & B 🙂", item.title)
        assertTrue(item.lines.single().episodes.single().url.endsWith("?a=1&b=2"))
    }

    @Test fun realEmptyXmlListIsDistinctFromErrorXmlOrUnknownSchema() {
        assertEquals(0, (decodeMovieCatalogPayload("<rss><list page=\"1\" recordcount=\"0\"/></rss>")["list"] as JsonArray).size)
        assertEquals(0, (decodeMovieCatalogPayload("<list/>")["list"] as JsonArray).size)
        assertInvalid("<rss><channel><title>Other feed</title></channel></rss>")
        assertInvalid("<rss><list><item>Unsupported item schema</item></list></rss>")
        assertInvalid("<rss><list><video><name>missing id</name></video></list></rss>")
        val error = assertThrows(MovieCatalogPayloadException::class.java) {
            decodeMovieCatalogPayload("<rss><code>0</code><msg>接口错误</msg></rss>")
        }
        assertEquals(MovieCatalogPayloadFailure.API_ERROR, error.reason)
    }

    @Test fun videoElementsOutsideTheMovieListDoNotCreateCatalogResults() {
        val root = decodeMovieCatalogPayload("<rss><list/><class><video><id>1</id><name>Not a catalog entry</name></video></class></rss>")
        assertEquals(0, (root["list"] as JsonArray).size)
    }

    @Test fun doctypeEntitiesAndMalformedXmlFailWithoutReadingExternalIdentifiers() {
        listOf(
            "<!DOCTYPE rss SYSTEM \"file:///must-not-be-read\"><rss><list/></rss>",
            "<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"https://example.invalid/must-not-request\">]><rss><list><video><id>1</id><name>&xxe;</name></video></list></rss>",
            "<!DOCTYPE rss [<!ENTITY a \"amplify\">]><rss><list/></rss>",
            "<rss><list></rss>", "<rss><list/><list/></rss>",
        ).forEach(::assertInvalid)
    }

    @Test fun deepAndOversizedPayloadsCannotCrashTheParserOrBecomeSuccessfulEmptyResults() {
        assertInvalid("<rss><list>" + "<x>".repeat(35) + "</x>".repeat(35) + "</list></rss>")
        assertInvalid("{\"list\":[],\"nested\":" + "[".repeat(35) + "0" + "]".repeat(35) + "}")
        assertInvalid("{\"list\":[],\"padding\":\"" + "中".repeat(400_000) + "\"}")
        assertInvalid("<rss><list>" + (1..513).joinToString("") { "<video><id>$it</id><name>Film $it</name></video>" } + "</list></rss>")
    }

    @Test fun jsonBudgetIgnoresBracketsInsideEscapedStrings() {
        val root = decodeMovieCatalogPayload("""{"list":[{"id":1,"name":"[{{ not nesting \\\" []"}]}""")
        assertEquals(1, (root["list"] as JsonArray).size)
    }

    private fun assertInvalid(body: String) {
        val error = assertThrows(MovieCatalogPayloadException::class.java) { decodeMovieCatalogPayload(body) }
        assertEquals(MovieCatalogPayloadFailure.INVALID_FORMAT, error.reason)
    }
}
