package com.constx.onvyra.data.media

import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

private const val MAX_CATALOG_PAYLOAD_BYTES = 1_048_576
private const val MAX_CATALOG_PAYLOAD_ROWS = 512
private const val MAX_CATALOG_PAYLOAD_DEPTH = 32
private const val MAX_CATALOG_PAYLOAD_NODES = 8_192
private val forbiddenXmlDeclaration = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
private val explicitApiFailureMessage = Regex(
    "error|fail|denied|forbidden|invalid|unauthori[sz]ed|错误|失败|未授权|无权限|权限不足|访问受限|非法|验证码|参数.{0,12}(?:无效|缺少|缺失)",
    RegexOption.IGNORE_CASE,
)

/** Normalize only the bounded public catalog formats we understand; never execute provider code. */
internal fun decodeMovieCatalogPayload(content: String): JsonObject {
    if (content.length > MAX_CATALOG_PAYLOAD_BYTES || content.toByteArray(Charsets.UTF_8).size > MAX_CATALOG_PAYLOAD_BYTES) {
        invalidCatalogPayload()
    }
    val text = content.trim().removePrefix("\uFEFF").trim()
    val root = try {
        when {
            text.startsWith('{') -> {
                checkJsonBudget(text)
                Json.parseToJsonElement(text) as? JsonObject ?: invalidCatalogPayload()
            }
            text.startsWith('<') -> decodeCatalogXml(text)
            else -> invalidCatalogPayload()
        }
    } catch (error: MovieCatalogPayloadException) {
        throw error
    } catch (_: Exception) {
        // Remote payloads, exception messages and URLs do not become model-facing error text.
        invalidCatalogPayload()
    }
    validateCatalogRoot(root)
    return root
}

private fun validateCatalogRoot(root: JsonObject) {
    val rows = root["list"] as? JsonArray
    val code = (root["code"] as? JsonPrimitive)?.intOrNull
    val status = (root["status"] as? JsonPrimitive)?.contentOrNull
    val message = listOf("msg", "message").mapNotNull { (root[it] as? JsonPrimitive)?.contentOrNull }.joinToString(" ")
    val explicitFailure = (root["success"] as? JsonPrimitive)?.booleanOrNull == false ||
        status?.lowercase(Locale.ROOT) in setOf("error", "failed", "failure", "denied") ||
        meaningfulCatalogError(root["error"]) || code != null && (code < 0 || code >= 400) ||
        code == 0 && (rows == null || explicitApiFailureMessage.containsMatchIn(message))
    if (explicitFailure) throw MovieCatalogPayloadException(MovieCatalogPayloadFailure.API_ERROR)
    if (rows == null || rows.size > MAX_CATALOG_PAYLOAD_ROWS || rows.any { it !is JsonObject }) invalidCatalogPayload()
    if (rows.isNotEmpty() && rows.none { entry ->
        val item = entry as JsonObject
        fun field(vararg names: String) = names.firstNotNullOfOrNull { name ->
            (item[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }
        field("vod_id", "id")?.matches(Regex("[A-Za-z0-9_-]{1,80}")) == true && field("vod_name", "name") != null
    }) invalidCatalogPayload()
}

private fun meaningfulCatalogError(error: JsonElement?): Boolean = when (error) {
    null, JsonNull -> false
    is JsonObject -> error.isNotEmpty()
    is JsonArray -> error.isNotEmpty()
    is JsonPrimitive -> error.contentOrNull?.trim()?.let {
        it.isNotEmpty() && !it.equals("false", true) && !it.equals("null", true) && it != "0"
    } == true
}

/** Avoid passing extremely deep untrusted JSON to the recursive JSON parser. */
private fun checkJsonBudget(text: String) {
    var quoted = false
    var escaped = false
    var depth = 0
    var containers = 0
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> {
                depth++
                containers++
                if (depth > MAX_CATALOG_PAYLOAD_DEPTH || containers > MAX_CATALOG_PAYLOAD_NODES) invalidCatalogPayload()
            }
            '}', ']' -> if (--depth < 0) invalidCatalogPayload()
        }
    }
    if (quoted || depth != 0) invalidCatalogPayload()
}

