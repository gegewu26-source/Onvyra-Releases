package com.constx.onvyra.ui.pages.chat

import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.ai.previousConfirmedContextUsage
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.RequestContextUsageSnapshot
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ContextCapacityStateTest {
    private val model = Model(modelId = "fixture-model", contextLength = 1_000_000)
    private val assistant = Assistant()
    private val snapshot = RequestContextUsageSnapshot(
        systemTools = 10_000, messages = 5_000, mcpTools = 70_000,
        skills = 2_000, other = 3_000, systemPrompt = 10_000,
    )

    private fun reply(input: Int = 255_000, output: Int = 1_000, cached: Int = 250_000) =
        UIMessage.assistant("Synthetic reply").copy(modelId = model.id,
            lastRequestUsage = TokenUsage(promptTokens = input, completionTokens = output, cachedTokens = cached),
            usage = TokenUsage(promptTokens = input, completionTokens = output, cachedTokens = cached),
            requestContextUsage = snapshot)

    private fun state(messages: List<UIMessage>, threshold: Int = 512_000, selected: Model = model) =
        buildContextCapacityState(messages, selected, assistant, threshold, true)

    @Test fun explicitThresholdIsTheFullCircleAndCurrentContextIsHalfFull() {
        val state = state(listOf(UIMessage.user("fixture"), reply()))
        assertEquals(512_000L, state.capacityTokens)
        assertEquals(256_000L, state.usedTokens)
        assertEquals(0.5, state.usedTokens!!.toDouble() / state.capacityTokens, 0.00001)
        assertFalse(state.usageEstimated)
        assertEquals(state.usedTokens, state.breakdown.sumOf { it.tokens })
        assertEquals(6, state.breakdown.size)
    }

    @Test fun toolTurnTotalsAndCacheAreNotAddedToTheCurrentRequestAgain() {
        val message = reply().copy(usage = TokenUsage(promptTokens = 990_000,
            completionTokens = 10_000, cachedTokens = 900_000, totalTokens = 1_000_000))
        assertEquals(256_000L, state(listOf(message)).usedTokens)
        assertEquals(900_000.0 / 990_000.0, state(listOf(message)).cacheHitRate!!, 0.00001)
    }

    @Test fun pendingNewMessageKeepsConfirmedReferenceUntilTheNextRequestReports() {
        val original = state(listOf(reply()))
        val pending = state(listOf(reply(), UIMessage.user("A newly sent fixture")))
        assertEquals(original.usedTokens, pending.usedTokens)
        assertTrue(pending.awaitingUsage)
        assertFalse(pending.usageEstimated)
        assertEquals(original.breakdown, pending.breakdown)
    }

    @Test fun compactedPrefixAndDifferentModelsCannotLeakOldUsage() {
        val checkpoint = UIMessage.user("Synthetic context checkpoint").copy(isContextCheckpoint = true)
        assertNull(state(listOf(reply(), checkpoint, UIMessage.user("next"))).usedTokens)
        assertNull(state(listOf(reply()), selected = Model(modelId = "other")).usedTokens)
    }

    @Test fun oldSingleRequestKeepsRealTotalWithoutInventingCategories() {
        val old = reply().copy(requestContextUsage = null, lastRequestUsage = null)
        val value = state(listOf(old))
        assertEquals(256_000L, value.usedTokens)
        assertTrue(value.breakdown.isEmpty())
        val multiStepOld = old.copy(parts = listOf(UIMessagePart.Tool(
            toolCallId = "call_fixture", toolName = "fixture", input = "{}", output = emptyList())))
        assertNull(state(listOf(multiStepOld)).usedTokens)
    }

    @Test fun newRequestUsesFreshSnapshotWhilePreviousTaskUsageRemainsStored() {
        val message = reply().copy(lastRequestUsage = null, requestContextUsage = snapshot.copy(
            systemTools = 100, messages = 200, mcpTools = 300,
            skills = 0, other = 0, systemPrompt = 0))
        val value = state(listOf(message))
        assertTrue(value.awaitingUsage)
        assertNull(value.usedTokens)
        assertTrue(value.breakdown.isEmpty())
    }

    @Test fun resumedToolTurnUsesSnapshotModelWithoutChangingHistoricalModelLabel() {
        val prior = Model(modelId = "old-model")
        val resumed = reply().copy(modelId = prior.id,
            requestContextUsage = snapshot.copy(requestModelId = model.id.toString()))
        assertEquals(256_000L, state(listOf(resumed)).usedTokens)
        assertNull(state(listOf(resumed), selected = prior).usedTokens)
        assertEquals(prior.id, resumed.modelId)
    }

    @Test fun cacheIsWeightedAndUnknownOrInvalidUsageIsNotZero() {
        val a = reply(input = 100, output = 0, cached = 90)
        val b = reply(input = 900, output = 0, cached = 0)
        assertEquals(0.09, contextCacheHitRate(listOf(a, b))!!, 0.00001)
        assertNull(contextCacheHitRate(listOf(UIMessage.user("fixture"))))
        assertNull(contextCacheHitRate(listOf(a.copy(usage = TokenUsage(promptTokens = 10, cachedTokens = 20)))))
    }

    @Test fun configuredAutomaticBudgetAndDisabledStatusRemainTruthful() {
        assertEquals(800_000L, state(listOf(reply()), threshold = 0).capacityTokens)
        val value = buildContextCapacityState(listOf(reply()), model, assistant, 512_000, false)
        assertFalse(value.autoCompressionEnabled)
        assertEquals(512_000L, value.capacityTokens)
        val unknown = buildContextCapacityState(emptyList(), null, assistant, 0, true)
        assertNull(unknown.usedTokens)
        assertEquals(0L, unknown.capacityTokens)
    }

    @Test fun shortRepliesGrowAboveLargeMcpBaseWithoutSummingRequestTotals() {
        val first = reply(input = 98_590, output = 261, cached = 98_432)
        val second = reply(input = 98_856, output = 115, cached = 98_688)
        val third = reply(input = 99_249, output = 170, cached = 99_072)
        val early = state(listOf(UIMessage.user("a"), first))
        val later = state(listOf(UIMessage.user("a"), first, UIMessage.user("b"), second,
            UIMessage.user("c"), third))
        assertEquals(98_851L, early.usedTokens)
        assertEquals(99_419L, later.usedTokens)
        assertEquals(448L, later.changeTokens)
        assertEquals(568L, later.usedTokens!! - early.usedTokens!!)
        assertTrue(later.usedTokens!! < 100_000L)
        assertEquals(later.usedTokens, later.breakdown.sumOf { it.tokens })
    }

    @Test fun compressionCanDecreaseContextAndFirstRequestHasNoInventedDelta() {
        val first = reply()
        assertNull(state(listOf(first)).changeTokens)
        val reduced = reply(input = 80_000, output = 100, cached = 0)
        assertEquals(-175_900L, state(listOf(first, UIMessage.user("next"), reduced)).changeTokens)
    }

    @Test fun streamingDoesNotReplaceNinetyEightKWithUncalibratedOneHundredSixtySixK() {
        val first = reply(input = 98_590, output = 261)
        val nextSnapshot = snapshot.copy(systemTools = 20_149, messages = 324, mcpTools = 144_655,
            skills = 0, other = 1297, systemPrompt = 110, requestModelId = model.id.toString(),
            previousConfirmedContextTokens = 98_851)
        val pending = UIMessage.assistant("A growing response ".repeat(100)).copy(modelId = model.id,
            requestContextUsage = nextSnapshot)
        val history = listOf(UIMessage.user("1"), first, UIMessage.user("2"))
        val streaming = state(history + pending)
        assertTrue(nextSnapshot.estimatedInputTokens > 166_000)
        assertEquals(98_851L, streaming.usedTokens)
        assertEquals(state(history.take(2)).breakdown, streaming.breakdown)
        assertTrue(streaming.awaitingUsage)
        assertFalse(streaming.usageEstimated)
        assertNull(streaming.changeTokens)
        assertEquals(98_851L, streaming.cumulativeTokens)

        val usage = TokenUsage(promptTokens = 98_856, completionTokens = 115)
        val completed = state(history + pending.copy(lastRequestUsage = usage, usage = usage))
        assertFalse(completed.awaitingUsage)
        assertEquals(98_971L, completed.usedTokens)
        assertEquals(120L, completed.changeTokens)
        // The repeated tool/system prefix is charged on both calls, but occupies the context once.
        assertEquals(197_822L, completed.cumulativeTokens)
        assertEquals(completed.usedTokens, completed.breakdown.sumOf { it.tokens })
    }

    @Test fun reader62StreamingMessagesUseEarlierActualUsageWithoutNewReferenceField() {
        val first = reply(input = 98_590, output = 261)
        val pending = UIMessage.assistant("partial").copy(modelId = model.id,
            requestContextUsage = snapshot.copy(mcpTools = 160_000))
        assertEquals(98_851L, state(listOf(first, UIMessage.user("2"), pending)).usedTokens)
        val firstRequest = state(listOf(UIMessage.user("1"), pending))
        assertNull(firstRequest.usedTokens)
        assertTrue(firstRequest.awaitingUsage)
    }

    @Test fun reusedToolMessageKeepsConfirmedReferenceWithoutUsingItsCumulativeCost() {
        val looping = reply().copy(lastRequestUsage = null,
            usage = TokenUsage(promptTokens = 900_000, completionTokens = 10_000),
            requestContextUsage = snapshot.copy(previousConfirmedContextTokens = 98_851))
        val pending = state(listOf(looping))
        assertEquals(98_851L, pending.usedTokens)
        assertEquals(910_000L, pending.cumulativeTokens)
        assertTrue(pending.awaitingUsage)
    }

    @Test fun retainedOldRepliesAfterCheckpointCannotSupplyUncompressedReference() {
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        val retained = reply(input = 255_000, output = 1_000)
        assertNull(state(listOf(checkpoint, retained)).usedTokens)
        val pending = UIMessage.assistant("").copy(modelId = model.id,
            requestContextUsage = snapshot.copy(requestCheckpointId = checkpoint.id.toString()))
        val waiting = state(listOf(checkpoint, retained, UIMessage.user("next"), pending))
        assertTrue(waiting.awaitingUsage)
        assertNull(waiting.usedTokens)
        assertEquals(256_000L, waiting.cumulativeTokens)
    }

    @Test fun completeBreakdownSurvivesRepeatedThinkingAndToolRequestsUntilRealUsageArrives() {
        val first = reply(input = 124_000, output = 314, cached = 120_000)
        val confirmed = state(listOf(first))
        val reference = requireNotNull(previousConfirmedContextUsage(listOf(first), model))
        var looping = first.copy(lastRequestUsage = null, requestContextUsage = snapshot.copy(
            systemTools = 200, messages = 100, mcpTools = 999_000,
            requestModelId = model.id.toString(), previousConfirmedContextUsage = reference))
        repeat(5) {
            val thinking = state(listOf(looping))
            assertEquals(124_314L, thinking.usedTokens)
            assertEquals(confirmed.breakdown, thinking.breakdown)
            assertEquals(confirmed.cacheHitRate, thinking.cacheHitRate)
            assertEquals(6, thinking.breakdown.size)
            assertTrue(thinking.awaitingUsage)
            looping = looping.copy(requestContextUsage = looping.requestContextUsage!!.copy(
                messages = 100L + it * 100L,
                previousConfirmedContextUsage = previousConfirmedContextUsage(listOf(looping), model)))
        }
        // An output-only partial event cannot replace the complete confirmed view.
        val partial = state(listOf(looping.copy(lastRequestUsage = TokenUsage(completionTokens = 50))))
        assertEquals(confirmed.breakdown, partial.breakdown)
        assertEquals(124_314L, partial.usedTokens)

        val actual = TokenUsage(promptTokens = 125_000, completionTokens = 500, cachedTokens = 121_000)
        val completed = state(listOf(looping.copy(lastRequestUsage = actual,
            usage = TokenUsage(promptTokens = 249_000, completionTokens = 814, cachedTokens = 241_000))))
        assertFalse(completed.awaitingUsage)
        assertEquals(125_500L, completed.usedTokens)
        assertEquals(1_186L, completed.changeTokens)
        assertEquals(249_814L, completed.cumulativeTokens)
        assertEquals(completed.usedTokens, completed.breakdown.sumOf { it.tokens })
        assertNotEquals(confirmed.breakdown, completed.breakdown)
    }

    @Test fun pendingTurnRetainsThePreviousCategoriesEvenWhenCurrentToolMixChanged() {
        val first = reply(input = 98_590, output = 261)
        val expected = state(listOf(first))
        val pending = UIMessage.assistant("thinking").copy(modelId = model.id,
            requestContextUsage = snapshot.copy(systemTools = 0, messages = 10, mcpTools = 200_000))
        val waiting = state(listOf(first, UIMessage.user("next"), pending))
        assertEquals(expected.breakdown, waiting.breakdown)
        assertEquals(expected.usedTokens, waiting.usedTokens)
        assertEquals(expected.cacheHitRate, waiting.cacheHitRate)
    }

    @Test fun referenceCannotRestoreAnotherModelOrPreCheckpointBreakdown() {
        val reference = requireNotNull(previousConfirmedContextUsage(listOf(reply()), model))
        val other = Model(modelId = "another-model")
        val wrongModel = UIMessage.assistant("").copy(modelId = other.id,
            requestContextUsage = snapshot.copy(requestModelId = other.id.toString(),
                previousConfirmedContextUsage = reference))
        assertNull(state(listOf(wrongModel), selected = other).usedTokens)
        assertTrue(state(listOf(wrongModel), selected = other).breakdown.isEmpty())
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        val wrongCheckpoint = UIMessage.assistant("").copy(modelId = model.id,
            requestContextUsage = snapshot.copy(requestCheckpointId = checkpoint.id.toString(),
                previousConfirmedContextUsage = reference))
        val value = state(listOf(checkpoint, wrongCheckpoint))
        assertNull(value.usedTokens)
        assertTrue(value.breakdown.isEmpty())
    }

    @Test fun latestLegacyNumericTotalTakesPriorityOverOlderOrInconsistentCompleteReferences() {
        val earlier = reply(input = 97_000, output = 1000)
        val oldReference = requireNotNull(previousConfirmedContextUsage(listOf(earlier), model))
        val pending = UIMessage.assistant("thinking").copy(modelId = model.id,
            requestContextUsage = snapshot.copy(previousConfirmedContextTokens = 124_314))
        val base = listOf(earlier, UIMessage.user("next"))
        val legacy = state(base + pending)
        assertEquals(124_314L, legacy.usedTokens)
        assertTrue(legacy.breakdown.isEmpty())
        val inconsistent = state(base + pending.copy(requestContextUsage = pending.requestContextUsage!!.copy(
            previousConfirmedContextUsage = oldReference)))
        assertEquals(124_314L, inconsistent.usedTokens)
        assertTrue(inconsistent.breakdown.isEmpty())

        val matching = state(base + pending.copy(requestContextUsage = pending.requestContextUsage!!.copy(
            previousConfirmedContextTokens = 98_000)))
        assertEquals(state(listOf(earlier)).breakdown, matching.breakdown)
        val usage = TokenUsage(promptTokens = 130_000, completionTokens = 500)
        val completed = state(base + pending.copy(lastRequestUsage = usage, usage = usage))
        assertEquals(130_500L, completed.usedTokens)
        assertEquals(6_186L, completed.changeTokens)
    }
}
