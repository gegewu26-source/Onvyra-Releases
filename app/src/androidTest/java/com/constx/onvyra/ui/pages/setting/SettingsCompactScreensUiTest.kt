package com.constx.onvyra.ui.pages.setting

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import com.constx.onvyra.Screen
import com.constx.onvyra.R
import com.constx.onvyra.data.ai.mcp.McpCommonOptions
import com.constx.onvyra.data.ai.mcp.McpServerConfig
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.ui.context.LocalTTSState
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.pages.assistant.AssistantPage
import com.constx.onvyra.ui.theme.ColorMode
import com.constx.onvyra.ui.theme.OnvyraTheme
import com.constx.onvyra.ui.hooks.CustomTtsState
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.model.PlaybackState
import me.rerere.ui.toast.rememberToasterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.java.KoinJavaComponent.getKoin

/** Real landing pages, real callbacks and persistent settings; dedicated emulator only. */
class SettingsCompactScreensUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sixCompactServicePagesPreserveSearchSelectionEditingAndTabDestinations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "CodexReaderBarsQA")
        val store = getKoin().get<SettingsStore>()
        val original = runBlocking { store.awaitLoaded() }
        val assistantA = Assistant(name = "QA 主助手")
        val assistantB = Assistant(name = "QA 备用助手")
        val speech = TTSProviderSetting.OpenAI(name = "QA 语音", baseUrl = "http://127.0.0.1:9/v1")
        val systemSpeech = TTSProviderSetting.SystemTTS(name = "系统 TTS")
        val tts = SilentTts()
        val media = MediaGenerationProviderSetting.OpenAI(name = "QA 图片生成", baseUrl = "http://127.0.0.1:9/v1")
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "QA MCP", enable = false), url = "http://127.0.0.1:9/mcp")
        val fixture = original.copy(assistants = listOf(assistantA, assistantB), assistantTags = emptyList(),
            assistantId = assistantA.id, searchServices = listOf(SearchServiceOptions.DoubaoOptions(),
                SearchServiceOptions.GrokOptions(), SearchServiceOptions.BingLocalOptions()), searchServiceSelected = 0,
            mediaGenerationProviders = listOf(media), ttsProviders = listOf(systemSpeech, speech), selectedTTSProviderId = systemSpeech.id,
            mcpServers = listOf(server), webServerEnabled = false, webServerPort = 18080,
            webServerLocalhostOnly = true, webServerJwtEnabled = false)
        val page = mutableIntStateOf(0)
        val stack = mutableStateListOf<NavKey>(Screen.Setting)
        val navigator = Navigator(stack)
        val chineseResources = instrumentation.targetContext.createConfigurationContext(
            android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply {
                setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            }).resources
        try {
            runBlocking { store.update { fixture } }
            // SettingsStore intentionally restores built-in assistants/providers.
            // Compare to that normalized baseline, not to a pre-normalization fixture.
            val storedFixture = runBlocking { store.awaitLoaded() }
            compose.setContent {
                val settings by store.settingsFlow.collectAsState()
                CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides navigator,
                    LocalToaster provides rememberToasterState(), LocalTTSState provides tts,
                    LocalResources provides chineseResources) {
                    OnvyraTheme(colorMode = ColorMode.LIGHT) {
                        when (page.intValue) {
                            0 -> AssistantPage()
                            1 -> SettingSearchPage()
                            2 -> SettingMediaPage()
                            3 -> SettingSpeechPage()
                            4 -> SettingWebPage()
                            5 -> SettingMcpPage()
                            6 -> SettingPage()
                            7 -> SettingAboutPage()
                            else -> SettingDonationPage()
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(assistantA.name).fetchSemanticsNodes().isNotEmpty() }
            capture("assistant")
            compose.onNodeWithTag("assistant-search-input").performTextInput("备用")
            compose.onNodeWithText(assistantA.name).assertDoesNotExist()
            compose.onNodeWithText(assistantB.name).assertIsDisplayed()
            compose.onNodeWithTag("assistant-search-input").performTextClearance()
            device.pressBack() // Dismiss the keyboard before switching the mocked navigation destination.
            compose.waitForIdle()
            compose.onNodeWithText(assistantA.name).performClick()
            compose.runOnIdle { assertTrue(stack.last() is Screen.AssistantDetail); stack.clear(); stack.add(Screen.Setting); page.intValue = 1 }
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("搜索提供商").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("RikkaHub").assertDoesNotExist()
            capture("search")
            compose.onNodeWithTag("search-result-count").performTextReplacement("12")
            compose.waitUntil(5_000) { store.settingsFlow.value.searchCommonOptions.resultSize == 12 }
            compose.runOnIdle { page.intValue = 2 }
            compose.onNodeWithText(media.name).assertIsDisplayed()
            capture("media")
            compose.onNodeWithText("素材存储").performClick()
            compose.onNodeWithText("图像服务").assertDoesNotExist()
            compose.runOnIdle { page.intValue = 3 }
            compose.onNodeWithText(speech.name).assertIsDisplayed()
            capture("speech")
            compose.onNodeWithText(chineseResources.getString(R.string.test_tts)).performClick()
            compose.runOnIdle { assertEquals(1, tts.speakCalls) }
            compose.onNodeWithText("语音识别").performClick()
            compose.onNodeWithText(speech.name).assertDoesNotExist()
            compose.runOnIdle { page.intValue = 4 }
            compose.onNodeWithText("18080").assertIsDisplayed()
            capture("web")
            compose.runOnIdle { page.intValue = 5 }
            compose.onNodeWithText(server.commonOptions.name).assertIsDisplayed()
            compose.onNodeWithText("已添加的服务器").assertIsDisplayed()
            capture("mcp")
            compose.runOnIdle { page.intValue = 6 }
            compose.onNodeWithTag("settings-page-list").performScrollToNode(hasText("赞赏"))
            compose.onNodeWithText("赞赏").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(Screen.SettingDonate, stack.last()); page.intValue = 8 }
            compose.onNodeWithTag("donation-page").assertIsDisplayed()
            compose.onNodeWithTag("donation-author-support").assertExists()
            compose.onNodeWithTag("support-qr-WECHAT").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("support-qr-ALIPAY").assertIsDisplayed()
            // The clickable QR card merges its image semantics. Inspect its child
            // in the unmerged tree, just as the dedicated support-dialog test does.
            for (payment in listOf("WECHAT", "ALIPAY")) {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("support-qr-loaded-$payment", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            capture("donation")
            compose.onNodeWithTag("support-qr-WECHAT").performClick()
            compose.onNodeWithTag("support-image-dialog").assertIsDisplayed()
            compose.onNodeWithTag("support-image-close").performClick()
            compose.runOnIdle { page.intValue = 7 }
            compose.onNodeWithTag("donation-author-support").assertDoesNotExist()
            compose.onNodeWithTag("about-author-support").assertDoesNotExist()
            compose.onNodeWithText("☕ 请作者喝杯咖啡").assertDoesNotExist()
            capture("about")
            val actual = runBlocking { store.awaitLoaded() }
            assertTrue("Provider configuration is preserved", storedFixture.providers == actual.providers)
            assertTrue("Media configuration is preserved", storedFixture.mediaGenerationProviders == actual.mediaGenerationProviders)
            assertTrue("MCP configuration is preserved", storedFixture.mcpServers == actual.mcpServers)
            assertEquals(storedFixture.webServerPort, actual.webServerPort)
            assertTrue("Web password is preserved", storedFixture.webServerAccessPassword == actual.webServerAccessPassword)
            assertTrue("Assistant configuration is preserved", storedFixture.assistants == actual.assistants)
        } catch (failure: Throwable) {
            runCatching {
                capture("failure")
                File(instrumentation.targetContext.filesDir, "reader-qa/reader18-ui-failure.txt")
                    .writeText(compose.onRoot().printToString())
            }
            throw failure
        } finally { runBlocking { store.update { original } } }
    }

    private class SilentTts : CustomTtsState {
        var speakCalls = 0
        override val isAvailable = MutableStateFlow(true)
        override val isSpeaking = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(0)
        override val playbackState = MutableStateFlow(PlaybackState())
        override fun speak(text: String, flushCalled: Boolean) { speakCalls++ }
        override fun stop() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() = Unit
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reader-qa").apply { mkdirs() }
        File(directory, "reader18-compact-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
