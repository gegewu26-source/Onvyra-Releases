package me.rerere.ai.provider

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Request controls shared by the picker and each protocol encoder. AUTO keeps provider defaults. */
data class ReasoningCapabilities(
    val supportedLevels: List<ReasoningLevel>,
    val supportsOff: Boolean = ReasoningLevel.OFF in supportedLevels,
    val isKnown: Boolean = true,
    val levelMappings: Map<ReasoningLevel, ReasoningLevel> = emptyMap(),
    val isToggleOnly: Boolean = false,
) {
    fun effectiveLevel(requested: ReasoningLevel): ReasoningLevel {
        levelMappings[requested]?.let { return it }
        if (requested in supportedLevels) return requested
        if (requested == ReasoningLevel.AUTO) return ReasoningLevel.AUTO
        val active = supportedLevels.filter { it != ReasoningLevel.OFF && it != ReasoningLevel.AUTO }
        if (active.isEmpty()) return supportedLevels.firstOrNull { it == ReasoningLevel.AUTO }
            ?: supportedLevels.firstOrNull() ?: ReasoningLevel.AUTO
        if (requested == ReasoningLevel.OFF) return active.first()
        return active.minBy { kotlin.math.abs(it.ordinal - requested.ordinal) }
    }
}

fun reasoningCapabilities(model: Model, providerSetting: ProviderSetting?): ReasoningCapabilities {
    // IMAGE and EMBEDDING models do not use a text-chat reasoning control. Keeping
    // only AUTO prevents stale assistant settings from being sent to a text endpoint.
    if (model.type != ModelType.CHAT) {
        return ReasoningCapabilities(listOf(ReasoningLevel.AUTO), supportsOff = false)
    }
    val actualProvider = model.providerOverwrite ?: providerSetting
    val inferred = inferReasoningCapabilities(model, actualProvider)
    // Native encoders use their own budgets; this catalogue schema belongs to compatible effort APIs.
    if (actualProvider is ProviderSetting.Google || actualProvider is ProviderSetting.Claude) return inferred
    val advertised = model.reasoningEffortLevels?.mapNotNull { value ->
        ReasoningLevel.entries.firstOrNull { it.effort == value && it != ReasoningLevel.OFF && it != ReasoningLevel.AUTO }
    }?.distinct()?.filter { !inferred.isKnown || it in inferred.supportedLevels }
        ?.sortedBy { it.ordinal }.orEmpty()
    if (advertised.isEmpty() || inferred.isToggleOnly) return inferred
    // DeepSeek's effort.supported_levels only advertises active efforts, not the off switch.
    return inferred.copy(
        supportedLevels = buildList {
            if (inferred.supportsOff) add(ReasoningLevel.OFF)
            add(ReasoningLevel.AUTO)
            addAll(advertised)
        },
        levelMappings = inferred.levelMappings.filterValues { it in advertised },
    )
}

