package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Choose the protocol, never rewrite the selected model or API origin. */
internal fun usesResponsesForRequest(setting: ProviderSetting.OpenAI, params: TextGenerationParams): Boolean {
    if (setting.useResponseApi) return true
    // Named gateways own their protocol; a model name alone must not override OpenRouter.
    if (setting.baseUrl.toHttpUrlOrNull()?.host == "openrouter.ai") return false
    if (params.model.supportsOpenAIImageTool()) return true
    if (params.model.type != ModelType.CHAT) return false
    if (BuiltInTools.ImageGeneration in params.model.tools ||
        me.rerere.ai.provider.Modality.IMAGE in params.model.outputModalities) return true
    if (ModelAbility.TOOL !in params.model.abilities || params.tools.isEmpty()) return false
    val id = canonicalOpenAiReasoningModelId(params.model.modelId)
    if (id in setOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra")) return true
    if (id !in setOf("gpt-6-luna", "gpt-6-sol", "gpt-5.6", "gpt-5.6-sol")) return false
    val level = reasoningCapabilities(params.model, setting).effectiveLevel(params.reasoningLevel)
    // AUTO can carry a custom effort, otherwise the model defaults to medium.
    return level != ReasoningLevel.OFF || params.customBody.any {
        it.key == "reasoning" || it.key == "reasoning_effort"
    }
}
