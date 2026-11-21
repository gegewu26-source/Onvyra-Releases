package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import com.constx.onvyra.ui.context.Navigator
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderStoreUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bookstoreShowsSectionsPagesAndGenderWithoutSourceSelectorThenReadsMatchedWork() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"reader9-store-ui-${System.nanoTime()}").apply { mkdirs() }
        val context=object:ContextWrapper(base){override fun getFilesDir()=dir}
        val calls=CopyOnWriteArrayList<String>()
        val taxonomy=buildJsonObject { put("ok",true);put("firstTags",JsonArray(listOf("male","female").map { gender -> buildJsonObject {
            put("tagChannel","${gender}_v2");put("secondTags",JsonArray(listOf(buildJsonObject {
                put("secondTagName","热门");put("thirdTags",JsonArray(listOf("都市","玄幻","历史","军事","悬疑灵异","科幻","游戏体育","同人").mapIndexed { index,name ->
                    buildJsonObject {put("name",if(gender=="female") "女$name" else name);put("alias","$gender-$index")}
                }))
            })))
        } })) }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();val path=request.url.encodedPath
            calls+="${request.url.host}$path?${request.url.query}"
            assertNull(request.url.queryParameter("token"));assertNull(request.url.queryParameter("userid"))
            val body=when {
                path=="/tag/statics" -> taxonomy.toString()
                path=="/tag/fuzzy-search" -> {
                    val alias=request.url.queryParameter("alias")!!;val start=request.url.queryParameter("start")!!.toInt()
                    val limit=request.url.queryParameter("limit")!!.toInt()
                    buildJsonObject {put("ok",true);put("total",45);put("books",JsonArray((start until (start+limit).coerceAtMost(45)).map { n -> buildJsonObject {
                        put("_id","$alias-$n");put("title","作品$alias-$n");put("author","作者$n")
                        put("minorCate",request.url.queryParameter("query"));put("shortIntro","有简介、有封面、多分类的实际接口夹具。")
                    } }))}.toString()
                }
                request.url.host=="reader.fixture.test" && path=="/search" -> {
                    val title=request.url.queryParameter("q")!!
                    "<div class='book'><a href='/book/$title'>$title</a><span class='author'>作者0</span></div>"
                }
                request.url.host=="reader.fixture.test" && path.startsWith("/book/") ->
                    "<h1>${request.url.pathSegments.last()}</h1><p class='author'>作者0</p><div class='chapters'><a href='/chapter/1'>第一章</a></div>"
                request.url.host=="reader.fixture.test" && path=="/chapter/1" -> "<div id='content'><p>从书城自动查找匹配来源后阅读到的真实夹具正文。</p></div>"
                else -> throw IOException("Unexpected fixture route ${request.url.host}$path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody((if(request.url.host=="reader.fixture.test") "text/html; charset=utf-8" else "application/json; charset=utf-8").toMediaType())).build()
        }.build()
        val library=ReaderSourceLibrary { """[{"bookSourceName":"真实匹配来源","bookSourceUrl":"https://reader.fixture.test","enabled":true,"bookSourceType":0,"searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":".book","name":"a@text","author":".author@text","bookUrl":"a@href"},"ruleBookInfo":{"name":"h1@text","author":".author@text"},"ruleToc":{"chapterList":".chapters a","chapterName":"text","chapterUrl":"href"},"ruleContent":{"content":"#content@html"}}]""" }
        val vm=NovelViewModel(context,NovelSourceEngine(client,sourceLibrary=library),suppliedStoreRepository=ReaderStoreRepository(client,File(dir,"store")))
        lateinit var view: android.view.View
        var nativeBars=false
        try {
            compose.setContent { view=LocalView.current;NovelMainPage(Navigator(mutableStateListOf<NavKey>()),vm) }
            compose.onNodeWithText("书城").performClick()
            compose.waitUntil(10_000) { vm.storeHome.value?.sections?.size==8 && !vm.storeLoading.value }
            assertEquals(48,vm.storeHome.value!!.sections.sumOf { it.books.size })
            compose.onNodeWithText("切换 ›").assertDoesNotExist()
            compose.onNodeWithText("换一换").assertDoesNotExist()
            compose.onNodeWithTag("store-featured").assertIsDisplayed()
            compose.onNodeWithTag("store-channel-female").performClick()
            compose.waitUntil(10_000) { vm.storeHome.value?.channel=="female" && !vm.storeLoading.value }
            assertTrue(vm.storeHome.value!!.sections.all { it.category.channel=="female" })
            compose.onNodeWithTag("store-channel-male").performClick()
            compose.waitUntil(10_000) { vm.storeHome.value?.channel=="male" && !vm.storeLoading.value }
            compose.onNodeWithTag("store-home").performScrollToNode(hasTestTag("store-more-male-0"))
            compose.onNodeWithTag("store-more-male-0").performClick()
            compose.waitUntil(10_000) { vm.storeCategory.value?.books?.size==20 && vm.storeCategory.value?.loading==false }
            compose.onNodeWithTag("store-category-list").performScrollToNode(hasTestTag("store-load-more"))
            compose.onNodeWithTag("store-load-more").performClick()
            compose.waitUntil(10_000) { vm.storeCategory.value?.books?.size==40 && vm.storeCategory.value?.loading==false }
            assertEquals(40,vm.storeCategory.value!!.books.map { it.id }.distinct().size)
            assertTrue(calls.any { it.contains("start=20") })
            compose.onNodeWithTag("store-category-list").performScrollToIndex(0)
            compose.runOnIdle {
                val insets=ViewCompat.getRootWindowInsets(view)?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                nativeBars=insets!=null && (insets.top>0 || insets.bottom>0)
            }
            println("READER_SYSTEM_BARS_NATIVE=$nativeBars")
            val first=vm.storeCategory.value!!.books.first()
            compose.onNodeWithTag("store-book-${first.id}").performClick()
            compose.waitUntil(10_000) { vm.currentBook.value?.sourceId?.startsWith("legado_")==true && !vm.isLoadingChapter.value }
            compose.onNodeWithTag("reader-book-detail").assertIsDisplayed()
            compose.onNodeWithTag("detail-start-reading").performClick()
            compose.onNodeWithText("从书城自动查找匹配来源后阅读到的真实夹具正文。",substring=true).assertIsDisplayed()
            compose.onNodeWithTag("reader-current-chapter").assertTextEquals("第一章")
            if(nativeBars) compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.let { !it.isVisible(WindowInsetsCompat.Type.statusBars()) && !it.isVisible(WindowInsetsCompat.Type.navigationBars()) }==true }
            if(nativeBars) UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(base.filesDir,"reader-qa/reader9-immersive-reading.png").apply {parentFile?.mkdirs()})
            compose.onNodeWithTag("reader-body").performClick()
            compose.onNodeWithContentDescription("目录").assertIsDisplayed()
            if(nativeBars) compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.let { it.isVisible(WindowInsetsCompat.Type.statusBars()) && it.isVisible(WindowInsetsCompat.Type.navigationBars()) }==true }
            if(nativeBars) UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(base.filesDir,"reader-qa/reader9-reader-controls.png"))
            compose.onNodeWithContentDescription("目录").performClick()
            val drawer=compose.onNodeWithTag("reader-catalog-drawer").fetchSemanticsNode().boundsInRoot
            val screen=compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Compact catalog drawer should use at most 82 percent of the screen",drawer.width<=screen.width*.82f)
            val destination=File(base.filesDir,"reader-qa").apply {mkdirs()}
            File(destination,"reader9-compact-catalog.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
            compose.onNodeWithContentDescription("关闭目录").performClick()
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNodeWithTag("reader-current-chapter").assertDoesNotExist()
            if(nativeBars) compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(view)?.let { it.isVisible(WindowInsetsCompat.Type.statusBars()) && it.isVisible(WindowInsetsCompat.Type.navigationBars()) }==true }
            if(nativeBars) UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(base.filesDir,"reader-qa/reader9-after-reading.png"))
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                putString("reader_system_bars_verified",nativeBars.toString())
            })
        } finally {compose.runOnIdle {vm.viewModelScope.cancel()};dir.deleteRecursively()}
    }
}
