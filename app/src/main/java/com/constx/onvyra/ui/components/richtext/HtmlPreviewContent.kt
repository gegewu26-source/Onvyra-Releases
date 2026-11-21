package com.constx.onvyra.ui.components.richtext

private val htmlLanguages = setOf("html", "htm", "html5", "xhtml")
private val htmlDocumentStart = Regex("""(?is)^\s*(?:<!doctype\s+html\b|<html(?:\s|>))""")
private val htmlDocumentEnd = Regex("""(?is)</html\s*>\s*$""")
private val htmlClosingTag = Regex("""(?is)</html\s*>""")
private val markdownFence = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

internal fun previewLanguage(language: String, code: String): String {
    val normalized = language.trim().lowercase().substringBefore(' ')
    return when {
        normalized in htmlLanguages -> "html"
        normalized in setOf("", "text", "plaintext") && htmlDocumentStart.containsMatchIn(code) &&
            htmlDocumentEnd.containsMatchIn(code) -> "html"
        else -> normalized
    }
}

/** Fence bare HTML documents without consuming adjacent Markdown or touching existing code fences. */
internal fun htmlPreviewMarkdown(content: String): String {
    if (!content.contains("<html", ignoreCase = true) && !content.contains("<!doctype", ignoreCase = true)) return content
    val fenceLength = maxOf(3, (Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1)
    val fence = "`".repeat(fenceLength)
    val result = StringBuilder()
    var copiedUntil = 0
    var position = 0
    var openFence: String? = null
    while (position < content.length) {
        val lineEnd = content.indexOf('\n', position).let { if (it < 0) content.length else it }
        val line = content.substring(position, lineEnd)
        val marker = markdownFence.matchEntire(line)
        if (openFence != null) {
            if (marker != null && marker.groupValues[1].first() == openFence.first() &&
                marker.groupValues[1].length >= openFence.length && marker.groupValues[2].isBlank()
            ) openFence = null
        } else if (marker != null) {
            openFence = marker.groupValues[1]
        } else if (htmlDocumentStart.containsMatchIn(line)) {
            val closing = htmlClosingTag.find(content, position)
            val end = closing?.range?.last?.plus(1) ?: content.length
            result.append(content, copiedUntil, position)
            result.append("\n$fence").append("html\n").append(content, position, end)
            if (closing != null) result.append("\n$fence\n")
            copiedUntil = end
            position = end
            continue
        }
        position = lineEnd + 1
    }
    return if (copiedUntil == 0) content else result.append(content, copiedUntil, content.length).toString()
}
