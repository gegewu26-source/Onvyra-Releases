package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.download.NetDiskDownloadStatus
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NetDiskChatCoordinatorTest {
    private class Fixture {
        val calls = ArrayList<Pair<String, String?>>()
        val resolved = ArrayList<NetDiskFile>()
        val enqueued = ArrayList<NetDiskDownload>()
        val live = ArrayList<NetDiskDownloadTask>()
        var label = "同名目录"
        var destination = "tree-one"
        var listing: suspend (String, String?) -> NetDiskListing = { _, _ -> NetDiskListing(listOf(file("a"))) }
        var resolving: suspend (NetDiskSession, NetDiskFile) -> NetDiskDownload = { _, file ->
            NetDiskDownload("https://download.invalid/signed?secret=URL_SECRET", file.name,
                headers = mapOf("Authorization" to "CREDENTIAL_SECRET"))
        }
        val service = NetDiskChatCoordinator(
            open = { _, password -> NetDiskSession(NetDiskShare(NetDiskPlatform.PAN123,
                "https://123pan.com/s/SHARE_SECRET", "share", password), token = "TOKEN_SECRET",
                extra = buildJsonObject { put("private", "EXTRA_SECRET") }) },
            list = { _, directory, cursor -> calls.add(directory to cursor); listing(directory, cursor) },
            resolve = { session, file -> resolved.add(file); resolving(session, file) },
            enqueue = { link ->
                enqueued.add(link)
                "task-${enqueued.size}".also { id -> live.add(NetDiskDownloadTask(id, link.fileName,
                    destinationLabel = label, destinationTreeUri = destination)) }
            },
            tasks = { live.toList() }, destinationLabel = { label }, destinationKey = { destination },
        )
        suspend fun parse(owner: String = "chat-one") = service.parse(owner, "https://123pan.com/s/share")
    }

    @Test fun referencesAreOpaqueAndCursorsStayBoundToTheirDirectory() = runBlocking {
        val f = Fixture()
        f.listing = { directory, cursor ->
            when {
                directory == "raw-dir" -> NetDiskListing(emptyList())
                cursor == null -> NetDiskListing(listOf(file("raw-file"), file("raw-dir", true)), "RAW_CURSOR_SECRET")
                else -> NetDiskListing(listOf(file("second")))
            }
        }
        val parsed = f.parse()
        val session = parsed.text("session_id")
        val directory = parsed.array("files")[1].jsonObject.text("id")
        val cursor = parsed.text("next_cursor")
        assertNotEquals("raw-dir", directory)
        assertNotEquals("RAW_CURSOR_SECRET", cursor)
        assertFalse(parsed.toString().contains("SECRET"))
        val wrongDirectory = f.service.parse("chat-one", sessionId = session, directoryId = directory, cursor = cursor)
        assertEquals("invalid_cursor", wrongDirectory.text("error_code"))
        val more = f.service.parse("chat-one", sessionId = session, cursor = cursor)
        assertEquals("ready", more.text("status"))
        assertEquals("" to "RAW_CURSOR_SECRET", f.calls.last())
    }

    @Test fun sessionsFilesAndTaskOwnershipCannotCrossChats() = runBlocking {
        val f = Fixture()
        val parsed = f.parse()
        val id = parsed.text("session_id")
        val submitted = f.service.download("chat-one", sessionId = id)
        val taskId = submitted.array("task_ids").single().jsonPrimitive.content
        assertEquals("session_expired", f.service.parse("other", sessionId = id).text("error_code"))
        assertEquals("session_expired", f.service.download("other", sessionId = id).text("error_code"))
        val hidden = f.service.status("other", listOf(taskId))
        assertTrue(hidden.array("tasks").isEmpty())
        assertEquals(taskId, hidden.array("unknown_task_ids").single().jsonPrimitive.content)
        assertFalse(submitted.toString().contains("SECRET"))
    }

    @Test fun allFilesTraversePagesAndDirectoriesWithoutDuplicatingFilesOrCycles() = runBlocking {
        val f = Fixture()
        f.listing = { directory, cursor -> when {
            directory == "folder" -> NetDiskListing(listOf(file("a"), file("c"), file("", true)))
            cursor == null -> NetDiskListing(listOf(file("a"), file("folder", true)), "page-two")
            else -> NetDiskListing(listOf(file("b"), file("folder", true)))
        } }
        val result = f.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("submitted", result.text("status"))
        assertEquals(setOf("a", "b", "c"), f.resolved.map { it.id }.toSet())
        assertEquals(3, f.enqueued.size)
        assertEquals(listOf("" to null, "" to "page-two", "folder" to null), f.calls)
    }

    @Test fun selectedFileDoesNotListOrDownloadUnselectedSiblings() = runBlocking {
        val f = Fixture()
        f.listing = { _, _ -> NetDiskListing(listOf(file("a"), file("b"), file("folder", true))) }
        val parsed = f.parse()
        val selected = parsed.array("files")[1].jsonObject.text("id")
        val result = f.service.download("chat-one", sessionId = parsed.text("session_id"), fileIds = listOf(selected))
        assertEquals("submitted", result.text("status"))
        assertEquals(listOf("b"), f.resolved.map { it.id })
        assertEquals(1, f.calls.size)
    }

    @Test fun transferMustBeExplicitAndAllFailuresDoNotClaimSubmission() = runBlocking {
        val f = Fixture()
        f.resolving = { session, file ->
            if (session.extra["allow_transfer"]?.jsonPrimitive?.booleanOrNull != true)
                throw NetDiskException("transfer_required", "https://private.invalid TOKEN_SECRET")
            NetDiskDownload("https://download.invalid/file", file.name)
        }
        val parsed = f.parse()
        val blocked = f.service.download("chat-one", sessionId = parsed.text("session_id"))
        assertEquals("error", blocked.text("status"))
        assertEquals("transfer_required", blocked.text("error_code"))
        assertTrue(blocked.array("task_ids").isEmpty())
        assertFalse(blocked.text("message").contains("已提交"))
        assertFalse(blocked.toString().contains("TOKEN_SECRET"))
        val allowed = f.service.download("chat-one", sessionId = parsed.text("session_id"), allowTransfer = true)
        assertEquals(1, allowed.int("enqueued"))
    }

    @Test fun retryDeduplicatesRealTasksAndStatusReadsLiveProgress() = runBlocking {
        val f = Fixture()
        val id = f.parse().text("session_id")
        f.service.download("chat-one", sessionId = id)
        val retry = f.service.download("chat-one", sessionId = id)
        assertEquals(0, retry.int("enqueued")); assertEquals(1, retry.int("skipped"))
        assertEquals(1, f.resolved.size)
        f.live[0] = f.live[0].copy(status = NetDiskDownloadStatus.COMPLETED, downloadedBytes = 123, totalBytes = 123)
        val status = f.service.status("chat-one").array("tasks").single().jsonObject
        assertEquals("completed", status.text("status"))
        assertEquals(123L, status["downloaded_bytes"]?.jsonPrimitive?.long)
        assertFalse(status.containsKey("destinationTreeUri"))
        f.live.clear()
        val pending = f.service.status("chat-one").array("tasks").single().jsonObject
        assertEquals("pending", pending.text("status"))
        val deletedRetry = f.service.download("chat-one", sessionId = id)
        assertEquals(1, deletedRetry.int("enqueued"))
        assertEquals(2, f.enqueued.size)
    }

    @Test fun failedAndCancelledTasksMayBeResolvedAgain() = runBlocking {
        val f = Fixture()
        val id = f.parse().text("session_id")
        f.service.download("chat-one", sessionId = id)
        f.live[0] = f.live[0].copy(status = NetDiskDownloadStatus.FAILED)
        assertEquals(1, f.service.download("chat-one", sessionId = id).int("enqueued"))
        f.live[1] = f.live[1].copy(status = NetDiskDownloadStatus.CANCELLED)
        assertEquals(1, f.service.download("chat-one", sessionId = id).int("enqueued"))
        assertEquals(3, f.resolved.size)
    }

    @Test fun identicalLabelsInDifferentDirectoriesDoNotReuseTheOldTask() = runBlocking {
        val f = Fixture()
        val id = f.parse().text("session_id")
        f.service.download("chat-one", sessionId = id)
        f.destination = "tree-two"
        val changed = f.service.download("chat-one", sessionId = id)
        assertEquals(1, changed.int("enqueued"))
        assertEquals(2, f.enqueued.size)
        assertFalse(changed.toString().contains("tree-two"))
    }

    @Test fun directoryChangesWhileResolvingDoNotEnqueueToAnUnexpectedDestination() = runBlocking {
        val f = Fixture()
        f.resolving = { _, file -> f.destination = "changed"; NetDiskDownload("https://download.invalid/file", file.name) }
        val result = f.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("directory_changed", result.text("error_code"))
        assertTrue(f.enqueued.isEmpty())
    }

    @Test fun destinationAwareEnqueueReceivesTheCapturedKeyAndCanRejectALateChange() = runBlocking {
        var currentKey = "original-tree"
        var observedKey = ""
        var fallbackCalled = false
        val service = NetDiskChatCoordinator(
            open = { _, _ -> NetDiskSession(NetDiskShare(NetDiskPlatform.PAN123, "https://123pan.com/s/share", "share")) },
            list = { _, _, _ -> NetDiskListing(listOf(file("a"))) },
            resolve = { _, selected -> NetDiskDownload("https://download.invalid/file", selected.name) },
            enqueue = { fallbackCalled = true; "wrong-task" }, tasks = { emptyList() },
            destinationLabel = { "同名目录" }, destinationKey = { currentKey },
            enqueueWithDestination = { _, expected ->
                // Simulate a directory change while the real manager awaits its ledger initialization.
                currentKey = "different-tree"
                observedKey = expected
                if (expected != currentKey) throw NetDiskException("directory_changed", "changed")
                "unreachable-task"
            },
        )
        val result = service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("original-tree", observedKey)
        assertEquals("directory_changed", result.text("error_code"))
        assertFalse(fallbackCalled)
        assertTrue(service.status("chat-one").array("tasks").isEmpty())
    }

    @Test fun traversalFailureKeepsSuccessfulSubmissionsAndSanitizesUpstreamErrors() = runBlocking {
        val f = Fixture()
        f.listing = { directory, _ ->
            if (directory.isNotBlank()) throw NetDiskException("auth_required", "Cookie=TOKEN_SECRET https://private.invalid")
            NetDiskListing(listOf(file("a"), file("private-folder", true)))
        }
        val result = f.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("partial", result.text("status"))
        assertEquals(1, result.int("enqueued"))
        assertEquals("auth_required", result.array("errors").single().jsonObject.text("code"))
        assertFalse(result.toString().contains("TOKEN_SECRET"))
        assertFalse(result.toString().contains("private.invalid"))
    }

    @Test fun fileAndPageBudgetsAreExplicitInsteadOfClaimingAllFilesCompleted() = runBlocking {
        val files = Fixture()
        files.listing = { _, _ -> NetDiskListing((0..200).map { file("file-$it") }) }
        val limited = files.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("partial", limited.text("status")); assertEquals(200, limited.int("enqueued"))
        assertEquals("batch_file_limit", limited.array("errors").single().jsonObject.text("code"))
        val pages = Fixture()
        pages.listing = { _, cursor ->
            val page = cursor?.toInt() ?: 0
            NetDiskListing(listOf(file("file-$page")), (page + 1).toString())
        }
        val pageLimit = pages.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("partial", pageLimit.text("status")); assertEquals(100, pages.calls.size)
        assertEquals("page_limit", pageLimit.array("errors").single().jsonObject.text("code"))

        val hiddenCap = Fixture()
        hiddenCap.listing = { _, cursor ->
            val page = cursor?.toInt() ?: 0
            NetDiskListing(listOf(file("file-$page")), (page + 1).toString().takeIf { page < 99 })
        }
        val capped = hiddenCap.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("partial", capped.text("status"))
        assertEquals("page_limit", capped.array("errors").single().jsonObject.text("code"))
    }

    @Test fun repeatedCursorAndExcessiveDepthCannotLoopForever() = runBlocking {
        val cycle = Fixture()
        cycle.listing = { _, _ -> NetDiskListing(listOf(file("a")), "same-cursor") }
        val cycled = cycle.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals(2, cycle.calls.size)
        assertEquals("pagination_cycle", cycled.array("errors").single().jsonObject.text("code"))
        val deep = Fixture()
        deep.listing = { directory, _ ->
            val depth = directory.removePrefix("d").toIntOrNull() ?: 0
            NetDiskListing(listOf(file("a"), file("d${depth + 1}", true)))
        }
        val depthLimit = deep.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals(33, deep.calls.size)
        assertEquals("depth_limit", depthLimit.array("errors").single().jsonObject.text("code"))
        assertEquals(1, deep.enqueued.size)
    }

    @Test fun providerDirectDownloadLimitIsDifferentFromTheBatchBudget() = runBlocking {
        val f = Fixture()
        f.resolving = { _, _ -> throw NetDiskException("file_limit", "23018 private upstream content") }
        val result = f.service.download("chat-one", shareText = "https://123pan.com/s/share")
        assertEquals("file_limit", result.text("error_code"))
        assertTrue(result.text("message").contains("登录"))
        assertFalse(result.text("message").contains("200"))
        assertTrue(f.enqueued.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutPretendingAFileWasSubmitted() = runBlocking {
        val f = Fixture()
        f.resolving = { _, _ -> throw CancellationException("cancelled") }
        try {
            f.service.download("chat-one", shareText = "https://123pan.com/s/share")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(f.enqueued.isEmpty())
        assertTrue(f.service.status("chat-one").array("tasks").isEmpty())
    }

    @Test fun lruEvictionExpiresOnlyTheOldReferenceAndUnexpectedErrorsAreSafe() = runBlocking {
        val f = Fixture()
        val first = f.parse().text("session_id")
        repeat(32) { f.parse() }
        assertEquals("session_expired", f.service.parse("chat-one", sessionId = first).text("error_code"))
        f.listing = { _, _ -> throw IllegalStateException("Authorization=TOKEN_SECRET https://private.invalid") }
        val result = f.parse()
        assertEquals("operation_failed", result.text("error_code"))
        assertFalse(result.toString().contains("SECRET"))
        assertFalse(result.toString().contains("private.invalid"))
    }

    companion object {
        private fun file(id: String, directory: Boolean = false) = NetDiskFile(id, "$id.txt", directory, 3)
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
        private fun JsonObject.array(key: String) = getValue(key).jsonArray
    }
}
