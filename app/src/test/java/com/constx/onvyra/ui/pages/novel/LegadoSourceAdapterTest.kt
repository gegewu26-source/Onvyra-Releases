package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.File
import java.nio.charset.Charset

class LegadoSourceAdapterTest {
    @Test fun bundledXs8SearchUsesTheActualLazyCoverInsteadOfTheSharedSvgPlaceholder() = runBlocking {
        val row = bundledSource { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://m.xs8.cn" }
        val expectedId = ReaderSourceLibrary.decode(JsonArray(listOf(row)).toString()).single().id
        val (engine, id) = engine(row) {
            "text/html" to """<ul><li class="book-li"><a class="book-layout" href="/book/10333280804940003">
                <img src="//yuxseocdn.yuewen.com/pro/xs8_m/_prelease/hongxium/img/book-cover-56f5a31f55.svg"
                    data-src="//bookcover.yuewen.com/qdbimg/349573/c_10333280804940003/150" class="book-cover" alt="剑来封天">
                <h4 class="book-title"><mark>剑来</mark>封天</h4>
                <div class="book-meta-l"><span class="book-author">洗星辰</span></div>
                </a></li></ul>"""
        }
        val book = engine.search("剑来", id).single()
        assertEquals(expectedId, id)
        assertEquals(id, book.sourceId)
        assertEquals("剑来封天", book.title)
        assertEquals("洗星辰", book.author)
        assertEquals("https://m.xs8.cn/book/10333280804940003", book.detailUrl)
        assertEquals("https://bookcover.yuewen.com/qdbimg/349573/c_10333280804940003/150", book.coverUrl)
        assertEquals("https://m.xs8.cn/so/剑来", java.net.URLDecoder.decode(book.coverReferer, "UTF-8"))
        assertEquals(book.requestHeaders, book.artworkHeaders)
    }

    @Test fun lazyCoverReusesOnlyTheDeclaredSelectorAndKeepsOrdinaryAndScriptedCovers() = runBlocking {
        val row = fixture(search = fields("bookList" to ".book", "name" to "a@text", "author" to ".author@text",
            "bookUrl" to "a@href", "coverUrl" to ".cover@src"),
            info = fields("name" to "h1@text", "author" to ".author@text", "coverUrl" to ".cover@src"),
            toc = fields(), content = fields())
        val (engine, id) = engine(row) { request ->
            "text/html" to if (request.url.encodedPath == "/book/1")
                "<h1>作品1</h1><span class='author'>作者</span><img data-src='/advert.jpg'><img class='cover' src='/detail-placeholder.svg' data-original='/detail-real.jpg'>"
            else """<div class="book"><a href="/book/1">作品1</a><span class="author">作者</span>
                    <img data-src="/advert.jpg"><img class="cover" src="/placeholder.svg" data-original="/real.jpg"></div>
                <div class="book"><a href="/book/2">作品2</a><span class="author">作者</span>
                    <img class="cover" src="/ordinary.jpg" data-src="javascript:bad()" data-original="data:image/png;base64,AA"></div>
                <div class="book"><a href="/book/3">作品3</a><span class="author">作者</span>
                    <img class="cover" src="/ordinary-three.jpg"></div>"""
        }
        val books = engine.search("作品", id)
        assertEquals(listOf("https://fixture.example.test/real.jpg", "https://fixture.example.test/ordinary.jpg",
            "https://fixture.example.test/ordinary-three.jpg"), books.map { it.coverUrl })
        assertEquals("https://fixture.example.test/detail-real.jpg", engine.getBookDetail(books.first()).coverUrl)
        val scripted = JsonObject(row + ("ruleSearch" to JsonObject(row["ruleSearch"]!!.jsonObject +
            ("coverUrl" to JsonPrimitive("@js:'https://images.example/explicit.jpg'")))))
        val (scriptEngine, scriptId) = engine(scripted) {
            "text/html" to "<div class='book'><a href='/book/1'>作品1</a><span class='author'>作者</span><img class='cover' src='/src.jpg' data-src='/lazy.jpg'></div>"
        }
        assertEquals("https://images.example/explicit.jpg", scriptEngine.search("作品", scriptId).single().coverUrl)
    }

    @Test fun twoThousandChapterCatalogRemainsCompleteOrderedAndBodyFiltersStillApply() = runBlocking {
        val row = fixture(search = fields("bookList" to ".book"),
            toc = fields("chapterList" to "dd a", "chapterName" to "text", "chapterUrl" to "href"),
            content = fields("content" to "#content@html", "replaceRegex" to "##广告推广[^\\n]*##"))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val html = "<dl>" + (1..2_000).joinToString("") { n ->
            "<dd><a href='/chapter/$n'>第${n}章 少年 &amp; 旅途</a></dd>"
        } + "</dl>"
        val (engine, id) = engine(row) { request ->
            requests.incrementAndGet()
            "text/html" to if (request.url.encodedPath == "/book/1") html
                else "<div id='content'><p>少年开始真正的故事。</p><p>广告推广请访问某个网站</p><p>下一段正文。</p></div>"
        }
        val start = System.nanoTime()
        val chapters = engine.getCatalog("https://fixture.example.test/book/1", id)
        val millis = (System.nanoTime() - start) / 1_000_000
        println("reader10_2000_chapter_parse_ms=$millis")
        assertEquals(2_000, chapters.size)
        assertEquals((1..2_000).map { "第${it}章 少年 & 旅途" }, chapters.map { it.title })
        assertEquals((0 until 2_000).toList(), chapters.map { it.index })
        assertTrue(chapters.last().url.endsWith("/chapter/2000"))
        assertEquals(1, requests.get())
        assertEquals(chapters, engine.getCatalog("https://fixture.example.test/book/1", id))
        assertEquals(1, requests.get())
        val body = engine.getChapterContent(chapters.first().url, id)
        assertTrue(body.contains("少年开始真正的故事")); assertTrue(body.contains("下一段正文"))
        assertFalse(body.contains("广告推广"))
    }

    @Test fun latestPreviewBeforeCompleteCatalogDoesNotMoveLastChapterToBeginning() = runBlocking {
        val row = fixture(search = fields("bookList" to ".book"),
            toc = fields("chapterList" to "dd a", "chapterName" to "text", "chapterUrl" to "href"),
            content = fields("content" to "#content@html"))
        val (engine,id) = engine(row) {
            "text/html" to "<dl><dt>最新章节</dt><dd><a href='/chapter/3'>第三章</a></dd><dd><a href='/chapter/2'>第二章</a></dd>" +
                "<dt>正文</dt>"+(1..3).joinToString("") { n -> "<dd><a href='/chapter/$n'>第${n}章</a></dd>" }+"</dl>"
        }
        val chapters=engine.getCatalog("https://fixture.example.test/book/1",id)
        assertEquals(listOf("第1章","第2章","第3章"),chapters.map { it.title })
        assertEquals(listOf(0,1,2),chapters.map { it.index })
    }
    @Test fun contentTextRulePreservesParagraphsBeforeFilteringActualWebsiteFooter() = runBlocking {
        val row = bundledSource { it["bookSourceUrl"]?.jsonPrimitive?.content == "http://www.yetianlian.info" }
        val (engine,id) = engine(row) {
            "text/html" to "<div id='content' class='showtxt'>正文第一段。<br/><br/>正文第二段，少年站在林间。<br/><br/>http://www.xyetianlian.com/yt30804/13807998.html<br/><br/>请记住本书首发域名：www.xyetianlian.com。何以笙箫默小说手机版阅读网址：m.xyetianlian.com</div>"
        }
        val body=engine.getChapterContent("http://www.xyetianlian.com/yt30804/13807998.html",id)
        assertTrue(body.contains("正文第一段。\n\n"));assertTrue(body.contains("正文第二段，少年站在林间。"))
        assertFalse(body.contains("首发域名"));assertFalse(body.contains("手机版阅读"));assertFalse(body.contains("http"))
    }
    @Test fun verifiedPermanentRedirectKeepsOriginalRuleIdAndUsesCurrentHostForBookAndCover() = runBlocking {
        val row = bundledSource { it["bookSourceUrl"]?.jsonPrimitive?.content == "http://www.rulianshi.org" }
        val (engine, id) = engine(row) { request ->
            assertEquals("www.rulianshi.cc", request.url.host)
            "text/html" to "<div class='bookbox'><h4><a href='/book/1'>封神进化</a></h4><p class='author'>工笔画</p><img src='/cover/1.jpg'></div>"
        }
        val hit = engine.search("封神进化", id).single()
        assertEquals(id,hit.sourceId); assertEquals("http://www.rulianshi.cc/book/1",hit.detailUrl)
        assertEquals("http://www.rulianshi.cc/cover/1.jpg",hit.coverUrl)
    }
    private fun bundledSource(predicate: (JsonObject) -> Boolean): JsonObject {
        val file = listOf(File("src/main/assets/reader/book-sources-489.json"),
            File("app/src/main/assets/reader/book-sources-489.json")).first(File::isFile)
        return Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonArray
            .map { it.jsonObject }.first(predicate)
    }

    @Test fun bundledDonationFilterRemovesAdsInsteadOfInsertingItsPatternIntoBody() = runBlocking {
        val row = bundledSource { it["bookSourceUrl"]?.jsonPrimitive?.content == "http://m.bgnovel.com" }
        val (engine, id) = engine(row) {
            "text/html" to """<div id="text"><p>主人公走进庭院，开始真正的故事。</p><p>自愿捐助网站</p><p>网站无广告收入,非盈利,捐助用于服务器开支！</p><p>怕迷路，可前往捐助页面加联系方式！</p><p>他指着墙上的广告，说这句话也是正文。</p></div>"""
        }
        val body = engine.getChapterContent("http://m.bgnovel.com/read/1.html", id)
        assertTrue(body.contains("主人公走进庭院"))
        assertTrue(body.contains("他指着墙上的广告"))
        assertFalse(body.contains("自愿捐助")); assertFalse(body.contains("服务器开支"))
        assertFalse(body.contains("联系方式")); assertFalse(body.contains("##"))
        assertFalse(body.contains("|网站无广告收入"))
    }

    @Test fun bundledTemplatedFilterReceivesCatalogChapterTitleAndStillExecutesRegex() = runBlocking {
        val row = bundledSource { it["bookSourceName"]?.jsonPrimitive?.content == "🎉 香书小说" }
        val (engine, id) = engine(row) { request ->
            "text/html" to if (request.url.encodedPath == "/book/1")
                "<ul><li>导航</li></ul><ul><li><a href='/chapter/1'>第一章 初见</a></li></ul>"
            else "<div id='nr1'>第一章 初见 <p>故事正文应当保留。</p><p>（本章未完，请继续阅读）</p><p>最新网址：https://ad.example.com</p></div>"
        }
        val chapters = engine.getCatalog("http://wap.xbiqugu.net/book/1", id)
        assertEquals("第一章 初见", chapters.single().title)
        val body = engine.getChapterContent(chapters.single().url, id)
        assertTrue(body.contains("故事正文应当保留"))
        assertFalse("Chapter title was not removed: $body", body.contains("第一章 初见"))
        assertFalse("Advert URL was not removed: $body", body.contains("最新网址"))
        assertFalse("Pagination advert was not removed: $body", body.contains("本章未完"))
        assertFalse(body.contains("chapter.title"))
        assertFalse(body.contains("#nr1"))
    }

    @Test fun bundledJavascriptReplacementRunsAsScriptOnExtractedContent() = runBlocking {
        val original = bundledSource { it["bookSourceName"]?.jsonPrimitive?.content == "🎃笔趣阁🎃#50" }
        val content = original["ruleContent"]!!.jsonObject
        val row = JsonObject(original + ("ruleContent" to JsonObject(content + ("content" to JsonPrimitive("#content@html")))))
        val (engine, id) = engine(row) {
            "text/html" to "<div id='content'><p>保留故事。</p><p>搜书名找不到, 可以试试搜作者哦, 也许只是改名了!</p><p>如果书架和阅读记录丢失了, 可以前往另一个网域,再打开书架试试!</p></div>"
        }
        val body = engine.getChapterContent("https://fixture.example.test/chapter/1", id)
        assertTrue(body.contains("保留故事")); assertFalse(body.contains("搜书名找不到"))
        assertFalse(body.contains("书架和阅读记录丢失")); assertFalse(body.contains("@js:"))
    }

    @Test fun bundledGreedyDomainFilterRemovesOnlyItsAdvertLineAndKeepsBothStoryParagraphs() = runBlocking {
        val row = bundledSource { it["bookSourceUrl"]?.jsonPrimitive?.content == "http://www.cxzz958.com/" }
        val (engine, id) = engine(row) { request ->
            assertTrue(request.header("User-Agent").orEmpty().contains("V2304A"))
            assertEquals("http://www.cxzz958.com/", request.header("Referer"))
            "text/html" to "<div id='content'><p>前一段正文，主角推开院门。</p><p>首发域名：www.advert.example.com</p><p>后一段正文，故事继续展开。</p></div>"
        }
        val body = engine.getChapterContent("http://www.cxzz958.com/read/1.html", id)
        assertTrue("Story before the advert must remain: $body", body.contains("前一段正文"))
        assertTrue("Story after the advert must remain: $body", body.contains("后一段正文"))
        assertFalse(body.contains("首发域名")); assertFalse(body.contains("advert.example"))
    }

    @Test fun legacySingleQuotedHeaderAndRequestOptionsAreParsedAsDataWithEscapes() = runBlocking {
        val row = fixture(search = fields("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
            toc = fields(), content = fields("content" to "#content@html"),
            searchUrl = """/search,{'method':'POST','headers':{'X-Option':'yes'},'body':'q={{key}}'}""",
            header = """{'User-Agent':'Legacy Agent','X-Quote':'author\'s "name"','X-Unicode':'\u0041',"X-Double":"double quoted's value"}""")
        val (engine, id) = engine(row) { request ->
            assertEquals("POST", request.method)
            assertEquals("Legacy Agent", request.header("User-Agent"))
            assertEquals("author's \"name\"", request.header("X-Quote"))
            assertEquals("A", request.header("X-Unicode"))
            assertEquals("double quoted's value", request.header("X-Double"))
            assertEquals("yes", request.header("X-Option"))
            val body = Buffer(); request.body!!.writeTo(body); assertEquals("q=book", body.readUtf8())
            "text/html" to "<div class='book'><a href='/book/1'>book</a></div>"
        }
        assertEquals("book", engine.search("book", id).single().title)
    }

    @Test fun malformedLegacyDataIsRejectedBeforeAnHttpRequest() = runBlocking {
        val row = fixture(search = fields("bookList" to ".book"), toc = fields(), content = fields(),
            header = """{'X-Value':'unterminated}""")
        var calls = 0
        val (engine, id) = engine(row) { calls++; "text/html" to "" }
        try { engine.search("book", id); fail("Malformed data must fail") }
        catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("JSON")) }
        assertEquals(0, calls)
    }

