package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import com.constx.onvyra.ui.context.Navigator
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ReaderStoreLiveUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun referencePublicStoreHasManySectionsNewPagesVisibleImagesAndReadableMatchedBook() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_live")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="CodexReaderQA")
        val base=instrumentation.targetContext
        val dir=File(base.cacheDir,"reader9-live-store-${System.nanoTime()}").apply {mkdirs()}
        val context=object:ContextWrapper(base){override fun getFilesDir()=dir}
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().url.queryParameter("token"));assertNull(chain.request().url.queryParameter("userid"))
            check(chain.request().url.host !in setOf("biquge.skin","www.biquge.skin","apibi.cc"))
            chain.proceed(chain.request())
        }.build()
        val sources=ReaderSourceLibrary {base.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use {it.readText()}}
        val engine=NovelSourceEngine(client,sourceLibrary=sources)
        val repository=ReaderStoreRepository(client,File(dir,"store"))
        val evidence=mutableListOf<JsonObject>()
        val home=runBlocking { withTimeout(55_000){repository.home("male",true)} }
        assertTrue("Expected full male genre catalog",home.sections.size>=8)
        assertTrue(home.sections.sumOf {it.books.size}>=48)
        assertTrue(home.sections.flatMap {it.books}.map {it.id}.distinct().size>=35)
        evidence+=buildJsonObject {put("channel","male");put("sections",home.sections.size);put("books",home.sections.sumOf {it.books.size});put("unique",home.sections.flatMap {it.books}.map {it.id}.distinct().size);put("names",JsonArray(home.sections.map {JsonPrimitive(it.category.title)}))}
        val female=runBlocking {withTimeout(45_000){repository.home("female",true)}}
        assertTrue(female.sections.size>=5 && female.sections.sumOf {it.books.size}>=30)
        evidence+=buildJsonObject {put("channel","female");put("sections",female.sections.size);put("books",female.sections.sumOf {it.books.size})}
        val category=home.sections.first().category
        val pages=runBlocking {withTimeout(30_000){listOf(repository.category(category,1),repository.category(category,2))}}
        assertEquals(listOf(20,20),pages.map {it.books.size})
        assertEquals(40,pages.flatMap {it.books}.map {it.id}.distinct().size)
        evidence+=buildJsonObject {put("category",category.title);put("pages",2);put("unique_books",40);put("has_more",pages.last().hasMore)}
        val vm=NovelViewModel(context,engine,suppliedStoreRepository=repository)
        fun capture(name:String) {val folder=File(base.filesDir,"reader-qa").apply {mkdirs()};File(folder,name).outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}}
        try {
            compose.setContent {NovelMainPage(Navigator(mutableStateListOf<NavKey>()),vm)}
            compose.onNodeWithText("书城").performClick()
            compose.waitUntil(10_000){vm.storeHome.value?.sections?.size==home.sections.size}
            compose.onNodeWithText("切换 ›").assertDoesNotExist()
            compose.waitUntil(40_000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"图片已加载")).fetchSemanticsNodes().size>=3
            }
            capture("reader9-real-store-top.png")
            compose.onNodeWithTag("store-home").performScrollToNode(hasTestTag("store-section-${home.sections[3].category.id}"))
            capture("reader9-real-store-sections.png")
            compose.onNodeWithTag("store-home").performScrollToIndex(0)
            compose.onNodeWithTag("store-channel-female").performClick()
            compose.waitUntil(10_000){vm.storeHome.value?.channel=="female"}
            capture("reader9-real-store-female.png")
            // A representative book actually returned by this catalog must find
            // an exact title/author source and return readable first-chapter text.
            val work=home.sections.flatMap {it.books}.firstOrNull {it.title=="仙逆"}
                ?: home.sections.flatMap {it.books}.first {it.title=="完美世界"}
            val actual=runBlocking {withTimeout(20_000){engine.resolveCatalogBook(work)}}
            assertTrue(actual.sourceId.startsWith("legado_"));assertTrue(ReaderBookIdentity.matches(work,actual))
            val details=runBlocking {engine.getBookDetail(actual)}
            val chapters=runBlocking {engine.getCatalog(details.detailUrl,details.sourceId)}
            val text=runBlocking {engine.getChapterContent(chapters.first().url,details.sourceId)}
            assertTrue(chapters.size>100 && text.length>500)
            evidence+=buildJsonObject {put("reading_title",work.title);put("matched_source",details.sourceId);put("catalog",chapters.size);put("first_chapter",chapters.first().title);put("body_characters",text.length)}
            File(base.filesDir,"reader-qa/reader9-live-store-checks.json").writeText(JsonArray(evidence).toString())
        } finally {compose.runOnIdle {vm.viewModelScope.cancel()};dir.deleteRecursively()}
    }
}
