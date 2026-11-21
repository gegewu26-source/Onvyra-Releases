package com.constx.onvyra.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolResultTest {
    private fun json(value: String) = Json.parseToJsonElement(value)

    private suspend fun CallToolResult.convert(): List<UIMessagePart> = toMessageParts {
        error("Unexpected image conversion")
    }

    private fun List<UIMessagePart>.firstJson(): JsonObject =
        json((first() as UIMessagePart.Text).text).jsonObject

    private fun assertCallDoesNotResume(output: List<UIMessagePart>) {
        val tool = UIMessagePart.Tool("completed-call", "mcp__server__tool", "{}", output)
        assertTrue("A completed MCP call must have an execution result", tool.isExecuted)
        assertFalse("A completed MCP call must not be automatically executed again", tool.canResumeExecution)
    }

    @Test
    fun `structured-only result reaches the model and cannot be executed again`() = runTest {
        val structured = json("""{"status":"running","id":"job-123"}""")
        val output = CallToolResult(content = emptyList(), structuredContent = structured).convert()

        assertEquals(structured, output.firstJson())
        assertCallDoesNotResume(output)
    }

    @Test
    fun `empty success marks call completed without claiming task success`() = runTest {
        val output = CallToolResult(content = emptyList()).convert()

        assertTrue(output.firstJson().getValue("tool_call_completed").jsonPrimitive.boolean)
        assertTrue(output.firstJson().getValue("empty_result").jsonPrimitive.boolean)
        assertTrue((output.first() as UIMessagePart.Text).text.contains("does not confirm"))
        assertCallDoesNotResume(output)
    }

    @Test
    fun `blank text result also has a meaningful completion result`() = runTest {
        val output = CallToolResult(content = listOf(TextContent(""))).convert()

        assertTrue(output.firstJson().getValue("empty_result").jsonPrimitive.boolean)
        assertCallDoesNotResume(output)
    }

    @Test
    fun `existing text result is preserved without duplicating structured content`() = runTest {
        val text = """{"status":"finished","exit_code":0}"""
        val output = CallToolResult(content = listOf(TextContent(text)), structuredContent = json(text)).convert()

        assertEquals(listOf(UIMessagePart.Text(text)), output)
        assertCallDoesNotResume(output)
    }

    @Test
    fun `MCP error flag is explicit while original error details are retained`() = runTest {
        val detail = "Authentication failed"
        val output = CallToolResult(content = listOf(TextContent(detail)), isError = true).convert()

        assertTrue(output.firstJson().getValue("tool_error").jsonPrimitive.boolean)
        assertEquals(UIMessagePart.Text(detail), output[1])
        assertCallDoesNotResume(output)
    }

    @Test
    fun `structured-only error keeps both error state and structured details`() = runTest {
        val structured = json("""{"reason":"job_missing","id":"job-123"}""")
        val output = CallToolResult(content = emptyList(), isError = true, structuredContent = structured).convert()

        assertTrue(output.firstJson().getValue("tool_error").jsonPrimitive.boolean)
        assertEquals(structured, json((output[1] as UIMessagePart.Text).text))
        assertCallDoesNotResume(output)
    }

    @Test
    fun `empty MCP error cannot look like an empty successful call`() = runTest {
        val output = CallToolResult(content = emptyList(), isError = true).convert()

        assertTrue(output.firstJson().getValue("tool_error").jsonPrimitive.boolean)
        assertFalse(output.firstJson().containsKey("tool_call_completed"))
        assertCallDoesNotResume(output)
    }

    @Test
    fun `images remain images and accompanying structured result is retained`() = runTest {
        val structured = json("""{"status":"captured"}""")
        val output = CallToolResult(
            content = listOf(ImageContent(data = "encoded-image", mimeType = "image/png")),
            structuredContent = structured,
        ).toMessageParts { image ->
            assertEquals("encoded-image", image.data)
            UIMessagePart.Image("content://test/image.png")
        }

        assertEquals(UIMessagePart.Image("content://test/image.png"), output[0])
        assertEquals(structured, json((output[1] as UIMessagePart.Text).text))
        assertCallDoesNotResume(output)
    }

    @Test
    fun `connection error is machine readable and safely escapes its details`() {
        val message = "Server \"demo\" is unavailable\nReconnect required"
        val result = listOf(mcpToolError(message))

        assertTrue(result.firstJson().getValue("tool_error").jsonPrimitive.boolean)
        assertEquals(message, result.firstJson().getValue("error").jsonPrimitive.content)
        assertCallDoesNotResume(result)
    }
}
