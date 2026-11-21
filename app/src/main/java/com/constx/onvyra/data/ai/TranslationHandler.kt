package com.constx.onvyra.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.findModelById
import com.constx.onvyra.data.datastore.findProvider
import com.constx.onvyra.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

class TranslationHandler(
    private val providerManager: ProviderManager,
) {
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        sessionId: String = Uuid.random().toString(),
        onStreamUpdate: ((String) -> Unit)? = null,
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            val streamChunkHandler = StreamChunkHandler(model)
            var previousText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    sessionId = sessionId,
                ),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                val translatedText = messages.lastOrNull()
                    ?.takeIf { it.role == MessageRole.ASSISTANT }?.translationOutputText().orEmpty()

                if (translatedText.isNotBlank() && translatedText != previousText) {
                    previousText = translatedText
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies.filterNot { it.key == "translation_options" } + listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH)),
                                )
                            },
                        )
                    ),
                    sessionId = sessionId,
                ),
            )
            val translatedText = result.message.takeIf { it.role == MessageRole.ASSISTANT }
                ?.translationOutputText().orEmpty()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

private fun UIMessage.translationOutputText(): String = parts.filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text }
