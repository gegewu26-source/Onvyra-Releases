package com.constx.onvyra.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionAnswerRegistryTest {
    @Test
    fun `an answer submitted before await retains its payload and attachments`() = runTest {
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val answer = QuestionAnswer("{\"destination\":\"Shanghai\"}", listOf(UIMessagePart.Text("attachment")))

        assertTrue(registry.submit(run, "question", answer))
        assertEquals(answer, registry.await(run, "question"))
        registry.close(run)
    }

    @Test
    fun `duplicate answers keep the first result even after it was consumed`() = runTest {
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val first = QuestionAnswer("first")

        assertTrue(registry.submit(run, "question", first))
        assertFalse(registry.submit(run, "question", QuestionAnswer("double tap")))
        assertEquals(first, registry.await(run, "question"))
        assertFalse(registry.submit(run, "question", QuestionAnswer("late double tap")))
        assertEquals(first, registry.await(run, "question"))
        registry.close(run)
    }

    @Test
    fun `multiple questions accept answers independently and out of order`() = runTest {
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val first = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, "first") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, "second") }

        assertTrue(registry.submit(run, "second", QuestionAnswer("second answer")))
        assertEquals("second answer", second.await().payload)
        assertFalse(first.isCompleted)
        assertTrue(registry.submit(run, "first", QuestionAnswer("first answer")))
        assertEquals("first answer", first.await().payload)
        registry.close(run)
    }

    @Test
    fun `equal run tokens remain isolated by identity`() = runTest {
        data class RunToken(val value: Int)
        val registry = QuestionAnswerRegistry()
        val firstRun = RunToken(1)
        val secondRun = RunToken(1)
        assertEquals(firstRun, secondRun)
        assertTrue(registry.submit(firstRun, "question", QuestionAnswer("first run")))
        val second = async(start = CoroutineStart.UNDISPATCHED) { registry.await(secondRun, "question") }

        assertFalse(second.isCompleted)
        registry.close(firstRun)
        assertFalse(second.isCompleted)
        assertFalse(registry.submit(firstRun, "question", QuestionAnswer("stale first run")))
        assertTrue(registry.submit(secondRun, "question", QuestionAnswer("second run")))
        assertEquals("second run", second.await().payload)
        registry.close(secondRun)
    }

    @Test
    fun `a cancelled unanswered wait can be replaced without reusing its cancelled deferred`() = runTest {
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, "question") }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, "question") }

        assertFalse(replacement.isCompleted)
        assertTrue(registry.submit(run, "question", QuestionAnswer("replacement answer")))
        assertEquals("replacement answer", replacement.await().payload)
        registry.close(run)
    }

    @Test
    fun `an answer completed before waiter cancellation still rejects duplicate submissions`() = runTest {
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, "question") }
        val answer = QuestionAnswer("completed answer")

        assertTrue(registry.submit(run, "question", answer))
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertFalse(registry.submit(run, "question", QuestionAnswer("late double tap")))
        assertEquals(answer, registry.await(run, "question"))
        registry.close(run)
    }

    @Test
    fun `a late answer to a superseded question cannot answer the replacement question`() = runTest {
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val superseded = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, "old question") }
        superseded.cancelAndJoin()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, "new question") }

        assertTrue(registry.submit(run, "old question", QuestionAnswer("late old answer")))
        assertFalse(replacement.isCompleted)
        assertTrue(registry.submit(run, "new question", QuestionAnswer("new answer")))
        assertEquals("new answer", replacement.await().payload)
        registry.close(run)
    }

    @Test
    fun `close cancels every pending question of only its run and rejects late submissions`() = runTest {
        val registry = QuestionAnswerRegistry()
        val closedRun = Any()
        val activeRun = Any()
        val first = async(start = CoroutineStart.UNDISPATCHED) { registry.await(closedRun, "first") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { registry.await(closedRun, "second") }
        val active = async(start = CoroutineStart.UNDISPATCHED) { registry.await(activeRun, "first") }

        registry.close(closedRun)
        first.join()
        second.join()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertFalse(active.isCompleted)
        assertFalse(registry.submit(closedRun, "first", QuestionAnswer("late answer")))
        try {
            registry.await(closedRun, "another question")
            error("A closed run must not start waiting again")
        } catch (_: CancellationException) {
            // The run remains closed even when no earlier question has this call ID.
        }
        assertTrue(registry.submit(activeRun, "first", QuestionAnswer("still active")))
        assertEquals("still active", active.await().payload)
        registry.close(activeRun)
    }

    @Test fun `owner and input scoped registry keys do not reuse another questions accepted answer`() = runTest {
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val first = UIMessagePart.Tool("call_0", "ask_user", "first question")
        val second = first.copy(input = "second question")
        val firstKey = questionAnswerRegistryKey("owner-one", first)
        val secondKey = questionAnswerRegistryKey("owner-one", second)
        val nextOwner = questionAnswerRegistryKey("owner-two", first)
        assertFalse(firstKey == secondKey)
        assertFalse(firstKey == nextOwner)
        assertFalse(firstKey.contains(first.input))
        assertTrue(registry.submit(run, firstKey, QuestionAnswer("first")))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { registry.await(run, secondKey) }
        assertFalse(pending.isCompleted)
        assertTrue(registry.submit(run, secondKey, QuestionAnswer("second")))
        assertEquals("second", pending.await().payload)
        assertEquals("first", registry.await(run, firstKey).payload)
        registry.close(run)
    }

    @Test
    fun `clear cancels pending questions across runs and drops completed answers`() = runTest {
        val registry = QuestionAnswerRegistry()
        val firstRun = Any()
        val secondRun = Any()
        val first = async(start = CoroutineStart.UNDISPATCHED) { registry.await(firstRun, "pending") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { registry.await(secondRun, "pending") }
        assertTrue(registry.submit(firstRun, "completed", QuestionAnswer("old answer")))

        registry.clear()
        first.join()
        second.join()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(registry.submit(firstRun, "completed", QuestionAnswer("fresh answer")))
        assertEquals("fresh answer", registry.await(firstRun, "completed").payload)
        registry.clear()
    }
}
