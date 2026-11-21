package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.findProvider
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.isDedicatedImageModel
import me.rerere.ai.provider.supportsOpenAIImageTool
import me.rerere.common.http.forImageEndpoint
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import kotlin.uuid.Uuid

/** Image input is vision only. Native image output requires an explicit output/tool declaration. */
internal fun Model.supportsNativeChatImageOutput(provider: ProviderSetting): Boolean {
    if ((type != ModelType.CHAT && !supportsOpenAIImageTool()) || isDedicatedImageModel() || !provider.enabled) return false
    val effective = providerOverwrite ?: provider
    if (!effective.enabled) return false
    return when (effective) {
        is ProviderSetting.Google -> Modality.IMAGE in outputModalities
        // The provider encoder routes declared image tools/output through Responses.
        // OpenRouter retains its own chat-image protocol.
        is ProviderSetting.OpenAI -> supportsOpenAIImageTool() ||
            Modality.IMAGE in outputModalities || BuiltInTools.ImageGeneration in tools
        else -> false
    }
}

internal fun Settings.currentModelUsesNativeImageOutput(currentModel: Model?): Boolean {
    val model = currentModel ?: return false
    val owner = providers.firstOrNull { provider -> provider.models.any { it.id == model.id } } ?: return false
    return model.supportsNativeChatImageOutput(owner)
}

/** Native chat output stays on the chat protocol; a dedicated image model uses its own image endpoint. */
internal fun Settings.chatImageMediaSelection(currentModel: Model?): Pair<MediaGenerationProviderSetting, MediaGenerationModel>? {
    // Native capability is a preferred attempt, not proof this gateway will return
    // an image. Keep the user's selected endpoint available for a failed attempt.
    if (currentModelUsesNativeImageOutput(currentModel)) return selectedMediaModel(MediaKind.IMAGE)
    if (currentModel?.isDedicatedImageModel() == true) {
        return existingProviderMediaSelection(currentModel, MediaKind.IMAGE)?.let { it.mediaProvider to it.mediaModel }
    }
    return selectedMediaModel(MediaKind.IMAGE)
}

/** Select by the stored model UUID; names and model-name substrings never select an API protocol. */
internal fun Model.supportsMediaGeneration(provider: ProviderSetting, kind: MediaKind): Boolean {
    if (type == ModelType.EMBEDDING) return false
    val effective = providerOverwrite ?: provider
    if (!provider.enabled || !effective.enabled) return false
    return when (kind) {
        MediaKind.IMAGE -> (isDedicatedImageModel() && (effective is ProviderSetting.OpenAI || effective is ProviderSetting.Google)) ||
            supportsNativeChatImageOutput(provider)
        MediaKind.VIDEO -> effective is ProviderSetting.OpenAI
    }
}

/** A role selection explicitly opts into a compatible image API, even for a gateway alias. */
internal fun Model.canSelectMediaRole(provider: ProviderSetting, kind: MediaKind): Boolean =
    supportsMediaGeneration(provider, kind) || kind == MediaKind.IMAGE && type == ModelType.CHAT &&
        provider.enabled && (providerOverwrite ?: provider).let { it.enabled && it is ProviderSetting.OpenAI }

internal data class ExistingProviderMediaSelection(
    val provider: ProviderSetting,
    val model: Model,
    val kind: MediaKind,
    val mediaProvider: MediaGenerationProviderSetting,
) {
    val mediaModel: MediaGenerationModel
        get() = MediaGenerationModel(modelId = model.modelId, kind = kind, displayName = model.displayName, id = model.id)
}

/** Model/provider headers and custom bodies apply only to requests to the configured API origin. */
internal fun ExistingProviderMediaSelection.mediaClient(client: OkHttpClient): OkHttpClient {
    val base = mediaProvider.baseUrl.toHttpUrlOrNull() ?: return client
    val headers = provider.mergeCustomHeaders(model.customHeaders)
    return client.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        val target = request.url
        if (target.scheme != base.scheme || target.host != base.host || target.port != base.port) {
            chain.proceed(request)
        } else {
            val builder = request.newBuilder()
            headers.names().forEach { name ->
                builder.removeHeader(name)
                headers.values(name).forEach { builder.addHeader(name, it) }
            }
            request.body?.takeIf { it.contentType()?.subtype?.contains("json") == true && model.customBodies.isNotEmpty() }
                ?.let { body ->
                    val merged = Json.parseToJsonElement(Buffer().also(body::writeTo).readUtf8()).jsonObject
                        .mergeCustomBody(model.customBodies)
                    val bodyJson = if (kind == MediaKind.IMAGE && target.encodedPath.endsWith("/images/generations"))
                        merged.forImageEndpoint() else merged
                    builder.method(request.method, bodyJson.toString().toRequestBody(body.contentType()))
                }
            chain.proceed(builder.build())
        }
    }.build()
}

