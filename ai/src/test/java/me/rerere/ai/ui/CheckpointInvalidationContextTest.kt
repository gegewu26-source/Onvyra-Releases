package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CheckpointInvalidationContextTest {
    private fun checkpoint(text: String, invalid: Boolean = false) = UIMessage.user(text).copy(
        isContextCheckpoint = true, contextCheckpointInvalidated = invalid,
    )

    @Test
    fun `old serialized checkpoint without invalidation flag remains valid`() {
        val message = Json.decodeFromString<UIMessage>("""{"role":"user","parts":[],"isContextCheckpoint":true}""")
        assertTrue(message.isContextCheckpoint)
        assertFalse(message.contextCheckpointInvalidated)
        assertEquals(0, listOf(message).activeContextCheckpointIndex())
    }

    @Test
    fun `invalidated marker survives serialization`() {
        val original = checkpoint("old summary", invalid = true)
        val decoded = Json.decodeFromString<UIMessage>(Json.encodeToString(original))
        assertTrue(decoded.contextCheckpointInvalidated)
        assertEquals(-1, listOf(decoded).activeContextCheckpointIndex())
    }

    @Test
    fun `invalidated latest checkpoint cannot hide corrected original history`() {
        val messages = listOf(UIMessage.user("corrected fact"), checkpoint("obsolete summary", true), UIMessage.user("latest request"))
        assertEquals(-1, messages.activeContextCheckpointIndex())
        assertEquals(listOf(messages[0], messages[2]), messages.limitContext(0))
    }

    @Test
    fun `latest valid checkpoint is used and all later invalid checkpoints are omitted`() {
        val messages = listOf(UIMessage.user("old raw fact"), checkpoint("valid summary"),
            UIMessage.user("recent fact"), checkpoint("invalid later summary", true), UIMessage.user("latest request"))
        assertEquals(1, messages.activeContextCheckpointIndex())
        assertEquals(listOf(messages[1], messages[2], messages[4]), messages.limitContext(0))
        assertTrue(messages.limitContext(1).none { it.isContextCheckpoint && it.contextCheckpointInvalidated })
        assertEquals(messages[1], messages.limitContext(1).first())
    }

    @Test
    fun `index helper returns raw list index without removing invalid placeholders`() {
        val messages = listOf(checkpoint("invalid", true), UIMessage.user("raw"), checkpoint("new valid"), UIMessage.user("latest"))
        assertEquals(2, messages.activeContextCheckpointIndex())
        assertEquals(listOf(messages[2], messages[3]), messages.limitContext(0))
        assertEquals(4, messages.size)
    }
}
