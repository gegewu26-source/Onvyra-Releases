package com.constx.onvyra.ui.components.message

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.ChatExtensionSelection
import com.constx.onvyra.data.model.QuickMessage
import com.constx.onvyra.ui.components.ui.ExtensionSelector
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatExtensionSelectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun queuedSelectionsApplyToLatestAssistantEvenBeforeTheSelectorReceivesANewSnapshot() {
        val first = QuickMessage(title = "Queued choice A", content = "A")
        val second = QuickMessage(title = "Queued choice B", content = "B")
        val initial = Assistant()
        val selections = mutableListOf<ChatExtensionSelection>()
        val pending = mutableListOf<(Assistant) -> Assistant>()
        compose.setContent {
            MaterialExpressiveTheme {
                // Deliberately keep the rendered snapshot unchanged while both callbacks queue.
                ExtensionSelector(
                    modifier = Modifier.fillMaxSize(),
                    assistant = initial,
                    settings = Settings(quickMessages = listOf(first, second)),
                    onUpdate = { selection, update ->
                        selections += selection
                        pending += update
                    },
                )
            }
        }

        compose.onNodeWithText(first.title).performClick()
        compose.onNodeWithText(second.title).performClick()
        compose.runOnIdle {
            val latest = initial.copy(enabledSkills = setOf("newer-skill"))
            val applied = pending.fold(latest) { assistant, update -> update(assistant) }
            assertEquals(listOf(ChatExtensionSelection.QUICK_MESSAGES, ChatExtensionSelection.QUICK_MESSAGES), selections)
            assertEquals(setOf(first.id, second.id), applied.quickMessageIds)
            assertEquals(latest.enabledSkills, applied.enabledSkills)
        }
    }
}
