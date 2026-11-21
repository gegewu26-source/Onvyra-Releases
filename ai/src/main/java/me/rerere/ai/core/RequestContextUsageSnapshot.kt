package me.rerere.ai.core

import kotlinx.serialization.Serializable

/**
 * Local category estimates for one prepared model request, never a billing record.
 * Only counts are saved: prompts, schemas, arguments, credentials and image bytes
 * remain in their existing owners. Provider input usage remains authoritative.
 */
@Serializable
data class RequestContextUsageSnapshot(
    val systemTools: Long = 0,
    val messages: Long = 0,
    val mcpTools: Long = 0,
    val skills: Long = 0,
    val other: Long = 0,
    val systemPrompt: Long = 0,
    val preparedMessageCount: Int = 0,
    val sourceMessageCount: Int = 0,
    val requestModelId: String? = null,
    // Existing assistant content already included in this request, for in-flight growth estimates.
    val responsePrefixTokens: Long = 0,
    // The preceding confirmed request is a reference while this request awaits provider usage.
    // It is not part of the local category estimate and is never a cumulative billing total.
    val previousConfirmedContextTokens: Long? = null,
    // Binds both this request's usage and its reference to the active compression checkpoint.
    val requestCheckpointId: String? = null,
    val containsImages: Boolean = false,
    val schemaVersion: Int = 1,
    // Hash of the MCP declaration name set only; never stores catalog text or schemas.
    val mcpDeclarationKey: String? = null,
    // One flat confirmed reference survives clearing lastRequestUsage for the next
    // tool request. It deliberately cannot contain another snapshot/reference.
    val previousConfirmedContextUsage: RequestContextUsageReference? = null,
) {
    val estimatedInputTokens: Long
        get() = listOf(systemTools, messages, mcpTools, skills, other, systemPrompt)
            .sumOf { it.coerceAtLeast(0) }
}

/** Counts only, for keeping the last complete context view visible between usage reports. */
@Serializable
data class RequestContextUsageReference(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val systemTools: Long = 0,
    val messages: Long = 0,
    val mcpTools: Long = 0,
    val skills: Long = 0,
    val other: Long = 0,
    val systemPrompt: Long = 0,
    val requestModelId: String? = null,
    val requestCheckpointId: String? = null,
    val cacheHitRate: Double? = null,
) {
    val contextTokens: Long get() = promptTokens + completionTokens
}
