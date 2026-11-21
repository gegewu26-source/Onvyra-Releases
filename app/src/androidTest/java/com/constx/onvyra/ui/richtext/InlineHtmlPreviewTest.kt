package com.constx.onvyra.ui.richtext

import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.ui.components.message.PreservedMessageTextContent
import com.constx.onvyra.ui.components.richtext.MarkdownBlock
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.theme.LocalDarkMode
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise the actual embedded browser, without a server, credentials, or device chat data. */
@RunWith(AndroidJUnit4::class)
class InlineHtmlPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun completedHtmlRunsInItsOriginalSlotAndKeepsBothSurroundingParagraphs() {
        showMarkdown(mutableStateOf(message(Html)))

        val browser = awaitPreview()
        assertParagraphOrder()
        compose.onNodeWithTag(PreviewTag).assertHeightIsEqualTo(200.dp)
        awaitJavaScript(browser, "document.title", "\"inline-demo\"")
        assertEquals("0", evaluateJavaScript(browser, "window.demoClicks"))

        // A real touch on the full-window demo button must run the generated HTML's handler.
        nativeBrowser().perform(click())
        awaitJavaScript(browser, "document.getElementById('count').textContent", "\"1\"")
        assertEquals("1", evaluateJavaScript(browser, "window.demoClicks"))
        assertParagraphOrder()
    }

    @Test
    fun completeUnfencedHtmlBetweenMarkdownParagraphsRunsInTheOriginalSlot() {
        showMarkdown(mutableStateOf("$Before\n\n$Html\n\n$After"))

        val browser = awaitPreview()
        compose.onNodeWithTag(PreviewTag).assertHeightIsEqualTo(200.dp)
        assertParagraphOrder()
        awaitJavaScript(browser, "document.title", "\"inline-demo\"")
        nativeBrowser().perform(click())
        awaitJavaScript(browser, "window.demoClicks", "1")
        assertParagraphOrder()
    }

    @Test
    fun selfContainedDataImageActuallyDecodesInTheInlineBrowser() {
        val html = Html.replace("</body>", "<img id=\"dataImage\" src=\"$DataImage\"></body>")
        showMarkdown(mutableStateOf(message(html)))

        val browser = awaitPreview()
        awaitJavaScript(
            browser,
            "(() => { const image = document.getElementById('dataImage'); return !!image && image.complete && image.naturalWidth === 16 && image.naturalHeight === 16; })()",
            "true",
        )
        assertParagraphOrder()
    }

    @Test
    fun streamingWaitsForClosingFenceAndAppendingTextDoesNotRestartTheDemo() {
        val content = mutableStateOf("$Before\n\n```html\n$Html")
        showMarkdown(content)
        compose.onNodeWithTag(PreviewTag).assertDoesNotExist()
        compose.onNodeWithContentDescription("Code").assertDoesNotExist()

        compose.runOnIdle { content.value += "\n```" }
        val browser = awaitPreview()
        nativeBrowser().perform(click())
        awaitJavaScript(browser, "window.demoClicks", "1")

        compose.runOnIdle { content.value += "\n\n$After" }
        awaitText(After)
        assertSame("Appending a later paragraph replaced the running browser", browser, currentBrowser())
        assertEquals("Appending a later paragraph reset the demo", "1", evaluateJavaScript(browser, "window.demoClicks"))
        assertParagraphOrder()
    }

    @Test
    fun finishingTheReplyPreservesTheBrowserAndItsScriptStateWhenSelectionBecomesAvailable() {
        val loading = mutableStateOf(true)
        showMarkdown(mutableStateOf(message(Html)), loading = loading)
        val browser = awaitPreview()
        val initialBounds = compose.onNodeWithTag(PreviewTag).fetchSemanticsNode().boundsInRoot
        nativeBrowser().perform(click())
        awaitJavaScript(browser, "window.demoClicks", "1")

        // This invokes the same wrapper as ChatMessage's transition from streaming to selectable text.
        compose.runOnIdle { loading.value = false }
        compose.waitForIdle()
        assertSame("Enabling text selection replaced the running browser", browser, currentBrowser())
        assertEquals("Enabling text selection reset the demo", "1", evaluateJavaScript(browser, "window.demoClicks"))
        assertEquals(initialBounds, compose.onNodeWithTag(PreviewTag).fetchSemanticsNode().boundsInRoot)
        assertParagraphOrder()

        nativeBrowser().perform(click())
        awaitJavaScript(browser, "window.demoClicks", "2")
    }

    @Test
    fun toolbarCanSwitchToSourceAndBackWithoutMovingTheCodeBlock() {
        showMarkdown(mutableStateOf(message(Html)))
        awaitPreview()
        val initialBefore = compose.onNodeWithText(Before).fetchSemanticsNode().boundsInRoot

        compose.onNodeWithContentDescription("Code").performClick()
        compose.onNodeWithTag(PreviewTag).assertDoesNotExist()
        compose.onNodeWithText("window.demoClicks", substring = true).assertIsDisplayed()
        assertEquals(initialBefore, compose.onNodeWithText(Before).fetchSemanticsNode().boundsInRoot)

        // The original toolbar also offers a separate full-page preview with the same label.
        // Its inline toggle is first, so this must re-open the browser in the same message.
        previewToggle().performClick()
        awaitPreview()
        assertParagraphOrder()
        assertEquals(initialBefore, compose.onNodeWithText(Before).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun disabledAutomaticPreviewLeavesHtmlAsSourceUntilInlinePreviewIsChosen() {
        showMarkdown(mutableStateOf(message(Html)), autoPreviewHtml = false)

        compose.onNodeWithTag(PreviewTag).assertDoesNotExist()
        compose.onNodeWithContentDescription("Code").assertDoesNotExist()
        compose.onNodeWithText("window.demoClicks", substring = true).assertIsDisplayed()

        previewToggle().performClick()
        val browser = awaitPreview()
        nativeBrowser().perform(click())
        awaitJavaScript(browser, "window.demoClicks", "1")
        assertParagraphOrder()
    }

    @Test
    fun htmlAliasesAndUnlabelledDocumentsPreviewButUnlabelledFragmentsStayAsCode() {
        val content = mutableStateOf(message(Html, language = "html"))
        showMarkdown(content)
        awaitPreview()

        listOf("htm", "html5", "xhtml", "").forEachIndexed { index, language ->
            val title = "inline-demo-$index"
            compose.runOnIdle { content.value = message(Html.replace("inline-demo", title), language) }
            val browser = awaitPreview()
            awaitJavaScript(browser, "document.title", "\"$title\"")
            assertParagraphOrder()
        }

        val fragmentMarker = "A fragment remains source"
        compose.runOnIdle { content.value = message("<button>$fragmentMarker</button>", language = "") }
        awaitText("<button>$fragmentMarker</button>", substring = true)
        compose.onNodeWithTag(PreviewTag).assertDoesNotExist()
    }

    private fun showMarkdown(
        content: MutableState<String>,
        autoPreviewHtml: Boolean = true,
        loading: MutableState<Boolean>? = null,
    ) {
        val navigator = Navigator(mutableListOf())
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides navigator,
                LocalDarkMode provides false,
            ) {
                MaterialExpressiveTheme {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        val markdown = @Composable {
                            MarkdownBlock(
                                content = content.value,
                                modifier = Modifier.fillMaxWidth(),
                                autoPreviewHtml = autoPreviewHtml,
                            )
                        }
                        if (loading == null) markdown() else {
                            PreservedMessageTextContent(loading = loading.value, content = markdown)
                        }
                    }
                }
            }
        }
    }

    private fun awaitPreview(): WebView {
        compose.waitUntil(timeoutMillis = 15_000L) {
            compose.onAllNodesWithTag(PreviewTag).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(PreviewTag).assertIsDisplayed()
        val browser = currentBrowser()
        awaitJavaScript(browser, "window.demoReady === true", "true")
        return browser
    }

    private fun assertParagraphOrder() {
        val before = compose.onNodeWithText(Before).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithTag(PreviewTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val after = compose.onNodeWithText(After).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The preceding paragraph moved below the preview", before.bottom <= preview.top)
        assertTrue("The following paragraph moved above the preview", preview.bottom <= after.top)
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 15_000L) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun previewToggle() = compose.onAllNodesWithContentDescription(
        context.getString(R.string.code_block_preview),
    )[0]

    private fun nativeBrowser() = onView(
        allOf(isAssignableFrom(WebView::class.java), withTagValue(equalTo<Any>(NativeTag))),
    )

    private fun currentBrowser(): WebView {
        val browser = AtomicReference<WebView>()
        nativeBrowser().perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
            override fun getDescription(): String = "inspect the real inline HTML browser"
            override fun perform(uiController: UiController, view: View) {
                browser.set(view as WebView)
            }
        })
        return requireNotNull(browser.get())
    }

    private fun awaitJavaScript(browser: WebView, script: String, expectedJson: String) {
        compose.waitUntil(timeoutMillis = 15_000L) {
            evaluateJavaScript(browser, script) == expectedJson
        }
    }

    private fun evaluateJavaScript(browser: WebView, script: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            browser.evaluateJavascript(script) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("The embedded browser did not answer: $script", completed.await(5, TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }

    private fun message(html: String, language: String = "html") =
        "$Before\n\n```$language\n$html\n```\n\n$After"

    private companion object {
        const val PreviewTag = "inline-html-preview"
        const val NativeTag = "inline-html-preview-webview"
        const val Before = "The paragraph before the HTML demo."
        const val After = "The paragraph after the HTML demo."
        const val DataImage = "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%2216%22%20height%3D%2216%22%3E%3Crect%20width%3D%2216%22%20height%3D%2216%22%20fill%3D%22green%22%2F%3E%3C%2Fsvg%3E"

        // One physical line also keeps the source-mode fixture within the phone-size viewport.
        const val Html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>inline-demo</title><style>body{margin:0}#advance{position:fixed;inset:0;width:100%;height:100%;border:0;background:#d9f5dd;color:#173d23;font:24px sans-serif}</style></head><body><button id=\"advance\" type=\"button\">HTML demo <span id=\"count\">0</span></button><script>window.demoClicks=0;document.getElementById('advance').onclick=function(){document.getElementById('count').textContent=String(++window.demoClicks)};window.demoReady=true;</script></body></html>"
    }
}
