package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ReasoningCapabilities
import me.rerere.ai.provider.TextGenerationParams

// Source: https://ai.google.dev/gemini-api/docs/generate-content/thinking
// Interactions accepts levels only, including for the 2.5 models that use budgets in generateContent.
private data class GoogleThinkingProfile(
    val levels: List<ReasoningLevel>? = null,
    val minimumBudget: Int = 0,
    val maximumBudget: Int = 24_576,
    val supportsOff: Boolean = false,
    val lowIsMinimal: Boolean = false,
)

private fun Model.googleThinkingProfile(): GoogleThinkingProfile? {
    val id = modelId.lowercase().substringAfterLast('/').removePrefix("google.")
    fun matches(base: String): Boolean = Regex(
        Regex.escape(base) + "(?:-preview(?:-\\d{2}-\\d{2})?|-latest|-\\d{3}|-\\d{8})?"
    ).matches(id)
    val threeLevels = listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
    return when {
        matches("gemini-4-argon") || matches("gemini-3.8-flash") ||
            matches("gemini-3.6-flash") || matches("gemini-3.5-flash-lite") ||
            matches("gemini-3.1-flash-lite") || matches("gemini-3.1-pro") ||
            matches("gemini-3-flash") || matches("gemini-robotics-er-2") ->
            GoogleThinkingProfile(levels = threeLevels)
        matches("gemini-3-pro") -> GoogleThinkingProfile(
            levels = listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH),
        )
        matches("gemini-3.1-flash-lite-image") -> GoogleThinkingProfile(
            levels = listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH), lowIsMinimal = true,
        )
        matches("gemini-2.5-pro") -> GoogleThinkingProfile(
            minimumBudget = 128, maximumBudget = 32_768,
        )
        matches("gemini-2.5-flash") -> GoogleThinkingProfile(supportsOff = true)
        matches("gemini-2.5-flash-lite") -> GoogleThinkingProfile(
            minimumBudget = 512, supportsOff = true,
        )
        matches("gemini-robotics-er-1.6") -> GoogleThinkingProfile(supportsOff = true)
        else -> null
    }
}

internal fun googleReasoningCapabilities(
    model: Model,
    useInteractionsApi: Boolean = false,
): ReasoningCapabilities? {
    val profile = model.googleThinkingProfile() ?: return null
    val active = profile.levels ?: if (useInteractionsApi) {
        listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
    } else {
        listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH, ReasoningLevel.MAX)
    }
    val off = profile.supportsOff && !useInteractionsApi
    return ReasoningCapabilities(
        supportedLevels = (if (off) listOf(ReasoningLevel.OFF) else emptyList()) +
            ReasoningLevel.AUTO + active,
        supportsOff = off,
        levelMappings = buildMap {
            if (!off) put(ReasoningLevel.OFF, active.first())
            if (ReasoningLevel.MEDIUM !in active) put(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
            if (ReasoningLevel.XHIGH !in active) put(ReasoningLevel.XHIGH, ReasoningLevel.HIGH)
            if (ReasoningLevel.MAX !in active) put(ReasoningLevel.MAX, ReasoningLevel.HIGH)
        },
    )
}

internal fun Model.hasGoogleReasoningControls(useInteractionsApi: Boolean = false): Boolean =
    ModelAbility.REASONING in abilities || googleReasoningCapabilities(this, useInteractionsApi) != null

internal fun googleThinkingConfig(params: TextGenerationParams): JsonObject {
    val profile = params.model.googleThinkingProfile()
    val level = googleReasoningCapabilities(params.model)?.effectiveLevel(params.reasoningLevel)
        ?: params.reasoningLevel
    return buildJsonObject {
        put("includeThoughts", level != ReasoningLevel.OFF)
        if (profile?.levels != null) {
            // AUTO leaves the model's default level; minimal is never advertised as thinking-off.
            if (level != ReasoningLevel.AUTO) {
                put("thinkingLevel", if (profile.lowIsMinimal && level == ReasoningLevel.LOW) {
                    "minimal"
                } else level.effort)
            }
        } else {
            when (level) {
                ReasoningLevel.AUTO -> Unit
                ReasoningLevel.OFF -> put("thinkingBudget", 0)
                else -> put("thinkingBudget", level.budgetTokens.coerceIn(
                    profile?.minimumBudget ?: 0, profile?.maximumBudget ?: 32_768,
                ))
            }
        }
    }
}

internal fun googleInteractionsThinkingConfig(params: TextGenerationParams): JsonObject {
    val profile = params.model.googleThinkingProfile()
    val level = googleReasoningCapabilities(params.model, useInteractionsApi = true)
        ?.effectiveLevel(params.reasoningLevel) ?: params.reasoningLevel
    return buildJsonObject {
        put("thinking_summaries", if (level == ReasoningLevel.OFF) "none" else "auto")
        if (level != ReasoningLevel.AUTO) {
            put("thinking_level", when {
                profile?.lowIsMinimal == true && level == ReasoningLevel.LOW -> "minimal"
                level == ReasoningLevel.OFF || level == ReasoningLevel.LOW -> "low"
                level == ReasoningLevel.MEDIUM -> "medium"
                else -> "high"
            })
        }
    }
}
