package com.constx.onvyra.data.ai.tools

import com.artifex.mupdf.fitz.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WebPdfText(val text: String, val pageCount: Int, val truncated: Boolean)

internal fun interface WebPdfTextParser {
    suspend fun parse(bytes: ByteArray): WebPdfText
}

/** Parse in memory, without retaining a downloaded PDF or leaking native page/text handles. */
internal object NativeWebPdfTextParser : WebPdfTextParser {
    override suspend fun parse(bytes: ByteArray): WebPdfText = withContext(Dispatchers.IO) {
        val document = Document.openDocument(bytes, "application/pdf")
        try {
            require(!document.needsPassword()) { "The PDF is encrypted or needs a password." }
            val pages = document.countPages()
            val output = StringBuilder()
            var truncated = pages > 100
            var hasText = false
            for (index in 0 until minOf(pages, 100)) {
                currentCoroutineContext().ensureActive()
                val page = document.loadPage(index)
                try {
                    val text = page.toStructuredText()
                    try {
                        val pageText = text.asText().trim()
                        hasText = hasText || pageText.isNotBlank()
                        val value = "--- Page ${index + 1} ---\n$pageText\n\n"
                        val remaining = MAX_WEB_DOCUMENT_CHARS - output.length
                        output.append(value.take(remaining))
                        if (value.length > remaining) {
                            truncated = true
                            break
                        }
                    } finally {
                        text.destroy()
                    }
                } finally {
                    page.destroy()
                }
            }
            var value = output.toString().trim()
            if (value.lastOrNull()?.isHighSurrogate() == true) value = value.dropLast(1)
            WebPdfText(if (hasText) value else "", pages, truncated)
        } finally {
            document.destroy()
        }
    }
}
