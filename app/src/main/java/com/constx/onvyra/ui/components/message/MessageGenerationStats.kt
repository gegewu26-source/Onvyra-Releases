package com.constx.onvyra.ui.components.message

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Duration

internal data class MessageGenerationStats(
    val totalTokens: Long?,
    val elapsedMillis: Long?,
    val averageTokensPerSecond: Double?,
    val showUsageNotice: Boolean,
)

internal fun UIMessage.generationStats(now: LocalDateTime, loading: Boolean): MessageGenerationStats {
    val end = if (loading) now else finishedAt
    val elapsed = end?.let {
        Duration.between(createdAt.toJavaLocalDateTime(), it.toJavaLocalDateTime())
            .toMillis().takeIf { duration -> duration >= 0 }
    }
    val reportedUsage = usage
    val total = reportedUsage?.let {
        if (it.totalTokens > 0) it.totalTokens.toLong()
        else it.promptTokens.toLong() + it.completionTokens
    }
    // This is turn throughput, including request wait and tool execution, rather than
    // a decoding speed. A missing or zero duration must never produce Infinity/NaN.
    val speed = if (!loading && elapsed != null && elapsed > 0 && reportedUsage != null && reportedUsage.completionTokens > 0) {
        reportedUsage.completionTokens.toDouble() * 1000 / elapsed
    } else null
    val generatedImage = parts.any { part ->
        when (part) {
            is UIMessagePart.Image -> part.isUsableImageOutput()
            is UIMessagePart.Tool -> part.toolName in setOf("generate_native_image", "generate_media") &&
                part.output.any { it is UIMessagePart.Image && it.isUsableImageOutput() }
            else -> false
        }
    }
    return MessageGenerationStats(total, elapsed, speed,
        showUsageNotice = reportedUsage == null && (loading || !generatedImage))
}

private fun UIMessagePart.Image.isUsableImageOutput(): Boolean = url.isNotBlank() &&
    (!url.startsWith("data:") || url.substringAfter("base64,", "").isNotBlank())
