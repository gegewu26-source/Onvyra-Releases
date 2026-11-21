package com.constx.onvyra.data.media

import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ExternalVideoSiteSupportTest {

    @Test
    fun `recognizes JableTV MissAV SupJav and Hanime1 video links and rewrites dead MissAV hosts to missav123`() {
        val validUrls = listOf(
            "https://jable-mirror.private-media.invalid/videos/jul-331/",
            "https://jable-primary.private-media.invalid/videos/mird-194/",
            "https://missav-primary.private-media.invalid/cn/jul-331",
            "https://missav-legacy-1.private-media.invalid/dm57/cn/sone-543-chinese-subtitle",
            "https://missav-legacy-2.private-media.invalid/081315_133",
            "https://supjav-primary.private-media.invalid/zh/123456.html",
            "https://supjav-primary.private-media.invalid/654321.html",
            "https://hanime1-primary.private-media.invalid/watch?v=98765",
            "https://hanime1-mirror-1.private-media.invalid/watch?v=408760",
            "https://hanime1-alternate.private-media.invalid/watch?v=159365",
        )
        validUrls.forEach { url ->
            assertNotNull("Expected recognized external site for $url", identifyExternalVideoSite(url))
            assertNotNull("Expected parseVideoLink to accept $url", parseVideoLink(url))
        }

        assertEquals(
            "https://missav-primary.private-media.invalid/dm57/cn/jul-331",
            rewriteToActiveMirror("https://missav-legacy-1.private-media.invalid/dm57/cn/jul-331"),
        )
        assertEquals(
            "https://missav-primary.private-media.invalid/sone-543",
            rewriteToActiveMirror("https://missav-legacy-2.private-media.invalid/sone-543"),
        )

        val invalidUrls = listOf(
            "https://jable-mirror.private-media.invalid/search/jul-331/",
            "https://jable-mirror.private-media.invalid/categories/chinese-subtitle/",
            "https://missav-primary.private-media.invalid/cn/search/JUL-331",
            "https://missav-legacy-1.private-media.invalid/dm278/chinese-subtitle",
            "https://missav-legacy-2.private-media.invalid/actresses/yui-hatano",
            "https://supjav-primary.private-media.invalid/zh/?s=JUL-331",
            "https://supjav-primary.private-media.invalid/category/censored-jav",
            "https://hanime1-primary.private-media.invalid/search?query=test",
        )
        invalidUrls.forEach { url ->
            assertNull("Expected non-video URL $url to be rejected", identifyExternalVideoSite(url))
            assertNull("Expected parseVideoLink to reject $url", parseVideoLink(url))
        }
    }

    @Test
    fun `parses JableTV search results and excludes member-only items`() {
        val html = """
            <div id="list_videos_common_videos_list">
                <div class="video-img-box">
                    <div class="img-box">
                        <a href="https://jable-primary.private-media.invalid/videos/jul-331/"><img data-src="https://cdn.example/1.jpg" alt="JUL-331"></a>
                        <span class="label">1:58:12</span>
                    </div>
                    <div class="detail">
                        <h6 class="title"><a href="https://jable-primary.private-media.invalid/videos/jul-331/">JUL-331 人妻家庭教師 波多野結衣</a></h6>
                    </div>
                </div>
                <div class="video-img-box">
                    <div class="img-box">
                        <a href="https://jable-primary.private-media.invalid/videos/vip-001/">
                            <div class="ribbon-top-left">會員</div>
                        </a>
                    </div>
                    <div class="detail">
                        <h6 class="title"><a href="https://jable-primary.private-media.invalid/videos/vip-001/">VIP-001 會員專屬</a></h6>
                    </div>
                </div>
            </div>
        """.trimIndent()
        val items = parseJableSearchHtml(html, "https://jable-primary.private-media.invalid/search/JUL-331/")
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("jul-331", item.id)
        assertEquals("jable", item.sourceId)
        assertEquals("JableTV", item.sourceName)
        assertEquals("JUL-331 人妻家庭教師 波多野結衣", item.title)
        assertEquals(7092.0, item.durationSeconds!!, 0.1)
        assertEquals("https://jable-primary.private-media.invalid/videos/jul-331/", item.lines.single().episodes.single().url)
    }

    @Test
    fun `parses MissAV search results with dm prefix duration and subtitle badges`() {
        val html = """
            <div class="grid">
                <div class="thumbnail group">
                    <div class="relative">
                        <a href="https://missav-primary.private-media.invalid/dm57/cn/jul-331" alt="jul-331">
                            <video data-src="https://fourhoi.com/jul-331/preview.mp4"></video>
                            <img data-src="https://fourhoi.com/jul-331/cover-t.jpg" alt="人妻秘书 波多野结衣">
                        </a>
                        <a href="https://missav-primary.private-media.invalid/dm57/cn/jul-331"><span class="bottom-1 left-1">中文字幕</span></a>
                        <a href="https://missav-primary.private-media.invalid/dm57/cn/jul-331"><span class="bottom-1 right-1">1:57:53</span></a>
                    </div>
                    <div class="my-2 text-sm truncate">
                        <a class="text-secondary" href="https://missav-primary.private-media.invalid/dm57/cn/jul-331">JUL-331 人妻秘书 波多野结衣</a>
                    </div>
                </div>
            </div>
        """.trimIndent()
        val items = parseMissAvSearchHtml(html, "https://missav-primary.private-media.invalid/cn/search/JUL-331")
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("jul-331", item.id)
        assertEquals("missav", item.sourceId)
        assertEquals("JUL-331 人妻秘书 波多野结衣", item.title)
        assertEquals(7073.0, item.durationSeconds!!, 0.1)
        assertEquals("https://missav-primary.private-media.invalid/cn/jul-331", item.lines.single().episodes.single().url)
    }

    @Test
    fun `parses SupJav and Hanime1 Ver2 horizontal-card search results and skips sponsor ads`() {
        val supjavHtml = """
            <div class="posts">
                <div class="post">
                    <a class="img" href="https://supjav-primary.private-media.invalid/zh/321654.html" title="JUL-331 波多野結衣">
                        <img data-original="https://img.supjav-primary.private-media.invalid/1.jpg">
                    </a>
                    <h3><a href="https://supjav-primary.private-media.invalid/zh/321654.html">JUL-331 波多野結衣</a></h3>
                </div>
            </div>
        """.trimIndent()
        val supjavItems = parseSupJavSearchHtml(supjavHtml)
        assertEquals(1, supjavItems.size)
        assertEquals("321654", supjavItems.single().id)
        assertEquals("https://supjav-primary.private-media.invalid/zh/321654.html", supjavItems.single().lines.single().episodes.single().url)

        val hanimeVer2Html = """
            <div class="content-padding-new">
                <div class="horizontal-card">
                    <a href="https://l.erodalabs.com/s/1L6gaU" class="video-link"><div class="thumb-container">贊助商</div></a>
                </div>
                <div class="horizontal-card">
                    <a class="video-link" href="https://hanime1-primary.private-media.invalid/watch?v=408760">
                        <div class="thumb-container">
                            <img class="main-thumb" src="https://hanime1-stream-primary.private-media.invalid/thumb.jpg">
                            <div class="duration">18:30</div>
                        </div>
                        <h4 class="video-title">[zzzwen] 【催眠】胡桃 芙宁娜</h4>
                    </a>
                </div>
            </div>
        """.trimIndent()
        val hanimeItems = parseHanime1SearchHtml(hanimeVer2Html, "https://hanime1-primary.private-media.invalid/search?query=催眠")
        assertEquals(1, hanimeItems.size)
        assertEquals("408760", hanimeItems.single().id)
        assertEquals("[zzzwen] 【催眠】胡桃 芙宁娜", hanimeItems.single().title)
        assertEquals(1110.0, hanimeItems.single().durationSeconds!!, 0.1)
    }

    @Test
    fun `extracts JableTV MissAV multi-eval Streamtape VOE and Hanime1 Ver2 streams`() {
        val jableHtml = """
            <html><head><meta property="og:title" content="JUL-331 波多野結衣 - jable-mirror.private-media.invalid"></head>
            <body><script>var hlsUrl = 'https://cdn.mushroomtrack.com/hls/token123/1131.m3u8';</script></body></html>
        """.trimIndent()
        val jableSource = extractJableVideoFromHtml(jableHtml, "https://jable-primary.private-media.invalid/videos/jul-331/")!!
        assertEquals("https://cdn.mushroomtrack.com/hls/token123/1131.m3u8", jableSource.url)
        assertEquals("JUL-331 波多野結衣", jableSource.title)
        assertEquals("https://jable-primary.private-media.invalid/videos/jul-331/", jableSource.headers["Referer"])
        assertEquals("https://jable-primary.private-media.invalid", jableSource.headers["Origin"])

        // MissAV script with multiple eval(function(p,a,c,k,e,d)) blocks in the same <script> tag
        val missAvHtml = """
            <html><head><meta property="og:title" content="WANZ-225 波多野結衣 - MissAV"></head>
            <body><script>
            eval(function(p,a,c,k,e,d){return p}('0=1;',2,2,'dummy|check'.split('|'),0,{}))
            eval(function(p,a,c,k,e,d){return p}('f=\'8://7.6/5-4-3-2-1/e.0\';',16,16,'m3u8|9055f63edd00|b739|4b71|e83b|38af2c15|invalid|missav-stream.private-media|https|video|1280x720|source1280|842x480|source842|playlist|source'.split('|'),0,{}))
            </script></body></html>
        """.trimIndent()
        val missAvSource = extractMissAvVideoFromHtml(missAvHtml, "https://missav-primary.private-media.invalid/dm82/cn/wanz-225")!!
        assertEquals("https://missav-stream.private-media.invalid/38af2c15-e83b-4b71-b739-9055f63edd00/playlist.m3u8", missAvSource.url)
        assertEquals("https://missav-primary.private-media.invalid/", missAvSource.headers["Referer"])
        assertEquals("https://missav-primary.private-media.invalid", missAvSource.headers["Origin"])

        // Hanime1 Ver2 <video id="player"> and #player-div-wrapper const source with cdn2020 host fix
        val hanimePlayerHtml = """
            <html><body>
            <h3 id="shareBtn-title">[中文字幕] 測試動畫</h3>
            <video id="player">
                <source src="https://hanime1-stream-primary.private-media.invalid/408760-720p.mp4?secure=a" size="720" type="video/mp4">
                <source src="https://hanime1-stream-primary.private-media.invalid/408760-1080p.mp4?secure=b" size="1080" type="video/mp4">
            </video></body></html>
        """.trimIndent()
        val hanimePrimary = extractHanime1VideoFromHtml(hanimePlayerHtml, "https://hanime1-primary.private-media.invalid/watch?v=408760")!!
        assertEquals("https://hanime1-stream-primary.private-media.invalid/408760-1080p.mp4?secure=b", hanimePrimary.url)
        assertEquals("[中文字幕] 測試動畫", hanimePrimary.title)

        val hanimeBackup = extractHanime1VideoFromHtml(hanimePlayerHtml, "https://hanime1-primary.private-media.invalid/watch?v=408760#cdn=backup")!!
        assertEquals("https://hanime1-stream-backup.private-media.invalid/408760-1080p.mp4?secure=b", hanimeBackup.url)

        val javchuScriptHtml = """
            <html><body>
            <h3 id="shareBtn-title">MFYD-075 波多野結衣</h3>
            <div id="player-div-wrapper">
                <script>const source = 'https://t26.hanime1-restricted-2.private-media.invalid/video/m3u8/2025/12/15/6db42512/index.m3u8';</script>
            </div></body></html>
        """.trimIndent()
        val javchuSource = extractHanime1VideoFromHtml(javchuScriptHtml, "https://hanime1-alternate.private-media.invalid/watch?v=159365")!!
        assertEquals("https://hanime1-stream-rewrite.private-media.invalid/video/m3u8/2025/12/15/6db42512/index.m3u8", javchuSource.url)
        assertEquals("application/x-mpegURL", javchuSource.mimeType)
    }

    @Test
    fun `SupJav client supports EVS master txt playlists and cross-site code fallback on 404`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val (code, body) = when {
                url.contains("supjav-primary.private-media.invalid") -> 200 to """
                    <html><head><title>JUL-331 波多野結衣 - supjav-primary.private-media.invalid</title></head>
                    <body><a class="btn-server" data-link="321cba">EVS</a></body></html>
                """.trimIndent()
                url.contains("supjav-embed.private-media.invalid/supjav.php?c=abc123") -> 200 to """
                    <html><body><script>
                    eval(function(p,a,c,k,e,d){return p}('0=\"1://2.3/4/5.6\";',7,7,'file|https|evsishere|xyz|hls3|master|txt'.split('|'),0,{}))
                    </script></body></html>
                """.trimIndent()
                url.contains("jable-primary.private-media.invalid/videos/wanz-225/") || url.contains("jable-mirror.private-media.invalid/videos/wanz-225/") ->
                    404 to "Not Found"
                url.contains("missav-primary.private-media.invalid/cn/wanz-225") -> 200 to """
                    <html><head><meta property="og:title" content="WANZ-225 波多野結衣"></head>
                    <body><script>
                    eval(function(p,a,c,k,e,d){return p}('0=\"1://2.3/4.5\";',6,6,'source|https|missav-stream.private-media|invalid|playlist|m3u8'.split('|'),0,{}))
                    </script></body></html>
                """.trimIndent()
                else -> error("Unexpected URL: $url")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }.build()

        com.constx.onvyra.security.PrivateMediaAccess.installVerifier { true }
        try {
            val client = ExternalVideoSiteClient(http)
            val evsSource = client.resolveVideo("https://supjav-primary.private-media.invalid/zh/99999.html")
            assertEquals("https://evsishere.xyz/hls3/master.txt", evsSource.url)
            assertEquals("application/x-mpegURL", evsSource.mimeType)

            // Authorized callers retain the existing cross-site fallback.
            val fallbackSource = client.resolveVideo("https://jable-mirror.private-media.invalid/videos/wanz-225/")
            assertEquals("https://missav-stream.private-media.invalid/playlist.m3u8", fallbackSource.url)
        } finally {
            com.constx.onvyra.security.PrivateMediaAccess.installVerifier { false }
        }
    }
}
