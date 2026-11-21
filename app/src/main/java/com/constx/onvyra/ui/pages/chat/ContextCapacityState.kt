package com.constx.onvyra.ui.pages.chat

import com.constx.onvyra.data.ai.compression.autoCompressionBudget
import com.constx.onvyra.data.ai.currentRequestCheckpointId
import com.constx.onvyra.data.ai.previousConfirmedContextTokens
import com.constx.onvyra.data.ai.previousConfirmedContextUsage
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.ui.components.ai.ContextCapacityCategory
import com.constx.onvyra.ui.components.ai.ContextCapacityState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.RequestContextUsageSnapshot
import me.rerere.ai.core.RequestContextUsageReference
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import kotlin.math.floor

internal data class ContextCapacityInput(
    val conversation: Conversation, val model: Model?, val assistant: Assistant,
    val threshold: Int, val autoCompress: Boolean,
)

/** A view of the active context, never the lifetime token sum of a tool task. */
internal fun buildContextCapacityState(
    messages: List<UIMessage>,
    model: Model?,
    assistant: Assistant,
    compressionThreshold: Int,
    autoCompressionEnabled: Boolean,
): ContextCapacityState {
    val capacity = model?.let { autoCompressionBudget(it, assistant.maxTokens, compressionThreshold).threshold }
        ?: compressionThreshold.toLong().coerceAtLeast(0)
    val cacheRate = contextCacheHitRate(messages)
    val cumulative = cumulativeRequestTokens(messages)
    val active = messages.limitContext(0)
    val checkpointId = currentRequestCheckpointId(active)
    val anchorIndex = active.indexOfLast { message ->
        val matchesModel = model != null &&
            (message.requestContextUsage?.requestModelId?.let { it == model.id.toString() }
                ?: (message.modelId == model.id))
        message.role == MessageRole.ASSISTANT && matchesModel &&
            message.requestContextUsage?.requestCheckpointId == checkpointId &&
            (message.requestContextUsage != null || message.lastRequestUsage != null ||
                message.getTools().isEmpty() && message.usage != null)
    }
    if (anchorIndex < 0) {
        return ContextCapacityState(null, capacity, true, emptyList(), cacheHitRate = cacheRate,
            autoCompressionEnabled = autoCompressionEnabled, cumulativeTokens = cumulative)
    }
    val reply = active[anchorIndex]
    val snapshot = reply.requestContextUsage
    // Old messages with multiple model/tool calls only have a task total. It cannot
    // safely substitute for the final request's context size.
    val reported = reply.lastRequestUsage ?: reply.usage.takeIf {
        snapshot == null && reply.getTools().isEmpty()
    }
    val validReported = reported?.takeIf { it.promptTokens > 0 && it.completionTokens >= 0 }
    val numericReference = snapshot?.previousConfirmedContextTokens?.takeIf { it >= 0L }
    val previousReference = snapshot?.previousConfirmedContextUsage?.takeIf {
        it.requestModelId == model?.id.toString() && it.requestCheckpointId == checkpointId &&
            it.promptTokens > 0 && it.completionTokens >= 0 &&
            (numericReference == null || it.contextTokens == numericReference)
    } ?: model?.let { previousConfirmedContextUsage(active.take(anchorIndex), it) }?.takeIf {
        numericReference == null || it.contextTokens == numericReference
    }
    val previousReported = numericReference ?: previousReference?.contextTokens
        ?: model?.let { previousConfirmedContextTokens(active.take(anchorIndex), it) }
    if (validReported == null) {
        // A local character/schema estimate can be far above the provider's tokenizer.
        // Never replace a confirmed ~99K with an uncalibrated ~166K while streaming.
        // Keep an explicitly labelled reference until this request reports its usage.
        return ContextCapacityState(previousReported, capacity, false,
            previousReference?.let(::categoryCounts).orEmpty(), cacheHitRate = previousReference?.cacheHitRate ?: cacheRate,
            autoCompressionEnabled = autoCompressionEnabled, awaitingUsage = true, cumulativeTokens = cumulative)
    }
    val input = validReported.promptTokens.toLong()
    val output = validReported.completionTokens.toLong()
    // Newly queued user instructions also wait for the next report rather than
    // briefly adding a local estimate before the new assistant placeholder arrives.
    val hasPendingMessages = anchorIndex < active.lastIndex
    val used = input + output
    val categories = snapshot?.let { categoryCounts(it, input, output) }.orEmpty()
    return ContextCapacityState(
        usedTokens = used,
        capacityTokens = capacity,
        usageEstimated = false,
        breakdown = categories,
        breakdownEstimated = true,
        cacheHitRate = cacheRate,
        autoCompressionEnabled = autoCompressionEnabled,
        changeTokens = previousReported?.takeUnless { hasPendingMessages }?.let { used - it },
        awaitingUsage = hasPendingMessages,
        cumulativeTokens = cumulative,
    )
}

