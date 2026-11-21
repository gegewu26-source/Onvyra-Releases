package com.constx.onvyra.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatImageTaskRunnerTest {
    private val gif = MediaGenerationOutput(data = "GIF89a-two-frame-fixture".toByteArray(), mimeType = "image/gif")
    private fun task(status: MediaGenerationStatus, outputs: List<MediaGenerationOutput> = emptyList()) =
        MediaGenerationTask("same-task", "fixture", "selected-model", status, outputs)

    @Test fun asynchronousTaskIsCreatedOnceAndReturnsOriginalImageBytesToTheChatSaver() = runBlocking {
        var creates = 0
        var queries = 0
        val result = runChatImageTask(create = { creates++; task(MediaGenerationStatus.QUEUED) },
            query = { id -> assertEquals("same-task", id); queries++; task(MediaGenerationStatus.SUCCEEDED, listOf(gif)) },
            save = { output -> assertSame(gif.data, output.data); assertEquals("image/gif", output.mimeType)
                UIMessagePart.Image("file:///upload/fixture.gif") }, pollMillis = 0)
        assertEquals(1, creates)
        assertEquals(1, queries)
        assertEquals("file:///upload/fixture.gif", (result.last() as UIMessagePart.Image).url)
        assertFalse(result.toString().contains("session_id"))
        assertFalse(result.toString().contains("record_id"))
    }

    @Test fun synchronousResultDoesNotPollAndFailureDoesNotSaveOrResubmit() = runBlocking {
        runChatImageTask(create = { task(MediaGenerationStatus.SUCCEEDED, listOf(gif)) },
            query = { error("No polling") }, save = { UIMessagePart.Image("file:///fixture.gif") })
        var created = 0
        val error = runCatching { runChatImageTask(create = { created++; task(MediaGenerationStatus.FAILED) },
            query = { error("No polling") }, save = { error("Must not save failed output") }) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(1, created)
    }

    @Test fun cancellationPropagatesAndNeverSavesAPartialResult() = runBlocking {
        val cancellation = CancellationException("fixture cancellation")
        val error = runCatching { runChatImageTask(create = { task(MediaGenerationStatus.RUNNING) },
            query = { throw cancellation }, save = { error("Must not save") }, pollMillis = 0) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(cancellation.message, error?.message)
    }
}