    @Test fun fullImportedAssetRetainsAll489RulesAndThreeMediaTypes() = runBlocking {
        val asset = listOf(File("src/main/assets/reader/book-sources-489.json"),
            File("app/src/main/assets/reader/book-sources-489.json")).firstOrNull(File::isFile)
            ?: error("Source library asset missing")
        val raw = asset.readText(Charsets.UTF_8)
        val library = ReaderSourceLibrary { raw }
        val sources = library.all()
        assertEquals(489, sources.size)
        assertEquals(mapOf(0 to 442, 1 to 6, 2 to 41), sources.groupingBy { it.type }.eachCount())
        assertEquals(489, sources.map { it.id }.distinct().size)
        assertTrue(sources.all { it.id.startsWith("legado_") && it.rules.isNotEmpty() })
        assertEquals((Json.parseToJsonElement(raw) as JsonArray).toList(), sources.map { it.rules })
        assertEquals(sources.map { it.id }, ReaderSourceLibrary.decode(raw).map { it.id })
    }
    private fun fixture(type: Int = 0, search: JsonObject, toc: JsonObject, content: JsonObject,
        info: JsonObject = buildJsonObject {}, searchUrl: String = "/search?q={{key}}", header: String = "") = buildJsonObject {
        put("bookSourceName", "Fixture"); put("bookSourceUrl", "https://fixture.example.test")
        put("bookSourceType", type); put("enabled", true); put("searchUrl", searchUrl)
        put("ruleSearch", search); put("ruleToc", toc); put("ruleContent", content); put("ruleBookInfo", info)
        if (header.isNotBlank()) put("header", header)
    }
    private fun fields(vararg fields: Pair<String, String>) = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }
    private suspend fun engine(source: JsonObject, reply: (Request) -> Pair<String, String>): Pair<NovelSourceEngine, String> {
        val library = ReaderSourceLibrary { JsonArray(listOf(source)).toString() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (mime, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody(mime.toMediaType())).build()
        }.build()
        return NovelSourceEngine(client, sourceLibrary = library) to library.all().single().id
    }

    @Test fun htmlSourceReadsAll230ChaptersAndBodyPagesAndRetainsHeader() = runBlocking {
        val requested = mutableListOf<String>()
        val row = fixture(search = fields("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href", "coverUrl" to "img@src"),
            info = fields("name" to "h1@text", "tocUrl" to ".catalog@href"),
            toc = fields("chapterList" to ".chapters a", "chapterName" to "text", "chapterUrl" to "href", "nextTocUrl" to ".next@href"),
            content = fields("content" to "#content@html", "nextContentUrl" to ".nextpage@href"),
            header = """{"User-Agent":"SourceAgent","X-Source":"fixture"}""")
        val (engine, id) = engine(row) { request ->
            val path = request.url.encodedPath
            requested += path
            assertEquals("SourceAgent", request.header("User-Agent"))
            val body = when {
                path == "/search" -> "<div class='book'><a href='/book/1'>作品</a><img src='/cover.jpg'></div>"
                path == "/book/1" -> "<h1>作品详情</h1><a class='catalog' href='/catalog/1'>全部目录</a>"
                path.startsWith("/catalog/") -> {
                    val p = path.substringAfterLast('/').toInt()
                    "<div class='chapters'>" + ((p - 1) * 100 + 1..minOf(230, p * 100)).joinToString("") {
                        "<a href='/chapter/$it'>第${it}章</a>"
                    } + "</div>" + if (p < 3) "<a class='next' href='/catalog/${p + 1}'>下一页</a>" else ""
                }
                path == "/chapter/1" -> "<div id='content'><p>第一页正文</p></div><a class='nextpage' href='/chapter/1-part2'>下一页</a>"
                path == "/chapter/1-part2" -> "<div id='content'><p>第二页正文</p></div>"
                else -> error("Unexpected request $path")
            }
            "text/html; charset=utf-8" to body
        }
        val book = engine.search("作品", id).single()
        assertEquals("https://fixture.example.test/cover.jpg", book.coverUrl)
        assertEquals("fixture", book.requestHeaders["X-Source"])
        assertEquals("作品详情", engine.getBookDetail(book).title)
        val progress = mutableListOf<Int>()
        val chapters = engine.getCatalog(book.detailUrl, id) { progress += it.size }
        assertEquals(listOf(100, 200, 230), progress)
        assertEquals(230, chapters.size)
        assertEquals(229, chapters.last().index)
        val body = engine.getChapterContent(chapters.first().url, id)
        assertTrue(body.contains("第一页正文")); assertTrue(body.contains("第二页正文"))
        engine.getCatalog(book.detailUrl, id)
        assertEquals(1, requested.count { it == "/catalog/1" })
    }

    @Test fun jsonAudioSourceRetainsTypeAndResolvesActualTrackUrl() = runBlocking {
        val row = fixture(type = 1,
            search = fields("bookList" to "$.info.Datas", "name" to "$.name", "author" to "$.author",
                "bookUrl" to "$.id@js:'https://fixture.example.test/drama?id='+result", "coverUrl" to "$.cover"),
            toc = fields("chapterList" to "$.info.episodes.music&&$.info.episodes.episode", "chapterName" to "$.name",
                "chapterUrl" to "$.sound_id@js:'https://fixture.example.test/sound?id='+result"),
            content = fields("content" to "$.info.sound.soundurl_64@js:'https://cdn.example.test/'+result"))
        val (engine, id) = engine(row) { request -> "application/json" to when (request.url.encodedPath) {
            "/search" -> """{"info":{"Datas":[{"id":19,"name":"有声样本","author":"演播","cover":"https://cdn.example.test/cover.jpg"}]}}"""
            "/drama" -> """{"info":{"episodes":{"music":[{"name":"片头","sound_id":1}],"episode":[{"name":"第一集","sound_id":2}]}}}"""
            "/sound" -> """{"info":{"sound":{"soundurl_64":"audio/2.mp3"}}}"""
            else -> error("Unexpected request")
        } }
        val book = engine.search("有声", id).single()
        assertTrue(book.isAudio); assertFalse(book.isComic)
        val chapters = engine.getCatalog(book.detailUrl, id)
        assertEquals(2, chapters.size)
        assertEquals("第一集", chapters.last().title)
        assertEquals("https://cdn.example.test/audio/2.mp3", engine.getChapterContent(chapters.last().url, id))
    }

    @Test fun comicRulesReturnImagesWithoutTreatingHtmlAsImage() = runBlocking {
        val row = fixture(type = 2,
            search = fields("bookList" to "$.data", "name" to "$.title", "bookUrl" to "https://fixture.example.test/comic/{{$.id}}"),
            toc = fields("chapterList" to "li a", "chapterName" to "text", "chapterUrl" to "href"),
            content = fields("content" to "#pages@html"))
        val (engine, id) = engine(row) { request -> "text/html" to when (request.url.encodedPath) {
            "/search" -> """{"data":[{"id":3,"title":"漫画样本"}]}"""
            "/comic/3" -> "<li><a href='/read/3'>第一话</a></li>"
            "/read/3" -> "<div id='pages'><img src='/1.jpg'><img data-src='https://cdn.example.test/2.webp'></div>"
            else -> error("Unexpected request")
        } }
        val book = engine.searchComics("漫画", id).single()
        assertTrue(book.isComic)
        assertEquals("", book.coverUrl)
        val chapters = engine.getComicCatalog(book.detailUrl, id)
        assertEquals(listOf("https://fixture.example.test/1.jpg", "https://cdn.example.test/2.webp"),
            engine.getComicChapterPages(chapters.single().url, id))
    }

    @Test fun gb2312PostEncodesKeywordAndPreservesOptions() = runBlocking {
        val row = fixture(search = fields("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
            toc = fields("chapterList" to "a", "chapterName" to "text", "chapterUrl" to "href"),
            content = fields("content" to "body@text"),
            searchUrl = "/search,{\"method\":\"POST\",\"charset\":\"gb2312\",\"body\":\"keyword={{key}}\",\"headers\":{\"X-Source\":\"post\"}}")
        val (engine, id) = engine(row) { request ->
            assertEquals("POST", request.method)
            assertEquals("post", request.header("X-Source"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            assertEquals("keyword=%D6%D0%CE%C4", buffer.readString(Charset.forName("GB2312")))
            "text/html; charset=utf-8" to "<div class='book'><a href='/book/1'>中文</a></div>"
        }
        assertEquals("中文", engine.search("中文", id).single().title)
    }

    @Test fun sourceMissingJsDependencyFailsWithoutRequestOrProviderFallback() = runBlocking {
        val row = fixture(search = fields("bookList" to "div"), toc = fields(), content = fields(),
            searchUrl = "@js:missingSourceHelper(key)")
        var calls = 0
        val (engine, id) = engine(row) { calls++; "text/html" to "" }
        try { engine.search("test", id); fail("Missing helper must fail") }
        catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("missingSourceHelper")) }
        assertEquals(0, calls)
    }

    @Test fun sourceRemarkFragmentsAreNotHeadersAndKindResolvesIdsBeforeBookUrl() = runBlocking {
        val resourceId = "9876543210123456789"
        val raw = fixture(search = fields("bookList" to "$.data", "name" to "$.title", "author" to "$.author",
            "kind" to "$.groupID##.*_##", "bookUrl" to "https://fixture.example.test/info?resourceId={{book.kind}}"),
            info = fields("name" to "$.name", "author" to "$.author", "kind" to "$.resourceID",
                "tocUrl" to "https://fixture.example.test/catalog?bookId={{book.kind}}"),
            toc = fields("chapterList" to "$.chapters", "chapterName" to "$.title", "chapterUrl" to "$.url"),
            content = fields("content" to "body@text"))
        val row = JsonObject(raw + ("bookSourceUrl" to JsonPrimitive("https://fixture.example.test##旅途🎃")))
        val (engine, id) = engine(row) { request ->
            assertFalse(request.header("Referer").orEmpty().contains('#'))
            assertTrue(request.header("Referer").orEmpty().all { it.code < 128 })
            "application/json" to when (request.url.encodedPath) {
                "/search" -> """{"data":[{"title":"正确作品","author":"作者","groupID":"book_$resourceId"},{"title":"缺少ID作品","author":"作者"}]}"""
                "/info" -> {
                    assertEquals(resourceId, request.url.queryParameter("resourceId"))
                    """{"name":"正确作品","author":"作者","resourceID":"$resourceId"}"""
                }
                "/catalog" -> {
                    assertEquals(resourceId, request.url.queryParameter("bookId"))
                    """{"chapters":[{"title":"第一章","url":"/chapter/1"}]}"""
                }
                else -> error("Unexpected request ${request.url}")
            }
        }
        val book = engine.search("作品", id).single()
        assertEquals("正确作品", book.title)
        assertEquals(resourceId, book.sourceVariables["book.kind"])
        assertTrue(book.category.isBlank())
        assertEquals(resourceId, book.asSource().asBook().sourceVariables["book.kind"])
        val detail = engine.getBookDetail(book)
        assertEquals(resourceId, detail.sourceVariables["book.kind"])
        assertEquals(1, engine.getCatalog(detail.detailUrl, id).size)
    }

    @Test fun literalJsonBookUrlBindsPostIdentityAndRejectsMissingIdentity() = runBlocking {
        val row = fixture(search = fields("bookList" to "$.data", "name" to "$.name", "author" to "$.author",
            "bookUrl" to "https://fixture.example.test/catalog,{\"method\":\"POST\",\"body\":\"bookId={$.bookId}\"}"),
            toc = fields("chapterList" to "$.chapters", "chapterName" to "$.title", "chapterUrl" to "$.url"),
            content = fields("content" to "body@text"))
        val (engine, id) = engine(row) { request -> "application/json" to when (request.url.encodedPath) {
            "/search" -> """{"data":[{"name":"作品","author":"作者","bookId":321},{"name":"无ID作品","author":"作者"}]}"""
            "/catalog" -> {
                assertEquals("POST", request.method)
                val body = Buffer(); request.body!!.writeTo(body)
                assertEquals("bookId=321", body.readUtf8())
                """{"chapters":[{"title":"第一章","url":"/chapter/1"}]}"""
            }
            else -> error("Unexpected request ${request.url}")
        } }
        val book = engine.search("作品", id).single()
        assertTrue(book.detailUrl.endsWith("\"body\":\"bookId=321\"}"))
        assertEquals(1, engine.getCatalog(book.detailUrl, id).size)
    }
}
