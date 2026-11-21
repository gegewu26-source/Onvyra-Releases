package com.constx.onvyra.ui.pages.novel

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verify source assets and the interpreted JS bridge on Android's runtime. */
class ReaderAndroidCompatibilityTest {
    @Test fun bundledSourcesAndJavascriptWorkOnAndroid(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = ReaderSourceLibrary {
            context.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
        }
        val sources = library.all()
        assertEquals(489, sources.size)
        assertEquals(mapOf(0 to 442, 1 to 6, 2 to 41), sources.groupingBy { it.type }.eachCount())
        val runtime = LegadoRuleRuntime()
        assertEquals("标题", runtime.string("<h1>标题</h1>", "@js:java.getString('h1@text')", "https://example.org/"))
        assertEquals("https://example.org/search?page=2&key=keyword", runtime.template("",
            "https://example.org/search?page={{page+1}}&key={{key}}", "https://example.org/",
            mapOf("page" to "1", "key" to "keyword")))
        assertEquals("2", runtime.string("", "@js:JSON.parse('{\"value\":2}').value", "https://example.org/"))
        runtime.string("<h1>书名</h1>", "@put:{n:'h1@text'}", "https://example.org/")
        assertEquals("书名", runtime.string("", "@get:{n}", "https://example.org/"))
    }
}
