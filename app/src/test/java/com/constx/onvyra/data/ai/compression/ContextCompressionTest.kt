package com.constx.onvyra.data.ai.compression

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import com.constx.onvyra.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import com.constx.onvyra.data.ai.prompts.LEGACY_DEFAULT_COMPRESS_PROMPT
import com.constx.onvyra.data.ai.prompts.effectiveCompressionPrompt
import org.junit.Assert.*
import org.junit.Test

class ContextCompressionTest {
    private val json = Json
    @Test
    fun `full text and the tail requirement after 2000 characters survive rendering`() {
        val message = UIMessage.user("earlier details ".repeat(500) + "必须保留最后的约束：只改原目录")
        val rendered = renderCompressionMessage(message)
        val record = json.parseToJsonElement(rendered).jsonObject
        assertEquals(message.id.toString(), record.getValue("message_id").jsonPrimitive.content)
        assertEquals("USER", record.getValue("role").jsonPrimitive.content)
        assertEquals(message.toText(), record.getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(rendered.contains("只改原目录"))
    }

    @Test
    fun `tool only turns retain arguments results status and caller identity`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-evidence", toolName = "mcp__test__inspect",
                input = "{\"file\":\"sample.log\"}",
                output = listOf(UIMessagePart.Text("exit_code=0; confirmed result at line 347")),
            ),
            UIMessagePart.ServerTool(
                toolCallId = "server-evidence", toolName = "file_search",
                output = buildJsonObject { put("path", "report.txt") }, status = ServerToolStatus.COMPLETED,
            ),
        ))
        val rendered = renderCompressionMessage(message)
        assertTrue(rendered.contains("mcp__test__inspect"))
        assertTrue(rendered.contains("sample.log"))
        assertTrue(rendered.contains("line 347"))
        assertTrue(rendered.contains("executed"))
        assertTrue(rendered.contains("COMPLETED"))
        assertTrue(rendered.contains("report.txt"))
        assertEquals("ASSISTANT", json.parseToJsonElement(rendered).jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `pending and denied states are represented without claiming execution`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("pending-call", "pending-tool", "{}", approvalState = ToolApprovalState.Pending),
            UIMessagePart.Tool("denied-call", "denied-tool", "{}", approvalState = ToolApprovalState.Denied("not approved")),
        ))
        val parts = json.parseToJsonElement(renderCompressionMessage(message)).jsonObject.getValue("parts").jsonArray
        assertEquals("not_executed", parts[0].jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("pending", parts[0].jsonObject.getValue("approval").jsonPrimitive.content)
        assertEquals("denied", parts[1].jsonObject.getValue("approval").jsonPrimitive.content)
        assertEquals("not approved", parts[1].jsonObject.getValue("denial_reason").jsonPrimitive.content)
    }

    @Test
    fun `attachments and citations retain references but binary and reasoning are omitted`() {
        val message = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Document("file:///files/spec.pdf", "spec.pdf", "application/pdf"),
            UIMessagePart.Image("data:image/png;base64,AAABINARYPAYLOAD==\nANOTHERBINARYLINE=="),
            UIMessagePart.Audio("file:///files/recording.ogg"),
            UIMessagePart.Video("file:///files/demo.mp4"),
            UIMessagePart.Reasoning("REASONING_MUST_NOT_BE_COPIED"),
        ), annotations = listOf(UIMessageAnnotation.UrlCitation("Evidence", "https://example.test/reference")))
        val rendered = renderCompressionMessage(message)
        assertTrue(rendered.contains("spec.pdf"))
        assertTrue(rendered.contains("application/pdf"))
        assertTrue(rendered.contains("recording.ogg"))
        assertTrue(rendered.contains("demo.mp4"))
        assertTrue(rendered.contains("https://example.test/reference"))
        assertTrue(rendered.contains("inline binary data omitted"))
        assertFalse(rendered.contains("AAABINARYPAYLOAD"))
        assertFalse(rendered.contains("ANOTHERBINARYLINE"))
        assertFalse(rendered.contains("REASONING_MUST_NOT_BE_COPIED"))
    }

    @Test
    fun `checkpoint is marked and its full previous summary survives another compression`() {
        val previous = UIMessage.user("old-confirmed-fact ".repeat(400) + "last-previous-constraint")
            .copy(isContextCheckpoint = true)
        val record = json.parseToJsonElement(renderCompressionMessage(previous)).jsonObject
        assertEquals("true", record.getValue("context_checkpoint").jsonPrimitive.content)
        assertTrue(record.getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content.endsWith("last-previous-constraint"))
    }

    @Test
    fun `splitting is lossless budgeted and never separates surrogate pairs`() {
        val text = ("ASCII，中文；😀👩🏽‍💻\ncode = 123;\t".repeat(300)) + "tail"
        val pieces = splitCompressionText(text, 127)
        assertTrue(pieces.size > 2)
        assertEquals(text, pieces.joinToString(""))
        pieces.forEach { piece ->
            assertTrue(estimateTextTokens(piece) <= 127)
            assertFalse(piece.first().isLowSurrogate())
            assertFalse(piece.last().isHighSurrogate())
        }
    }

    @Test
    fun `empty text has zero estimate and no chunks`() {
        assertEquals(0L, estimateTextTokens(""))
        assertTrue(splitCompressionText("", 100).isEmpty())
        assertTrue(runCatching { splitCompressionText("text", 0) }.isFailure)
    }

    @Test
    fun `prepared source preserves original token budgets and request bytes`() = runBlocking {
        val messages = listOf(UIMessage.user("First \u4e2d \uD83D\uDE00 and ASCII"), UIMessage.user("Second \u6587\nwith punctuation."))
        val rendered = messages.map(::renderCompressionMessage)
        val joined = rendered.joinToString("\n\n")
        val source = CompressionSource(messages)
        assertEquals(rendered.sumOf(::estimateTextTokens), source.messageTokens)
        assertEquals(estimateTextTokens(joined), source.joinedTokens)

        val summary = buildCompressedSummary(source, 80, 512) { content, target, merging ->
            assertEquals(joined, content)
            assertEquals(80, target)
            assertFalse(merging)
            "Keep both notes."
        }
        assertEquals("Keep both notes.", summary)
    }

    @Test
    fun `multiple chunks preserve ordering use at most two requests and merge to one overall target`() = runBlocking {
        val messages = List(4) { index -> UIMessage.user("source-$index " + "details ".repeat(120)) }
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        var mergedSource = ""
        val mergeTargets = mutableListOf<Int>()
        val result = buildCompressedSummary(CompressionSource(messages), targetTokens = 80, inputBudgetTokens = 512) { content, target, merging ->
            assertTrue(estimateTextTokens(content) <= 512)
            if (merging) {
                mergedSource = content
                mergeTargets += target
                "Final objective and next step."
            } else {
                val now = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, now) }
                try {
                    val index = Regex("source-(\\d+)").find(content)!!.groupValues[1].toInt()
                    delay((4 - index) * 10L)
                    "summary-$index"
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        assertEquals("Final objective and next step.", result)
        assertEquals(2, maximum.get())
        assertEquals(listOf(80), mergeTargets)
        assertTrue(mergedSource.indexOf("summary-0") < mergedSource.indexOf("summary-1"))
        assertTrue(mergedSource.indexOf("summary-1") < mergedSource.indexOf("summary-2"))
        assertTrue(mergedSource.indexOf("summary-2") < mergedSource.indexOf("summary-3"))
        assertTrue(estimateTextTokens(result) <= 80)
    }

    @Test
    fun `oversized single message passes its final requirement without truncation`() = runBlocking {
        val message = UIMessage.user("a".repeat(8000) + "FINAL_CONSTRAINT")
        val sources = mutableListOf<String>()
        val summary = buildCompressedSummary(CompressionSource(listOf(message)), 80, 512) { content, _, merging ->
            assertTrue(estimateTextTokens(content) <= 512)
            if (merging) "Keep FINAL_CONSTRAINT." else {
                synchronized(sources) { sources += content }
                if (content.contains("FINAL_CONSTRAINT")) "Found FINAL_CONSTRAINT." else "Earlier details."
            }
        }
        assertTrue(summary.contains("FINAL_CONSTRAINT"))
        assertTrue(sources.any { it.contains("FINAL_CONSTRAINT") })
        assertTrue(sources.all { it.contains(message.id.toString()) && it.contains("role USER") })
    }

    @Test
    fun `empty output fails before a checkpoint can be produced`() = runBlocking {
        val failure = runCatching {
            buildCompressedSummary(CompressionSource(listOf(UIMessage.user("details ".repeat(100)))), 100, 1024) { _, _, _ -> "  " }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("empty summary"))
    }

    @Test
    fun `unchanged output fails instead of silently claiming compaction`() = runBlocking {
        val failure = runCatching {
            buildCompressedSummary(CompressionSource(listOf(UIMessage.user("details ".repeat(100)))), 30, 1024) { content, _, _ -> content }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("did not reduce"))
    }

    @Test
    fun `non converging output stops after a finite number of requests`() = runBlocking {
        var calls = 0
        val failure = runCatching {
            buildCompressedSummary(CompressionSource(listOf(UIMessage.user("details ".repeat(300)))), 10, 2048) { _, _, _ ->
                calls++
                "x".repeat(300)
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(calls <= 3)
    }

    @Test
    fun `cancellation propagates without returning a partial summary`() = runBlocking {
        val failure = runCatching {
            buildCompressedSummary(CompressionSource(listOf(UIMessage.user("details ".repeat(100)))), 100, 1024) { _, _, _ ->
                throw CancellationException("cancelled by user")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test
    fun `only exact old default is upgraded and custom placeholders remain compatible`() {
        assertEquals(DEFAULT_COMPRESS_PROMPT, effectiveCompressionPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT))
        val custom = "Summarize {content} with {target_tokens}; {additional_context}; {locale}"
        assertEquals(custom, effectiveCompressionPrompt(custom))
        assertEquals(LEGACY_DEFAULT_COMPRESS_PROMPT + "\n", effectiveCompressionPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT + "\n"))
        listOf("{content}", "{target_tokens}", "{additional_context}", "{locale}").forEach {
            assertTrue(DEFAULT_COMPRESS_PROMPT.contains(it))
        }
    }
}
