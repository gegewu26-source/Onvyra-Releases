package com.constx.onvyra.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class GenerationProgressTest {
    @Test
    fun `stopping while UI is suspended retains completed effects from buffered chunks`() = runTest {
        val progress = GenerationProgress()
        val firstReceived = CompletableDeferred<Unit>()
        val outcomeBuffered = CompletableDeferred<Unit>()
        val proposed = UIMessagePart.Tool("published", "publish", "{}")
        val initial = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(proposed)))
        val completed = proposed.copy(output = listOf(UIMessagePart.Text("published successfully")))
        val next = UIMessagePart.Tool("unstarted", "delete", "{}")
        val latest = listOf(initial.single().copy(parts = listOf(completed, next)))
        var shown = emptyList<UIMessage>()
        val generation = launch {
            channelFlow {
                progress.record(initial)
                send(initial)
                firstReceived.await()
                progress.record(latest)
                send(latest)
                outcomeBuffered.complete(Unit)
                awaitCancellation()
            }.collect {
                shown = it
                firstReceived.complete(Unit)
                awaitCancellation() // UI has not received the completed effect yet.
            }
        }
        outcomeBuffered.await()
        assertFalse(shown.single().getTools().first().isExecuted)
        generation.cancelAndJoin()
        val saved = progress.interruptedMessages()!!.single().getTools()
        assertEquals(completed, saved.first())
        assertTrue(saved.last().isExecuted)
        assertFalse(saved.last().canResumeExecution)
        assertTrue(saved.last().output.single().toString().contains("cancelled"))
    }
}
