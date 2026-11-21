package com.constx.onvyra.data.ai

import com.constx.onvyra.data.ai.compression.estimateTextTokens
import com.constx.onvyra.data.ai.compression.renderCompressionMessage
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.RequestContextUsageSnapshot
import me.rerere.ai.core.RequestContextUsageReference
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import com.constx.onvyra.utils.JsonInstant
import java.security.MessageDigest

/** Stable declaration identity without evaluating schemas or persisting their names/content. */
internal fun requestMcpDeclarationKey(tools: List<Tool>): String {
    val names = tools.asSequence().map { it.name }
        .filter { it.startsWith("mcp__") || it == MCP_TOOL_SEARCH }.toSortedSet()
    return MessageDigest.getInstance("SHA-256").digest(names.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

internal fun currentRequestCheckpointId(messages: List<UIMessage>): String? = messages.limitContext(0)
    .firstOrNull()?.takeIf { it.isContextCheckpoint }?.id?.toString()

/** Preserve the complete previous view, not just its total, across repeated tool requests. */
internal fun previousConfirmedContextUsage(messages: List<UIMessage>, model: Model): RequestContextUsageReference? {
    val active = messages.limitContext(0)
    val checkpointId = currentRequestCheckpointId(active)
    for (message in active.asReversed()) {
        if (message.role != MessageRole.ASSISTANT) continue
        val snapshot = message.requestContextUsage
        val sameModel = snapshot?.requestModelId?.let { it == model.id.toString() }
            ?: (message.modelId == model.id)
        if (!sameModel || snapshot?.requestCheckpointId != checkpointId) continue
        val reported = message.lastRequestUsage ?: message.usage.takeIf {
            snapshot == null && message.getTools().isEmpty()
        }
        if (reported != null && reported.promptTokens > 0 && reported.completionTokens >= 0) {
            // A legacy count without category weights cannot supply invented categories.
            if (snapshot == null) return null
            return RequestContextUsageReference(
                promptTokens = reported.promptTokens.toLong(), completionTokens = reported.completionTokens.toLong(),
                systemTools = snapshot.systemTools, messages = snapshot.messages, mcpTools = snapshot.mcpTools,
                skills = snapshot.skills, other = snapshot.other, systemPrompt = snapshot.systemPrompt,
                requestModelId = model.id.toString(), requestCheckpointId = checkpointId,
                cacheHitRate = confirmedCacheHitRate(messages),
            )
        }
        val numericReference = snapshot?.previousConfirmedContextTokens?.takeIf { it > 0L }
        snapshot?.previousConfirmedContextUsage?.takeIf {
            it.requestModelId == model.id.toString() && it.requestCheckpointId == checkpointId &&
                it.promptTokens > 0 && it.completionTokens >= 0 &&
                (numericReference == null || it.contextTokens == numericReference)
        }?.let { return it }
        // Older clients persisted only the latest total. An older assistant's
        // complete breakdown must not silently replace that newer confirmation.
        if (numericReference != null) return null
    }
    return null
}

private fun confirmedCacheHitRate(messages: List<UIMessage>): Double? {
    var input = 0L
    var cached = 0L
    for (message in messages) {
        if (message.role != MessageRole.ASSISTANT) continue
        val usage = message.usage ?: continue
        if (usage.promptTokens <= 0 || usage.cachedTokens < 0 || usage.cachedTokens > usage.promptTokens) continue
        input += usage.promptTokens.toLong()
        cached += usage.cachedTokens.toLong()
    }
    return if (input > 0) cached.toDouble() / input else null
}

/** Keep the last provider-confirmed context while a fresh request has no usage yet. */
internal fun previousConfirmedContextTokens(messages: List<UIMessage>, model: Model): Long? {
    val active = messages.limitContext(0)
    val checkpointId = active.firstOrNull()?.takeIf { it.isContextCheckpoint }?.id?.toString()
    for (message in active.asReversed()) {
        if (message.role != MessageRole.ASSISTANT) continue
        val snapshot = message.requestContextUsage
        val sameModel = snapshot?.requestModelId?.let { it == model.id.toString() }
            ?: (message.modelId == model.id)
        // Retained messages after a new checkpoint can still report the old, uncompressed input.
        if (!sameModel || snapshot?.requestCheckpointId != checkpointId) continue
        val reported = message.lastRequestUsage ?: message.usage.takeIf {
            snapshot == null && message.getTools().isEmpty()
        }
        if (reported != null && reported.promptTokens > 0 && reported.completionTokens >= 0) {
            return reported.promptTokens.toLong() + reported.completionTokens.toLong()
        }
        // Tool loops reuse the assistant message and clear lastRequestUsage before each call.
        snapshot?.previousConfirmedContextTokens?.takeIf { it > 0L }?.let { return it }
        snapshot?.previousConfirmedContextUsage?.takeIf {
            it.requestModelId == model.id.toString() && it.requestCheckpointId == checkpointId &&
                it.promptTokens > 0 && it.completionTokens >= 0
        }?.let { return it.contextTokens }
    }
    return null
}

/**
 * Measure the already transformed input, after compaction and image-history trimming.
 * Category values are deliberately labelled estimates: providers may add private
 * wrappers and tokenize text/images differently. No returned cache count is added.
 */
internal fun estimatePreparedRequestContextUsage(
    preparedMessages: List<UIMessage>,
    tools: List<Tool>,
    model: Model,
    toolSystemPrompts: List<Pair<String, String>> = emptyList(),
    otherSystemPrompts: List<String> = emptyList(),
    sourceMessageCount: Int = 0,
    responsePrefixTokens: Long = 0,
    previousConfirmedContextTokens: Long? = null,
    requestCheckpointId: String? = null,
    previousConfirmedContextUsage: RequestContextUsageReference? = null,
): RequestContextUsageSnapshot {
    var systemTools = 0L
    var mcpTools = 0L
    var skills = 0L
    // System guidance is included even for models that cannot receive function schemas.
    toolSystemPrompts.forEach { (name, text) ->
        val count = estimateTextTokens(text)
        when {
            name.startsWith("mcp__") || name == MCP_TOOL_SEARCH -> mcpTools += count
            name == "use_skill" -> skills += count
            else -> systemTools += count
        }
    }
    if (ModelAbility.TOOL in model.abilities) {
        tools.forEach { tool ->
            // Parameters are inspected only for counts, without logging/storing their contents.
            // A diagnostic must never fail a valid request if a custom schema supplier fails here.
            val schema = runCatching { JsonInstant.encodeToJsonElement(tool.parameters()).toString() }
                .getOrDefault("")
            val count = estimateTextTokens(tool.name) + estimateTextTokens(tool.description) +
                estimateTextTokens(schema) + 16
            when {
                tool.name.startsWith("mcp__") || tool.name == MCP_TOOL_SEARCH -> mcpTools += count
                tool.name == "use_skill" -> skills += count
                else -> systemTools += count
            }
        }
    }
    val toolGuidance = toolSystemPrompts.sumOf { estimateTextTokens(it.second) }
    val systemTokens = preparedMessages.filter { it.role == MessageRole.SYSTEM }
        .sumOf { message -> message.parts.filterIsInstance<UIMessagePart.Text>().sumOf { estimateTextTokens(it.text) } }
    val remainingSystem = (systemTokens - toolGuidance).coerceAtLeast(0)
    val other = otherSystemPrompts.sumOf(::estimateTextTokens).coerceAtMost(remainingSystem)
    val systemPrompt = remainingSystem - other
    val messageTokens = preparedMessages.filter { it.role != MessageRole.SYSTEM }
        .sumOf { estimateTextTokens(renderCompressionMessage(it)) + 8 }
    val containsImages = preparedMessages.any { message ->
        message.parts.any { part ->
            part is UIMessagePart.Image || part is UIMessagePart.Tool && part.output.any { it is UIMessagePart.Image }
        }
    }
    return RequestContextUsageSnapshot(
        systemTools = systemTools,
        messages = messageTokens,
        mcpTools = mcpTools,
        skills = skills,
        other = other,
        systemPrompt = systemPrompt,
        preparedMessageCount = preparedMessages.size,
        sourceMessageCount = sourceMessageCount,
        requestModelId = model.id.toString(),
        responsePrefixTokens = responsePrefixTokens,
        previousConfirmedContextTokens = previousConfirmedContextTokens ?: previousConfirmedContextUsage?.contextTokens,
        requestCheckpointId = requestCheckpointId,
        containsImages = containsImages,
        mcpDeclarationKey = if (ModelAbility.TOOL in model.abilities) requestMcpDeclarationKey(tools) else null,
        previousConfirmedContextUsage = previousConfirmedContextUsage,
    )
}
