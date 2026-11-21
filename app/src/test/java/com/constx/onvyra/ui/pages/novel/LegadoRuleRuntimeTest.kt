package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LegadoRuleRuntimeTest {
    private val base = "https://books.example/catalog/"
    private val html = """
        <div class="results"><div class="book"><a href="/book/1">第一本</a><img data-src="/1.jpg"></div>
        <div class="book"><a href="/book/2">第二本</a><img src="/2.jpg"></div></div>
    """.trimIndent()

    @Test fun simpleCssDirectoryFieldsEqualDetachedRuleEvaluationWithoutReserializingRows() = runBlocking {
        val page = "<html><body><dl class='listmain'>" +
            "<dd><a href='/chapter/1?x=1&amp;y=2'>第一章 &amp; 少年</a><span>副题</span></dd>" +
            "<dd><a href='https://other.example/chapter/2'>第<b>二</b>章&nbsp;旅途</a></dd>" +
            "<dd><a href='../chapter/3' title='提示'>第三章</a>附言</dd></dl></body></html>"
        val cases = listOf(
            "dd" to listOf("a@text", "a@href"),
            ".listmain@tag.dd!0" to listOf("tag.a@text", "tag.a@href", "a@title"),
            "dd a" to listOf("text", "href", "ownText", "textNodes"),
            "tag.dd.-1" to listOf("a", "a@href", "span@text"))
        for ((listRule, fields) in cases) {
            val runtime = LegadoRuleRuntime()
            val slow = runtime.elements(page, listRule, base).map { runtime.strings(it, fields, base) }
            val fast = runtime.simpleCssRows(page, listRule, fields, base)
            assertNotNull("Simple rules should use the fast path: $listRule", fast)
            assertEquals(slow, fast)
        }
    }

    @Test fun complexOrContextSensitiveDirectoryRulesKeepTheOriginalPath() = runBlocking {
        val runtime = LegadoRuleRuntime()
        val page = "<dl><dd><a href='/chapter/1'>第一章</a></dd></dl>"
        for (rule in listOf("a@text##章##节", "a@text||text", "@js:result", "@xpath://a", "$.name", "a:has(b)@text", "a + span@text", "body a@text", ".listmain a@text")) {
            assertNull(rule, runtime.simpleCssRows(page, "dd", listOf(rule, "a@href"), base))
        }
        assertNull(runtime.simpleCssRows("{\"chapters\":[]}", "$.chapters", listOf("$.name", "$.url"), base))
        assertNull(runtime.simpleCssRows("<table><tr><td>chapter</td></tr></table>", "tr", listOf("text", "a@href"), base))
    }

    @Test fun twoThousandChapterFastPathMatchesTheGeneralInterpreter() = runBlocking {
        val page = "<dl>" + (1..2_000).joinToString("") { n ->
            "<dd><a href='/chapter/$n'>第${n}章 少年 &amp; 旅途</a></dd>"
        } + "</dl>"
        val fields = listOf("a@text", "a@href")
        val generalRuntime = LegadoRuleRuntime()
        val generalStart = System.nanoTime()
        val expected = generalRuntime.elements(page, "dd", base).map { generalRuntime.strings(it, fields, base) }
        val generalMs = (System.nanoTime() - generalStart) / 1_000_000
        val fastStart = System.nanoTime()
        val actual = LegadoRuleRuntime().simpleCssRows(page, "dd", fields, base)
        val fastMs = (System.nanoTime() - fastStart) / 1_000_000
        println("reader10_directory_equivalence_general_ms=$generalMs fast_ms=$fastMs chapters=${actual?.size}")
        assertEquals(2_000, actual?.size)
        assertEquals(expected, actual)
    }

    @Test fun jsonRowsCanProduceLiteralUrlPostOptionsAndSingleBraceBindings() = runBlocking {
        val runtime = LegadoRuleRuntime()
        val data = """{"bookId":12345,"wapBookId":"abc","crazy_rating":9.2}"""
        assertEquals("https://api.example/chapter,{\"method\":\"POST\",\"body\":\"bookId=12345\"}",
            runtime.string(data, "https://api.example/chapter,{\"method\":\"POST\",\"body\":\"bookId={$.bookId}\"}", base))
        assertEquals("http://m.example/b/abc.html", runtime.string(data, "http://m.example/b/{$.wapBookId}.html", base))
        assertEquals("9.2分", runtime.string(data, "{$.crazy_rating}分", base))
        assertEquals("https://api.example/fixed", runtime.string(data, "https://api.example/fixed", base))
    }

    @Test fun selectedRowsRetainTheirOwnAttributesAndText() = runBlocking {
        val runtime = LegadoRuleRuntime()
        val rows = runtime.elements(html, "class.book@a", base)
        assertEquals(2, rows.size)
        assertEquals("/book/1", runtime.string(rows.first(), "href", base))
        assertEquals("第一本", runtime.string(rows.first(), "text", base))
        assertEquals("/book/2", runtime.string(rows.last(), "a@href", base))
    }

    @Test fun selectorsWithSpacesFallbackAndNegativeIndicesWork() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("第二本", runtime.string(html, "div.results .missing@text || div.book.-1@a@text", base))
        assertEquals("第一本", runtime.string(html, "class.book!1@a@text", base))
        assertEquals("/1.jpg", runtime.string(runtime.elements(html, ".book", base).first(), "img@src||img@data-src", base))
        assertEquals(listOf("第一本", "第二本"), runtime.elements(html, ".book@a@text", base))
    }

    @Test fun joinsAndReplacementsApplyToExtractedFields() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("第一本\n第二本", runtime.string(html, ".book.0@text&&.book.1@text", base))
        assertEquals("第一部\n第二部", runtime.string(html, ".book@text##本##部", base))
        assertEquals("12", runtime.string("<b>共12章</b>", "b@text##共(\\d+)章##$1###", base))
    }

    @Test fun replacementTemplatesExecuteAfterBindingAndEmptyPatternsLeaveContentIntact() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("正文", runtime.string("第一章 初见 正文（本章未完，请继续阅读）",
            "##{{chapter.title}} |（本章未完.+继续阅读）", base, mapOf("chapter.title" to "第一章 初见")))
        assertEquals("正文", runtime.string("<p>第一章 正文</p>", "p@text##{{chapter.title}} ", base,
            mapOf("chapter.title" to "第一章")).trim())
        assertEquals("保持原文", runtime.string("保持原文", "####不要插入", base))
        assertEquals("12章", runtime.string("<b>共12章</b>", "{{@@b@text##共}}", base))
    }

    @Test fun htmlFieldsKeepBlockBoundariesWithoutMutatingOtherRuleInputs() = runBlocking {
        val runtime = LegadoRuleRuntime()
        val html = "<div id='content'>TITLE <p>FIRST</p><p>ADVERT</p><p>LAST</p><script>var marker='</p>';</script></div>"
        val serialized = runtime.string(html, "#content@html", base)
        assertTrue(serialized.startsWith("TITLE <p>FIRST</p>\n"))
        assertTrue(serialized.contains("<p>ADVERT</p>\n<p>LAST</p>\n"))
        assertTrue(serialized.contains("var marker='</p>';"))
        assertEquals("FIRST", runtime.string(html, "#content@p.0@text", base))
        assertEquals("FIRST\nADVERT\nLAST", runtime.string(html, "#content@p@text", base))
        assertEquals("<p>JSON remains one line</p>", runtime.string("""{"txt":"<p>JSON remains one line</p>"}""", "$.txt", base))
        assertEquals("<p>JS remains one line</p>", runtime.string("", "@js:'<p>JS remains one line</p>'", base))
    }

    @Test fun jsonPathsPreserveObjectsAndSupportNestedEpisodeLists() = runBlocking {
        val runtime = LegadoRuleRuntime()
        val content = """{"info":{"episodes":{"music":[{"id":1,"title":"音乐"}],"episode":[{"id":2,"title":"正篇"}]}},"data":[{"n":1},{"n":3}]}"""
        val rows = runtime.elements(content, "$.info.episodes.music&&$.info.episodes.episode", base)
        assertEquals(2, rows.size)
        assertEquals("音乐", runtime.string(rows.first(), "$.title", base))
        assertEquals("正篇", runtime.string(rows.last(), "title", base))
        assertEquals("3", runtime.string(content, "$.data[?(@.n > 1)].n", base))
        assertEquals("1", runtime.string(content, "$.data[0:1].n", base))
        assertEquals(listOf("1", "2"), runtime.elements("[1,2]", "$.[*]", base))
    }

    @Test fun savedVariablesAreAvailableToLaterFieldRules() = runBlocking {
        val runtime = LegadoRuleRuntime()
        runtime.string("<h1>书名</h1><p class=author>作者</p>", "@put:{n:'h1@text',a:'.author@text'}", base)
        assertEquals("书名", runtime.string("", "@get:{n}", base))
        assertEquals("作者", runtime.string("", "@js:java.get('a')", base))
    }

    @Test fun javascriptCanTransformJsonAndUseExplicitNetworkAndHtmlBridge() = runBlocking {
        val runtime = LegadoRuleRuntime(ajax = { request -> assertEquals("https://books.example/api", request); "{\"id\":7}" })
        assertEquals("7", runtime.string("", "@js:JSON.parse(java.ajax('https://books.example/api')).id", base))
        assertEquals("第一本", runtime.string(html, "@js:java.getElements('.book').get(0).select('a').get(0).text()", base))
        assertEquals("hello", runtime.string("", "@js:java.base64Decode('aGVsbG8=')", base))
        assertEquals("/book/2", runtime.string(html, "@js:java.getElement('.book.-1').selectFirst('a').attr('href')", base))
    }

    @Test fun templatesTreatLiteralPathsAsTextAndNumericPageAsNumber() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("/search?q=%E4%B9%A6&p=3", runtime.template("", "/search?q={{key}}&p={{page+1}}", base, mapOf("key" to "%E4%B9%A6", "page" to "2")))
        assertEquals("/search", runtime.template("", "/search", base))
        assertEquals("https://books.example/catalog/api", runtime.template("", "{{baseUrl}}api", base))
        assertEquals("/book/", runtime.template("", "/book/<,index_{{page}}.html>", base, mapOf("page" to "1")))
        assertEquals("/book/index_2.html", runtime.template("", "/book/<,index_{{page}}.html>", base, mapOf("page" to "2")))
        assertEquals("/new?q=book", runtime.template("", "/old?q={{key}}@js:result.replace('/old','/new')", base, mapOf("key" to "book")))
    }

    @Test fun xpathReturnsFieldValuesAndReusableElementFragments() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("第一本\n第二本", runtime.string(html, "//a/text()", base))
        val rows = runtime.elements(html, "//div[@class='book']", base)
        assertEquals(2, rows.size)
        assertEquals("/book/2", runtime.string(rows.last(), "a@href", base))
    }

    @Test fun scriptsHaveNoJvmReflectionOrFilesystemAndMissingDependenciesFailExplicitly() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("undefined:undefined:undefined:undefined", runtime.string("",
            "@js:typeof Packages.java.lang+':'+typeof Packages.java.io.File+':'+typeof JavaAdapter+':'+typeof getClass", base))
        assertEquals("undefined", runtime.string(html, "@js:typeof java.getElement('.book').getClass", base))
        try {
            runtime.string("", "@js:yuluSearchBooks('abc')", base)
            fail("Missing helper must fail, not return no results")
        } catch (e: LegadoRuleException) {
            assertTrue(e.message.orEmpty().contains("yuluSearchBooks"))
        }
    }

    @Test fun nativeAllocationAndHugeSparseResultsAreBounded() = runBlocking {
        val runtime = LegadoRuleRuntime()
        for (script in listOf("new Array(1000000000)", "'x'.repeat(1000000000)", "var a=[];a.length=1000000000;a")) {
            try {
                runtime.string("", "@js:$script", base)
                fail("Oversized allocation/result must fail")
            } catch (_: LegadoRuleException) { }
        }
    }

    @Test fun runtimeLinkageFailureBecomesAnExplicitSourceError() = runBlocking {
        val runtime = LegadoRuleRuntime(ajax = { throw NoClassDefFoundError("fixture-runtime-missing") })
        try {
            runtime.string("", "@js:java.ajax('unused')", base)
            fail("A runtime linkage failure must become a source error")
        } catch (e: LegadoRuleException) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.take(12).any { it is LinkageError })
            assertTrue(e.message.orEmpty().contains("运行环境"))
        }
    }

    @Test(timeout = 10_000) fun sourceCannotCatchTheInterpreterDeadlineAndContinueForever() = runBlocking {
        try {
            LegadoRuleRuntime().string("", "@js:while(true){try{while(true){}}catch(e){}}", base)
            fail("An infinite source must be terminated")
        } catch (e: LegadoRuleException) {
            assertTrue(e.message.orEmpty().contains("超时"))
            assertFalse(e.message.orEmpty().contains("cannot be cast"))
        }
    }

    @Test fun sourceNetworkIOExceptionIsReportedWithoutRhinoCastingItToError() = runBlocking {
        val runtime = LegadoRuleRuntime(ajax = { throw java.io.IOException("fixture-offline") })
        try {
            runtime.string("", "@js:java.ajax('https://fixture.invalid/')", base)
            fail("Network failure must be reported")
        } catch (e: LegadoRuleException) {
            assertTrue(e.message.orEmpty().contains("fixture-offline"))
            assertFalse(e.message.orEmpty().contains("cannot be cast"))
        }
    }
}
