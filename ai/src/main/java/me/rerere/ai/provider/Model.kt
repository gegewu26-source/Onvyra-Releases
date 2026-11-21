package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // 上下文窗口大小(token)
    val contextLength: Int = 1_000_000,
    // Optional active effort levels advertised by the model catalogue. Absence keeps old settings compatible.
    val reasoningEffortLevels: List<String>? = null,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

/** These mainline models call the Responses image tool; they are not Images API model IDs. */
fun Model.supportsOpenAIImageTool(): Boolean =
    me.rerere.common.http.isOpenAiImageToolModel(canonicalOpenAiReasoningModelId(modelId))

/** Known image-only IDs also cover old imports that saved the default CHAT type. */
fun Model.isDedicatedImageModel(): Boolean = !supportsOpenAIImageTool() && (type == ModelType.IMAGE ||
    Regex("^(?:openai/)?(?:gpt-image-[a-z0-9.-]+|dall-e-[23])$", RegexOption.IGNORE_CASE).matches(modelId))

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



