package com.constx.onvyra.data.ai

import com.constx.onvyra.data.ai.compression.AutoCompressionBudget
import com.constx.onvyra.data.ai.compression.shouldAutoCompress
import com.constx.onvyra.data.model.AndroidControlPermissionMode.CONFIRM_CHANGES
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class DeferredMcpToolsTest {
    private val local = Tool("local_fixture", "A native tool", execute = { emptyList() })
    private fun catalog(size: Int = 317, schema: () -> Unit = {}): List<Tool> = (0 until size).map { index ->
        Tool(name = "mcp__${if (index < 200) "Alpha" else "Beta"}__tool_${index.toString().padStart(3, '0')}",
            description = "Fixture capability $index " + "optional description ".repeat(20),
            parameters = {
                schema()
                InputSchema.Obj(buildJsonObject { put("value", buildJsonObject {
                    put("type", "string"); put("description", "parameter detail ".repeat(100))
                }) })
            }, execute = { emptyList() })
    }

    private fun lookup(registry: DeferredMcpTools, input: JsonObject = buildJsonObject {}): JsonObject = runBlocking {
        val result = registry.executionTools.single { it.name == MCP_TOOL_SEARCH }.execute(input)
        Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun largeCatalogFirstRequestDoesNotReadOrAdvertiseAnyMcpSchema() {
        var schemaCalls = 0
        val mcp = catalog(schema = { schemaCalls++ })
        val registry = DeferredMcpTools(listOf(local) + mcp)
        val request = registry.requestTools(listOf(UIMessage.user("hello")))
        assertEquals(listOf("local_fixture", MCP_TOOL_SEARCH), request.map { it.name })
        assertEquals(319, registry.executionTools.size)
        assertEquals(0, schemaCalls)
        val model = Model(abilities = listOf(ModelAbility.TOOL))
        val prepared = listOf(UIMessage.user("hello"))
        val deferredPrompts = request.map { it.name to it.systemPrompt(model, prepared) }
        val deferredPrepared = listOf(UIMessage.system(deferredPrompts.joinToString("\n") { it.second })) + prepared
        val deferred = estimatePreparedRequestContextUsage(deferredPrepared, request, model,
            toolSystemPrompts = deferredPrompts)
        assertEquals(0, schemaCalls)
        val eager = estimatePreparedRequestContextUsage(prepared, listOf(local) + mcp, model)
        assertEquals(317, schemaCalls)
        assertTrue(deferred.estimatedInputTokens < eager.estimatedInputTokens)
        assertTrue(deferred.mcpTools < eager.mcpTools)
        println(buildJsonObject {
            put("synthetic", true)
            put("mcp_tool_count", 317)
            put("eager_estimated_input_tokens", eager.estimatedInputTokens)
            put("deferred_estimated_input_tokens", deferred.estimatedInputTokens)
            put("eager_estimated_mcp_tokens", eager.mcpTools)
            put("deferred_estimated_mcp_tokens", deferred.mcpTools)
        })
        val budget = AutoCompressionBudget(window = 1_000_000,
            threshold = (eager.estimatedInputTokens + deferred.estimatedInputTokens) / 2, summaryTokens = 2000)
        assertFalse(shouldAutoCompress(prepared, deferredPrepared, request, model, budget, messageLimit = 0))
        assertTrue(shouldAutoCompress(prepared, prepared, listOf(local) + mcp, model, budget, messageLimit = 0))
        val prompt = request.single { it.name == MCP_TOOL_SEARCH }.systemPrompt(model, prepared)
        assertTrue(prompt.contains("Alpha (200 tools)"))
        assertTrue(prompt.contains("Beta (117 tools)"))
        assertFalse(prompt.contains(mcp.last().description))
        assertFalse(prompt.contains("tool_316"))
    }

    @Test fun lookupLoadsOriginalToolAndKeepsItsExecutionAndApprovalClosures() = runBlocking {
        var received: JsonElement? = null
        var schemaCalls = 0
        var remoteExecutions = 0
        val special = Tool("mcp__Alpha__inspect", "Find a special named capability",
            parameters = { schemaCalls++; InputSchema.Obj(buildJsonObject {}) },
            needsApproval = { (it as? JsonObject)?.get("approve") == JsonPrimitive(true) },
            execute = { received = it; remoteExecutions++; listOf(UIMessagePart.Text("original result")) })
        val registry = DeferredMcpTools(listOf(local, special) + catalog())
        val result = lookup(registry, buildJsonObject { put("query", "special named"); put("limit", 1) })
        assertEquals(special.name, result["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("parameters"))
        assertEquals(0, schemaCalls)
        assertEquals(0, remoteExecutions)
        assertFalse(requiresTaskToolApproval(CONFIRM_CHANGES,
            registry.executionTools.single { it.name == MCP_TOOL_SEARCH }, buildJsonObject {}))
        val loaded = registry.requestTools(emptyList()).single { it.name == special.name }
        assertSame(special, loaded)
        val args = buildJsonObject { put("approve", true); put("value", "explicit value") }
        assertTrue(loaded.needsApproval(args))
        assertFalse(loaded.needsApproval(buildJsonObject {}))
        assertTrue(requiresTaskToolApproval(CONFIRM_CHANGES, loaded, buildJsonObject {}))
        assertEquals(listOf(UIMessagePart.Text("original result")), loaded.execute(args))
        assertEquals(args, received)
        assertEquals(1, remoteExecutions)
        assertSame(special, registry.requestTools(listOf(UIMessage.user("continue"))).single { it.name == special.name })
    }

    @Test fun everyPageCanReachTailToolsAndServerFilterKeepsServersSeparate() {
        val mcp = catalog()
        val registry = DeferredMcpTools(mcp)
        val found = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val result = lookup(registry, buildJsonObject { put("server", "Beta"); put("offset", offset) })
            val tools = result["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue(tools.all { it.startsWith("mcp__Beta__") })
            found.addAll(tools)
            assertEquals(117, result["total_matches"]!!.jsonPrimitive.content.toInt())
            if (result["has_more"] == JsonPrimitive(false)) break
            offset = result["next_offset"]!!.jsonPrimitive.content.toInt()
        }
        assertEquals(117, found.size)
        assertTrue(mcp.last().name in found)
        assertEquals(found, registry.requestTools(emptyList()).filter { it.name.startsWith("mcp__") }.map { it.name }.toSet())
    }

    @Test fun exactSelectionUnknownNamesAndInvalidRequestsNeverExecuteOrLoadOtherTools() {
        val mcp = catalog()
        val registry = DeferredMcpTools(mcp)
        val missing = lookup(registry, buildJsonObject {
            put("tool_names", buildJsonArray { add(JsonPrimitive(mcp.first().name)); add(JsonPrimitive("mcp__Alpha__absent")) })
        })
        assertEquals("unknown_tool_names", missing["error"]!!.jsonPrimitive.content)
        assertTrue(registry.requestTools(emptyList()).none { it.name.startsWith("mcp__") })
        val oversized = lookup(registry, buildJsonObject {
            put("tool_names", JsonArray(mcp.take(11).map { JsonPrimitive(it.name) }))
        })
        assertEquals("invalid_arguments", oversized["error"]!!.jsonPrimitive.content)
        val mismatch = lookup(registry, buildJsonObject {
            put("server", "Alpha"); put("tool_names", buildJsonArray { add(JsonPrimitive(mcp.last().name)) })
        })
        assertEquals("server_mismatch", mismatch["error"]!!.jsonPrimitive.content)
        val noMatch = lookup(registry, buildJsonObject { put("query", "no_such_capability_90877") })
        assertEquals(0, noMatch["total_matches"]!!.jsonPrimitive.content.toInt())
        assertTrue(registry.requestTools(emptyList()).none { it.name.startsWith("mcp__") })
        val loaded = lookup(registry, buildJsonObject {
            put("tool_names", buildJsonArray { add(JsonPrimitive(mcp.last().name)) })
        })
        assertEquals(mcp.last().name, loaded["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertSame(mcp.last(), registry.requestTools(emptyList()).single { it.name.startsWith("mcp__") })
    }

    @Test fun invalidArgumentTypesAndOutOfBoundsPagingLeaveCatalogUnchanged() {
        val registry = DeferredMcpTools(catalog())
        val bad = listOf(buildJsonObject { put("query", 123) }, buildJsonObject { put("server", true) },
            buildJsonObject { put("tool_names", "all") }, buildJsonObject { put("limit", 11) },
            buildJsonObject { put("offset", -1) }, buildJsonObject { put("offset", "1") },
            buildJsonObject { put("tool_names", buildJsonArray { add(JsonPrimitive(1)) }) })
        bad.forEach { assertEquals("invalid_arguments", lookup(registry, it)["error"]!!.jsonPrimitive.content) }
        assertEquals("unknown_server", lookup(registry, buildJsonObject { put("server", "Missing") })["error"]!!.jsonPrimitive.content)
        val outside = lookup(registry, buildJsonObject { put("offset", Int.MAX_VALUE) })
        assertEquals(JsonPrimitive(false), outside["has_more"])
        assertTrue(outside["tools"]!!.jsonArray.isEmpty())
        assertTrue(registry.requestTools(emptyList()).none { it.name.startsWith("mcp__") })
    }

    @Test fun resumedCurrentToolTurnLoadsOnlyItsToolsAndTasksDoNotShareLoadedState() {
        val mcp = catalog()
        val history = UIMessage.assistant("done").copy(parts = listOf(UIMessagePart.Tool(
            toolCallId = "old", toolName = mcp.first().name, input = "{}", output = listOf(UIMessagePart.Text("done")))))
        val resumed = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
            toolCallId = "pending", toolName = mcp.last().name, input = "{}", output = emptyList())))
        val registry = DeferredMcpTools(mcp)
        assertTrue(registry.requestTools(listOf(history, UIMessage.user("new task"))).none { it.name.startsWith("mcp__") })
        val continued = registry.requestTools(listOf(history, UIMessage.user("continue"), resumed))
        assertEquals(listOf(mcp.last()), continued.filter { it.name.startsWith("mcp__") })
        assertSame(mcp.last(), continued.single { it.name == mcp.last().name })
        assertEquals(listOf(mcp.last()), registry.requestTools(emptyList()).filter { it.name.startsWith("mcp__") })
        assertTrue(DeferredMcpTools(mcp).requestTools(emptyList()).none { it.name.startsWith("mcp__") })
    }

    @Test fun smallCatalogRetainsOriginalDeclarationsWithoutLookupOrMutation() {
        for (size in listOf(0, 1, 16)) {
            val tools = listOf(local) + catalog(size)
            val registry = DeferredMcpTools(tools)
            assertSame(tools, registry.executionTools)
            assertSame(tools, registry.requestTools(emptyList()))
            assertFalse(registry.executionTools.any { it.name == MCP_TOOL_SEARCH })
        }
        assertTrue(DeferredMcpTools(catalog(17)).executionTools.any { it.name == MCP_TOOL_SEARCH })
    }
}
