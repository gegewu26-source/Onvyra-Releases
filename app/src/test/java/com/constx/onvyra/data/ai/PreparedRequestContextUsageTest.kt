package com.constx.onvyra.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.RequestContextUsageSnapshot
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PreparedRequestContextUsageTest {
    private val model = Model(abilities = listOf(ModelAbility.TOOL))
    private fun tool(name: String, schemaText: String = "fixture") = Tool(
        name = name,
        description = "Fixture tool description",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("value", buildJsonObject { put("type", "string"); put("description", schemaText) })
        }) },
        execute = { emptyList() },
    )

    @Test fun `prepared classifications include MCP schemas and skill guidance without double counting system text`() {
        val ordinary = "System tool guidance."
        val mcp = "MCP tool guidance."
        val skill = "Enabled skill catalog."
        val other = "Runtime task policy."
        val system = "Assistant system prompt.\n$ordinary\n$mcp\n$skill\n$other"
        val input = listOf(UIMessage.system(system), UIMessage.user("fixture"))
        val result = estimatePreparedRequestContextUsage(input,
            listOf(tool("read_file"), tool("mcp__fixture__lookup", "a".repeat(8000)), tool("use_skill")),
            model, listOf("read_file" to ordinary, "mcp__fixture__lookup" to mcp, "use_skill" to skill),
            listOf(other), sourceMessageCount = 1)

        assertTrue(result.systemTools > 0)
        assertTrue(result.mcpTools > result.systemTools)
        assertTrue(result.skills > 0)
        assertTrue(result.other > 0)
        assertTrue(result.systemPrompt > 0)
        assertTrue(result.messages > 0)
        assertEquals(2, result.preparedMessageCount)
        assertEquals(1, result.sourceMessageCount)
        assertEquals(system, input.first().toText())
        assertEquals(requestMcpDeclarationKey(listOf(tool("read_file"), tool("mcp__fixture__lookup"), tool("use_skill"))),
            result.mcpDeclarationKey)
    }

    @Test fun `ordinary models exclude unsent function definitions but retain actual guidance`() {
        val guide = "Guidance already included in the prepared system message."
        val input = listOf(UIMessage.system(guide), UIMessage.user("hello"))
        val result = estimatePreparedRequestContextUsage(input, listOf(tool("mcp__fixture__lookup", "x".repeat(100_000))),
            Model(), listOf("mcp__fixture__lookup" to guide))

        assertEquals(0L, result.systemTools)
        assertTrue(result.mcpTools > 0)
        assertTrue(result.mcpTools < 100)
        assertEquals(0L, result.systemPrompt)
        assertNull(result.mcpDeclarationKey)
    }

    @Test fun `compacted prepared input replaces large old context instead of accumulating it`() {
        val old = listOf(UIMessage.user("old context ".repeat(20_000)), UIMessage.assistant("old reply"), UIMessage.user("latest"))
        val current = listOf(UIMessage.user("checkpoint summary").copy(isContextCheckpoint = true), UIMessage.user("latest"))
        val before = estimatePreparedRequestContextUsage(old, emptyList(), model, sourceMessageCount = old.size)
        val after = estimatePreparedRequestContextUsage(current, emptyList(), model, sourceMessageCount = current.size)

        assertTrue(after.estimatedInputTokens < before.estimatedInputTokens / 100)
        assertEquals(2, after.sourceMessageCount)
        assertEquals(2, after.preparedMessageCount)
    }

    @Test fun `image payloads are flagged without treating base64 characters as text tokens`() {
        val image = UIMessagePart.Image("data:image/png;base64," + "a".repeat(100_000))
        val input = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("inspect"), image)))
        val result = estimatePreparedRequestContextUsage(input, emptyList(), model)

        assertTrue(result.containsImages)
        assertTrue(result.messages < 500)
        assertEquals(image, input.single().parts.last())
    }

    @Test fun `usage diagnostics do not make a schema supplier exception fail request preparation`() {
        val broken = tool("fixture").copy(parameters = { error("Synthetic supplier failure") })
        val result = estimatePreparedRequestContextUsage(listOf(UIMessage.user("hello")), listOf(broken), model,
            responsePrefixTokens = 42)

        assertTrue(result.systemTools > 0)
        assertEquals(42L, result.responsePrefixTokens)
    }

    @Test fun `reference captures last actual request instead of cumulative tool turn cost`() {
        val latest = UIMessage.assistant("tool turn").copy(modelId = model.id,
            usage = TokenUsage(promptTokens = 900_000, completionTokens = 10_000),
            lastRequestUsage = TokenUsage(promptTokens = 98_590, completionTokens = 261),
            requestContextUsage = RequestContextUsageSnapshot(requestModelId = model.id.toString()))
        assertEquals(98_851L, previousConfirmedContextTokens(listOf(latest), model))
        val reused = latest.copy(lastRequestUsage = null, requestContextUsage = latest.requestContextUsage!!.copy(
            previousConfirmedContextTokens = previousConfirmedContextTokens(listOf(latest), model)))
        assertEquals(98_851L, previousConfirmedContextTokens(listOf(reused), model))
        assertNull(previousConfirmedContextTokens(listOf(latest), Model()))
        assertNull(previousConfirmedContextTokens(listOf(latest.copy(lastRequestUsage = null)), model))
    }

    @Test fun `checkpoint invalidates retained old references but accepts new matching request`() {
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        val old = UIMessage.assistant("retained").copy(modelId = model.id,
            lastRequestUsage = TokenUsage(promptTokens = 255_000, completionTokens = 1000),
            requestContextUsage = RequestContextUsageSnapshot(requestModelId = model.id.toString(),
                previousConfirmedContextTokens = 200_000))
        assertEquals(checkpoint.id.toString(), currentRequestCheckpointId(listOf(checkpoint, old)))
        assertNull(previousConfirmedContextTokens(listOf(checkpoint, old), model))
        val new = old.copy(lastRequestUsage = TokenUsage(promptTokens = 80_000, completionTokens = 100),
            requestContextUsage = old.requestContextUsage!!.copy(requestCheckpointId = checkpoint.id.toString()))
        assertEquals(80_100L, previousConfirmedContextTokens(listOf(checkpoint, new), model))
        val invalidated = checkpoint.copy(contextCheckpointInvalidated = true)
        assertNull(currentRequestCheckpointId(listOf(invalidated, old)))
        assertEquals(256_000L, previousConfirmedContextTokens(listOf(invalidated, old), model))
    }

    @Test fun `MCP declaration key ignores order duplicates and native tools without reading schemas`() {
        var schemaCalls = 0
        val first = tool("mcp__fixture__first").copy(parameters = { schemaCalls++; error("must not inspect schema") })
        val second = tool("mcp__fixture__second")
        val search = tool(MCP_TOOL_SEARCH)
        val key = requestMcpDeclarationKey(listOf(first, second, search))
        assertEquals(key, requestMcpDeclarationKey(listOf(search, second, first, first, tool("native"))))
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(key, requestMcpDeclarationKey(listOf(first, search)))
        assertNotEquals(key, requestMcpDeclarationKey(emptyList()))
        assertEquals(0, schemaCalls)
    }

    @Test fun `full confirmed reference survives overwritten tool snapshots and retains weighted conversation cache rate`() {
        val first = UIMessage.assistant("first").copy(modelId = model.id,
            usage = TokenUsage(promptTokens = 100, cachedTokens = 90))
        val current = UIMessage.assistant("tool turn").copy(modelId = model.id,
            requestContextUsage = RequestContextUsageSnapshot(systemTools = 20, messages = 30, mcpTools = 40),
            usage = TokenUsage(promptTokens = 900, completionTokens = 20, cachedTokens = 0),
            lastRequestUsage = TokenUsage(promptTokens = 450, completionTokens = 10))
        val reference = requireNotNull(previousConfirmedContextUsage(listOf(first, current), model))
        assertEquals(460L, reference.contextTokens)
        assertEquals(0.09, reference.cacheHitRate!!, 0.00001)
        assertEquals(40L, reference.mcpTools)
        val pending = current.copy(lastRequestUsage = null, requestContextUsage = current.requestContextUsage!!.copy(
            mcpTools = 500_000, previousConfirmedContextUsage = reference))
        assertEquals(reference, previousConfirmedContextUsage(listOf(first, pending), model))
        assertEquals(460L, previousConfirmedContextTokens(listOf(first, pending), model))
        assertNull(previousConfirmedContextUsage(listOf(pending), Model()))
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        assertNull(previousConfirmedContextUsage(listOf(checkpoint, pending), model))
        val prepared = estimatePreparedRequestContextUsage(listOf(UIMessage.user("next")), emptyList(), model,
            previousConfirmedContextUsage = reference)
        assertEquals(reference, prepared.previousConfirmedContextUsage)
        assertEquals(460L, prepared.previousConfirmedContextTokens)
        assertTrue(prepared.estimatedInputTokens < reference.contextTokens)
    }

    @Test fun `a newer numeric only reference cannot fall back to an older complete snapshot`() {
        val earlier = UIMessage.assistant("earlier").copy(modelId = model.id,
            requestContextUsage = RequestContextUsageSnapshot(mcpTools = 70_000),
            lastRequestUsage = TokenUsage(promptTokens = 97_000, completionTokens = 1000))
        val earlierReference = requireNotNull(previousConfirmedContextUsage(listOf(earlier), model))
        val legacyPending = UIMessage.assistant("thinking").copy(modelId = model.id,
            requestContextUsage = RequestContextUsageSnapshot(previousConfirmedContextTokens = 124_314))
        val history = listOf(earlier, UIMessage.user("next"), legacyPending)
        assertNull(previousConfirmedContextUsage(history, model))
        assertEquals(124_314L, previousConfirmedContextTokens(history, model))
        val inconsistent = legacyPending.copy(requestContextUsage = legacyPending.requestContextUsage!!.copy(
            previousConfirmedContextUsage = earlierReference))
        assertNull(previousConfirmedContextUsage(history.dropLast(1) + inconsistent, model))
        assertEquals(124_314L, previousConfirmedContextTokens(history.dropLast(1) + inconsistent, model))
        val matching = earlierReference.copy(promptTokens = 124_000, completionTokens = 314)
        val repaired = legacyPending.copy(requestContextUsage = legacyPending.requestContextUsage!!.copy(
            previousConfirmedContextUsage = matching))
        assertEquals(matching, previousConfirmedContextUsage(history.dropLast(1) + repaired, model))
    }
}
