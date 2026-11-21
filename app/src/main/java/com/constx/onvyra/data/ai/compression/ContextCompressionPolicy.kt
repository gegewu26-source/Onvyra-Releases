package com.constx.onvyra.data.ai.compression

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.activeContextCheckpointIndex
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.ai.currentRequestCheckpointId
import com.constx.onvyra.data.ai.previousConfirmedContextTokens
import com.constx.onvyra.data.ai.requestMcpDeclarationKey

internal data class CompressionPlan(val startIndex: Int, val cutIndex: Int) {
    fun source(messages: List<UIMessage>) = messages.subList(startIndex, cutIndex)
        .filter { !it.isContextCheckpoint || !it.contextCheckpointInvalidated }
}

/** Keep complete user turns, including all of their tool calls, in the recent window. */
internal fun planContextCompression(
    messages: List<UIMessage>,
    keepRecentMessages: Int,
    recentTokenBudget: Long? = null,
): CompressionPlan? {
    if (messages.size < 2) return null
    val checkpoint = messages.activeContextCheckpointIndex()
    val start = checkpoint.coerceAtLeast(0)
    var cut = (messages.size - keepRecentMessages.coerceAtLeast(1)).coerceAtLeast(start + 1)
        .coerceAtMost(messages.lastIndex)
    if (recentTokenBudget != null) {
        var recentTokens = 0L
        for (i in messages.lastIndex downTo start + 1) {
            val tokens = estimateTextTokens(renderCompressionMessage(messages[i]))
            if (recentTokens + tokens > recentTokenBudget && i < messages.lastIndex) {
                cut = maxOf(cut, i + 1)
                break
            }
            recentTokens += tokens
        }
    }
    // Any unfinished tool must remain outside the summarized prefix.
    val pending = (start until messages.size).firstOrNull { i -> messages[i].getTools().any { !it.isExecuted } }
    if (pending != null) cut = minOf(cut, pending)
    while (cut > start && (messages[cut].role != MessageRole.USER || messages[cut].isContextCheckpoint)) cut--
    if (cut <= start || (checkpoint >= 0 && cut <= checkpoint + 1)) return null
    return CompressionPlan(start, cut)
}

/** Translation completion and usage bookkeeping do not invalidate the content being summarized. */
internal fun unchangedCompressionPrefix(
    source: List<UIMessage>,
    current: List<UIMessage>,
    cutIndex: Int,
): Boolean {
    if (current.size < cutIndex) return false
    require(cutIndex >= 0) { "Requested element count $cutIndex is less than zero." }
    val sourceMessages = source.iterator()
    val currentMessages = current.iterator()
    repeat(minOf(cutIndex, source.size)) {
        val before = sourceMessages.next()
        val after = currentMessages.next()
        if (before.id != after.id || before.role != after.role || before.parts != after.parts ||
            before.isContextCheckpoint != after.isContextCheckpoint
        ) return false
    }
    return true
}

internal fun estimateRequestTokens(messages: List<UIMessage>, tools: List<Tool>): Long =
    messages.sumOf { estimateTextTokens(renderCompressionMessage(it)) + 8 } + tools.sumOf {
        estimateTextTokens(it.name) + estimateTextTokens(it.description) +
            estimateTextTokens(it.parameters().toString()) + 16
    }

internal data class AutoCompressionBudget(
    val window: Long,
    val threshold: Long,
    val summaryTokens: Int,
    val hasCustomThreshold: Boolean = false,
)

/** Use the existing per-model context setting, including custom proxy models. */
internal fun autoCompressionBudget(
    model: Model,
    outputTokens: Int?,
    thresholdTokens: Int = 0,
): AutoCompressionBudget {
    val window = model.contextLength.coerceAtLeast(2048).toLong()
    val reserve = maxOf(outputTokens?.toLong() ?: 4096L, window / 10).coerceAtMost(window / 3)
    val requestedThreshold = if (thresholdTokens > 0) thresholdTokens.toLong() else (window * 0.8).toLong()
    return AutoCompressionBudget(
        window,
        minOf(requestedThreshold, window - reserve),
        (window / 32).toInt().coerceIn(256, 4000),
        hasCustomThreshold = thresholdTokens > 0,
    )
}

internal fun shouldAutoCompress(
    source: List<UIMessage>,
    preparedInput: List<UIMessage>,
    tools: List<Tool>,
    model: Model,
    budget: AutoCompressionBudget,
    messageLimit: Int,
): Boolean {
    val active = source.limitContext(0)
    if (!budget.hasCustomThreshold && messageLimit > 0 &&
        active.count { !it.isContextCheckpoint } > messageLimit
    ) return true

    // Use the same provider-confirmed input + output count as the context indicator.
    // Local estimates can significantly overcount translated text and tool schemas,
    // so only use them to trigger when the provider has not reported usable counts.
    val confirmed = previousConfirmedContextTokens(active, model)
        ?: return estimateRequestTokens(preparedInput, tools) >= budget.threshold
    if (confirmed < budget.threshold) return false
    if (ModelAbility.TOOL !in model.abilities) return true

    // A narrower tool declaration set can reduce the next text-only request.
    // Do not compact it solely because the preceding, larger request was full.
    val checkpointId = currentRequestCheckpointId(active)
    val modelId = model.id.toString()
    val snapshot = active.lastOrNull { message ->
        val request = message.requestContextUsage
        val usage = message.lastRequestUsage
        message.role == MessageRole.ASSISTANT &&
            (request?.requestModelId ?: message.modelId?.toString()) == modelId &&
            request?.requestCheckpointId == checkpointId &&
            usage != null && usage.promptTokens > 0 && usage.completionTokens >= 0
    }?.takeIf { message ->
        val usage = requireNotNull(message.lastRequestUsage)
        usage.promptTokens.toLong() + usage.completionTokens.toLong() == confirmed
    }?.requestContextUsage
    val previousMcpKey = snapshot?.mcpDeclarationKey
    val changedTextOnlyMcpSet = previousMcpKey != null && snapshot?.containsImages == false &&
        previousMcpKey != requestMcpDeclarationKey(tools) &&
        preparedInput.none { message ->
            message.parts.any { part ->
                part is UIMessagePart.Image || part is UIMessagePart.Tool && part.output.any { it is UIMessagePart.Image }
            }
        }
    return !changedTextOnlyMcpSet || estimateRequestTokens(preparedInput, tools) >= budget.threshold
}

internal fun Conversation.compressionPrefixMatches(source: List<UIMessage>, cutIndex: Int): Boolean =
    unchangedCompressionPrefix(source, currentMessages, cutIndex)
