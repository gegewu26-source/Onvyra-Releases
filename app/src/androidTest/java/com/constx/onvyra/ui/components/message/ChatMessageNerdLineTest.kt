package com.constx.onvyra.ui.components.message

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.utils.toFixed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageNerdLineTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val message = UIMessage.assistant("A synthetic test answer").copy(
        createdAt = LocalDateTime(2026, 1, 1, 12, 0, 0),
        finishedAt = LocalDateTime(2026, 1, 1, 12, 0, 2),
        usage = TokenUsage(promptTokens = 100, completionTokens = 20, cachedTokens = 30, totalTokens = 120),
    )

    @Test
    fun providerUsageAndTimeAreRenderedAndSettingCanHideThem() {
        val settings = mutableStateOf(Settings())
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides settings.value) {
                MaterialExpressiveTheme { ChatMessageNerdLine(message = message) }
            }
        }
        val totalText = context.getString(R.string.chat_stats_total, 120L)
        compose.onNodeWithText(context.getString(R.string.chat_stats_input, 100)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_stats_output, 20)).assertIsDisplayed()
        compose.onNodeWithText(totalText).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_stats_cached, 30)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_stats_duration, 2.0.toFixed(1))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_stats_speed, 10.0.toFixed(1))).assertIsDisplayed()

        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showTokenUsage = false))
        }
        compose.onNodeWithText(totalText).assertDoesNotExist()
    }

    @Test
    fun missingProviderUsageStillShowsDurationWithoutInventingTokens() {
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialExpressiveTheme { ChatMessageNerdLine(message = message.copy(usage = null)) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.chat_stats_usage_missing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_stats_duration, 2.0.toFixed(1))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_stats_total, 120L)).assertDoesNotExist()
    }
}
