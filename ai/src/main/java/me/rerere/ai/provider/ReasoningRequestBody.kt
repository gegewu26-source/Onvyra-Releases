package me.rerere.ai.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.util.mergeCustomBody

/** Explicit effort selections own depth controls. AUTO leaves configured defaults untouched. */
fun JsonObject.mergeReasoningCustomBody(bodies: List<CustomBody>, requested: ReasoningLevel): JsonObject {
    if (requested == ReasoningLevel.AUTO) return mergeCustomBody(bodies)
    val controls = setOf(
        "reasoning_effort", "enable_thinking", "thinking_budget", "thinking_mode",
        "reasoning.effort", "reasoning.enabled", "reasoning.max_tokens",
        "thinking.type", "thinking.budget_tokens", "output_config.effort",
        "generationConfig.thinkingConfig.thinkingLevel", "generationConfig.thinkingConfig.thinkingBudget",
        "generationConfig.thinkingConfig.includeThoughts",
        "generation_config.thinking_level", "generation_config.thinking_summaries",
    )
    fun sanitized(value: JsonElement, path: String): JsonElement? {
        if (path in controls) return null
        val children = controls.any { it.startsWith("$path.") }
        if (!children) return value
        if (value !is JsonObject) return null
        val filtered = value.mapNotNull { (key, child) ->
            sanitized(child, "$path.$key")?.let { key to it }
        }.toMap()
        return if (filtered.isEmpty() && value.isNotEmpty()) null else JsonObject(filtered)
    }
    return mergeCustomBody(bodies.mapNotNull { body ->
        sanitized(body.value, body.key)?.let { body.copy(value = it) }
    })
}
