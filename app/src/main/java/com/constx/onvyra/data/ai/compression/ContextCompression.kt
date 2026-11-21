package com.constx.onvyra.data.ai.compression

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

private const val MAX_COMPRESSION_ROUNDS = 8
private val inlineData = Regex("data:[^\\s,\"<>]*;base64,[A-Za-z0-9+/=_-]+", RegexOption.IGNORE_CASE)

/**
 * Lightweight, deliberately cautious estimate for budgeting, not a provider tokenizer.
 * ASCII words cost roughly one token per three characters, punctuation one token,
 * other BMP characters two and supplementary characters four. The server's usage
 * remains authoritative; callers must also reserve prompt and output space.
 */
internal fun estimateTextTokens(text: String): Long =
    estimatedTokensFromUnits(estimateTextTokenUnits(text))

private fun estimatedTokensFromUnits(units: Long): Long = (units + 2L) / 3L

private fun estimateTextTokenUnits(text: String): Long {
    var units = 0L
    var index = 0
    while (index < text.length) {
        val count = codePointLength(text, index)
        units += tokenUnits(text, index, count)
        index += count
    }
    return units
}

/** Split without discarding text, cutting Unicode surrogate pairs, or exceeding the estimate. */
internal fun splitCompressionText(text: String, maxTokens: Int): List<String> {
    require(maxTokens > 0) { "Compression input budget must be positive" }
    if (text.isEmpty()) return emptyList()
    val maxUnits = maxTokens.toLong() * 3L
    val parts = ArrayList<String>()
    var start = 0
    var index = 0
    var units = 0L
    while (index < text.length) {
        val count = codePointLength(text, index)
        val nextUnits = tokenUnits(text, index, count)
        require(nextUnits <= maxUnits) { "Compression input budget cannot fit one Unicode character" }
        if (units + nextUnits > maxUnits) {
            parts += text.substring(start, index)
            start = index
            units = 0L
        }
        units += nextUnits
        index += count
    }
    if (start < text.length) parts += text.substring(start)
    return parts
}

private fun codePointLength(text: String, index: Int): Int =
    if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1

private fun tokenUnits(text: String, index: Int, count: Int): Long = when {
    count == 2 -> 12L
    text[index].code >= 128 -> 6L
    text[index].isLetterOrDigit() || text[index].isWhitespace() -> 1L
    else -> 3L
}

/** Full role-labelled source material for summarization; never the 2,000-character UI preview. */
internal fun renderCompressionMessage(message: UIMessage): String = buildJsonObject {
    put("message_id", message.id.toString())
    put("role", message.role.name)
    put("context_checkpoint", message.isContextCheckpoint)
    put("parts", JsonArray(message.parts.mapNotNull(::compressionPart)))
    if (message.annotations.isNotEmpty()) {
        put("citations", JsonArray(message.annotations.map { annotation ->
            when (annotation) {
                is UIMessageAnnotation.UrlCitation -> buildJsonObject {
                    put("title", annotation.title.withoutInlineData())
                    put("url", annotation.url.withoutInlineData())
                }
            }
        }))
    }
}.toString()

@Suppress("DEPRECATION")
private fun compressionPart(part: UIMessagePart): JsonObject? = when (part) {
    is UIMessagePart.Reasoning, UIMessagePart.Search -> null
    is UIMessagePart.Text -> buildJsonObject {
        put("type", "text")
        put("text", part.text.withoutInlineData())
    }
    is UIMessagePart.Tool -> buildJsonObject {
        put("type", "tool")
        put("tool_call_id", part.toolCallId)
        put("name", part.toolName)
        put("arguments", part.input.withoutInlineData())
        put("status", if (part.isExecuted) "executed" else "not_executed")
        put("approval", when (part.approvalState) {
            ToolApprovalState.Auto -> "auto"
            ToolApprovalState.Pending -> "pending"
            ToolApprovalState.Approved -> "approved"
            is ToolApprovalState.Denied -> "denied"
            is ToolApprovalState.Answered -> "answered"
        })
        when (val approval = part.approvalState) {
            is ToolApprovalState.Denied -> put("denial_reason", approval.reason.withoutInlineData())
            is ToolApprovalState.Answered -> put("user_answer", approval.answer.withoutInlineData())
            else -> Unit
        }
        put("output", JsonArray(part.output.mapNotNull(::compressionPart)))
    }
    is UIMessagePart.ServerTool -> buildJsonObject {
        put("type", "server_tool")
        put("tool_call_id", part.toolCallId)
        put("name", part.toolName)
        put("status", part.status.name)
        part.input?.let { put("input", it.withoutInlineData()) }
        part.output?.let { put("output", it.withoutInlineData()) }
    }
    is UIMessagePart.ToolCall -> buildJsonObject {
        put("type", "legacy_tool_call")
        put("tool_call_id", part.toolCallId)
        put("name", part.toolName)
        put("arguments", part.arguments.withoutInlineData())
    }
    is UIMessagePart.ToolResult -> buildJsonObject {
        put("type", "legacy_tool_result")
        put("tool_call_id", part.toolCallId)
        put("name", part.toolName)
        put("arguments", part.arguments.withoutInlineData())
        put("output", part.content.withoutInlineData())
    }
    is UIMessagePart.Document -> buildJsonObject {
        put("type", "document_reference")
        put("name", part.fileName)
        put("mime", part.mime)
        put("reference", part.url.asCompressionReference())
    }
    is UIMessagePart.Image -> mediaReference("image_reference", part.url)
    is UIMessagePart.Audio -> mediaReference("audio_reference", part.url)
    is UIMessagePart.Video -> mediaReference("video_reference", part.url)
}