/** Bridge IDs are model UUIDs, so identically named models with distinct overrides remain separate tasks. */
internal fun Settings.existingProviderMediaSelection(modelId: Uuid, kind: MediaKind): ExistingProviderMediaSelection? {
    val owner = providers.firstOrNull { provider -> provider.models.any { it.id == modelId } } ?: return null
    val model = owner.models.first { it.id == modelId }
    return existingProviderMediaSelection(model, kind)
}

internal fun Settings.existingProviderMediaSelection(model: Model, kind: MediaKind): ExistingProviderMediaSelection? {
    val owner = providers.firstOrNull { provider -> provider.models.any { it.id == model.id } } ?: return null
    val explicitImageRole = kind == MediaKind.IMAGE && model.id == chatImageModelId &&
        model.canSelectMediaRole(owner, kind)
    if (!explicitImageRole && !model.supportsMediaGeneration(owner, kind)) return null
    val effective = model.findProvider(providers) ?: return null
    val mediaProvider = when (kind) {
        MediaKind.IMAGE -> when (effective) {
            is ProviderSetting.OpenAI -> MediaGenerationProviderSetting.OpenAI(
                id = model.id, name = owner.name, apiKey = effective.apiKey,
                baseUrl = effective.baseUrl, models = emptyList(),
            )
            // This supplies existing image capabilities only. Execution uses Google's existing GenerateContent API.
            is ProviderSetting.Google -> MediaGenerationProviderSetting.OpenRouter(
                id = model.id, name = owner.name, apiKey = effective.apiKey,
                baseUrl = effective.baseUrl, models = emptyList(),
            )
            else -> return null
        }
        MediaKind.VIDEO -> (effective as? ProviderSetting.OpenAI)?.videoMediaProvider(model.id, owner.name) ?: return null
    }
    return ExistingProviderMediaSelection(effective, model, kind, mediaProvider)
}

private fun ProviderSetting.OpenAI.videoMediaProvider(bridgeId: Uuid, displayName: String): MediaGenerationProviderSetting? {
    val url = baseUrl.toHttpUrlOrNull() ?: return null
    val host = url.host.lowercase()
    val path = url.encodedPath.trimEnd('/')
    fun base(path: String) = url.newBuilder().encodedPath(path).query(null).fragment(null).build().toString().trimEnd('/')
    return when {
        host == "openrouter.ai" && path == "/api/v1" -> MediaGenerationProviderSetting.OpenRouter(
            id = bridgeId, name = displayName, apiKey = apiKey, baseUrl = baseUrl, models = emptyList(),
        )
        (host == "ark.cn-beijing.volces.com" || host.endsWith(".volces.com")) && path == "/api/v3" ->
            MediaGenerationProviderSetting.Volcengine(
                id = bridgeId, name = displayName, apiKey = apiKey, baseUrl = baseUrl, models = emptyList(),
            )
        (host == "dashscope.aliyuncs.com" || host.endsWith(".maas.aliyuncs.com")) &&
            (path == "/api/v1" || path == "/compatible-mode/v1") -> MediaGenerationProviderSetting.Aliyun(
                id = bridgeId, name = displayName, apiKey = apiKey, baseUrl = base("/api/v1"), models = emptyList(),
            )
        (host == "api.minimax.io" || host == "api.minimaxi.com") && path == "/v2" ->
            MediaGenerationProviderSetting.MiniMax(
                id = bridgeId, name = displayName, apiKey = apiKey, baseUrl = baseUrl, models = emptyList(),
            )
        else -> MediaGenerationProviderSetting.OpenAI(
            id = bridgeId, name = displayName, apiKey = apiKey, baseUrl = baseUrl, models = emptyList(),
        )
    }
}
