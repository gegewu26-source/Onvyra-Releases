package me.rerere.mediagen.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import me.rerere.mediagen.provider.providers.toUrlOrDataUri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The /videos job protocol used by compatible gateways. Endpoint and model availability remain provider-specific. */
internal class OpenAIVideoGeneration(private val client: OkHttpClient) {
    suspend fun create(setting: MediaGenerationProviderSetting.OpenAI, model: MediaGenerationModel,
        request: MediaGenerationRequest): MediaGenerationTask {
        val httpRequest = authorized(setting, endpoint(setting)).postJson(buildBody(model, request)).build()
        return parseTask(setting, client.executeJson(httpRequest, "openai"), model.modelId)
    }

    suspend fun query(setting: MediaGenerationProviderSetting.OpenAI, model: MediaGenerationModel,
        taskId: String): MediaGenerationTask {
        val httpRequest = authorized(setting, endpoint(setting, taskId)).get().build()
        return parseTask(setting, client.executeJson(httpRequest, "openai"), model.modelId)
    }

    internal fun buildBody(model: MediaGenerationModel, request: MediaGenerationRequest): JsonObject = buildJsonObject {
        require(!request.prompt.isNullOrBlank()) { "Video generation requires a non-empty prompt" }
        require(request.count == null || request.count == 1) { "The /videos endpoint creates one video per job" }
        require(request.aspectRatio == null) { "The /videos endpoint uses pixel resolution instead of aspectRatio" }
        require(request.seed == null && request.watermark == null && request.generateAudio == null &&
            request.promptEnhancement == null && request.callbackUrl == null) {
            "The /videos endpoint does not expose seed, watermark, audio, prompt enhancement, or callback fields"
        }
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        request.durationSeconds?.let {
            require(it > 0) { "Video duration must be positive" }
            put("seconds", it.toString())
        }
        request.resolution?.takeIf { it.isNotBlank() && !it.equals("auto", true) }?.let { put("size", it) }
        require(request.inputs.size <= 1) { "The /videos endpoint accepts at most one first-frame image" }
        request.inputs.singleOrNull()?.let { input ->
            require(input is MediaGenerationInput.Image && input.role != ImageRole.LAST_FRAME) {
                "The /videos endpoint accepts only a first-frame image"
            }
            put("input_reference", buildJsonObject { put("image_url", input.toUrlOrDataUri()) })
        }
    }

    internal fun parseTask(setting: MediaGenerationProviderSetting.OpenAI, root: JsonObject,
        fallbackModel: String): MediaGenerationTask {
        val taskId = root.string("id") ?: error("The /videos endpoint did not return a video id")
        val status = when (root.string("status")) {
            "queued" -> MediaGenerationStatus.QUEUED
            "in_progress" -> MediaGenerationStatus.RUNNING
            "completed" -> MediaGenerationStatus.SUCCEEDED
            "failed" -> MediaGenerationStatus.FAILED
            "cancelled" -> MediaGenerationStatus.CANCELLED
            "expired" -> MediaGenerationStatus.EXPIRED
            else -> MediaGenerationStatus.UNKNOWN
        }
        return MediaGenerationTask(
            id = taskId, provider = "openai", model = root.string("model") ?: fallbackModel, status = status,
            outputs = if (status == MediaGenerationStatus.SUCCEEDED) listOf(MediaGenerationOutput(
                url = root.string("url") ?: endpoint(setting, taskId, content = true),
                mimeType = "video/mp4", durationSeconds = root.string("seconds")?.toDoubleOrNull(),
                resolution = root.string("size"),
            )) else emptyList(),
            error = root.obj("error")?.let { MediaGenerationError(code = it.string("code"), message = it.string("message") ?: "Video generation failed") },
            createdAtEpochSeconds = root.long("created_at"), updatedAtEpochSeconds = root.long("completed_at"),
            metadata = root,
        )
    }

    private fun authorized(setting: MediaGenerationProviderSetting.OpenAI, url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${setting.apiKey}")

    private fun endpoint(setting: MediaGenerationProviderSetting.OpenAI, taskId: String? = null,
        content: Boolean = false): String = setting.baseUrl.trimEnd('/').toHttpUrl().newBuilder().apply {
        addPathSegment("videos")
        taskId?.let { addPathSegment(it) }
        if (content) addPathSegment("content")
    }.build().toString()
}
