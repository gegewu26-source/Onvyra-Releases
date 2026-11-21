package com.constx.onvyra.ui.pages.chat

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.constx.onvyra.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationPrivacyGateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lockedContentIsNeverComposedAndLeavingDoesNotAuthenticate() {
        val chat = mutableStateOf(Conversation(assistantId = Uuid.random(), messageNodes = emptyList(), isLocked = true))
        var leaves = 0
        var sensitiveCompositions = 0
        compose.setContent {
            MaterialExpressiveTheme {
                if (!ConversationPrivacyGate(chat.value, onLeave = { leaves++ })) {
                    sensitiveCompositions++
                    Text("private-message-fixture")
                }
            }
        }
        compose.onNodeWithTag("conversation-locked").assertIsDisplayed()
        compose.onNodeWithText("private-message-fixture").assertDoesNotExist()
        compose.onNodeWithText("新建聊天").performClick()
        compose.runOnIdle { assertEquals(1, leaves); assertEquals(0, sensitiveCompositions) }
        compose.onNodeWithText("private-message-fixture").assertDoesNotExist()
        compose.runOnIdle { chat.value = chat.value.copy(isLocked = false) }
        compose.onNodeWithText("private-message-fixture").assertIsDisplayed()
    }
}