private fun mediaReference(type: String, reference: String): JsonObject = buildJsonObject {
    put("type", type)
    put("reference", reference.asCompressionReference())
}

private fun String.withoutInlineData(): String = replace(inlineData, "[inline binary data omitted]")

private fun String.asCompressionReference(): String =
    if (trimStart().startsWith("data:", ignoreCase = true)) "[inline binary data omitted]" else withoutInlineData()

private fun JsonElement.withoutInlineData(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.withoutInlineData() })
    is JsonArray -> JsonArray(map { it.withoutInlineData() })
    is JsonPrimitive -> if (isString) JsonPrimitive(content.withoutInlineData()) else this
}

internal class CompressionBlock(
    val text: String,
    val continuationHeader: String? = null,
) {
    val units = estimateTextTokenUnits(text)
    val tokens = estimatedTokensFromUnits(units)
}

internal class CompressionSource(messages: List<UIMessage>) {
    val blocks = messages.map { message ->
        CompressionBlock(
            text = renderCompressionMessage(message),
            continuationHeader = "[Source message ${message.id}; role ${message.role.name}; continuation fragment]\n",
        )
    }
    val messageTokens = blocks.sumOf { it.tokens }

    // Each of the two whitespace characters between JSON objects costs one unit.
    val joinedTokens = estimatedTokensFromUnits(
        blocks.sumOf { it.units } + (blocks.size - 1).coerceAtLeast(0) * 2L,
    )
}

/**
 * Budgeted map/reduce compaction. The callback performs a plain model request;
 * [inputBudgetTokens] is the remaining content budget after its prompt overhead.
 * Every result is validated before a caller may commit a new checkpoint.
 */
internal suspend fun buildCompressedSummary(
    source: CompressionSource,
    targetTokens: Int,
    inputBudgetTokens: Int,
    generate: suspend (content: String, targetTokens: Int, merging: Boolean) -> String,
): String {
    require(source.blocks.isNotEmpty()) { "No messages available for compression" }
    require(targetTokens > 0) { "Compression summary target must be positive" }
    require(inputBudgetTokens >= 64) { "Compression input budget is too small" }
    var chunks = packCompressionBlocks(source.blocks, inputBudgetTokens)
    var previousOutputTokens = source.joinedTokens
    val permits = Semaphore(2)

    repeat(MAX_COMPRESSION_ROUNDS) { round ->
        val totalInputTokens = chunks.sumOf { it.tokens }.coerceAtLeast(1L)
        val summaries = coroutineScope {
            chunks.map { chunk ->
                async {
                    permits.withPermit {
                        val share = if (chunks.size == 1) targetTokens else {
                            // Leave room for an ordered final reduction, including small tail chunks.
                            (targetTokens.toLong() * 2L * chunk.tokens / totalInputTokens)
                                .coerceIn(minOf(128, targetTokens).toLong(), targetTokens.toLong()).toInt()
                        }
                        generate(chunk.text, share, round > 0).trim().also {
                            check(it.isNotBlank()) { "Compression returned an empty summary" }
                        }
                    }
                }
            }.awaitAll()
        }
        val outputTokens = summaries.sumOf(::estimateTextTokens)
        if (summaries.size == 1 && outputTokens <= targetTokens) {
            check(outputTokens < source.joinedTokens) { "Compression did not reduce the original context; original history is preserved" }
            return summaries.single()
        }
        check(outputTokens < previousOutputTokens) { "Compression did not reduce the context; original history is preserved" }
        previousOutputTokens = outputTokens
        val mergeBlocks = summaries.mapIndexed { index, summary ->
            CompressionBlock("[Chronological summary segment ${index + 1}; later segments contain newer statements]\n$summary")
        }
        chunks = packCompressionBlocks(mergeBlocks, inputBudgetTokens)
    }
    error("Compression did not converge within $MAX_COMPRESSION_ROUNDS passes; original history is preserved")
}

private fun packCompressionBlocks(
    blocks: List<CompressionBlock>,
    budgetTokens: Int,
): List<CompressionBlock> {
    val result = ArrayList<CompressionBlock>()
    val current = StringBuilder()
    var currentTokens = 0L
    for (block in blocks) {
        val pieces = if (block.tokens <= budgetTokens) listOf(block) else {
            // A long message's later fragments still identify its original speaker and ID.
            val header = block.continuationHeader ?: (block.text.substringBefore('\n') + "\n")
            val remaining = budgetTokens - estimateTextTokens(header).toInt() - 1
            require(remaining >= 4) { "Compression input budget cannot fit the source label" }
            splitCompressionText(block.text, remaining).map { CompressionBlock(header + it) }
        }
        for (piece in pieces) {
            if (current.isNotEmpty() && currentTokens + 1L + piece.tokens > budgetTokens) {
                result += CompressionBlock(current.toString())
                current.clear()
                currentTokens = 0L
            }
            if (current.isNotEmpty()) {
                current.append("\n\n")
                currentTokens++
            }
            current.append(piece.text)
            currentTokens += piece.tokens
        }
    }
    if (current.isNotEmpty()) result += CompressionBlock(current.toString())
    return result
}
