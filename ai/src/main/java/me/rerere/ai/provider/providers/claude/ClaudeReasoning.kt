package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ReasoningCapabilities
import me.rerere.ai.provider.TextGenerationParams

// Sources: https://platform.claude.com/docs/en/build-with-claude/thinking
// https://platform.claude.com/docs/en/build-with-claude/effort
private data class ClaudeThinkingProfile(
    val adaptive: Boolean,
    val supportsOff: Boolean = true,
    val supportsXhigh: Boolean = true,
    val supportsMax: Boolean = true,
    val supportsSampling: Boolean = true,
)

private fun Model.claudeThinkingProfile(): ClaudeThinkingProfile? {
    val id = modelId.lowercase().substringAfterLast('/').replace('.', '-')
    fun matches(base: String): Boolean = Regex(
        Regex.escape(base) + "(?:-\\d{8}|-latest)?"
    ).matches(id)
    val all = listOf("claude-opus-4-7", "claude-opus-4-8", "claude-opus-5", "claude-sonnet-5",
        "claude-haiku-5-5")
    val alwaysOn = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1",
        "claude-mythos-5-1", "claude-fable-5", "claude-mythos-5")
    return when {
        all.any(::matches) -> ClaudeThinkingProfile(adaptive = true, supportsSampling = false)
        alwaysOn.any(::matches) -> ClaudeThinkingProfile(
            adaptive = true, supportsOff = false, supportsSampling = false,
        )
        matches("claude-mythos-preview") -> ClaudeThinkingProfile(
            adaptive = true, supportsOff = false, supportsXhigh = false, supportsSampling = false,
        )
        matches("claude-opus-4-6") || matches("claude-sonnet-4-6") -> ClaudeThinkingProfile(
            adaptive = true, supportsXhigh = false,
        )
        matches("claude-opus-4-5") || matches("claude-sonnet-4-5") ||
            matches("claude-haiku-4-5") || matches("claude-opus-4-1") ||
            matches("claude-opus-4") || matches("claude-sonnet-4") ||
            matches("claude-3-7-sonnet") -> ClaudeThinkingProfile(adaptive = false)
        else -> null
    }
}

internal fun claudeReasoningCapabilities(model: Model): ReasoningCapabilities? {
    val profile = model.claudeThinkingProfile() ?: return null
    val levels = ReasoningLevel.entries.filter {
        when (it) {
            ReasoningLevel.OFF -> profile.supportsOff
            ReasoningLevel.XHIGH -> !profile.adaptive || profile.supportsXhigh
            ReasoningLevel.MAX -> !profile.adaptive || profile.supportsMax
            else -> true
        }
    }
    return ReasoningCapabilities(
        supportedLevels = levels,
        supportsOff = profile.supportsOff,
        levelMappings = buildMap {
            if (!profile.supportsOff) put(ReasoningLevel.OFF, ReasoningLevel.LOW)
            if (profile.adaptive && !profile.supportsXhigh) put(ReasoningLevel.XHIGH, ReasoningLevel.HIGH)
        },
    )
}

internal fun Model.hasClaudeReasoningControls(): Boolean =
    ModelAbility.REASONING in abilities || claudeReasoningCapabilities(this) != null

internal fun claudeAllowsSampling(model: Model): Boolean =
    model.claudeThinkingProfile()?.supportsSampling ?: true

internal fun claudeThinkingRequest(params: TextGenerationParams, maxTokens: Int): JsonObject {
    val profile = params.model.claudeThinkingProfile()
    val level = claudeReasoningCapabilities(params.model)?.effectiveLevel(params.reasoningLevel)
        ?: params.reasoningLevel
    return buildJsonObject {
        when {
            // AUTO means provider defaults; it must not manufacture adaptive support for older models.
            level == ReasoningLevel.AUTO -> Unit
            level == ReasoningLevel.OFF -> {
                put("thinking", buildJsonObject { put("type", "disabled") })
                if (profile?.adaptive == true) {
                    // Clear high effort inherited from a previous turn; some models reject off+xhigh/max.
                    put("output_config", buildJsonObject { put("effort", "low") })
                }
            }
            profile?.adaptive == true -> {
                put("thinking", buildJsonObject {
                    put("type", "adaptive")
                    put("display", "summarized")
                })
                put("output_config", buildJsonObject { put("effort", level.effort) })
            }
            else -> {
                require(maxTokens > 1_024) {
                    "Claude thinking requires max_tokens greater than 1024; increase the output limit or turn thinking off."
                }
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", level.budgetTokens.coerceIn(1_024, maxTokens - 1))
                })
            }
        }
    }
}
