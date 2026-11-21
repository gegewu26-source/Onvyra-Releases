package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.TokenUsage

/**
 * Gemini GenerateContent counts tool-use prompts separately from the original prompt.
 * Keep all input in the input bucket, and thoughts in output; cached content is already
 * contained in the original prompt and must not be added a second time.
 * https://ai.google.dev/api/generate-content#UsageMetadata
 */
internal fun parseGoogleUsageMetadata(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    fun count(key: String): Int = (usage[key] as? JsonPrimitive)?.longOrNull
        ?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    fun sum(first: Int, second: Int): Int =
        (first.toLong() + second).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val input = sum(count("promptTokenCount"), count("toolUsePromptTokenCount"))
    val output = sum(count("candidatesTokenCount"), count("thoughtsTokenCount"))
    return TokenUsage(
        promptTokens = input,
        completionTokens = output,
        cachedTokens = count("cachedContentTokenCount"),
        // Preserve the upstream total, including any provider-specific usage breakdown.
        totalTokens = if ("totalTokenCount" in usage) count("totalTokenCount") else sum(input, output),
    )
}
