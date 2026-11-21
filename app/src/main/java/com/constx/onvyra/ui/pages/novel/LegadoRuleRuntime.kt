package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.mozilla.javascript.*
import java.io.IOException
import java.io.StringWriter
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.coroutines.CoroutineContext

class LegadoRuleException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Rhino requires an Error to terminate interpretation without a script catching the deadline. */
private class SourceScriptTimeout : Error("书源脚本执行超时")

/**
 * Independent compatibility implementation for user-supplied Legado book-source rules.
 * Format reference: https://github.com/LegadoTeam/legado (GPL-3.0).
 * No upstream implementation code is copied. Unsupported source helpers fail explicitly.
 */
class LegadoRuleRuntime(
    private val ajax: ((String) -> String)? = null,
    private val jsLib: String = "",
    private val sourceVariables: MutableMap<String, String> = ConcurrentHashMap(),
) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val saved = ConcurrentHashMap<String, String>()
    // A row's name, author, URL and artwork rules inspect the same immutable DOM.
    // Re-parsing it for every field multiplied search/catalog work by the field count.
    // Keep only the current few small rows; content rules still clone before editing.
    private data class HtmlKey(val content: String, val base: String)
    private val htmlRows = object : LinkedHashMap<HtmlKey, Document>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HtmlKey, Document>) = size > 4
    }
    private fun html(content: String, base: String): Document {
        if (content.length > 256_000) return Jsoup.parse(content, base).also { it.outputSettings().prettyPrint(false) }
        val key = HtmlKey(content, base)
        return synchronized(htmlRows) {
            htmlRows[key] ?: Jsoup.parse(content, base).also {
                it.outputSettings().prettyPrint(false)
                htmlRows[key] = it
            }
        }
    }

    /** Evaluate one directory row together, preserving the exact original rule semantics. */
    internal suspend fun strings(content: String, rules: List<String>, baseUrl: String,
        variables: Map<String, String> = emptyMap()): List<String> = withContext(Dispatchers.IO) {
        val session = Session(content, baseUrl, variables, coroutineContext)
        rules.map { rule -> if (rule.isBlank()) "" else session.eval(content, rule, false).joinToString("\n").trim() }
    }

    private data class FastCssStep(val evaluator: org.jsoup.select.Evaluator,
        val indices: List<Int>?, val exclude: Boolean)
    private data class FastCssField(val steps: List<FastCssStep>, val selectionKey: String, val terminal: String)

    /**
     * Common HTML directories need only `dd`, `a@text`, `a@href`. Keep those
     * elements in their already parsed DOM instead of serializing and reparsing
     * every chapter. The deliberately small grammar cannot observe ancestors or
     * siblings outside a row; all scripting/JSON/XPath/replacement rules fall back.
     */
    internal suspend fun simpleCssRows(content: String, listRule: String, fieldRules: List<String>,
        baseUrl: String, variables: Map<String, String> = emptyMap()): List<List<String>>? = withContext(Dispatchers.IO) {
        if (content.length > MAX_TEXT_LENGTH || content.trimStart().firstOrNull() != '<' ||
            variables["_onvyra_content_lines"] == "1") return@withContext null
        fun tokens(rule: String) = rule.trim().removePrefix("@css:").removePrefix("@CSS:").split('@')
            .filter { it.isNotBlank() }
        fun step(raw: String, rowField: Boolean = false): FastCssStep? {
            val index = Regex("([.!])(-?\\d+(?::-?\\d+)*)$").find(raw)
            var selector = if (index == null) raw else raw.substring(0, index.range.first)
            selector = when {
                selector.startsWith("class.") -> "." + selector.drop(6).trim().split(Regex("\\s+")).joinToString(".")
                selector.startsWith("id.") -> "#" + selector.drop(3)
                selector.startsWith("tag.") -> selector.drop(4)
                else -> selector
            }
            // A field selector must not consult an ancestor outside its detached
            // row (for example `.listmain a`). Explicit @ steps stay inside it.
            if (rowField && selector.any { it.isWhitespace() }) return null
            if (!Regex("(?:[a-zA-Z][a-zA-Z0-9_-]*|[.#][a-zA-Z_][a-zA-Z0-9_-]*)(?:[.#][a-zA-Z_][a-zA-Z0-9_-]*)*(?:\\s+(?:[a-zA-Z][a-zA-Z0-9_-]*|[.#][a-zA-Z_][a-zA-Z0-9_-]*)(?:[.#][a-zA-Z_][a-zA-Z0-9_-]*)*)*").matches(selector)) return null
            return FastCssStep(org.jsoup.select.QueryParser.parse(selector),
                index?.groupValues?.get(2)?.split(':')?.mapNotNull { it.toIntOrNull() }, index?.groupValues?.get(1) == "!")
        }
        val listTokens = tokens(listRule)
        if (listTokens.isEmpty() || listTokens.any { it in ATTRS }) return@withContext null
        val listSteps = listTokens.map { step(it) ?: return@withContext null }
        val fields = fieldRules.map { rule ->
            if (rule.isBlank()) FastCssField(emptyList(), "", "") else {
                val parts = tokens(rule)
                val last = parts.lastOrNull() ?: return@withContext null
                val terminal = if (last in setOf("text", "ownText", "textNodes") ||
                    (parts.size == 1 && last in ATTRS) || (parts.size > 1 &&
                        Regex("[a-zA-Z_][a-zA-Z0-9_:-]*").matches(last) && last !in TAGS)) last else "text"
                if (terminal in setOf("html", "all", "outerHtml")) return@withContext null
                val selectors = if (terminal == last) parts.dropLast(1) else parts
                FastCssField(selectors.map { step(it, rowField = true) ?: return@withContext null }, selectors.joinToString("@"), terminal)
            }
        }
        fun select(start: List<Element>, steps: List<FastCssStep>): List<Element> {
            var selected = start
            for (part in steps) {
                selected = selected.flatMap { it.select(part.evaluator) }.distinct()
                part.indices?.let { indices ->
                    val wanted = indices.map { if (it < 0) selected.size + it else it }.toSet()
                    selected = if (part.exclude) selected.filterIndexed { i, _ -> i !in wanted }
                        else wanted.mapNotNull { selected.getOrNull(it) }
                }
            }
            return selected
        }
        val document = html(content, baseUrl)
        val fragment = !Regex("^\\s*(?:<!doctype|<html|<head|<body)", RegexOption.IGNORE_CASE).containsMatchIn(content)
        val roots = if (fragment && document.body().childrenSize() == 1) listOf(document.body().child(0)) else listOf(document)
        val rows = select(roots, listSteps)
        // These tags are reparented/dropped by the old detached-fragment parser.
        // Retain the old path for them instead of changing selector semantics.
        if (rows.any { it.normalName() in setOf("html", "head", "body", "tr", "td", "th", "tbody", "thead", "tfoot", "col", "colgroup", "caption", "option", "optgroup", "script", "style", "meta", "link", "title", "base") }) return@withContext null
        rows.map { row ->
            coroutineContext.ensureActive()
            val selected = mutableMapOf<String, List<Element>>()
            fields.map { field ->
                if (field.terminal.isBlank()) "" else selected.getOrPut(field.selectionKey) { select(listOf(row), field.steps) }
                    .flatMap { node -> when (field.terminal) {
                        "text" -> listOf(node.text())
                        "ownText" -> listOf(node.ownText())
                        "textNodes" -> node.textNodes().map { it.text() }
                        else -> listOf(node.attr(field.terminal.removePrefix("attr:")))
                    } }.filter { it.isNotBlank() }.joinToString("\n").trim()
            }
        }
    }

    suspend fun elements(content: String, rule: String, baseUrl: String,
        variables: Map<String, String> = emptyMap()): List<String> = withContext(Dispatchers.IO) {
        Session(content, baseUrl, variables, coroutineContext).eval(content, rule, true)
    }

    suspend fun string(content: String, rule: String, baseUrl: String,
        variables: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        Session(content, baseUrl, variables, coroutineContext).eval(content, rule, false).joinToString("\n")
    }

    /** Request/header templates are literals except for explicit {{...}} or JS.
     * Variable text is substituted as provided; the request layer chooses the
     * charset/URL encoding before passing key and page. */
    suspend fun template(content: String, rule: String, baseUrl: String,
        variables: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val session = Session(content, baseUrl, variables, coroutineContext)
        val value = rule.trim()
        if (value.startsWith("@js:", true) || value.startsWith("<js>", true))
            session.eval(content, value, false).joinToString("\n")
        else session.requestTemplate(content, rule)
    }

    private inner class Session(val original: String, val baseUrl: String,
        val variables: Map<String, String>, val coroutine: CoroutineContext) {
        var depth = 0
        fun requestTemplate(content: String, value: String): String {
            val scriptAt = value.indexOf("@js:", ignoreCase = true)
            if (scriptAt >= 0) return js(format(content, value.substring(0, scriptAt)).trimEnd(),
                value.substring(scriptAt + 4), false).joinToString("\n")
            val formatted = format(content, value)
            val page = (variables["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            return Regex("<([^<>]*,[^<>]*)>").replace(formatted) { match ->
                val choices = splitOutside(match.groupValues[1], ",")
                choices.getOrElse((page - 1).coerceAtMost(choices.lastIndex)) { "" }
            }
        }
        fun format(content: String, value: String): String = Regex("\\{\\{([\\s\\S]*?)\\}\\}")
            .replace(value) { match ->
                val inner = match.groupValues[1].trim()
                when {
                    variables.containsKey(inner) -> variables[inner].orEmpty()
                    inner == "baseUrl" -> baseUrl
                    inner.startsWith("@@") -> eval(content, inner.drop(2), false).joinToString("\n")
                    inner.startsWith("$") || inner.startsWith("@css:", true) || inner.startsWith("@json:", true) ->
                        eval(content, inner, false).joinToString("\n")
                    else -> js(content, inner, false).joinToString("\n")
                }
            }
        fun eval(content: String, expression: String, asElements: Boolean): List<String> {
            coroutine.ensureActive()
            if (content.length > MAX_TEXT_LENGTH || expression.length > MAX_TEXT_LENGTH)
                throw LegadoRuleException("书源响应或规则超过处理大小限制")
            if (++depth > 24) throw LegadoRuleException("书源规则递归层数过多")
            try {
                var rule = expression.trim()
                if (rule.isEmpty()) return emptyList()
                val jsAt = rule.indexOf("@js:", ignoreCase = true)
                val xmlJsAt = rule.indexOf("<js>", ignoreCase = true)
                val scriptAt = listOf(jsAt, xmlJsAt).filter { it >= 0 }.minOrNull()
                if (scriptAt != null) {
                    val prefix = rule.substring(0, scriptAt)
                    val input: Any = if (prefix.isBlank()) content else {
                        val values = eval(content, prefix, asElements)
                        if (asElements && values.size > 1) values else values.joinToString("\n")
                    }
                    val script = if (scriptAt == xmlJsAt) rule.substring(scriptAt + 4).substringBeforeLast("</js>")
                        else rule.substring(scriptAt + 4)
                    return js(input, script, asElements)
                }
                while (rule.contains("@put:")) {
                    val start = rule.indexOf("@put:")
                    val open = rule.indexOf('{', start + 5)
                    if (open < 0) throw LegadoRuleException("书源@put变量格式无效")
                    val close = matchingBrace(rule, open)
                    for (entry in splitOutside(rule.substring(open + 1, close), ",")) {
                        val colon = entry.indexOf(':')
                        if (colon < 0) throw LegadoRuleException("书源@put变量格式无效")
                        val key = entry.substring(0, colon).trim().trim('\'', '"')
                        val raw = entry.substring(colon + 1).trim()
                        val field = if (raw.startsWith("'") && raw.endsWith("'")) raw.substring(1, raw.lastIndex)
                            else runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull() ?: raw.trim('"')
                        saved[key] = eval(content, field, false).joinToString("\n")
                    }
                    rule = rule.removeRange(start, close + 1).trim()
                }
                if (rule.isEmpty()) return listOf(content)
                if (rule.contains("@get:{")) {
                    val value = Regex("@get:\\{([^}]+)\\}").replace(rule) { saved[it.groupValues[1]].orEmpty() }
                    if (rule.startsWith("@get:{") || value.startsWith("http")) return listOf(value)
                    rule = value
                }
                // Replacement fields can embed chapter/book templates. Resolve
                // those inside the regex and replacement, then execute the rule;
                // returning the formatted expression would put it in the body.
                val replacementParts = splitReplacement(rule)
                val replace = if (replacementParts.size > 1) replacementParts.first().length else -1
                if (replace >= 0) {
                    val selected = rule.substring(0, replace)
                    val values = if (selected.isBlank()) listOf(content) else eval(content, selected, asElements)
                    val pieces = replacementParts.drop(1)
                    val patternText = format(content, pieces[0])
                    // The reference AnalyzeRule leaves content intact for an
                    // empty pattern; this also avoids empty-match insertion.
                    if (patternText.isEmpty()) return values
                    val pattern = try { Regex(patternText) } catch (e: Exception) {
                        throw LegadoRuleException("书源替换正则无效", e)
                    }
                    val replacement = format(content, pieces.getOrElse(1) { "" }.removeSuffix("#"))
                    val single = rule.endsWith("###")
                    return values.map { text ->
                        if (single) pattern.find(text)?.let { match ->
                            Regex("\\$(\\d+)").replace(replacement) { group ->
                                match.groupValues.getOrNull(group.groupValues[1].toInt()).orEmpty()
                            }
                        }.orEmpty() else pattern.replace(text, replacement)
                    }
                }
                if (rule.contains("{{")) {
                    val formatted = format(content, rule)
                    if (formatted == rule) throw LegadoRuleException("模板规则格式无效")
                    return if (rule.startsWith("{{") || formatted.startsWith("http") || rule.contains("{{")) listOf(formatted)
                        else eval(content, formatted, asElements)
                }
                // Legado URL fields can be literals even when the row is JSON;
                // their single-brace JSON bindings differ from request {{...}} JS.
                val jsonBindings = Regex("(?<!\\{)\\{(\\$[^{}]+)\\}(?!\\})")
                if (jsonBindings.containsMatchIn(rule)) {
                    return listOf(jsonBindings.replace(rule) { match ->
                        eval(content, match.groupValues[1], false).joinToString("\n")
                    })
                }
                if (rule.startsWith("http://", true) || rule.startsWith("https://", true)) return listOf(rule)
                rule = rule.removePrefix("@@")
                val fallback = splitOutside(rule, "||")
                if (fallback.size > 1) {
                    for (part in fallback) eval(content, part, asElements).filter { it.isNotBlank() }.let {
                        if (it.isNotEmpty()) return it
                    }
                    return emptyList()
                }
                val joined = splitOutside(rule, "&&")
                if (joined.size > 1) return joined.flatMap { eval(content, it, asElements) }
                val interleave = splitOutside(rule, "%%")
                if (interleave.size > 1) {
                    val lists = interleave.map { eval(content, it, asElements) }
                    return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
                }
                if (rule.startsWith("@get:")) return listOf(saved[rule.removePrefix("@get:").trim('{', '}')].orEmpty())
                if (rule.startsWith("@json:", true)) rule = rule.substring(6)
                // Lenient JSON accepts some entire HTML fragments as an unquoted
                // string primitive. Only actual containers enable implicit paths.
                val jsonContent = if (content.trimStart().firstOrNull() in setOf('{', '['))
                    runCatching { json.parseToJsonElement(content) }.getOrNull() else null
                if (rule.startsWith("$") || (jsonContent != null && !rule.startsWith("@css:", true))) {
                    if (jsonContent == null) throw LegadoRuleException("JSONPath规则收到非JSON响应")
                    return jsonPath(jsonContent, rule).map(::jsonValue)
                }
                if (rule.startsWith("@xpath:", true)) rule = rule.substring(7)
                if (rule.startsWith("//") || rule.startsWith("./") || rule.startsWith("(/")) return xpath(content, rule, asElements)
                return css(content, rule.removePrefix("@css:").removePrefix("@CSS:"), asElements)
            } catch (e: LegadoRuleException) { throw e
            } catch (e: java.util.concurrent.CancellationException) { throw e
            } catch (e: Exception) { throw LegadoRuleException("书源规则无法执行：${expression.take(100)}；${e.message}", e)
            } finally { depth-- }
        }

        private fun css(content: String, rule: String, asElements: Boolean): List<String> {
            val doc = html(content, baseUrl)
            // Imported regexes run on the extracted HTML. Pretty-printing it
            // rewrites a real trailing space before <p> into a newline, breaking
            // rules such as the bundled 香书 chapter-title cleanup expression.
            doc.outputSettings().prettyPrint(false)
            val tokens = splitOutside(rule, "@").filter { it.isNotBlank() }
            // A field rule is evaluated against the selected result row, not the new
            // synthetic document wrapper. In particular, `href` must read the <a>
            // row itself and not the empty attribute on #root.
            val isFragment = !Regex("^\\s*(?:<!doctype|<html|<head|<body)", RegexOption.IGNORE_CASE).containsMatchIn(content)
            var nodes: List<Element> = if (isFragment && doc.body().childrenSize() == 1)
                listOf(doc.body().child(0)) else listOf(doc)
            if (tokens.isEmpty()) return emptyList()
            for ((position, part) in tokens.withIndex()) {
                val last = position == tokens.lastIndex
                val terminal = part in setOf("text", "ownText", "textNodes", "html", "all", "outerHtml") ||
                    (last && tokens.size > 1 && part.matches(Regex("[a-zA-Z_][a-zA-Z0-9_:-]*")) &&
                        part !in TAGS)
                if (terminal || (last && tokens.size == 1 && part in ATTRS)) {
                    return nodes.flatMap { node -> when (part) {
                        "text" -> listOf(textField(node))
                        "ownText" -> listOf(node.ownText())
                        "textNodes" -> node.textNodes().map { it.text() }
                        "html" -> listOf(htmlField(node, outer = false))
                        "all", "outerHtml" -> listOf(htmlField(node, outer = true))
                        else -> listOf(node.attr(part.removePrefix("attr:")))
                    } }.filter { it.isNotBlank() }
                }
                val index = Regex("([.!])(-?\\d+(?::-?\\d+)*)$").find(part)
                var selector = if (index == null) part else part.substring(0, index.range.first)
                selector = when {
                    selector.startsWith("class.") -> "." + selector.drop(6).trim().split(Regex("\\s+")).joinToString(".")
                    selector.startsWith("id.") -> "#" + selector.drop(3)
                    selector.startsWith("tag.") -> selector.drop(4)
                    selector.startsWith("text.") -> ":containsOwn(" + selector.drop(5) + ")"
                    else -> selector
                }
                val selected = nodes.flatMap { if (selector.isBlank()) listOf(it) else it.select(selector).toList() }.distinct()
                nodes = if (index == null) selected else {
                    val wanted = index.groupValues[2].split(':').mapNotNull { it.toIntOrNull() }
                        .map { if (it < 0) selected.size + it else it }.toSet()
                    if (index.groupValues[1] == "!") selected.filterIndexed { i, _ -> i !in wanted }
                    else wanted.mapNotNull { selected.getOrNull(it) }
                }
            }
            return nodes.map { if (asElements) it.outerHtml() else textField(it) }.filter { it.isNotBlank() }
        }

        private fun textField(element: Element): String {
            if (variables["_onvyra_content_lines"] != "1") return element.text()
            val copy = element.clone()
            copy.select("script,style,noscript,iframe,.ad,.ads,.advertisement").remove()
            copy.select("br").after("\n")
            copy.select("p,div,li").prepend("\n").append("\n")
            return copy.wholeText()
        }

        private fun htmlField(element: Element, outer: Boolean): String {
            // Preserve literal text whitespace, but keep block boundaries as real
            // lines for imported .*advert.* filters. Work on a private clone so
            // later selectors, textNodes, JSON fields and JS results are unchanged.
            val copy = element.clone()
            val document = Jsoup.parseBodyFragment("", baseUrl)
            document.outputSettings().prettyPrint(false)
            document.body().appendChild(copy)
            copy.select("br,p,div,li,blockquote,section,article,h1,h2,h3,h4,h5,h6").forEach {
                it.after(org.jsoup.nodes.TextNode("\n"))
            }
            return if (outer) copy.outerHtml() else copy.html()
        }

        private fun xpath(content: String, rule: String, asElements: Boolean): List<String> {
            val doc = org.jsoup.helper.W3CDom().namespaceAware(false).fromJsoup(Jsoup.parse(content, baseUrl))
            val result = XPathFactory.newInstance().newXPath().evaluate(rule, doc, XPathConstants.NODESET) as org.w3c.dom.NodeList
            return (0 until result.length).map { i ->
                val node = result.item(i)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE || !asElements) node.textContent.orEmpty()
                else StringWriter().also { writer ->
                    TransformerFactory.newInstance().newTransformer().apply {
                        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                    }.transform(DOMSource(node), StreamResult(writer))
                }.toString()
            }.filter { it.isNotBlank() }
        }

        private fun js(input: Any, script: String, asElements: Boolean): List<String> {
            if (script.length > MAX_SCRIPT_LENGTH || jsLib.length > MAX_SCRIPT_LENGTH)
                throw LegadoRuleException("书源JavaScript超过处理大小限制")
            // Runtime/class loading and our fixed bridge bootstrap are not source execution.
            // Start the budget immediately before evaluating source-controlled variables/code.
            var started = 0L
            try {
                val factory = object : ContextFactory() {
                    override fun makeContext(): Context = super.makeContext().apply {
                        optimizationLevel = -1; languageVersion = Context.VERSION_ES6; instructionObserverThreshold = 10000
                        maximumInterpreterStackDepth = 128
                        // Source JavaScript can use the explicit bridge below, never
                        // arbitrary application/JVM objects or reflection.
                        setClassShutter { false }
                    }
                    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                        coroutine.ensureActive()
                        if (started != 0L && System.nanoTime() - started > 3_000_000_000L) throw SourceScriptTimeout()
                    }
                }
                return factory.call(ContextAction { cx ->
                    val scope = cx.initSafeStandardObjects()
                    // Rhino runs off the UI thread. Bound common native allocation
                    // operations too: instruction callbacks alone do not run inside
                    // String.repeat/Array.join, and a sparse huge array must not
                    // become an unbounded Kotlin result traversal afterward.
                    cx.evaluateString(scope, """
                        (function(){
                          const OriginalArray=Array;
                          function BoundedArray(){
                            if(arguments.length===1 && typeof arguments[0]==='number' && arguments[0]>100000)
                              throw new Error('书源数组超过大小限制');
                            return OriginalArray.apply(null,arguments);
                          }
                          BoundedArray.prototype=OriginalArray.prototype;
                          BoundedArray.isArray=OriginalArray.isArray;
                          BoundedArray.from=OriginalArray.from;
                          BoundedArray.of=OriginalArray.of;
                          Array=BoundedArray;
                          const repeat=String.prototype.repeat;
                          String.prototype.repeat=function(n){
                            if(String(this).length*Number(n)>4194304) throw new Error('书源文本超过大小限制');
                            return repeat.call(this,n);
                          };
                          const join=OriginalArray.prototype.join;
                          OriginalArray.prototype.join=function(separator){
                            if(this.length>100000) throw new Error('书源数组超过大小限制');
                            var length=0;
                            for(var i=0;i<this.length;i++){length+=String(this[i]).length;
                              if(length>4194304) throw new Error('书源文本超过大小限制');}
                            return join.call(this,separator);
                          };
                        })();
                    """.trimIndent(), "source-resource-limits", 1, null)
                    fun function(target: Scriptable, name: String, block: (Array<out Any?>) -> Any?) {
                        ScriptableObject.putProperty(target, name, object : BaseFunction() {
                            override fun call(context: Context, sc: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                                coroutine.ensureActive()
                                return try { toJs(context, scope, block(args)) }
                                catch (e: RuntimeException) { throw e }
                                catch (e: Exception) { throw WrappedException(e) }
                            }
                        })
                    }
                    fun arg(args: Array<out Any?>, index: Int = 0) = args.getOrNull(index)?.let { Context.toString(it) }.orEmpty()
                    val unresolvedHosts = mutableMapOf<String, Exception>()
                    fun fetchSource(request: String): String {
                        val address = Regex(",\\s*(?=\\{)").find(request)?.let { request.substring(0, it.range.first) } ?: request
                        val host = runCatching { java.net.URI(address.trim()).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
                        // Some imported rules immediately retry java.ajax with java.connect.
                        // A confirmed DNS failure is identical for both within this script turn;
                        // let the rule reach its healthy mirrors instead of repeating DNS waits.
                        host?.let { unresolvedHosts[it] }?.let { throw it }
                        val before = System.nanoTime()
                        try {
                            return (ajax ?: throw LegadoRuleException("此书源需要网络桥接java.ajax"))(request)
                        } catch (error: Exception) {
                            if (host != null && unresolvedHosts.size < 32 && generateSequence(error as Throwable) {
                                it.cause?.takeIf { cause -> cause !== it }
                            }.take(12).any { it is java.net.UnknownHostException }) unresolvedHosts[host] = error
                            throw error
                        } finally {
                            // Network has the adapter's own cancellable deadline.
                            // Count interpreter work, rather than charging a slow
                            // HTTP response against the pure-JS instruction limit.
                            if (started != 0L) started += System.nanoTime() - before
                            coroutine.ensureActive()
                        }
                    }
                    fun bytes(value: Any?): ByteArray = when (value) {
                        is ByteArray -> value.also { require(it.size <= MAX_TEXT_LENGTH) { "书源字节数组超过大小限制" } }
                        is NativeArray -> {
                            require(value.length <= MAX_TEXT_LENGTH) { "书源字节数组超过大小限制" }
                            ByteArray(value.length.toInt()) { index ->
                                if (index % 4096 == 0) coroutine.ensureActive()
                                val number = Context.toNumber(value.get(index, value))
                                require(number.isFinite()) { "书源字节数组含无效数值" }
                                number.toInt().toByte()
                            }
                        }
                        else -> throw LegadoRuleException("此书源操作需要字节数组")
                    }
                    fun networkResponse(args: Array<out Any?>): Scriptable {
                        val headersValue = args.getOrNull(1)
                        val headersText = when (headersValue) {
                            null, Undefined.instance -> "{}"
                            is Scriptable -> NativeJSON.stringify(cx, scope, headersValue, null, null).toString()
                            else -> arg(args, 1).ifBlank { "{}" }
                        }
                        val headers = json.parseToJsonElement(headersText) as? JsonObject
                            ?: throw LegadoRuleException("书源请求头必须是 JSON 对象")
                        // Route the supported response facade through the existing
                        // adapter fetch, including its PublicWebNetwork checks,
                        // source headers, cookie handling, deadlines and errors.
                        val url = arg(args)
                        val request = if (headers.isEmpty()) url else {
                            val suffix = Regex(",\\s*(?=\\{)").find(url)
                            val options = suffix?.let { json.parseToJsonElement(url.substring(it.range.last + 1)) as? JsonObject }
                                ?: JsonObject(emptyMap())
                            val base = suffix?.let { url.substring(0, it.range.first) } ?: url
                            val previous = options["headers"] as? JsonObject ?: JsonObject(emptyMap())
                            base + "," + JsonObject(options + ("headers" to JsonObject(previous + headers))).toString()
                        }
                        val body = fetchSource(request)
                        if (body.length > MAX_TEXT_LENGTH) throw LegadoRuleException("书源响应超过大小限制")
                        return cx.newObject(scope).also { response ->
                            function(response, "body") { body }
                            function(response, "code") { 200 }
                            function(response, "url") { url.substringBefore(",{") }
                        }
                    }
                    val bridge = cx.newObject(scope)
                    var bridgeContent = original
                    function(bridge, "getString") { eval(bridgeContent, arg(it), false).joinToString("\n") }
                    function(bridge, "getStringList") { eval(bridgeContent, arg(it), false) }
                    function(bridge, "getElements") { eval(bridgeContent, arg(it), true).map { fragment -> Jsoup.parseBodyFragment(fragment, baseUrl).body().children().firstOrNull() ?: Jsoup.parse(fragment, baseUrl) } }
                    function(bridge, "getElement") { eval(bridgeContent, arg(it), true).firstOrNull()?.let { fragment -> Jsoup.parseBodyFragment(fragment, baseUrl).body().children().firstOrNull() } }
                    function(bridge, "setContent") { bridgeContent = arg(it); bridge }
                    function(bridge, "get") { saved[arg(it)] ?: variables[arg(it)] ?: "" }
                    function(bridge, "put") { saved[arg(it)] = arg(it, 1); arg(it, 1) }
                    function(bridge, "ajax") { fetchSource(arg(it)) }
                    function(bridge, "connect") { networkResponse(it) }
                    function(bridge, "base64Decode") { String(Base64.getDecoder().decode(arg(it)), charset(arg(it, 1))) }
                    function(bridge, "base64DecodeToByteArray") { Base64.getDecoder().decode(arg(it)) }
                    function(bridge, "base64Encode") { Base64.getEncoder().encodeToString(
                        if (it.firstOrNull() is NativeArray) bytes(it[0]) else arg(it).toByteArray(charset(arg(it, 1)))) }
                    function(bridge, "strToBytes") { arg(it).toByteArray(charset(arg(it, 1))) }
                    function(bridge, "bytesToStr") { String(bytes(it.firstOrNull()), charset(arg(it, 1))) }
                    function(bridge, "hexDecodeToByteArray") { hexBytes(arg(it)) }
                    function(bridge, "hexDecodeToString") { String(hexBytes(arg(it)), charset(arg(it, 1))) }
                    function(bridge, "hexEncodeToString") { arg(it).toByteArray().joinToString("") { byte -> "%02x".format(byte) } }
                    function(bridge, "encodeURI") { URLEncoder.encode(arg(it), arg(it, 1).ifBlank { "UTF-8" }).replace("+", "%20") }
                    function(bridge, "md5Encode") { digest(arg(it), "MD5") }
                    function(bridge, "md5Encode16") { digest(arg(it), "MD5").substring(8, 24) }
                    function(bridge, "digestHex") { args ->
                        val algorithm = arg(args, 1).uppercase().replace("_", "-")
                        require(algorithm in setOf("MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512")) { "不支持的书源摘要算法" }
                        val inputBytes = if (args.firstOrNull() is NativeArray) bytes(args[0]) else arg(args).toByteArray(Charsets.UTF_8)
                        MessageDigest.getInstance(algorithm).digest(inputBytes).joinToString("") { "%02x".format(it) }
                    }
                    function(bridge, "createSymmetricCrypto") { args ->
                        val algorithm = arg(args).replace("PKCS7Padding", "PKCS5Padding", ignoreCase = true)
                        require(algorithm in setOf("AES/CBC/PKCS5Padding", "AES/CBC/NoPadding", "AES/ECB/PKCS5Padding", "AES/ECB/NoPadding")) {
                            "不支持的书源对称加密算法"
                        }
                        val key = bytes(args.getOrNull(1)).copyOf()
                        require(key.size in setOf(16, 24, 32)) { "AES 密钥长度无效" }
                        val iv = if (algorithm.contains("/CBC/")) bytes(args.getOrNull(2)).copyOf().also {
                            require(it.size == 16) { "AES IV 长度无效" }
                        } else null
                        fun applyCipher(mode: Int, value: Any?): ByteArray = Cipher.getInstance(algorithm).run {
                            if (iv == null) init(mode, SecretKeySpec(key, "AES"))
                            else init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            doFinal(bytes(value))
                        }
                        cx.newObject(scope).also { crypto ->
                            function(crypto, "decrypt") { applyCipher(Cipher.DECRYPT_MODE, it.firstOrNull()) }
                            function(crypto, "encrypt") { applyCipher(Cipher.ENCRYPT_MODE, it.firstOrNull()) }
                        }
                    }
                    function(bridge, "log") { "" }; function(bridge, "toast") { "" }; function(bridge, "longToast") { "" }
                    ScriptableObject.putProperty(scope, "java", bridge)
                    // These two compatibility constructors are ordinary bounded JS
                    // objects, not NativeJavaClass: no class lookup, reflection,
                    // filesystem or arbitrary Packages namespace is exposed.
                    cx.evaluateString(scope, """
                        var Packages=Object.freeze({java:Object.freeze({
                          util:Object.freeze({ArrayList:function(){
                            var items=[];
                            items.add=function(value){if(this.length>=100000)throw new Error('书源数组超过大小限制');this.push(value);return true;};
                            items.get=function(index){return this[index];};
                            items.size=function(){return this.length;};
                            items.toArray=function(){return this.slice();};
                            return items;
                          }}),
                          io:Object.freeze({ByteArrayOutputStream:function(capacity){
                            if(capacity!==undefined && (!isFinite(Number(capacity)) || Number(capacity)<0 || Number(capacity)>8388608))
                              throw new Error('书源字节数组超过大小限制');
                            var data=[];
                            this.write=function(value,offset,length){
                              if(typeof value==='number'){
                                if(data.length>=8388608)throw new Error('书源字节数组超过大小限制');data.push((Number(value)<<24)>>24);return;
                              }
                              if(!value || typeof value.length!=='number')throw new Error('需要字节数组');
                              var start=offset===undefined?0:Number(offset), count=length===undefined?value.length:Number(length);
                              if(start<0 || count<0 || start+count>value.length || data.length+count>8388608)throw new Error('书源字节范围无效');
                              for(var i=0;i<count;i++)data.push((Number(value[start+i])<<24)>>24);
                            };
                            this.toByteArray=function(){return data.slice();};
                            this.size=function(){return data.length;};
                            this.close=function(){};
                            this.reset=function(){data=[];};
                          }})
                        })});
                    """.trimIndent(), "source-byte-compatibility", 1, null)
                    ScriptableObject.putProperty(scope, "result", toJs(cx, scope, input))
                    ScriptableObject.putProperty(scope, "src", original)
                    ScriptableObject.putProperty(scope, "baseUrl", baseUrl)
                    started = System.nanoTime()
                    for ((name, value) in variables.filterKeys { !it.contains('.') }) {
                        val parsed = runCatching { json.parseToJsonElement(value) }.getOrNull()
                        ScriptableObject.putProperty(scope, name, when {
                            parsed is JsonObject || parsed is JsonArray -> cx.evaluateString(scope, "(" + parsed.toString() + ")", "source-variable", 1, null)
                            name == "page" -> value.toDoubleOrNull() ?: value
                            else -> value
                        })
                    }
                    for (entity in listOf("source", "book", "chapter")) {
                        val obj = (ScriptableObject.getProperty(scope, entity) as? Scriptable) ?: cx.newObject(scope)
                        variables.filterKeys { it.startsWith("$entity.") }.forEach { (name, value) -> ScriptableObject.putProperty(obj, name.substringAfter('.'), value) }
                        function(obj, "getVariable") { saved["$entity:" + arg(it)] ?: "" }
                        function(obj, "putVariable") { saved["$entity:" + arg(it)] = arg(it, 1); arg(it, 1) }
                        if (entity == "source") {
                            fun saveSource(args: Array<out Any?>): String {
                                val key = arg(args)
                                val value = arg(args, 1)
                                synchronized(sourceVariables) {
                                    require(key.length <= 1024 && value.length <= MAX_TEXT_LENGTH) { "书源变量超过大小限制" }
                                    require(key in sourceVariables || sourceVariables.size < 128) { "书源变量条目过多" }
                                    require(sourceVariables.entries.filter { it.key != key }.sumOf { it.value.length.toLong() } + value.length <= 16L * 1024 * 1024) {
                                        "书源变量超过总大小限制"
                                    }
                                    sourceVariables[key] = value
                                }
                                return value
                            }
                            function(obj, "get") { sourceVariables[arg(it)] ?: "" }
                            function(obj, "put") { saveSource(it) }
                            function(obj, "getVariable") { sourceVariables[arg(it)] ?: "" }
                            function(obj, "putVariable") { saveSource(it) }
                        }
                        function(obj, "getKey") { variables["sourceKey"] ?: variables["source.bookSourceUrl"] ?: baseUrl }
                        ScriptableObject.putProperty(scope, entity, obj)
                    }
                    if (jsLib.isNotBlank()) {
                        if (jsLib.trim().startsWith("http")) throw LegadoRuleException("书源jsLib是外部地址，尚未载入脚本依赖")
                        cx.evaluateString(scope, jsLib, "source-jsLib", 1, null)
                    }
                    val result = cx.evaluateString(scope, script, "source-rule", 1, null)
                    jsValues(result, asElements)
                })
            } catch (e: SourceScriptTimeout) { throw LegadoRuleException("书源脚本执行超时", e)
            } catch (e: LegadoRuleException) { throw e
            } catch (e: java.util.concurrent.CancellationException) { throw e
            } catch (e: LinkageError) {
                // A missing runtime implementation must become a reader error,
                // including a later NoClassDefFoundError after failed class init.
                // Do not catch VirtualMachineError / OutOfMemoryError here.
                throw LegadoRuleException("书源脚本运行环境无法初始化，请更新应用后重试。", e)
            } catch (e: Exception) {
                var cause: Throwable? = e
                repeat(12) {
                    val failure = cause
                    if (failure is LinkageError) throw LegadoRuleException("书源脚本运行环境无法初始化，请更新应用后重试。", failure)
                    cause = failure?.cause?.takeIf { it !== failure }
                }
                val missing = Regex("(?:ReferenceError: )?[\"']?([A-Za-z_$][\\w$]*)[\"']? is not defined").find(e.message.orEmpty())?.groupValues?.get(1)
                throw LegadoRuleException(if (missing != null) "书源缺少JavaScript依赖：$missing" else "书源JavaScript无法执行：${e.message}", e)
            }
        }

        private fun toJs(cx: Context, scope: Scriptable, value: Any?): Any? = when (value) {
            null -> null
            is ByteArray -> {
                require(value.size <= MAX_TEXT_LENGTH) { "书源字节数组超过大小限制" }
                // Rhino requires Object[] here; Kotlin's Array<Int> is an Integer[] at runtime.
                cx.newArray(scope, Array<Any?>(value.size) { value[it].toInt() })
            }
            is List<*> -> cx.newArray(scope, value.map { toJs(cx, scope, it) }.toTypedArray()).apply {
                val array = this
                ScriptableObject.putProperty(this, "get", object : BaseFunction() {
                    override fun call(context: Context, sc: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                        val index = args.firstOrNull()?.let { Context.toNumber(it) }?.toInt() ?: 0
                        return array.get(index, array).takeUnless { it == Scriptable.NOT_FOUND }
                    }
                })
                ScriptableObject.putProperty(this, "size", object : BaseFunction() {
                    override fun call(context: Context, sc: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any = value.size
                })
            }
            is Element -> elementObject(cx, scope, value)
            else -> value
        }
        private fun elementObject(cx: Context, scope: Scriptable, element: Element): Scriptable {
            val obj = SourceElement(element).apply { parentScope = scope; prototype = ScriptableObject.getObjectPrototype(scope) }
            fun method(name: String, block: (Array<out Any?>) -> Any?) {
                ScriptableObject.putProperty(obj, name, object : BaseFunction() {
                    override fun call(context: Context, sc: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                        coroutine.ensureActive()
                        return toJs(context, scope, block(args))
                    }
                })
            }
            fun arg(args: Array<out Any?>) = args.firstOrNull()?.let { Context.toString(it) }.orEmpty()
            method("text") { element.text() }; method("ownText") { element.ownText() }
            method("html") { element.html() }; method("outerHtml") { element.outerHtml() }
            method("attr") { element.attr(arg(it)) }; method("absUrl") { element.absUrl(arg(it)) }
            method("select") { element.select(arg(it)).toList() }
            method("selectFirst") { element.selectFirst(arg(it)) }
            method("children") { element.children().toList() }
            method("parent") { element.parent() }
            method("tagName") { element.tagName() }
            method("toString") { element.outerHtml() }
            return obj
        }
        private fun jsValues(value: Any?, asElements: Boolean): List<String> {
            val unwrapped = if (value is Wrapper) value.unwrap() else value
            return when (unwrapped) {
                null, Undefined.instance, Scriptable.NOT_FOUND -> emptyList()
                is NativeArray -> {
                    if (unwrapped.length > MAX_RESULT_COUNT) throw LegadoRuleException("书源脚本返回条目过多")
                    (0 until unwrapped.length.toInt()).flatMap { coroutine.ensureActive(); jsValues(unwrapped.get(it, unwrapped), asElements) }
                }
                is Iterable<*> -> unwrapped.flatMap { jsValues(it, asElements) }
                is SourceElement -> listOf(if (asElements) unwrapped.element.outerHtml() else unwrapped.element.text())
                is Element -> listOf(if (asElements) unwrapped.outerHtml() else unwrapped.text())
                is NativeObject -> listOf(NativeJSON.stringify(Context.getCurrentContext(), unwrapped.parentScope, unwrapped, null, null).toString())
                else -> listOf(Context.toString(unwrapped).also {
                    if (it.length > MAX_TEXT_LENGTH) throw LegadoRuleException("书源脚本返回文本过大")
                }).filter { it.isNotBlank() }
            }
        }
    }

    private fun jsonPath(root: JsonElement, expression: String): List<JsonElement> {
        var path = expression.trim().removePrefix("$")
        if (path.isEmpty()) return if (root is JsonArray) root.toList() else listOf(root)
        var values = listOf(root)
        while (path.isNotEmpty()) {
            when {
                path.startsWith("..") -> {
                    val key = Regex("^\\.\\.([\\w$-]+|\\*)").find(path) ?: throw LegadoRuleException("不支持的JSONPath：$expression")
                    fun descendants(node: JsonElement): List<JsonElement> = when (node) {
                        is JsonObject -> node.entries.flatMap { (name, child) -> (if (key.groupValues[1] == "*" || name == key.groupValues[1]) listOf(child) else emptyList()) + descendants(child) }
                        is JsonArray -> node.flatMap(::descendants)
                        else -> emptyList()
                    }
                    values = values.flatMap(::descendants); path = path.drop(key.value.length)
                }
                path.startsWith("[") -> {
                    val end = matchingBracket(path)
                    val token = path.substring(1, end).trim(); path = path.drop(end + 1)
                    values = when {
                        token == "*" -> values.flatMap { when (it) { is JsonArray -> it.toList(); is JsonObject -> it.values.toList(); else -> emptyList() } }
                        token.startsWith("?(") && token.endsWith(")") -> values.flatMap { if (it is JsonArray) it.toList() else listOf(it) }.filter { matchesFilter(it, token.drop(2).dropLast(1)) }
                        token.startsWith("'") || token.startsWith("\"") -> values.mapNotNull { (it as? JsonObject)?.get(token.trim('\'', '"')) }
                        token.toIntOrNull() != null -> values.mapNotNull { node -> (node as? JsonArray)?.let { it.getOrNull(token.toInt().let { i -> if (i < 0) it.size + i else i }) } }
                        token.contains(':') -> {
                            val parts = token.split(':')
                            if (parts.size !in 2..3) throw LegadoRuleException("不支持的JSONPath切片：$token")
                            values.flatMap { node -> (node as? JsonArray)?.let { array ->
                                val step = parts.getOrNull(2)?.toIntOrNull() ?: 1
                                if (step <= 0) throw LegadoRuleException("JSONPath切片步长必须为正数")
                                fun position(raw: String, default: Int) = raw.toIntOrNull()?.let { if (it < 0) array.size + it else it } ?: default
                                val start = position(parts[0], 0).coerceIn(0, array.size)
                                val end = position(parts[1], array.size).coerceIn(0, array.size)
                                (start until end step step).map { array[it] }
                            } ?: emptyList() }
                        }
                        else -> throw LegadoRuleException("不支持的JSONPath索引：$token")
                    }
                }
                else -> {
                    path = path.removePrefix(".")
                    if (path.startsWith("[")) continue
                    val key = Regex("^[\\w$-]+|^\\*").find(path) ?: throw LegadoRuleException("不支持的JSONPath：$expression")
                    values = values.flatMap { node ->
                        val nodes = if (node is JsonArray) node.toList() else listOf(node)
                        nodes.flatMap { item -> if (item is JsonObject) {
                            if (key.value == "*") item.values.toList() else listOfNotNull(item[key.value])
                        } else emptyList() }
                    }
                    path = path.drop(key.value.length)
                }
            }
        }
        return values.flatMap { if (it is JsonArray) it.toList() else listOf(it) }.filter { it != JsonNull }
    }

    private fun matchesFilter(node: JsonElement, predicate: String): Boolean {
        val found = Regex("^\\s*@\\.([\\w.]+)\\s*(==|!=|>=|<=|>|<)\\s*(.*?)\\s*$").matchEntire(predicate)
            ?: throw LegadoRuleException("不支持的JSONPath过滤条件：$predicate")
        val actual = jsonPath(node, found.groupValues[1]).firstOrNull()?.let(::jsonValue).orEmpty()
        val expected = found.groupValues[3].trim('\'', '"')
        val comparison = actual.toDoubleOrNull()?.let { a -> expected.toDoubleOrNull()?.let { a.compareTo(it) } } ?: actual.compareTo(expected)
        return when (found.groupValues[2]) { "==" -> actual == expected; "!=" -> actual != expected; ">" -> comparison > 0; "<" -> comparison < 0; ">=" -> comparison >= 0; else -> comparison <= 0 }
    }
    private fun jsonValue(value: JsonElement): String = if (value is JsonPrimitive) value.contentOrNull.orEmpty() else value.toString()
    private fun matchingBracket(path: String): Int {
        var depth = 0; var quote: Char? = null; var escaped = false
        for ((i, c) in path.withIndex()) {
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '\'' || c == '"') { quote = c; continue }
            if (c == '[') depth++
            if (c == ']' && --depth == 0) return i
        }
        throw LegadoRuleException("JSONPath括号未闭合")
    }
    private fun matchingBrace(value: String, start: Int): Int {
        var depth = 0; var quote: Char? = null; var escaped = false
        for (i in start until value.length) {
            val c = value[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '\'' || c == '"') { quote = c; continue }
            if (c == '{') depth++
            if (c == '}' && --depth == 0) return i
        }
        throw LegadoRuleException("书源变量括号未闭合")
    }
    /** A ## inside {{@@...##...}} belongs to the embedded field, not the outer rule. */
    private fun splitReplacement(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < value.length) {
            when {
                value.startsWith("{{", index) -> {
                    val end = value.indexOf("}}", index + 2)
                    index = if (end < 0) value.length else end + 2
                }
                value.startsWith("##", index) -> {
                    result += value.substring(start, index)
                    index += 2; start = index
                }
                else -> index++
            }
        }
        result += value.substring(start)
        return result
    }
    private fun splitOutside(value: String, separator: String): List<String> {
        val result = mutableListOf<String>(); var start = 0; var i = 0; var depth = 0; var quote: Char? = null; var escaped = false
        while (i < value.length) {
            val c = value[i]
            if (escaped) { escaped = false; i++; continue }
            if (c == '\\') { escaped = true; i++; continue }
            if (quote != null) { if (c == quote) quote = null; i++; continue }
            if (c == '\'' || c == '"') { quote = c; i++; continue }
            if (c in "[({") depth++ else if (c in "])}") depth--
            if (depth == 0 && value.startsWith(separator, i)) { result += value.substring(start, i); i += separator.length; start = i }
            else i++
        }
        result += value.substring(start)
        return result
    }
    private fun charset(name: String): Charset = Charset.forName(name.ifBlank { "UTF-8" })
    private fun hexBytes(value: String): ByteArray {
        val text = value.trim(); if (text.length % 2 != 0 || !text.matches(Regex("[a-fA-F0-9]*"))) throw LegadoRuleException("十六进制数据无效")
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun digest(value: String, algorithm: String) = MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private class SourceElement(val element: Element) : NativeObject()
    companion object {
        private const val MAX_TEXT_LENGTH = 8 * 1024 * 1024
        private const val MAX_SCRIPT_LENGTH = 1024 * 1024
        private const val MAX_RESULT_COUNT = 100_000L
        private val ATTRS = setOf("href", "src", "content", "value", "data-src", "data-original", "alt", "title", "text", "ownText", "textNodes", "html", "all", "outerHtml")
        private val TAGS = setOf("a", "p", "li", "ul", "div", "span", "img", "h1", "h2", "h3", "h4", "b", "i", "em", "dl", "dt", "dd", "article", "section", "table", "tr", "td", "option", "select", "script", "meta", "body")
    }
}
