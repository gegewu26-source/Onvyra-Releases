package com.constx.onvyra.ui.components.message

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MessageTranslationActionTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun firstChoiceIsReusedForOtherMessagesWithoutOpeningLanguageSelection() {
        val remembered = mutableStateOf<String?>(null)
        val message = mutableStateOf(1)
        val translations = mutableListOf<Locale>()
        compose.setContent {
            MaterialExpressiveTheme {
                key(message.value) {
                    MessageTranslationAction(
                        lastLanguageTag = remembered.value,
                        onRememberLanguage = { remembered.value = it },
                        onTranslate = { translations += it },
                        onClearTranslation = {},
                    )
                }
            }
        }

        translationButton().performClick()
        languageSelection().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.language_japanese)).performClick()
        compose.runOnIdle {
            assertEquals("ja", remembered.value)
            assertEquals(listOf(Locale.JAPANESE), translations)
            message.value = 2
        }

        translationButton().performClick()
        languageSelection().assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Locale.JAPANESE, Locale.JAPANESE), translations) }
    }

    @Test
    fun oneSecondLongPressChangesLanguageAndClearKeepsRememberedChoice() {
        val remembered = mutableStateOf<String?>("ja")
        val translations = mutableListOf<Locale>()
        var clearCount = 0
        compose.setContent {
            MaterialExpressiveTheme {
                MessageTranslationAction(
                    lastLanguageTag = remembered.value,
                    onRememberLanguage = { remembered.value = it },
                    onTranslate = { translations += it },
                    onClearTranslation = { clearCount++ },
                )
            }
        }

        // Longer than Android's usual long press, but shorter than this button's one-second threshold.
        translationButton().performTouchInput {
            down(center)
            advanceEventTime(800L)
            up()
        }
        languageSelection().assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Locale.JAPANESE), translations) }

        translationButton().performTouchInput { longClick(durationMillis = 1_100L) }
        languageSelection().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(Locale.JAPANESE), translations) }
        compose.onNodeWithText(context.getString(R.string.language_korean)).performClick()
        compose.runOnIdle {
            assertEquals("ko", remembered.value)
            assertEquals(listOf(Locale.JAPANESE, Locale.KOREAN), translations)
        }

        translationButton().performTouchInput { longClick(durationMillis = 1_100L) }
        compose.onNodeWithText(context.getString(R.string.translation_clear)).performClick()
        compose.runOnIdle {
            assertEquals(1, clearCount)
            assertEquals("ko", remembered.value)
        }
        translationButton().performClick()
        languageSelection().assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Locale.JAPANESE, Locale.KOREAN, Locale.KOREAN), translations) }
    }

    private fun translationButton() = compose.onNodeWithContentDescription(context.getString(R.string.translate))

    private fun languageSelection() = compose.onNodeWithText(context.getString(R.string.translation_language_selection_title))
}
