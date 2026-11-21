package com.constx.onvyra.data.ai.compression

import com.constx.onvyra.data.ai.MCP_TOOL_SEARCH
import com.constx.onvyra.data.ai.requestMcpDeclarationKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.RequestContextUsageSnapshot
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ContextCompressionPolicyTest {
    private fun tools(pending: Boolean = false) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Tool("call", "inspect", "{}",
            output = if (pending) emptyList() else listOf(UIMessagePart.Text("result")),
            approvalState = if (pending) ToolApprovalState.Pending else ToolApprovalState.Auto),
    ))

    @Test fun `cut backs up to the start of the whole user and tool turn`() {
        val source = listOf(UIMessage.user("old"), UIMessage.assistant("old answer"),
            UIMessage.user("current task"), tools(), tools(), UIMessage.assistant("answer"), UIMessage.user("new"))
        val plan = planContextCompression(source, 3)!!
        assertEquals(2, plan.cutIndex)
        assertEquals(source.take(2), plan.source(source))
    }

    @Test fun `repeat compaction includes the prior checkpoint and new older turns`() {
        val source = listOf(UIMessage.user("archived"), UIMessage.user("prior summary").copy(isContextCheckpoint = true),
            UIMessage.user("older"), UIMessage.assistant("older answer"), UIMessage.user("latest"))
        val plan = planContextCompression(source, 1)!!
        assertEquals(1, plan.startIndex)
        assertEquals(listOf("prior summary", "older", "older answer"), plan.source(source).map { it.toText() })
    }

    @Test fun `a single oversized user turn cannot be compacted away`() {
        val source = listOf(UIMessage.user("current"), tools(), tools())
        assertNull(planContextCompression(source, 1, recentTokenBudget = 10))
    }

    @Test fun `an unresolved tool keeps its whole turn outside the checkpoint`() {
        val source = listOf(UIMessage.user("old"), UIMessage.assistant("done"),
            UIMessage.user("awaiting"), tools(true), UIMessage.user("new"))
        assertEquals(2, planContextCompression(source, 1)!!.cutIndex)
    }

    @Test fun `no repeated compaction of a checkpoint without new archived content`() {
        val source = listOf(UIMessage.user("summary").copy(isContextCheckpoint = true), UIMessage.user("new"), tools())
        assertNull(planContextCompression(source, 1))
    }

    @Test fun `edits and selected branches invalidate a compression but appended messages do not`() {
        val source = listOf(UIMessage.user("old"), UIMessage.assistant("fact"), UIMessage.user("latest"))
        assertTrue(unchangedCompressionPrefix(source, source + UIMessage.user("appended"), 2))
        assertFalse(unchangedCompressionPrefix(source, listOf(source[0].copy(parts = listOf(UIMessagePart.Text("edited")))) + source.drop(1), 2))
        assertFalse(unchangedCompressionPrefix(source, listOf(UIMessage.user("different branch")) + source.drop(1), 2))
        assertFalse(unchangedCompressionPrefix(source, emptyList(), 2))
        assertTrue(unchangedCompressionPrefix(source, source.map { it.copy(translation = "new translation") }, 2))
    }

    @Test fun `per model configuration controls the full capacity without a separate cap`() {
        val budget = autoCompressionBudget(Model(contextLength = 1_000_000), null)
        assertEquals(1_000_000L, budget.window)
        assertTrue(budget.threshold < budget.window)
        assertEquals(32_000L, autoCompressionBudget(Model(contextLength = 32_000), 8192).window)
        assertEquals(16_000L, autoCompressionBudget(Model(contextLength = 16_000), null).window)
    }

    @Test fun `zero custom threshold retains the existing automatic budget`() {
        val model = Model(contextLength = 128_000)
        assertEquals(102_400L, autoCompressionBudget(model, null).threshold)
        assertEquals(autoCompressionBudget(model, null), autoCompressionBudget(model, null, 0))
        assertEquals(autoCompressionBudget(model, null), autoCompressionBudget(model, null, -1))
    }

    @Test fun `custom threshold triggers exactly at the configured token count`() {
        val model = Model(contextLength = 1_000_000)
        val budget = autoCompressionBudget(model, null, thresholdTokens = 258_000)
        fun source(tokens: Int) = listOf(UIMessage.user("old"), UIMessage.assistant("answer").copy(
            modelId = model.id, lastRequestUsage = TokenUsage(promptTokens = tokens)))
        val below = source(257_999)
        val reached = source(258_000)
        assertEquals(258_000L, budget.threshold)
        assertFalse(shouldAutoCompress(below, below, emptyList(), model, budget, 0))
        assertTrue(shouldAutoCompress(reached, reached, emptyList(), model, budget, 0))
    }

    @Test fun `confirmed count below the chosen threshold wins over a large local estimate`() {
        val model = Model(contextLength = 1_000_000)
        val budget = autoCompressionBudget(model, null, thresholdTokens = 512_000)
        val prepared = listOf(UIMessage.user("token ".repeat(260_000)))
        val currentRequest = RequestContextUsageSnapshot(requestModelId = model.id.toString())
        val lastReply = UIMessage.assistant("answer").copy(
            modelId = Model().id,
            lastRequestUsage = TokenUsage(promptTokens = 374_215, completionTokens = 260),
            requestContextUsage = currentRequest,
        )
        val source = listOf(UIMessage.user("old"), lastReply, UIMessage.user("next"))
        val onlyEstimate = source.map { it.copy(lastRequestUsage = null) }
        assertTrue(estimateRequestTokens(prepared, emptyList()) >= budget.threshold)
        assertFalse(shouldAutoCompress(source, prepared, emptyList(), model, budget, messageLimit = 1))
        assertTrue(shouldAutoCompress(onlyEstimate, prepared, emptyList(), model, budget, messageLimit = 0))

        val reference = lastReply.copy(
            lastRequestUsage = null,
            requestContextUsage = currentRequest.copy(previousConfirmedContextTokens = 374_475),
        )
        assertFalse(shouldAutoCompress(listOf(reference), prepared, emptyList(), model, budget, 0))
    }

    @Test fun `custom threshold may exceed eighty percent while retaining model output reserve`() {
        val model = Model(contextLength = 1_000_000)
        val budget = autoCompressionBudget(model, null, thresholdTokens = 850_000)
        assertEquals(850_000L, budget.threshold)
        assertEquals(900_000L, autoCompressionBudget(model, null, Int.MAX_VALUE).threshold)
        assertEquals(800_000L, autoCompressionBudget(model, 200_000, Int.MAX_VALUE).threshold)
        assertEquals(64_000L, autoCompressionBudget(Model(contextLength = 96_000), 40_000, 512_000).threshold)
        assertEquals(4_000, budget.summaryTokens)
    }

    @Test fun `complete tool schema contributes to request budget`() {
        val messages = listOf(UIMessage.user("small"))
        val tool = Tool("large", "description", parameters = { InputSchema.Obj(buildJsonObject {
            put("payload", buildJsonObject { put("description", "schema ".repeat(1000)) })
        }) }, execute = { emptyList() })
        assertTrue(estimateRequestTokens(messages, listOf(tool)) > estimateRequestTokens(messages, emptyList()) + 1000)
    }

    @Test fun `actual previous request usage can trigger earlier than a text estimate`() {
        val model = Model()
        val source = listOf(UIMessage.user("old"), UIMessage.assistant("answer").copy(modelId = model.id,
            lastRequestUsage = TokenUsage(promptTokens = 110_000, completionTokens = 100)), UIMessage.user("next"))
        assertTrue(shouldAutoCompress(source, source, emptyList(), model, autoCompressionBudget(model.copy(contextLength = 128_000), null), 0))
    }

    @Test fun `old usage and another models usage do not retrigger after a checkpoint`() {
        val model = Model()
        val before = UIMessage.assistant("old answer").copy(modelId = model.id,
            createdAt = LocalDateTime(2026, 10, 29, 1, 0),
            finishedAt = LocalDateTime(2026, 10, 29, 1, 2),
            lastRequestUsage = TokenUsage(promptTokens = 999_000))
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true,
            createdAt = LocalDateTime(2026, 10, 29, 1, 1))
        val source = listOf(checkpoint, before, UIMessage.user("next"))
        assertFalse(shouldAutoCompress(source, source, emptyList(), model, autoCompressionBudget(model.copy(contextLength = 128_000), null), 0))
        val otherModel = listOf(before.copy(modelId = Model().id), UIMessage.user("next"))
        assertFalse(shouldAutoCompress(otherModel, otherModel, emptyList(), model, autoCompressionBudget(model.copy(contextLength = 128_000), null), 0))
    }

    @Test fun `message count triggers compaction only with the automatic threshold`() {
        val model = Model()
        val source = List(6) { UIMessage.user("turn $it") }
        assertTrue(shouldAutoCompress(source, source, emptyList(), model, autoCompressionBudget(model.copy(contextLength = 128_000), null), 4))
        assertFalse(shouldAutoCompress(source, source, emptyList(), model, autoCompressionBudget(model.copy(contextLength = 1_000_000), null, 512_000), 4))
    }

    @Test fun `changed MCP declarations do not reuse another tasks large text request floor`() {
        val model = Model(abilities = listOf(ModelAbility.TOOL), contextLength = 1_000_000)
        val oldTools = listOf(Tool("mcp__server__old", "old declaration", execute = { emptyList() }))
        val currentTools = listOf(Tool(MCP_TOOL_SEARCH, "local directory", execute = { emptyList() }))
        val snapshot = RequestContextUsageSnapshot(mcpDeclarationKey = requestMcpDeclarationKey(oldTools))
        val previous = UIMessage.assistant("answer").copy(modelId = model.id,
            lastRequestUsage = TokenUsage(promptTokens = 98_590, completionTokens = 261),
            requestContextUsage = snapshot)
        val source = listOf(UIMessage.user("old task"), previous, UIMessage.user("hello"))
        val budget = AutoCompressionBudget(window = 1_000_000, threshold = 50_000, summaryTokens = 2000)
        assertFalse(shouldAutoCompress(source, source, currentTools, model, budget, 0))

        fun withSnapshot(value: RequestContextUsageSnapshot?) = source.map {
            if (it.id == previous.id) it.copy(requestContextUsage = value) else it
        }
        for (conservative in listOf(
            snapshot.copy(mcpDeclarationKey = requestMcpDeclarationKey(currentTools)),
            snapshot.copy(mcpDeclarationKey = null),
            snapshot.copy(containsImages = true),
            null,
        )) {
            val history = withSnapshot(conservative)
            assertTrue(shouldAutoCompress(history, history, currentTools, model, budget, 0))
        }
        val withImage = source + UIMessage.user("inspect").copy(parts = listOf(UIMessagePart.Image("file:///fixture.png")))
        assertTrue(shouldAutoCompress(source, withImage, currentTools, model, budget, 0))
        assertTrue(shouldAutoCompress(source, source, currentTools, model.copy(abilities = emptyList()), budget, 0))
        assertTrue(shouldAutoCompress(source, source, currentTools, model, budget, 1))

        val genuinelyLarge = Tool("mcp__server__large", "large current tool", parameters = {
            InputSchema.Obj(buildJsonObject { put("payload", buildJsonObject {
                put("type", "string"); put("description", "schema detail ".repeat(30_000))
            }) })
        }, execute = { emptyList() })
        assertTrue(shouldAutoCompress(source, source, listOf(genuinelyLarge), model, budget, 0))
    }
}
