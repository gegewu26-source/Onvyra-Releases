package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.datastore.findModelById
import com.constx.onvyra.data.datastore.findProvider
import kotlin.uuid.Uuid

/**
 * 负责通过 AI 智能分析用户的找书需求并提炼标准书名
 */
class NovelAIService(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore
) {
    /**
     * 根据用户自然语言输入，由 AI 提炼出标准书名列表
     */
    suspend fun analyzeBookKeywords(userQuery: String): List<String> {
        val trimmed = userQuery.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            val settings = settingsStore.settingsFlow.first()
            val modelId = settings.chatModelId
            val model = settings.providers.findModelById(modelId) ?: return listOf(trimmed)
            val provider = model.findProvider(settings.providers) ?: return listOf(trimmed)
            val providerHandler = providerManager.getProviderByType(provider)

            val prompt = """
                你是一个网络小说检索专家。用户正在找书，给出的描述或问题如下：
                "$trimmed"
                
                请直接分析出最符合用户需求的 1 至 3 本具体中文网络小说书名。
                仅输出书名，每行一个，不要包含书名号《》、序号、作者名或任何解释性废话。
            """.trimIndent()

            val messages = listOf(UIMessage.user(prompt))
            val sessionId = Uuid.random().toString()

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    sessionId = sessionId,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    temperature = 0.3f,
                    maxTokens = 200
                )
            )

            val lines = result.message.toText().lines()
                .map { it.replace("《", "").replace("》", "").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.length <= 40 }

            if (lines.isNotEmpty()) lines else listOf(trimmed)
        } catch (e: Exception) {
            // 降级使用用户原词
            listOf(trimmed)
        }
    }
}
