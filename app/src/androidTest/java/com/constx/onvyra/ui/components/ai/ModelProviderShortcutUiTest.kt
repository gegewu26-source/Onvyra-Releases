package com.constx.onvyra.ui.components.ai

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import com.constx.onvyra.Screen
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/** Uses synthetic provider IDs; no account settings, credentials, or network calls are changed. */
@RunWith(AndroidJUnit4::class)
class ModelProviderShortcutUiTest {
    @get:Rule val compose = createComposeRule()

    private fun provider(id: String, modelPrefix: String, models: Int = 12) = ProviderSetting.OpenAI(
        id = Uuid.parse(id),
        name = "WorkBuddy", // The same visible name must never determine the destination.
        models = (1..models).map {
            Model(modelId = "$modelPrefix-$it", displayName = "$modelPrefix-$it", type = ModelType.CHAT)
        },
    )

    @Test fun tapStillLocatesTheProviderWhileLongPressClosesTheSheetAndNavigatesByUuid() {
        val international = provider("a1d40be8-1241-4a8c-9942-b4f985c15301", "wb-intl")
        val china = provider("b1d40be8-1241-4a8c-9942-b4f985c15302", "wb-cn")
        val state = ModelListState(international.models.first().id, listOf(international, china), ModelType.CHAT)
        state.open()
        val selected = CopyOnWriteArrayList<Uuid>()
        val stack = mutableStateListOf<NavKey>(Screen.SettingModels)
        val navigator = Navigator(stack)
        compose.setContent {
            CompositionLocalProvider(LocalNavController provides navigator) {
                MaterialExpressiveTheme {
                    ModelListSheet(state = state, onSelect = { selected += it.id })
                }
            }
        }

        val shortcut = compose.onNodeWithTag("model-provider-shortcut-${china.id}")
        shortcut.assertIsDisplayed().assert(SemanticsMatcher("long press has a settings action label") {
            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "打开WorkBuddy的模型设置"
        })
        shortcut.performClick()
        compose.onNodeWithText("wb-cn-1").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(state.visible)
            assertEquals(listOf(Screen.SettingModels), stack.toList())
            assertTrue(selected.isEmpty())
            assertEquals(international.models.first().id, state.modelId)
        }

        shortcut.performTouchInput { longClick() }
        compose.waitUntil(5_000) { !state.visible && stack.lastOrNull() is Screen.SettingProviderDetail }
        compose.runOnIdle {
            assertEquals(Screen.SettingProviderDetail(china.id.toString(), openModels = true), stack.last())
            assertEquals(2, stack.size)
            assertTrue(selected.isEmpty())
            assertEquals(international.models.first().id, state.modelId)
        }
        compose.onNodeWithTag("model-provider-shortcut-${china.id}").assertDoesNotExist()
    }

    @Test fun customNavigationHostReceivesTheExactProviderOnlyAfterTheSheetIsClosed() {
        val provider = provider("c1d40be8-1241-4a8c-9942-b4f985c15303", "wb-custom", models = 1)
        val state = ModelListState(provider.models.single().id, listOf(provider), ModelType.CHAT)
        state.open()
        val opened = CopyOnWriteArrayList<Uuid>()
        val selected = CopyOnWriteArrayList<Uuid>()
        compose.setContent {
            // A callback host intentionally has no LocalNavController.
            MaterialExpressiveTheme {
                ModelListSheet(
                    state = state,
                    onOpenProviderSettings = { id ->
                        assertFalse("The modal must be closed before opening settings", state.visible)
                        opened += id
                    },
                    onSelect = { selected += it.id },
                )
            }
        }
        compose.onNodeWithTag("model-provider-shortcut-${provider.id}").performTouchInput { longClick() }
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf(provider.id), opened.toList())
            assertTrue(selected.isEmpty())
            assertEquals(provider.models.single().id, state.modelId)
        }
    }
}
