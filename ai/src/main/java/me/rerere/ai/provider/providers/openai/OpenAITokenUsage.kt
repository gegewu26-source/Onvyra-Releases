package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.accumulate

/** Provider counts only. An absent, null or empty usage object remains unknown. */
internal fun parseOpenAITokenUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    fun JsonObject.count(key: String): Int? = (get(key) as? JsonPrimitive)?.longOrNull
        ?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    val input = usage.count("input_tokens") ?: usage.count("prompt_tokens")
    val output = usage.count("output_tokens") ?: usage.count("completion_tokens")
    val total = usage.count("total_tokens")
    if (input == null && output == null && total == null) return null
    val cached = (usage["input_tokens_details"] as? JsonObject)?.count("cached_tokens")
        ?: (usage["prompt_tokens_details"] as? JsonObject)?.count("cached_tokens")
    return TokenUsage(input ?: 0, output ?: 0, cached ?: 0,
        maxOf(total ?: 0, ((input ?: 0).toLong() + (output ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
}

/** Hosted image-tool usage is separate from the response model's usage. */
internal fun parseOpenAIResponseUsage(response: JsonObject): TokenUsage? =
    parseOpenAITokenUsage(response["usage"] as? JsonObject).accumulate(
        parseOpenAITokenUsage((response["tool_usage"] as? JsonObject)?.get("image_gen") as? JsonObject))
