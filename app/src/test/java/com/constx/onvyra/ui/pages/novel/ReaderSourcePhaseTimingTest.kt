package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ReaderSourcePhaseTimingTest {
    @Test fun actualRequestsAndRuleParsingHaveSeparatePhaseEvidenceAndCacheAvoidsReseaching() = runBlocking {
        var requests = 0
        val library = library(listOf("one", "two"))
        val client = client { request ->
            requests++
            when (request.url.encodedPath) {
                "/search" -> "<div class='book'><a href='/book'>计时验证作品</a><b>独立作者</b></div>"
                "/book" -> "<h1>计时验证作品</h1><b class='author'>独立作者</b><a class='toc' href='/toc'>目录</a>"
                "/toc" -> "<div class='chapters'><a href='/chapter'>第一章 初见</a></div>"
                "/chapter" -> "<div id='content'>晨光从窗外落到木桌上，他拿起远方朋友送来的信，认真读起那段久未听闻的往事。</div>"
                else -> error("Unexpected fixture request")
            }
        }
        val engine = NovelSourceEngine(client, sourceLibrary = library)
        val wanted = NovelBook("catalog-timing", "计时验证作品", "独立作者",
            detailUrl = "https://catalog.fixture.test/timing", sourceId = NovelSourceEngine.SOURCE_CATALOG)
        engine.resolveCatalogBook(wanted)
        val phases = engine.sourcePhaseSnapshot()
        for (phase in listOf("title_search", "exact_detail", "exact_catalog", "exact_body", "http", "search_rules", "detail_rules", "request_rules"))
            assertTrue("Missing stage evidence: $phase", phases.any { it.phase == phase && it.status == "ok" })
        assertTrue(phases.all { it.sourceId.startsWith("legado_") && it.queueMs >= 0 && it.executionMs >= 0 })
        val before = requests
        engine.resolveCatalogBook(wanted)
        assertEquals("A proved route must not search the library again", before, requests)
    }

    @Test fun dynamicSourceVariablesSurviveNewSessionsAndRemainIsolatedFromAnotherSource() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val adapter = LegadoSourceAdapter(client { request ->
            requests += request.url.encodedPath
            if (request.url.encodedPath == "/config") request.url.scheme + "://" + request.url.host
            else "<div class='book'><a href='/book'>变量隔离作品</a><b>独立作者</b></div>"
        }, library(listOf("one", "two"), scripted = true))
        val ids = library(listOf("one", "two"), scripted = true).searchable(0).map { it.id }
        adapter.search("首次", ids[0]); adapter.search("再次", ids[0])
        assertEquals("Source domain/config cache must survive separate search sessions", 1, requests.count { it == "/config" })
        adapter.search("另一来源", ids[1])
        assertEquals("Another source ID must fetch its own configuration", 2, requests.count { it == "/config" })
    }

    private fun library(names: List<String>, scripted: Boolean = false) = ReaderSourceLibrary {
        JsonArray(names.map { name -> buildJsonObject {
            put("bookSourceName", name); put("bookSourceUrl", "https://$name.fixture.test")
            put("bookSourceType", 0); put("enabled", true)
            put("searchUrl", if (scripted) "@js:var v=source.get('domain'); if(!v){v=java.ajax(baseUrl+'/config');source.put('domain',v);} v+'/search?q='+key;" else "/search?q={{key}}")
            putJsonObject("ruleSearch") { put("bookList", ".book"); put("name", "a@text"); put("author", "b@text"); put("bookUrl", "a@href") }
            putJsonObject("ruleBookInfo") { put("name", "h1@text"); put("author", ".author@text"); put("tocUrl", ".toc@href") }
            putJsonObject("ruleToc") { put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href") }
            putJsonObject("ruleContent") { put("content", "#content@html") }
        } }).toString()
    }

    private fun client(body: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body(request).toResponseBody("text/html; charset=utf-8".toMediaType())).build()
    }.build()
}
