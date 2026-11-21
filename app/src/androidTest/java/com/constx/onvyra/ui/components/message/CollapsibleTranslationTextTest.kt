package com.constx.onvyra.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.ui.context.LocalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Verify the real translation card's rendering, independently of any network/provider. */
@RunWith(AndroidJUnit4::class)
class CollapsibleTranslationTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pendingWithoutAnOldTranslationKeepsTheSurroundingParagraphsStill() {
        val content = mutableStateOf("")
        val pending = mutableStateOf(true)
        showTranslation(content = content, pending = pending)

        val loading = context.getString(R.string.translating)
        compose.onAllNodesWithText(loading).assertCountEquals(1)
        compose.onNodeWithText(loading).assertIsDisplayed()
        compose.onNodeWithText(Before).assertIsDisplayed()
        compose.onNodeWithText(After).assertIsDisplayed()
        val loadingBounds = compose.onNodeWithText(loading).fetchSemanticsNode().boundsInRoot
        val afterBounds = compose.onNodeWithText(After).fetchSemanticsNode().boundsInRoot

        // The spinner may animate; neither the loading text nor its layout should pulse/reflow.
        compose.mainClock.autoAdvance = false
        repeat(6) {
            compose.mainClock.advanceTimeBy(320L)
            compose.waitForIdle()
            assertEquals(loadingBounds, compose.onNodeWithText(loading).fetchSemanticsNode().boundsInRoot)
            assertEquals(afterBounds, compose.onNodeWithText(After).fetchSemanticsNode().boundsInRoot)
            compose.onAllNodesWithText(loading).assertCountEquals(1)
        }
        compose.mainClock.autoAdvance = true

        // A legitimate result equal to the old loading sentinel is text, not a stuck spinner.
        compose.runOnIdle {
            content.value = loading
            pending.value = false
        }
        compose.onNodeWithText(loading).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test
    fun failureBeforeAnyResultShowsItsReasonInsteadOfRemovingTheTranslationSection() {
        val pending = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        showTranslation(pending = pending, error = error)
        compose.onNodeWithText(context.getString(R.string.translating)).assertIsDisplayed()

        compose.runOnIdle {
            pending.value = false
            error.value = Failure
        }

        compose.onNodeWithText(context.getString(R.string.translation_text)).assertIsDisplayed()
        compose.onNodeWithText(Failure, substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translating)).assertDoesNotExist()
        compose.onNodeWithText(Before).assertIsDisplayed()
        compose.onNodeWithText(After).assertIsDisplayed()
    }

    @Test
    fun retryAndFailureKeepThePreviouslyCompletedTranslationVisible() {
        val content = mutableStateOf("旧译文第一段，不能被重新翻译过程清空。\n\n旧译文第二段，保留完整结果。")
        val pending = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        showTranslation(content = content, pending = pending, error = error)
        assertOldTranslationVisible()

        compose.runOnIdle { pending.value = true }
        compose.onNodeWithText(context.getString(R.string.translating)).assertIsDisplayed()
        assertOldTranslationVisible()

        compose.runOnIdle {
            pending.value = false
            error.value = Failure
        }
        compose.onNodeWithText(Failure, substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translating)).assertDoesNotExist()
        assertOldTranslationVisible()
    }

    @Test
    fun completedLongTranslationSettlesAndStillSupportsManualCollapseAndExpand() {
        val content = mutableStateOf("")
        val pending = mutableStateOf(true)
        val measuredHeight = AtomicInteger()
        val sizeChanges = AtomicInteger()
        val paragraphs = (1..60).map { index ->
            "第${index}段完整译文：长文本只在翻译完成后出现，段落内容保持清楚，页面不应反复刷新。"
        }
        showTranslation(
            content = content,
            pending = pending,
            measuredHeight = measuredHeight,
            sizeChanges = sizeChanges,
        )
        val pendingHeight = measuredHeight.get()

        compose.runOnIdle {
            content.value = paragraphs.joinToString("\n\n")
            pending.value = false
        }
        // Wait for actual asynchronous Markdown parsing and text composition to finish.
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText(paragraphs.last()).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.translating)).assertDoesNotExist()
        assertTrue("Completed translation must occupy its full paragraph layout", measuredHeight.get() > pendingHeight)

        compose.mainClock.autoAdvance = false
        val completedHeight = measuredHeight.get()
        val completedSizeChanges = sizeChanges.get()
        repeat(6) {
            compose.mainClock.advanceTimeBy(160L)
            compose.waitForIdle()
            assertEquals("A completed translation must stop changing height", completedHeight, measuredHeight.get())
            assertEquals("No repeated layout animation after completion", completedSizeChanges, sizeChanges.get())
        }
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription(context.getString(R.string.collapse_translation))
            .performScrollTo().performClick()
        compose.onNodeWithText(paragraphs.first()).assertDoesNotExist()
        compose.onNodeWithText(paragraphs.last()).assertDoesNotExist()
        compose.onNodeWithText(After).assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.expand_translation)).performClick()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText(paragraphs.last()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText(paragraphs.first()).assertIsDisplayed()
        compose.onNodeWithText(paragraphs.last()).performScrollTo().assertIsDisplayed()
    }

    private fun assertOldTranslationVisible() {
        compose.onNodeWithText("旧译文第一段，不能被重新翻译过程清空。").assertIsDisplayed()
        compose.onNodeWithText("旧译文第二段，保留完整结果。").assertIsDisplayed()
    }

    private fun showTranslation(
        content: MutableState<String> = mutableStateOf(""),
        pending: MutableState<Boolean> = mutableStateOf(false),
        error: MutableState<String?> = mutableStateOf(null),
        measuredHeight: AtomicInteger = AtomicInteger(),
        sizeChanges: AtomicInteger = AtomicInteger(),
    ) {
        compose.setContent {
            MaterialExpressiveTheme {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Column(Modifier.onSizeChanged {
                            measuredHeight.set(it.height)
                            sizeChanges.incrementAndGet()
                        }) {
                            Text(Before)
                            CollapsibleTranslationText(
                                content = content.value,
                                onClickCitation = {},
                                isTranslating = pending.value,
                                errorMessage = error.value,
                            )
                            Text(After)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val Before = "翻译上方的原段落"
        const val After = "翻译下方的原段落"
        const val Failure = "测试连接中断，请重试"
    }
}