/** Sum reported request costs once per assistant, independently of the active checkpoint. */
private fun cumulativeRequestTokens(messages: List<UIMessage>): Long? {
    var total = 0L
    var hasReportedUsage = false
    for (message in messages) {
        if (message.role != MessageRole.ASSISTANT) continue
        val usage = message.usage ?: message.lastRequestUsage ?: continue
        if (usage.promptTokens < 0 || usage.completionTokens < 0) continue
        val reported = maxOf(usage.totalTokens.toLong(), usage.promptTokens.toLong() + usage.completionTokens)
        if (reported <= 0L) continue
        total += reported
        hasReportedUsage = true
    }
    return total.takeIf { hasReportedUsage }
}

/** Weighted input-cache rate; cached tokens are a subset of input, not another cost. */
internal fun contextCacheHitRate(messages: List<UIMessage>): Double? {
    var input = 0L
    var cached = 0L
    for (message in messages) {
        if (message.role != MessageRole.ASSISTANT) continue
        val usage = message.usage ?: continue
        if (usage.promptTokens <= 0 || usage.cachedTokens < 0 || usage.cachedTokens > usage.promptTokens) continue
        input += usage.promptTokens.toLong()
        cached += usage.cachedTokens.toLong()
    }
    return if (input > 0L) cached.toDouble() / input else null
}

/** Provider reports total input only. Split it using local weights, clearly marked estimates. */
private fun categoryCounts(snapshot: RequestContextUsageSnapshot, input: Long, additionalMessages: Long): List<ContextCapacityCategory> {
    return categoryCounts(listOf(snapshot.systemTools, snapshot.messages, snapshot.mcpTools,
        snapshot.skills, snapshot.other, snapshot.systemPrompt), input, additionalMessages)
}

private fun categoryCounts(reference: RequestContextUsageReference): List<ContextCapacityCategory> =
    categoryCounts(listOf(reference.systemTools, reference.messages, reference.mcpTools,
        reference.skills, reference.other, reference.systemPrompt), reference.promptTokens, reference.completionTokens)

private fun categoryCounts(rawWeights: List<Long>, input: Long, additionalMessages: Long): List<ContextCapacityCategory> {
    val weights = rawWeights.map { it.coerceAtLeast(0) }
    val denominator = weights.sum()
    val allocation = LongArray(weights.size)
    if (denominator > 0L) {
        val remainders = DoubleArray(weights.size)
        for (index in weights.indices) {
            val exact = weights[index].toDouble() * input.toDouble() / denominator.toDouble()
            allocation[index] = floor(exact).toLong()
            remainders[index] = exact - allocation[index]
        }
        var left = input - allocation.sum()
        for (index in weights.indices.sortedByDescending { remainders[it] }) {
            if (left <= 0L) break
            allocation[index]++
            left--
        }
    } else allocation[4] = input
    allocation[1] += additionalMessages
    val ids = listOf("system_tools", "messages", "mcp_tools", "skills", "other", "system_prompt")
    val labels = listOf("系统工具", "消息", "MCP 工具", "技能", "其他", "系统提示词")
    return ids.indices.map { ContextCapacityCategory(ids[it], labels[it], allocation[it]) }
}
