package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LanzouNetDiskProviderTest {
    @Test fun modernObjectParametersUseLiveVariablesAndOverrideThePasswordPlaceholder() {
        val fields = LanzouProtocol.downFields("""
            <!-- data:{action:'downprocess', sign:'decoy'} -->
            var signedValue = 'real-sign'; var keyValue='real-key';
            $.ajax({url:'/ajaxm.php',data:{'action':'downprocess','sign':signedValue,'websignkey':keyValue,'p':'','kd':0}})
        """.trimIndent(), "ab12")
        assertEquals("real-sign", fields["sign"])
        assertEquals("real-key", fields["websignkey"])
        assertEquals("ab12", fields["p"])
        assertEquals("1", fields["kd"])
        assertEquals("https://wwx.lanzn.com/ajaxm.php", LanzouProtocol.ajaxUrl("$.ajax({url:'/ajaxm.php'})", "https://wwx.lanzn.com/iabc", false))
    }

    @Test fun challengeAndRelativeUrlsRetainTheirProtocolMeaning() {
        assertEquals("b85a6b51e58344f431d3acbe81e8d44fb59cfad5", LanzouProtocol.acw("3B1F2C4D5E6A7B8C9D0E1F2A3B4C5D6E7F8091A2"))
        assertEquals("3000176000856006061501533003690027800375", LanzouProtocol.acw("0".repeat(40)))
        assertEquals("https://wwx.lanzn.com/fn?x=1&y=2", LanzouProtocol.absolute("https://wwx.lanzn.com/iabc", "//wwx.lanzn.com/fn?x=1&amp;y=2"))
        assertEquals("https://wwx.lanzn.com/next", LanzouProtocol.absolute("https://wwx.lanzn.com/folder/page", "../next"))
        assertTrue(LanzouProtocol.isLanzouHost("wwx.lanzn.com"))
        assertFalse(LanzouProtocol.isLanzouHost("www.ilanzou.com"))
        assertFalse(LanzouProtocol.isLanzouHost("lanzou.com.evil.example"))
    }

    @Test fun folderMemberUsesItsSignedPageAndTheObservedIframeBeforeResolvingTheDownload() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixtureClient { request ->
            synchronized(requests) { requests.add(request) }
            val url = request.url
            when {
                url.encodedPath == "/broot" -> html("""<title>目录 - 蓝奏云</title><a class="folderlink" href="/bchild">子目录</a><script>let params={'lx':2,'fid':123,'t':1700000000,'k':'folder-key'};filemoreajax</script>""")
                url.encodedPath == "/filemoreajax.php" -> {
                    val fields = form(request)
                    assertEquals("123", fields["fid"]); assertEquals("1700000000", fields["t"])
                    if (fields["pg"] == "1") json("""{"zt":1,"text":[{"id":"imember?signature=keep","name_all":"测试.apk","size":"1.5 M"}]}""")
                    else json("""{"zt":2,"text":[]}""")
                }
                url.encodedPath == "/imember" -> {
                    assertEquals("keep", url.queryParameter("signature"))
                    html("""<title>测试.apk - 蓝奏云</title><iframe src="/fn?x=1"></iframe>""")
                }
                url.encodedPath == "/fn" -> html("""<script>var signature='live';var k='web-key';$.ajax({url:'https://apifile.woozooo.com/ajaxm.php',data:{action:'downprocess',sign:signature,websignkey:k}})</script>""")
                url.host == "apifile.woozooo.com" -> {
                    assertEquals("live", form(request)["sign"])
                    json("""{"zt":1,"dom":"https://download.lanzoug.com","url":"jump-id"}""")
                }
                url.encodedPath == "/file/jump-id" -> Fixture(302, "", mapOf("Location" to "https://cdn.lanzoug.com/test.apk?signature=download"))
                url.host == "cdn.lanzoug.com" -> Fixture(200, "", mapOf("Content-Type" to "application/octet-stream", "Content-Length" to "1572864"))
                else -> throw AssertionError("Unexpected fixture URL ${url.host}${url.encodedPath}")
            }
        }
        val provider = LanzouNetDiskProvider(NetDiskHttp(client))
        val session = provider.open(NetDiskShare(NetDiskPlatform.LANZOU, "https://wwx.lanzn.com/broot", "broot"), "private-account-cookie-must-not-leak")
        val listing = provider.list(session, "", null, "")
        assertEquals(2, listing.files.size)
        assertTrue(listing.files.first().isDirectory)
        val file = listing.files.last()
        assertEquals(1_572_864L, file.size)
        assertTrue(file.extra["share_url"]!!.jsonPrimitive.content.endsWith("imember?signature=keep"))
        val download = provider.resolve(session, file, "private-account-cookie-must-not-leak")
        assertEquals("https://cdn.lanzoug.com/test.apk?signature=download", download.url)
        assertEquals("测试.apk", download.fileName)
        assertTrue(download.headers["Cookie"]!!.contains("down_ip=1"))
        assertTrue(requests.none { it.header("Cookie").orEmpty().contains("private-account") })
        assertTrue(provider.list(session, "", listing.nextCursor, "").files.isEmpty())
    }

    @Test fun aSubfolderUsesItsOwnPageAndPagingParameters() = runBlocking {
        val provider = LanzouNetDiskProvider(NetDiskHttp(fixtureClient { request ->
            when (request.url.encodedPath) {
                "/broot" -> html("<title>root</title><script>filemoreajax;var fid='1';var t='t1';var k='k1';</script>")
                "/bchild" -> html("<title>child</title><script>filemoreajax;var fid='2';var t='t2';var k='k2';</script>")
                "/filemoreajax.php" -> {
                    assertEquals("2", form(request)["fid"]); assertEquals("t2", form(request)["t"])
                    json("""{"zt":1,"text":[{"id":"ichild","name":"child.txt"}]}""")
                }
                else -> throw AssertionError("Unexpected request")
            }
        }))
        val session = provider.open(NetDiskShare(NetDiskPlatform.LANZOU, "https://www.lanzoui.com/broot", "broot"), "")
        assertEquals("child.txt", provider.list(session, "bchild", null, "").files.single().name)
    }

    @Test fun anUnrecognizedLandingPageDoesNotBecomeASuccessfulEmptyFile() = runBlocking {
        val provider = LanzouNetDiskProvider(NetDiskHttp(fixtureClient { html("<html><title>Access check</title></html>") }))
        try {
            provider.open(NetDiskShare(NetDiskPlatform.LANZOU, "https://www.lanzoui.com/iabc", "iabc"), "")
            fail("No file structure must not report success")
        } catch (expected: NetDiskException) { assertEquals("verification_required", expected.reason) }
    }

    @Test fun aDownloadNodeChallengeReturnsToHeadAfterTheBoundedHtmlRead() = runBlocking {
        var headCalls = 0
        var downloadGets = 0
        val provider = LanzouNetDiskProvider(NetDiskHttp(fixtureClient { request ->
            when {
                request.url.host == "www.lanzoui.com" && request.url.encodedPath == "/iabc" ->
                    html("""<title>sample.apk</title><script>var s='live';$.ajax({url:'/ajaxm.php',data:{action:'downprocess',sign:s}})</script>""")
                request.url.encodedPath == "/ajaxm.php" ->
                    json("""{"zt":1,"dom":"https://download.lanzoug.com","url":"jump"}""")
                request.url.host == "download.lanzoug.com" && request.method == "HEAD" -> {
                    headCalls++
                    if (request.header("Cookie").orEmpty().contains("acw_sc__v2="))
                        Fixture(200, "", mapOf("Content-Type" to "application/octet-stream"))
                    else Fixture(200, "", mapOf("Content-Type" to "text/html"))
                }
                request.url.host == "download.lanzoug.com" -> {
                    downloadGets++
                    assertEquals("bytes=0-65535", request.header("Range"))
                    html("<script>var arg1='0000000000000000000000000000000000000000';</script>")
                }
                else -> throw AssertionError("Unexpected request")
            }
        }))
        val session = provider.open(NetDiskShare(NetDiskPlatform.LANZOU, "https://www.lanzoui.com/iabc", "iabc"), "")
        val file = provider.list(session, "", null, "").files.single()
        val link = provider.resolve(session, file, "")
        assertEquals("https://download.lanzoug.com/file/jump", link.url)
        assertEquals(2, headCalls)
        assertEquals(1, downloadGets)
        assertTrue(link.headers["Cookie"]!!.contains("acw_sc__v2=3000176000856006061501533003690027800375"))
    }

    private data class Fixture(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    private fun html(body: String) = Fixture(200, body, mapOf("Content-Type" to "text/html"))
    private fun json(body: String) = Fixture(200, body, mapOf("Content-Type" to "application/json"))
    private fun fixtureClient(answer: (Request) -> Fixture): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val data = answer(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(data.status).message("fixture")
            .apply { data.headers.forEach { (key, value) -> header(key, value) } }
            .body(data.body.toResponseBody(data.headers["Content-Type"]?.toMediaType())).build()
    }).build()
    private fun form(request: Request): Map<String, String> = (request.body as FormBody).let { body ->
        (0 until body.size).associate { body.name(it) to body.value(it) }
    }
}
