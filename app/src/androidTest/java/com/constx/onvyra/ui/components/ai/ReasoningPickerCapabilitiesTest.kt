package com.constx.onvyra.ui.components.ai

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.reasoningCapabilities
import com.constx.onvyra.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReasoningPickerCapabilitiesTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://reasoning-fixture.invalid/v1")

    @Test
    fun deepseekAliasShowsSupportedStepsAndOffIsAnExplicitSelection() {
        val selected = mutableStateOf(ReasoningLevel.MEDIUM)
        val model = Model(modelId = "deepseekv4.1f")
        val capabilities = reasoningCapabilities(model, provider)
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.LOW,
            ReasoningLevel.HIGH, ReasoningLevel.MAX), capabilities.supportedLevels)
        compose.setContent {
            MaterialExpressiveTheme {
                ReasoningPicker(selected.value, capabilities, onUpdateReasoningLevel = { selected.value = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.reasoning_heavy)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.reasoning_picker_mapped_hint,
            context.getString(R.string.reasoning_medium), context.getString(R.string.reasoning_heavy),
            context.getString(R.string.reasoning_picker_supported_hint))).assertIsDisplayed()
        val slider = slider()
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(4f, range.range.endInclusive)
        assertEquals(3, range.steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(ReasoningLevel.OFF, selected.value) }
        compose.onNodeWithText(context.getString(R.string.reasoning_off)).assertIsDisplayed()
    }

    @Test
    fun geminiWithNoOffShowsLightInsteadOfAnUnusableOffSetting() {
        val selected = mutableStateOf(ReasoningLevel.OFF)
        val capabilities = reasoningCapabilities(Model(modelId = "gemini-3.8-flash"), provider)
        assertFalse(capabilities.supportsOff)
        compose.setContent {
            MaterialExpressiveTheme {
                ReasoningPicker(selected.value, capabilities, onUpdateReasoningLevel = { selected.value = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.reasoning_light)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.reasoning_off)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.reasoning_picker_no_off_hint), substring = true)
            .assertIsDisplayed()
        val range = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals((capabilities.supportedLevels.size - 1).toFloat(), range.range.endInclusive)
    }

    @Test
    fun switchingModelsWhileOpenUpdatesTheSliderRangeAndItsEffectiveValue() {
        val model = mutableStateOf(Model(modelId = "gpt-6-luna"))
        val selected = mutableStateOf(ReasoningLevel.MAX)
        compose.setContent {
            MaterialExpressiveTheme {
                ReasoningPicker(selected.value, reasoningCapabilities(model.value, provider),
                    onUpdateReasoningLevel = { selected.value = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.reasoning_max)).assertIsDisplayed()
        assertEquals(6f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive)
        compose.runOnIdle { model.value = Model(modelId = "gemini-3.8-flash") }
        val range = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(3f, range.range.endInclusive)
        assertEquals(3f, range.current)
        compose.onNodeWithText(context.getString(R.string.reasoning_heavy)).assertIsDisplayed()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals(ReasoningLevel.LOW, selected.value) }
    }

    @Test
    fun opaqueAliasesDiscloseThatTheGatewayMustSupportTheParameters() {
        val capabilities = reasoningCapabilities(Model(modelId = "private-model-alias"), provider)
        assertFalse(capabilities.isKnown)
        compose.setContent {
            MaterialExpressiveTheme {
                ReasoningPicker(ReasoningLevel.LOW, capabilities, onUpdateReasoningLevel = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.reasoning_picker_unknown_hint)).assertIsDisplayed()
    }

    @Test
    fun autoExplainsThatItUsesTheApiAndCustomModelDefaults() {
        val capabilities = reasoningCapabilities(Model(modelId = "deepseekv4.1f"), provider)
        compose.setContent {
            MaterialExpressiveTheme {
                ReasoningPicker(ReasoningLevel.AUTO, capabilities, onUpdateReasoningLevel = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.reasoning_picker_auto_hint,
            context.getString(R.string.reasoning_picker_supported_hint))).assertIsDisplayed()
    }

    @Test
    fun toggleOnlyGlmDoesNotPretendThatOnIsASeparateHighDepthLevel() {
        val selected = mutableStateOf(ReasoningLevel.HIGH)
        val capabilities = reasoningCapabilities(Model(modelId = "glm-5.1"), provider)
        assertEquals(true, capabilities.isToggleOnly)
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH), capabilities.supportedLevels)
        compose.setContent {
            MaterialExpressiveTheme {
                ReasoningPicker(selected.value, capabilities, onUpdateReasoningLevel = { selected.value = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.reasoning_on)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.reasoning_heavy)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.reasoning_picker_toggle_hint)).assertIsDisplayed()
        assertEquals(2f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive)
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(ReasoningLevel.OFF, selected.value) }
    }

    private fun slider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
}
