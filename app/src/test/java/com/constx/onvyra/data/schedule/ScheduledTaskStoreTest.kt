package com.constx.onvyra.data.schedule

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import com.constx.onvyra.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScheduledTaskStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.newFolder(), "tasks.json")
    private fun task(at: Long = 1_000) = ScheduledTask(
        id = "task-id", title = "任务", assistantId = "assistant", prompt = "test",
        firstRunAtMillis = at, nextRunAtMillis = at, zoneId = "UTC",
    )
    private suspend fun ScheduledTaskStore.put(task: ScheduledTask) = update { listOf(task) to Unit }

    @Test fun privateJsonSurvivesRecreationWithoutChangingSettingsFiles() = runBlocking {
        val target = file()
        val original = task()
        ScheduledTaskStore(target) { 0 }.put(original)
        val reopened = ScheduledTaskStore(target) { 0 }
        reopened.awaitLoaded()
        assertEquals(listOf(original), reopened.tasks.value)
        assertEquals(listOf("tasks.json"), target.parentFile!!.list()!!.toList())
    }

    @Test fun simultaneousDuplicateDeliveriesClaimOnlyOnce() = runBlocking {
        val store = ScheduledTaskStore(file()) { 1_000 }
        val original = task()
        store.put(original)
        val claimed = coroutineScope {
            List(20) { async { store.claim(original.id, original.revision, 1_000) } }.awaitAll()
        }.filterNotNull()
        assertEquals(1, claimed.size)
        assertEquals(ScheduledTaskStatus.RUNNING, store.tasks.value.single().lastStatus)
        assertNull(store.claim(original.id, original.revision, 1_000))
    }

    @Test fun successfulOneTimeRunFinishesOnlyAfterExplicitCompletionAndDisablesItself() = runBlocking {
        val store = ScheduledTaskStore(file()) { 1_001 }
        store.put(task())
        val claimed = store.claim("task-id", 1, 1_000)!!
        assertEquals(ScheduledTaskStatus.RUNNING, store.tasks.value.single().lastStatus)
        val result = store.finish(claimed, true, "saved", claimed.lastConversationId)!!
        assertEquals(ScheduledTaskStatus.SUCCEEDED, result.lastStatus)
        assertFalse(result.enabled)
        assertNull(result.nextRunAtMillis)
        assertEquals(claimed.lastConversationId, result.lastConversationId)
    }

    @Test fun recurringFailureAdvancesOnceWithoutRetryingTheFailedPrompt() = runBlocking {
        val store = ScheduledTaskStore(file()) { 2_000 }
        store.put(task().copy(repeat = ScheduledTaskRepeat.DAILY))
        val claimed = store.claim("task-id", 1, 1_000)!!
        val result = store.finish(claimed, false, "model failed", null)!!
        assertTrue(result.enabled)
        assertEquals(86_401_000L, result.nextRunAtMillis)
        assertEquals(ScheduledTaskStatus.FAILED, result.lastStatus)
        assertNull(store.claim("task-id", 1, 1_000))
    }

    @Test fun editOrDisableInvalidatesOldWorkersAndCannotBeOverwrittenByTheirCompletion() = runBlocking {
        val store = ScheduledTaskStore(file()) { 1_001 }
        store.put(task())
        val claimed = store.claim("task-id", 1, 1_000)!!
        val edited = claimed.copy(revision = 2, prompt = "new prompt", enabled = false, lastStatus = ScheduledTaskStatus.IDLE)
        store.put(edited)
        assertNull(store.finish(claimed, true, "late result", claimed.lastConversationId))
        assertNull(store.claim("task-id", 1, 1_000))
        assertEquals(edited, store.tasks.value.single())
    }

    @Test fun deletedTaskCannotBeResurrectedByWorkerCompletion() = runBlocking {
        val store = ScheduledTaskStore(file()) { 1_001 }
        store.put(task())
        val claimed = store.claim("task-id", 1, 1_000)!!
        store.update { emptyList<ScheduledTask>() to Unit }
        assertNull(store.finish(claimed, true, "late result", claimed.lastConversationId))
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test fun processDeathKeepsClaimTokenAndFailsOneTimeRunWithoutResubmitting() = runBlocking {
        val target = file()
        val original = ScheduledTaskStore(target) { 1_000 }
        original.put(task())
        val claimed = original.claim("task-id", 1, 1_000)!!
        val restored = ScheduledTaskStore(target) { 2_000 }
        restored.awaitLoaded()
        val result = restored.tasks.value.single()
        assertEquals(ScheduledTaskStatus.FAILED, result.lastStatus)
        assertEquals(claimed.lastRunToken, result.lastRunToken)
        assertFalse(result.enabled)
        assertNull(restored.claim("task-id", 1, 1_000))
    }

    @Test fun interruptedRecurringRunRestoresTheNextFutureOccurrence() = runBlocking {
        val target = file()
        val original = ScheduledTaskStore(target) { 1_000 }
        original.put(task().copy(repeat = ScheduledTaskRepeat.DAILY))
        original.claim("task-id", 1, 1_000)
        val restored = ScheduledTaskStore(target) { 86_402_000 }
        restored.awaitLoaded()
        val result = restored.tasks.value.single()
        assertTrue(result.enabled)
        assertEquals(172_801_000L, result.nextRunAtMillis)
        assertEquals(ScheduledTaskStatus.FAILED, result.lastStatus)
    }

    @Test fun corruptJsonIsPreservedAndFurtherWritesAreBlocked() = runBlocking {
        val target = file().apply { writeText("invalid json") }
        val store = ScheduledTaskStore(target)
        store.awaitLoaded()
        assertNotNull(store.error.value)
        val attempted = runCatching { store.put(task()) }
        assertTrue(attempted.isFailure)
        assertEquals("invalid json", target.readText())
    }
}
