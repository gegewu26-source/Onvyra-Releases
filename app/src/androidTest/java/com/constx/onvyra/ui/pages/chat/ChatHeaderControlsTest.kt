package com.constx.onvyra.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.constx.onvyra.data.model.TemporaryChatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHeaderControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyAndPopulatedChatsShowTheirOwnNavigationActions() {
        val populated = mutableStateOf(false)
        var temporaryToggles = 0
        var newChats = 0
        compose.setContent {
            MaterialExpressiveTheme {
                ChatHeaderActions(populated.value, TemporaryChatMode.OFF, "测试聊天", false, false, false,
                    onTemporaryToggle = { temporaryToggles++ }, onNewChat = { newChats++ }, onAction = {})
            }
        }
        compose.onNodeWithTag("chat-temporary-toggle").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, temporaryToggles) }
        compose.onNodeWithTag("chat-new-conversation").assertDoesNotExist()
        compose.onNodeWithTag("chat-more-actions").assertDoesNotExist()
        compose.runOnIdle { populated.value = true }
        compose.onNodeWithTag("chat-temporary-toggle").assertDoesNotExist()
        compose.onNodeWithTag("chat-new-conversation").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-more-actions").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, newChats) }
    }

    @Test fun everyNormalChatMenuActionCallsItsRealParentHandlerAndCloses() {
        val actions = mutableListOf<ChatHeaderAction>()
        compose.setContent {
            MaterialExpressiveTheme {
                ChatHeaderActions(true, TemporaryChatMode.OFF, "测试聊天", true, true, true,
                    onTemporaryToggle = {}, onNewChat = {}, onAction = { actions += it })
            }
        }
        for (action in ChatHeaderAction.entries) {
            compose.onNodeWithTag("chat-more-actions").performClick()
            compose.onNodeWithTag("chat-actions-menu").assertIsDisplayed()
            compose.onNodeWithTag("chat-action-${action.name}").performScrollTo().performClick()
            compose.onNodeWithTag("chat-actions-menu").assertDoesNotExist()
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        compose.runOnIdle { assertEquals(ChatHeaderAction.entries.toList(), actions) }
        compose.onNodeWithTag("chat-more-actions").performClick()
        compose.onNodeWithText("取消置顶").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("解除聊天锁定").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消归档").performScrollTo().assertIsDisplayed()
    }

    @Test fun temporaryChatsOnlyExposeTheirApplicableActions() {
        compose.setContent {
            MaterialExpressiveTheme {
                ChatHeaderActions(true, TemporaryChatMode.NON_PERSONALIZED, "临时聊天", false, false, false,
                    onTemporaryToggle = {}, onNewChat = {}, onAction = {})
            }
        }
        compose.onNodeWithTag("chat-more-actions").performClick()
        for (action in listOf(ChatHeaderAction.SHARE, ChatHeaderAction.FILES, ChatHeaderAction.SEARCH, ChatHeaderAction.DELETE)) {
            compose.onNodeWithTag("chat-action-${action.name}").assertIsDisplayed()
        }
        for (action in listOf(ChatHeaderAction.PIN, ChatHeaderAction.LOCK, ChatHeaderAction.PROJECT,
            ChatHeaderAction.SHORTCUT, ChatHeaderAction.ARCHIVE)) {
            compose.onNodeWithTag("chat-action-${action.name}").assertDoesNotExist()
        }
    }

    @Test fun personalizationDraftOnlyCommitsOnDoneAndBackDiscardsIt() {
        val visible = mutableStateOf(true)
        val mode = mutableStateOf(TemporaryChatMode.NON_PERSONALIZED)
        val selections = mutableListOf<TemporaryChatMode>()
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { visible.value = true }, modifier = Modifier.testTag("reopen-temporary-settings")) {
                        Text("打开临时聊天设置")
                    }
                }
                if (visible.value) TemporaryChatSettingsSheet(mode.value,
                    onSelect = { selections += it; mode.value = it }, onDismiss = { visible.value = false })
            }
        }
        compose.onNodeWithTag("temporary-chat-option-NON_PERSONALIZED").assertIsSelected()
        compose.onNodeWithTag("temporary-chat-option-PERSONALIZED").performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("temporary-chat-settings-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(TemporaryChatMode.NON_PERSONALIZED, mode.value) }
        compose.onNodeWithTag("reopen-temporary-settings").performClick()
        compose.onNodeWithTag("temporary-chat-option-NON_PERSONALIZED").assertIsSelected()
        compose.onNodeWithTag("temporary-chat-option-PERSONALIZED").performClick()
        compose.onNodeWithTag("temporary-chat-settings-done").performClick()
        compose.onNodeWithTag("temporary-chat-settings-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(TemporaryChatMode.PERSONALIZED), selections) }
    }
}
