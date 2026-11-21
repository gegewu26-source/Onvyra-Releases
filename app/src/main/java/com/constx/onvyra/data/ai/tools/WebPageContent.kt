package com.constx.onvyra.data.ai.tools

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URI

internal data class WebPageContent(
    val title: String,
    val description: String,
    val language: String?,
    val text: String,
    val links: List<WebPageLink>,
    val truncated: Boolean,
)

@Serializable
internal data class WebPageLink(val title: String, val url: String)

/** Extracts document content only; scripts, embedded resources and links are never executed. */
internal fun extractWebPageContent(
    html: String,
    url: String,
    maxChars: Int = 24_000,
): WebPageContent {
    require(maxChars > 0) { "maxChars must be positive" }
    val document = Jsoup.parse(html, url)
    fun metadata(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        document.select("meta").firstOrNull {
            it.attr("name").equals(name, ignoreCase = true) ||
                it.attr("property").equals(name, ignoreCase = true)
        }?.attr("content")?.let(::normalizePageText)?.takeIf(String::isNotEmpty)
    }.orEmpty()

    val title = normalizePageText(document.title())
        .ifEmpty { metadata("og:title", "twitter:title") }
        .ifEmpty { document.selectFirst("h1")?.text().orEmpty() }
        .take(512)
    val description = metadata("description", "og:description", "twitter:description").take(2_000)
    val language = document.selectFirst("html[lang]")?.attr("lang")?.trim()
        ?.take(64)?.takeIf(String::isNotEmpty)

    document.select("script, style, noscript, template, svg, canvas, iframe, object, embed").remove()
    document.select("[hidden], [aria-hidden=true], input, button, select, textarea").remove()
    document.select("[style]").filter { element ->
        hiddenPageStyle.containsMatchIn(element.attr("style"))
    }.forEach(Element::remove)

    val body = document.body()
    // A home page often has no article, or has several small article cards. Keep its introduction.
    val main = body.select("main, [role=main]").firstOrNull { it.hasText() }
    val articles = body.select("article").filter { it.hasText() }
    val article = articles.singleOrNull()?.takeIf { it.text().length >= body.text().length / 2 }
    val content = main ?: article ?: body
    val links = extractPageLinks(content, body)

    // Keep navigation destinations above, but avoid repeating the entire site menu in the body text.
    val cleaned = content.clone()
    cleaned.select("nav, footer, aside").remove()
    val textSource = cleaned.takeIf { it.hasText() } ?: content
    val collector = PageTextCollector(maxChars)
    NodeTraversor.traverse(collector, textSource)
    return WebPageContent(
        title = title,
        description = description,
        language = language,
        text = collector.text,
        links = links,
        truncated = collector.truncated,
    )
}

private val pageWhitespace = Regex("[\\s\\u00a0]+")
private val hiddenPageStyle = Regex(
    "(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important\\s*)?(?:;|$)",
    RegexOption.IGNORE_CASE,
)

private fun normalizePageText(text: String): String = text.replace(pageWhitespace, " ").trim()

private fun extractPageLinks(content: Element, body: Element): List<WebPageLink> {
    val links = LinkedHashMap<String, WebPageLink>()
    for (root in listOf(content, body).distinct()) {
        for (anchor in root.select("a[href]")) {
            val absolute = anchor.absUrl("href")
            if (absolute.length > 4_096) continue
            val uri = runCatching { URI(absolute) }.getOrNull() ?: continue
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) continue
            val label = normalizePageText(anchor.text()).ifEmpty {
                normalizePageText(anchor.attr("aria-label"))
            }.ifEmpty {
                normalizePageText(anchor.attr("title"))
            }.ifEmpty {
                anchor.selectFirst("img[alt]")?.attr("alt")?.let(::normalizePageText).orEmpty()
            }
            if (label.isEmpty()) continue
            links.putIfAbsent(absolute, WebPageLink(label.take(160), absolute))
            if (links.size >= 30) return links.values.toList()
        }
    }
    return links.values.toList()
}

/** Bounds the output while preserving HTML block boundaries instead of flattening every paragraph. */
private class PageTextCollector(private val limit: Int) : NodeVisitor {
    private val output = StringBuilder(minOf(limit, 4_096))
    private var pendingSpace = false
    private var pendingBreaks = 0
    var truncated = false
        private set
    val text: String get() = output.toString()

    override fun head(node: Node, depth: Int) {
        if (truncated) return
        when (node) {
            is TextNode -> append(node.wholeText)
            is Element -> when (node.normalName()) {
                "br", "tr" -> separate(1)
                "li" -> {
                    separate(1)
                    append("- ")
                }
                "td", "th" -> pendingSpace = true
                in paragraphTags -> separate(2)
            }
        }
    }

    override fun tail(node: Node, depth: Int) {
        if (node is Element) {
            when (node.normalName()) {
                "li", "tr" -> separate(1)
                "td", "th" -> pendingSpace = true
                in paragraphTags -> separate(2)
            }
        }
    }

    private fun separate(count: Int) {
        pendingBreaks = maxOf(pendingBreaks, count)
        pendingSpace = false
    }

    private fun append(value: String) {
        for (character in value) {
            if (character.isWhitespace() || character == '\u00a0') {
                pendingSpace = true
                continue
            }
            if (output.isNotEmpty()) {
                repeat(pendingBreaks) { appendCharacter('\n') }
                if (pendingBreaks == 0 && pendingSpace) appendCharacter(' ')
            }
            pendingBreaks = 0
            pendingSpace = false
            appendCharacter(character)
            if (truncated) return
        }
    }

    private fun appendCharacter(character: Char) {
        if (output.length < limit) {
            output.append(character)
        } else {
            truncated = true
            // Do not expose half of a UTF-16 surrogate pair at the output boundary.
            if (output.lastOrNull()?.isHighSurrogate() == true) output.setLength(output.length - 1)
        }
    }

    private companion object {
        val paragraphTags = setOf(
            "p", "div", "section", "article", "main", "header", "footer", "nav", "aside",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "ul", "ol", "table",
            "dl", "dt", "dd", "figure", "figcaption", "hr",
        )
    }
}
