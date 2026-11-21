package com.constx.onvyra.data.ai.compression

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.openai.ResponsesPayloadException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessagePart
import java.io.IOException

private const val SUMMARY_REQUEST_TIMEOUT_MS = 60_000L
private val transientHttpFailure = Regex("^(?:Failed to get response: |HTTP )(?:500|502|503|504)\\b")
private val completeSummaryReasons = setOf("stop", "completed", "end_turn", "stop_sequence")

private class IncompleteSummaryResponse(message: String) : IllegalStateException(message)

/** Retry a text-only summary chunk once through streaming on the same model. */
internal suspend fun requestCompressionText(
    generate: suspend () -> TextGenerationResult,
    stream: suspend () -> Flow<StreamChunk>,
): String {
    try {
        return withTimeoutOrNull(SUMMARY_REQUEST_TIMEOUT_MS) {
            val result = generate()
            val reason = result.finishReason?.takeIf { it.isNotBlank() }
            val text = result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()
            if (text.isEmpty() || reason != null && reason.lowercase() !in completeSummaryReasons) {
                throw IncompleteSummaryResponse("压缩接口未返回完整的摘要正文")
            }
            text
        } ?: throw IncompleteSummaryResponse("压缩摘要请求超时")
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        currentCoroutineContext().ensureActive()
        val retryable = failure is IOException || failure is ResponsesPayloadException ||
            failure is IncompleteSummaryResponse || transientHttpFailure.containsMatchIn(failure.message.orEmpty())
        if (!retryable) throw failure
        try {
            return withTimeoutOrNull(SUMMARY_REQUEST_TIMEOUT_MS) {
                val text = StringBuilder()
                var completed = false
                stream().collect { chunk ->
                    when (chunk) {
                        is StreamChunk.TextDelta -> text.append(chunk.text)
                        is StreamChunk.Finish -> completed = chunk.finishReason?.lowercase() in completeSummaryReasons
                        else -> Unit
                    }
                }
                if (!completed || text.isBlank()) throw IncompleteSummaryResponse("压缩接口未返回完整的流式摘要")
                text.toString().trim()
            } ?: throw IncompleteSummaryResponse("流式压缩摘要请求超时")
        } catch (retryFailure: Exception) {
            if (retryFailure is CancellationException) throw retryFailure
            currentCoroutineContext().ensureActive()
            failure.addSuppressed(retryFailure)
            throw failure
        }
    }
}
