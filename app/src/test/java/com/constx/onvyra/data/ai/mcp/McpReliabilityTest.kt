package com.constx.onvyra.data.ai.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import org.junit.Assert.*
import org.junit.Test

class McpReliabilityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun tool(defaults: String = "{}") = McpTool(name = "demo", defaultArguments = obj(defaults), inputSchema = InputSchema.Obj(
        properties = obj("""{"wait":{"type":"integer","minimum":0,"maximum":20},"enabled":{"type":"boolean"},"path":{"type":"string"},"mode":{"type":"string","enum":["a","b"]}}"""),
        required = listOf("wait", "path"),
    ))

    @Test fun `old stored settings decode without a migration`() {
        val decoded = json.decodeFromString<McpTool>("""{"name":"demo","enable":false,"needsApproval":true}""")
        assertTrue(decoded.defaultArguments.isEmpty())
        assertFalse(decoded.enable)
        assertTrue(decoded.needsApproval)
    }

    @Test fun `saved defaults roundtrip and explicit false zero and null win`() {
        val saved = tool("""{"wait":10,"enabled":true,"path":"/sample"}""")
        val restored = json.decodeFromString<McpTool>(json.encodeToString(McpTool.serializer(), saved))
        assertEquals(saved, restored)
        assertEquals(obj("""{"wait":0,"enabled":false,"path":null}"""), restored.resolveArguments(obj("""{"wait":0,"enabled":false,"path":null}""")))
        assertEquals(saved.defaultArguments, restored.resolveArguments(obj("{}")))
    }

    @Test fun `removed parameters and defaults invalid for a changed schema are not sent`() {
        val saved = tool("""{"wait":30,"enabled":"true","mode":"c","removed":9,"path":"/valid"}""")
        assertEquals(obj("""{"path":"/valid"}"""), saved.validSavedArguments())
    }

    @Test fun `saved defaults are declared and no longer required on each call`() {
        val schema = tool("""{"wait":8}""").schemaWithSavedArguments() as InputSchema.Obj
        assertEquals(JsonPrimitive(8), schema.properties.getValue("wait").jsonObject["default"])
        assertEquals(listOf("path"), schema.required)
    }

    @Test fun `field parser preserves strings and validates numeric bounds`() {
        val schemas = tool().editableArgumentSchemas()
        assertEquals(JsonPrimitive("001"), parseMcpArgument("001", schemas.getValue("path")))
        for (bad in listOf("1.5", "-1", "21", "text")) {
            assertTrue(runCatching { parseMcpArgument(bad, schemas.getValue("wait")) }.isFailure)
        }
    }

    @Test fun `nullable primitives can have saved defaults but ambiguous unions cannot`() {
        val saved = McpTool(defaultArguments = obj("""{"seconds":8,"mixed":1}"""), inputSchema = InputSchema.Obj(
            obj("""{"seconds":{"anyOf":[{"type":"integer","minimum":0},{"type":"null"}]},"mixed":{"anyOf":[{"type":"integer"},{"type":"string"}]}}""")))
        assertEquals(obj("""{"seconds":8}"""), saved.validSavedArguments())
        assertEquals(obj("""{"seconds":null}"""), saved.resolveArguments(obj("""{"seconds":null}""")))
    }

    @Test fun `type arrays and complex properties do not break unconfigured tool calls`() {
        val schema = InputSchema.Obj(obj("""{"text":{"type":["string","null"]},"items":{"type":"array","items":{"type":"object"}}}"""))
        val plain = McpTool(inputSchema = schema)
        assertEquals(obj("""{"items":[{"a":1}]}"""), plain.resolveArguments(obj("""{"items":[{"a":1}]}""")))
        assertEquals(setOf("text"), plain.editableArgumentSchemas().keys)
        assertEquals(obj("""{"text":"saved"}"""), plain.copy(defaultArguments=obj("""{"text":"saved"}""")).validSavedArguments())
    }

    @Test fun `all pages are read and empty intermediate pages do not terminate the catalog`() = runBlocking {
        val cursors = mutableListOf<String?>()
        val values = collectMcpPages { cursor ->
            cursors += cursor
            when (cursor) {
                null -> listOf("one") to "next"
                "next" -> emptyList<String>() to "last"
                else -> listOf("two") to null
            }
        }
        assertEquals(listOf("one", "two"), values)
        assertEquals(listOf(null, "next", "last"), cursors)
    }

    @Test fun `broken pagination fails rather than returning a misleading partial catalog`() = runBlocking {
        assertTrue(runCatching { collectMcpPages { listOf("one") to "same" } }.isFailure)
        assertTrue(runCatching { collectMcpPages<String> { if (it == null) listOf("one") to "next" else error("offline") } }.isFailure)
    }

    @Test fun `unknown tool returns current names but never executes a substitute`() {
        val result = obj(unknownToolResult("mcp__Alpha__terminal_exec", listOf("mcp__Alpha__status", "mcp__Beta__terminal_exec")).text)
        assertEquals(JsonPrimitive(false), result["executed"])
        assertEquals(JsonPrimitive("unknown_tool"), result["error_code"])
        assertEquals(JsonArray(listOf(JsonPrimitive("mcp__Alpha__status"))), result["same_server_tools"])
    }

    @Test fun `connection errors omit addresses secrets and stack traces`() {
        val safe = safeMcpError(IllegalStateException("Connect https://user:pass@host/mcp?token=abc failed; Authorization: Bearer mysecret token=other"))
        for (secret in listOf("mysecret", "other", "token=abc", "user:pass", "host/mcp")) assertFalse(safe.contains(secret))
        assertTrue(safe.contains("failed"))
    }
}
