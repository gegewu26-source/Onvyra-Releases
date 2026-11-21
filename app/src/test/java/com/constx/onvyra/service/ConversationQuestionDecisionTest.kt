package com.constx.onvyra.service

import com.constx.onvyra.data.model.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationQuestionDecisionTest {
    @Test fun `stopping a run rejects stale answers and a new request can ask again`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
            val session = ConversationSession(conversation.id, conversation, scope, {})
            session.setJob(Job())
            assertTrue(session.canAcceptToolDecision())
            session.cancelGenerationRun(session.generationRunToken())
            assertFalse(session.canAcceptToolDecision())
            session.setJob(Job())
            assertTrue(session.canAcceptToolDecision())
            session.cleanup()
        } finally { scope.cancel() }
    }
}
