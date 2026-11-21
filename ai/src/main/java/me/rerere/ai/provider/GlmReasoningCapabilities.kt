package me.rerere.ai.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel

// Official API: https://docs.z.ai/api-reference/llm/chat-completion
// https://docs.z.ai/guides/llm/glm-5.3 and https://docs.z.ai/guides/llm/glm-4.5
private enum class GlmThinkingProtocol { TOGGLE, EFFORT_WITH_OFF, ALWAYS_ON_EFFORT }

private fun Model.glmThinkingProtocol(): GlmThinkingProtocol? {
    // These two opaque gateway aliases were explicitly supplied by the user.
    // Normalize only for capability selection; the encoder keeps model.modelId unchanged.
    val id = when (val name = modelId.lowercase().substringAfterLast('/')) {
        "glm5.3" -> "glm-5.3"
        "glm5.3flash" -> "glm-5.3-flash"
        else -> name
    }
    return when (id) {
        "glm-5.3", "glm-5.3-flash", "glm-5.3-flashx" -> GlmThinkingProtocol.ALWAYS_ON_EFFORT
        "glm-5.2" -> GlmThinkingProtocol.EFFORT_WITH_OFF
        "glm-5.1", "glm-5", "glm-4.7", "glm-4.7-flash", "glm-4.7-flashx", "glm-4.6",
        "glm-4.5", "glm-4.5-air", "glm-4.5-x", "glm-4.5-airx", "glm-4.5-flash" -> GlmThinkingProtocol.TOGGLE
        else -> null
    }
}

internal fun glmReasoningCapabilities(model: Model): ReasoningCapabilities? {
    val protocol = model.glmThinkingProtocol() ?: return null
    return when (protocol) {
        GlmThinkingProtocol.ALWAYS_ON_EFFORT -> ReasoningCapabilities(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            supportsOff = false,
            levelMappings = mapOf(
                ReasoningLevel.OFF to ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM to ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH to ReasoningLevel.MAX,
            ),
        )
        GlmThinkingProtocol.EFFORT_WITH_OFF -> ReasoningCapabilities(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            levelMappings = mapOf(
                ReasoningLevel.LOW to ReasoningLevel.HIGH,
                ReasoningLevel.MEDIUM to ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH to ReasoningLevel.MAX,
            ),
        )
        GlmThinkingProtocol.TOGGLE -> ReasoningCapabilities(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH),
            isToggleOnly = true,
            levelMappings = mapOf(
                ReasoningLevel.LOW to ReasoningLevel.HIGH,
                ReasoningLevel.MEDIUM to ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH to ReasoningLevel.HIGH,
                ReasoningLevel.MAX to ReasoningLevel.HIGH,
            ),
        )
    }
}

/** Native GLM controls for official endpoints and plain compatible proxies, never guessed aliases. */
internal fun glmThinkingRequest(model: Model, requested: ReasoningLevel): JsonObject? {
    val protocol = model.glmThinkingProtocol() ?: return null
    val effective = glmReasoningCapabilities(model)!!.effectiveLevel(requested)
    return buildJsonObject {
        if (effective == ReasoningLevel.AUTO) return@buildJsonObject
        put("thinking", buildJsonObject {
            put("type", if (effective == ReasoningLevel.OFF) "disabled" else "enabled")
        })
        if (protocol != GlmThinkingProtocol.TOGGLE && effective != ReasoningLevel.OFF) {
            put("reasoning_effort", effective.effort)
        }
    }
}
