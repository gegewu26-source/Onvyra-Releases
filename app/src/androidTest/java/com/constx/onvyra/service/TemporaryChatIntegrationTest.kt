package com.constx.onvyra.service

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.ReasoningLevel
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.db.AppDatabase
import com.constx.onvyra.data.model.TemporaryChatMode
import com.constx.onvyra.data.model.toMessageNode
import com.constx.onvyra.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid

/** Real ChatService + Room, no provider API, root permission or system setting changes. */
@RunWith(AndroidJUnit4::class)
class TemporaryChatIntegrationTest {
    @Test fun temporaryMessageAndLateSavesNeverReachRoomOrHistoryAndLeavingDiscardsTheSession() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("StatsUsageTest", androidx.test.uiautomator.UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation()).executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        val koin = getKoin()
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val db = koin.get<AppDatabase>()
        val settings = koin.get<SettingsStore>()
        settings.awaitLoaded()
        val id = Uuid.random()
        service.addConversationReference(id)
        try {
            service.initializeConversation(id)
            service.setTemporaryChatMode(id, TemporaryChatMode.NON_PERSONALIZED)
            assertEquals(TemporaryChatMode.NON_PERSONALIZED, service.getTemporaryChatModeFlow(id).value)
            assertTrue(service.getConversationFlow(id).value.currentMessages.isEmpty())
            val assistantsBefore = settings.settingsFlow.value.assistants
            service.updateChatAssistant(id) { it.copy(reasoningLevel = ReasoningLevel.HIGH, enabledSkills = setOf("temporary-only")) }
            assertEquals("temporary controls cannot overwrite assistant defaults", assistantsBefore, settings.settingsFlow.value.assistants)
            assertEquals(ReasoningLevel.HIGH, service.getConversationFlow(id).value.config!!.reasoningLevel)
            service.sendMessage(id, listOf(UIMessagePart.Text("temporary-private-fixture")), answer = false)
            withTimeout(10_000) {
                while (service.getConversationFlow(id).value.currentMessages.none { it.toText() == "temporary-private-fixture" } ||
                    service.getGenerationJobStateFlow(id).first() != null) delay(30)
            }
            val snapshot = service.getConversationFlow(id).value
            assertNull(repository.getConversationById(id))
            assertFalse(repository.existsConversationById(id))
            assertNull(db.conversationDao().getConversationById(id.toString()))
            assertFalse(repository.getConversationsOfAssistant(snapshot.assistantId).first().any { it.id == id })
            repository.insertConversation(snapshot)
            repository.updateConversation(snapshot)
            assertNull(db.conversationDao().getConversationById(id.toString()))
            val modeError = runCatching { service.setTemporaryChatMode(id, TemporaryChatMode.OFF) }.exceptionOrNull()
            assertTrue(modeError is IllegalStateException)
            service.leaveTemporaryChat(id)
            withTimeout(10_000) { while (service.getConversationFlow(id).value.messageNodes.isNotEmpty()) delay(20) }
            service.saveConversation(id, snapshot.copy(messageNodes = snapshot.messageNodes + UIMessage.user("late callback").toMessageNode()))
            repository.insertConversation(snapshot)
            assertNull(db.conversationDao().getConversationById(id.toString()))
            assertTrue(service.getConversationFlow(id).value.messageNodes.isEmpty())
            assertTrue(temporaryChatRegistry.isClosed(id))
            println("TEMPORARY_CHAT_ROOM_LIFECYCLE=PASS; provider_calls=0; private_history_rows=0")
        } finally {
            service.leaveTemporaryChat(id)
            service.removeConversationReference(id)
        }
    }
}