private fun decodeCatalogXml(text: String): JsonObject {
    // Android and desktop XML parsers support different optional security features. The decoded
    // declaration rejection and rejecting resolver are mandatory, independent of those features.
    if (forbiddenXmlDeclaration.containsMatchIn(text)) invalidCatalogPayload()
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        runCatching { isXIncludeAware = false }
    }
    val reader = factory.newSAXParser().xmlReader
    listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    ).forEach { (name, value) -> runCatching { reader.setFeature(name, value) } }
    runCatching { reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
    runCatching { reader.setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    val handler = CatalogXmlHandler()
    reader.contentHandler = handler
    reader.errorHandler = handler
    reader.setEntityResolver { _, _ -> throw SAXException("External catalog entities are unavailable") }
    reader.parse(InputSource(StringReader(text)))
    return handler.payload()
}

private class CatalogXmlHandler : DefaultHandler() {
    private data class Capture(val tag: String, val depth: Int, val flag: String? = null, val text: StringBuilder = StringBuilder())
    private val stack = ArrayList<String>()
    private val rows = ArrayList<JsonObject>()
    private val rootFields = LinkedHashMap<String, JsonElement>()
    private var listDepth = -1
    private var listSeen = false
    private var listOpen = false
    private var nodeCount = 0
    private var rowDepth = -1
    private var row: LinkedHashMap<String, JsonElement>? = null
    private var capture: Capture? = null
    private val lineNames = ArrayList<String>()
    private val lineUrls = ArrayList<String>()

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val tag = qName.orEmpty().ifBlank { localName.orEmpty() }.lowercase(Locale.ROOT)
        stack.add(tag)
        val depth = stack.size
        if (++nodeCount > MAX_CATALOG_PAYLOAD_NODES || depth > MAX_CATALOG_PAYLOAD_DEPTH) invalidCatalogPayload()
        if (depth == 1 && tag !in setOf("rss", "list")) {
            if (tag == "error") throw MovieCatalogPayloadException(MovieCatalogPayloadFailure.API_ERROR)
            invalidCatalogPayload()
        }
        if (tag == "list" && (depth == 1 || depth == 2 && stack[0] == "rss")) {
            if (listSeen) invalidCatalogPayload()
            listSeen = true
            listOpen = true
            listDepth = depth
            mapOf("page" to "page", "pagecount" to "pagecount", "pagesize" to "limit", "recordcount" to "total").forEach { (attribute, key) ->
                attributes.getValue(attribute)?.let { rootFields[key] = JsonPrimitive(it) }
            }
        } else if (tag == "video" && listOpen && depth == listDepth + 1) {
            if (row != null || rows.size >= MAX_CATALOG_PAYLOAD_ROWS) invalidCatalogPayload()
            row = LinkedHashMap()
            rowDepth = depth
            lineNames.clear()
            lineUrls.clear()
        } else if (row != null && capture == null && depth == rowDepth + 1 && tag in XML_CATALOG_FIELDS) {
            capture = Capture(tag, depth)
            if (tag == "type") attributes.getValue("id")?.let { row?.put("type_id", JsonPrimitive(it)) }
        } else if (row != null && capture == null && tag == "dd" && depth == rowDepth + 2 && stack[depth - 2] == "dl") {
            capture = Capture(tag, depth, attributes.getValue("flag").orEmpty())
        } else if (row == null && capture == null && tag in setOf("code", "msg", "message", "success", "error", "status") &&
            (depth == 2 || listOpen && depth == listDepth + 1)) {
            capture = Capture(tag, depth)
        } else if (listOpen && row == null && depth == listDepth + 1) {
            invalidCatalogPayload()
        }
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        capture?.text?.let {
            if (it.length + length > MAX_CATALOG_PAYLOAD_BYTES) invalidCatalogPayload()
            it.append(chars, start, length)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val depth = stack.size
        val tag = stack.lastOrNull() ?: invalidCatalogPayload()
        capture?.takeIf { it.depth == depth }?.let { field ->
            val value = field.text.toString().trim()
            when {
                field.tag == "dd" -> if (value.isNotEmpty()) {
                    lineNames.add(field.flag?.takeIf(String::isNotBlank) ?: "线路${lineNames.size + 1}")
                    lineUrls.add(value)
                }
                row != null -> row?.put(XML_CATALOG_FIELDS.getValue(field.tag), JsonPrimitive(value))
                field.tag == "success" -> rootFields[field.tag] = when (value.lowercase(Locale.ROOT)) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> JsonPrimitive(value)
                }
                else -> rootFields[field.tag] = JsonPrimitive(value)
            }
            capture = null
        }
        if (row != null && tag == "video" && depth == rowDepth) {
            val finished = requireNotNull(row)
            if (lineUrls.isNotEmpty()) {
                finished["vod_play_from"] = JsonPrimitive(lineNames.joinToString("$$$"))
                finished["vod_play_url"] = JsonPrimitive(lineUrls.joinToString("$$$"))
            }
            if ((finished["vod_id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() ||
                (finished["vod_name"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) invalidCatalogPayload()
            rows.add(JsonObject(finished))
            row = null
            rowDepth = -1
        }
        if (tag == "list" && depth == listDepth) listOpen = false
        stack.removeAt(stack.lastIndex)
    }

    override fun skippedEntity(name: String?) = invalidCatalogPayload()
    override fun error(error: SAXParseException?) = invalidCatalogPayload()
    override fun fatalError(error: SAXParseException?) = invalidCatalogPayload()

    fun payload(): JsonObject {
        if (row != null || listOpen || stack.isNotEmpty()) invalidCatalogPayload()
        if (listSeen) rootFields["list"] = JsonArray(rows)
        return JsonObject(rootFields)
    }

    companion object {
        private val XML_CATALOG_FIELDS = mapOf(
            "id" to "vod_id", "name" to "vod_name", "year" to "vod_year", "type" to "type_name",
            "pic" to "vod_pic", "note" to "vod_remarks", "des" to "vod_content", "lang" to "vod_lang",
            "area" to "vod_area", "actor" to "vod_actor", "director" to "vod_director", "last" to "vod_time",
            "state" to "vod_serial", "time" to "vod_duration",
            "vod_id" to "vod_id", "vod_name" to "vod_name", "vod_year" to "vod_year",
            "vod_play_from" to "vod_play_from", "vod_play_url" to "vod_play_url",
            "vod_remarks" to "vod_remarks", "vod_duration" to "vod_duration", "type_name" to "type_name",
        )
    }
}

private fun invalidCatalogPayload(): Nothing = throw MovieCatalogPayloadException(MovieCatalogPayloadFailure.INVALID_FORMAT)
