package com.constx.onvyra.ui.media

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.tools.createVideoResolveTool
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPreviewResolver
import com.constx.onvyra.data.model.MessageNode
import com.constx.onvyra.ui.components.message.ChatMessage
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.ui.context.LocalTTSState
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.hooks.CustomTtsState
import com.constx.onvyra.ui.theme.LocalDarkMode
import me.rerere.tts.model.PlaybackState
import okhttp3.OkHttpClient
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Full local path: built-in tool -> Android extractor -> cached result -> real chat -> decoder. */
@RunWith(AndroidJUnit4::class)
@Suppress("UnsafeOptInUsageError")
class ResolvedVideoPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun successfulBuiltinToolResultBecomesAPlayableWindowInTheActualAssistantMessage() = runBlocking {
        VideoFixtureServer().use { server ->
            val requestedUrl = "${server.url}/page"
            val resolver = VideoPreviewResolver(instrumentation.targetContext, OkHttpClient())
            val tool = createVideoResolveTool { url ->
                resolver.resolve(VideoLink(url, VideoPlatform.OTHER), enhanced = true)
            }
            val arguments = buildJsonObject { put("url", requestedUrl) }
            val output = tool.execute(arguments)
            val resultText = (output.single() as UIMessagePart.Text).text
            val result = Json.parseToJsonElement(resultText).jsonObject
            assertEquals("ready", result["status"]?.jsonPrimitive?.content)
            assertEquals("video_preview", result["type"]?.jsonPrimitive?.content)
            assertEquals(requestedUrl, result["original_url"]?.jsonPrimitive?.content)
            assertFalse("The model-facing tool result exposed a media URL", resultText.contains("/clip.mp4"))
            val extractionRequests = server.pageRequests.get()
            assertTrue("The tool did not fetch the real video page", extractionRequests > 0)

            val message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "local-video-resolve",
                        toolName = tool.name,
                        input = arguments.toString(),
                        output = output,
                    ),
                    UIMessagePart.Text("视频已经解析，可以在聊天中播放。"),
                ),
            )
            val node = MessageNode.of(message)
            val navigator = Navigator(mutableListOf())
            val shown = mutableStateOf(true)
            compose.setContent {
                CompositionLocalProvider(
                    LocalSettings provides Settings(),
                    LocalNavController provides navigator,
                    LocalDarkMode provides false,
                    LocalTTSState provides DisabledTts,
                ) {
                    MaterialExpressiveTheme {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            if (shown.value) ChatMessage(
                                node = node,
                                onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {},
                                onDelete = {}, onUpdate = {},
                            )
                        }
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("inline-video-preview").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("inline-video-preview").assertIsDisplayed()
            compose.waitForIdle()
            val nativeView = currentPlayerView()
            val player = readMain { requireNotNull(nativeView.player) as ExoPlayer }
            compose.waitUntil(20_000) { readMain { player.playbackState == Player.STATE_READY } }
            assertEquals("${server.url}/clip.mp4", readMain { player.currentMediaItem?.localConfiguration?.uri.toString() })
            assertFalse(readMain { player.playWhenReady })
            instrumentation.runOnMainSync { player.play() }
            compose.waitUntil(15_000) { readMain { player.isPlaying && player.currentPosition > 250 } }
            assertTrue("The actual chat player did not advance", readMain { player.currentPosition > 250 })
            assertEquals("Rendering the cached tool result unnecessarily extracted the page again",
                extractionRequests, server.pageRequests.get())
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
        }
    }

    private fun currentPlayerView(): PlayerView {
        val value = AtomicReference<PlayerView>()
        onView(allOf(isAssignableFrom(PlayerView::class.java), withTagValue(equalTo<Any>("inline-video-native-player"))))
            .perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
                override fun getDescription() = "inspect player rendered from the built-in video tool result"
                override fun perform(uiController: UiController, view: View) { value.set(view as PlayerView) }
            })
        return requireNotNull(value.get())
    }

    private fun <T> readMain(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    // Keep the real completed-message action row without initializing any user's TTS provider.
    private object DisabledTts : CustomTtsState {
        override val isAvailable = MutableStateFlow(false)
        override val isSpeaking = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(0)
        override val playbackState = MutableStateFlow(PlaybackState())
        override fun speak(text: String, flushCalled: Boolean) = Unit
        override fun stop() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() = Unit
    }
}