private fun inferReasoningCapabilities(model: Model, providerSetting: ProviderSetting?): ReasoningCapabilities {
    if (providerSetting is ProviderSetting.Google || providerSetting is ProviderSetting.Claude) {
        return nativeReasoningCapabilities(model, providerSetting)
            ?: ReasoningCapabilities(ReasoningLevel.entries, isKnown = false)
    }
    val openAI = providerSetting as? ProviderSetting.OpenAI
    val host = openAI?.baseUrl?.toHttpUrlOrNull()?.host
    // These services expose an on/off switch, not seven independent effort levels.
    val binaryHost = host in setOf("ark.cn-beijing.volces.com", "chat.intern-ai.org.cn", "aiping.cn", "api.moonshot.cn", "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com")
    val knownKimiSwitch = host == "api.moonshot.cn" && Regex("^kimi-k2[.-][56]$").matches(model.modelId.lowercase())
    if (binaryHost && (ModelAbility.REASONING in model.abilities || knownKimiSwitch)) {
        return ReasoningCapabilities(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH),
            isKnown = knownKimiSwitch,
            levelMappings = ReasoningLevel.entries.filter { it != ReasoningLevel.OFF && it != ReasoningLevel.AUTO }
                .associateWith { ReasoningLevel.HIGH },
            isToggleOnly = true,
        )
    }
    if (isDeepSeekReasoningModel(model.modelId) || (host == "api.deepseek.com" && ModelAbility.REASONING in model.abilities)) {
        return ReasoningCapabilities(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            isKnown = isDeepSeekReasoningModel(model.modelId),
            levelMappings = mapOf(ReasoningLevel.MEDIUM to ReasoningLevel.HIGH, ReasoningLevel.XHIGH to ReasoningLevel.HIGH),
        )
    }
    glmReasoningCapabilities(model)?.let { return it }
    nativeReasoningCapabilities(model, providerSetting)?.let { return it }
    val id = canonicalOpenAiReasoningModelId(model.modelId)
    fun matches(pattern: String) = Regex("^(?:$pattern)$").matches(id)
    fun levels(off: Boolean, xhigh: Boolean = false, max: Boolean = false): ReasoningCapabilities = ReasoningCapabilities(
        buildList {
            if (off) add(ReasoningLevel.OFF)
            add(ReasoningLevel.AUTO)
            add(ReasoningLevel.LOW)
            add(ReasoningLevel.MEDIUM)
            add(ReasoningLevel.HIGH)
            if (xhigh) add(ReasoningLevel.XHIGH)
            if (max) add(ReasoningLevel.MAX)
        }
    )
    when {
        matches("gpt-5-pro") -> return ReasoningCapabilities(listOf(ReasoningLevel.AUTO, ReasoningLevel.HIGH))
        matches("gpt-6-(?:luna|sol)|gpt-5\\.6(?:-sol)?") -> return levels(off = true, xhigh = true, max = true)
        matches("gpt-6-astra|gpt-6\\.1-sol") -> return levels(off = false, xhigh = true, max = true)
        matches("gpt-5\\.(?:2|5)|gpt-5\\.4(?:-mini)?") -> return levels(off = true, xhigh = true)
        matches("gpt-5\\.1") -> return levels(off = true)
        matches("gpt-5(?:-mini|-nano)?|o1|o3(?:-mini)?|o4-mini") -> return levels(off = false)
    }
    return ReasoningCapabilities(ReasoningLevel.entries, isKnown = false)
}

/** Only explicit model IDs select a native protocol; opaque aliases remain compatible/unknown. */
internal fun isDeepSeekReasoningModel(modelId: String): Boolean {
    // These are confirmed wire IDs from the user's compatible gateway. Only
    // capability/protocol selection is normalized; the request model ID stays intact.
    val id = when (val candidate = modelId.lowercase().removePrefix("deepseek/")) {
        "wb-deepseek-v4.1-flash", "wb-cn-deepseek-v4.1-flash" -> "deepseek-v4.1-flash"
        else -> candidate
    }
    if (id == "deepseekv4.1f") return true // Explicitly confirmed alias used by this gateway.
    return Regex("^deepseek-(?:v4(?:[.-]1)?-(?:flash|pro)|flash)(?:-\\d{4}-\\d{2}-\\d{2})?$").matches(id)
}

/** A named gateway owns its wire protocol even when it carries a DeepSeek model. */
internal fun chatReasoningProtocolHost(model: Model, host: String): String {
    val gateways = setOf(
        "openrouter.ai", "dashscope.aliyuncs.com", "ark.cn-beijing.volces.com", "api.mistral.ai",
        "chat.intern-ai.org.cn", "api.siliconflow.cn", "aiping.cn", "open.bigmodel.cn",
        "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com", "api.moonshot.cn",
        "integrate.api.nvidia.com", "opencode.ai",
    )
    if (glmReasoningCapabilities(model) != null && (host !in gateways || host == "open.bigmodel.cn")) {
        return "__glm_native__"
    }
    return if (isDeepSeekReasoningModel(model.modelId) && host !in gateways) "api.deepseek.com" else host
}

/** Capability-only normalization for official IDs and gateway aliases explicitly confirmed by the user. */
internal fun canonicalOpenAiReasoningModelId(modelId: String): String {
    val id = modelId.lowercase().removePrefix("openai/").replace("_", "-")
        .replace(Regex("-\\d{4}-\\d{2}-\\d{2}$"), "")
    return when {
        id == "gpt6a" -> "gpt-6-astra"
        id == "6.1sol" || Regex("^gpt-?6\\.1-?sol$").matches(id) -> "gpt-6.1-sol"
        Regex("^gpt-?6(?:\\.?)1(?:-?iso)?$").matches(id) -> "gpt-6.1-sol"
        id == "6luna" || Regex("^gpt-?6-?luna$").matches(id) -> "gpt-6-luna"
        Regex("^gpt-?6-?astra$").matches(id) -> "gpt-6-astra"
        Regex("^gpt-?6-?sol$").matches(id) -> "gpt-6-sol"
        else -> id
    }
}
