package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.netdisk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class NetDiskToolsTest {
    private class Fixture {
        val opened = mutableListOf<Pair<String, String>>()
        val resolved = mutableListOf<String>()
        val downloads = mutableListOf<NetDiskDownload>()
        var openFailure: Exception? = null
        val coordinator = NetDiskChatCoordinator(
            open = { text, password ->
                opened += text to password
                openFailure?.let { throw it }
                NetDiskSession(NetDiskShare(NetDiskPlatform.PAN123, "https://www.123pan.com/s/example", "example", password),
                    token = "private-provider-token")
            },
            list = { _, _, _ -> NetDiskListing(listOf(NetDiskFile("private-provider-file-id", "test.txt", size = 4))) },
            resolve = { _, file ->
                resolved += file.id
                NetDiskDownload("https://cdn.example.test/test.txt?signature=private-signature", file.name,
                    headers = mapOf("Authorization" to "private-authorization"))
            },
            enqueue = { link -> downloads += link; "download-${downloads.size}" },
            tasks = { emptyList() },
            destinationLabel = { "Download/Onvyra/网盘" },
        )
        fun tools(owner: String = "conversation-a") = createNetDiskTools(coordinator, owner).associateBy(Tool::name)
    }

    @Test
    fun `all three builtins are created without opening a share or starting a download`() {
        val fixture = Fixture()
        assertEquals(setOf(NETDISK_PARSE_TOOL, NETDISK_DOWNLOAD_TOOL, NETDISK_STATUS_TOOL), fixture.tools().keys)
        assertTrue(fixture.opened.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun `parsing forwards share text and extraction code without downloading`() = runBlocking {
        val fixture = Fixture()
        val shareText = "测试分享 https://www.123pan.com/s/example 提取码 abcd"
        val result = fixture.tools().getValue(NETDISK_PARSE_TOOL).payload(buildJsonObject {
            put("share_text", shareText); put("password", "abcd")
        })
        assertEquals(listOf(shareText to "abcd"), fixture.opened)
        assertEquals("parse", result.getValue("action").jsonPrimitive.content)
        assertTrue(result.containsKey("session_id"))
        assertTrue(fixture.resolved.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
        assertFalse(result.toString().contains("private-provider-token"))
        assertFalse(result.toString().contains("private-provider-file-id"))
    }

    @Test
    fun `a direct download request reaches the manager callback without a preceding parse`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.tools().getValue(NETDISK_DOWNLOAD_TOOL).payload(buildJsonObject {
            put("share_text", "https://www.123pan.com/s/example")
        })
        assertEquals(1, fixture.opened.size)
        assertEquals(listOf("private-provider-file-id"), fixture.resolved)
        assertEquals(1, fixture.downloads.size)
        assertEquals("download", result.getValue("action").jsonPrimitive.content)
        assertEquals("download-1", result.getValue("task_ids").jsonArray.single().jsonPrimitive.content)
        assertFalse(result.toString().contains("private-signature"))
        assertFalse(result.toString().contains("private-authorization"))
    }

    @Test
    fun `status is owner scoped and does not inspect a new share or enqueue anything`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.tools("another-conversation").getValue(NETDISK_STATUS_TOOL).payload(buildJsonObject {})
        assertEquals("status", result.getValue("action").jsonPrimitive.content)
        assertTrue(fixture.opened.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun `only an explicit boolean transfer opt in declares additional approval`() {
        val download = Fixture().tools().getValue(NETDISK_DOWNLOAD_TOOL)
        assertFalse(download.needsApproval(buildJsonObject {}))
        assertFalse(download.needsApproval(buildJsonObject { put("allow_transfer", false) }))
        assertFalse(download.needsApproval(buildJsonObject { put("allow_transfer", "true") }))
        assertTrue(download.needsApproval(buildJsonObject { put("allow_transfer", true) }))
    }

    @Test
    fun `guidance tells a tool capable model to download directly and distinguish submission from completion`() {
        val parse = Fixture().tools().getValue(NETDISK_PARSE_TOOL)
        val model = Model(abilities = listOf(ModelAbility.TOOL))
        val prompt = parse.systemPrompt(model, emptyList())
        assertTrue(prompt.contains("call netdisk_download directly"))
        assertTrue(prompt.contains("all files and subfolders"))
        assertTrue(prompt.contains("must not start a download"))
        assertTrue(prompt.contains("queued/submitted task is not a completed download"))
        assertTrue(prompt.contains("Never ask for account passwords or cookies"))
        assertEquals("", parse.systemPrompt(model.copy(abilities = emptyList()), emptyList()))
    }

    @Test
    fun `malformed arguments never reach providers or download callbacks`() = runBlocking {
        val fixture = Fixture()
        val tools = fixture.tools()
        val invalid = listOf(
            NETDISK_PARSE_TOOL to JsonNull,
            NETDISK_PARSE_TOOL to buildJsonObject { put("share_text", 123) },
            NETDISK_PARSE_TOOL to buildJsonObject { put("password", "x".repeat(65)) },
            NETDISK_DOWNLOAD_TOOL to buildJsonObject { put("allow_transfer", "true") },
            NETDISK_DOWNLOAD_TOOL to buildJsonObject { put("file_ids", JsonArray(listOf(JsonPrimitive(1)))) },
            NETDISK_STATUS_TOOL to buildJsonObject { put("task_ids", "download-1") },
            NETDISK_STATUS_TOOL to buildJsonObject { put("task_ids", JsonArray(List(201) { JsonPrimitive("task") })) },
        )
        invalid.forEach { (name, arguments) ->
            val result = tools.getValue(name).payload(arguments)
            assertEquals("invalid_arguments", result.getValue("error_code").jsonPrimitive.content)
        }
        assertTrue(fixture.opened.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun `cancellation propagates instead of reporting a successful or failed download`() = runBlocking {
        val fixture = Fixture().also { it.openFailure = CancellationException("stopped") }
        try {
            fixture.tools().getValue(NETDISK_DOWNLOAD_TOOL).payload(buildJsonObject {
                put("share_text", "https://www.123pan.com/s/example")
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(fixture.downloads.isEmpty())
        }
    }

    @Test
    fun `unexpected provider failures do not expose signed urls credentials or stack traces`() = runBlocking {
        val fixture = Fixture().also { it.openFailure = IllegalStateException("https://example.test/?token=private-upstream-token") }
        val result = fixture.tools().getValue(NETDISK_PARSE_TOOL).payload(buildJsonObject {
            put("share_text", "https://www.123pan.com/s/example")
        })
        assertEquals("error", result.getValue("status").jsonPrimitive.content)
        assertFalse(result.toString().contains("private-upstream-token"))
        assertFalse(result.toString().contains("IllegalStateException"))
    }

    private suspend fun Tool.payload(arguments: JsonElement): JsonObject =
        Json.parseToJsonElement((execute(arguments).single() as UIMessagePart.Text).text).jsonObject
}
