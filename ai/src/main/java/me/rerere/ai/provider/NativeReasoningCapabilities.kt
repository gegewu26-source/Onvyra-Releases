package me.rerere.ai.provider

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.providers.claude.claudeReasoningCapabilities
import me.rerere.ai.provider.providers.google.googleReasoningCapabilities

/** Only official model identifiers select a native model profile; custom aliases stay unverified. */
internal fun nativeReasoningCapabilities(
    model: Model,
    providerSetting: ProviderSetting?,
): ReasoningCapabilities? {
    val actualProvider = model.providerOverwrite ?: providerSetting
    val unknown = ReasoningCapabilities(ReasoningLevel.entries, isKnown = false)
    return when (actualProvider) {
        is ProviderSetting.Google -> googleReasoningCapabilities(
            model, actualProvider.useInteractionsApi && !actualProvider.vertexAI,
        ) ?: unknown
        is ProviderSetting.Claude -> claudeReasoningCapabilities(model) ?: unknown
        else -> googleReasoningCapabilities(model) ?: claudeReasoningCapabilities(model)
    }
}
