package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.security.MessageDigest

data class ImportedReaderSource(
    val id: String,
    val name: String,
    val url: String,
    /** Legado: 0=text, 1=audio, 2=comic. */
    val type: Int,
    val group: String,
    val enabled: Boolean,
    val rules: JsonObject,
)

/** One lazily parsed source library; no source requests run during application startup. */
class ReaderSourceLibrary(private val readJson: () -> String) {
    private val lock = Mutex()
    @Volatile private var loaded: List<ImportedReaderSource>? = null

    suspend fun all(): List<ImportedReaderSource> = withContext(Dispatchers.IO) {
        loaded ?: lock.withLock { loaded ?: decode(readJson()).also { loaded = it } }
    }

    suspend fun find(id: String): ImportedReaderSource? = all().firstOrNull { it.id == id }

    /** Disabled, unsearchable and other-media rules never enter aggregate requests. */
    suspend fun searchable(type: Int): List<ImportedReaderSource> = all().filter {
        it.enabled && it.type == type &&
            (it.rules["searchUrl"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true &&
            ((it.rules["ruleSearch"] as? JsonObject)?.get("bookList") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
    }

    companion object {
        const val ASSET_PATH = "reader/book-sources-489.json"
        fun decode(raw: String): List<ImportedReaderSource> {
            val rows = Json.parseToJsonElement(raw.removePrefix("\uFEFF")) as? JsonArray
                ?: throw IOException("书源文件必须是 JSON 数组")
            val duplicates = mutableMapOf<String, Int>()
            return rows.mapIndexed { index, element ->
                val rule = element as? JsonObject ?: throw IOException("第 ${index + 1} 条书源格式不正确")
                fun field(key: String) = (rule[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val name = field("bookSourceName").ifBlank { "未命名书源 ${index + 1}" }
                val url = field("bookSourceUrl")
                val type = field("bookSourceType").toIntOrNull() ?: 0
                val digest = MessageDigest.getInstance("SHA-256").digest(rule.toString().toByteArray(Charsets.UTF_8))
                    .take(12).joinToString("") { "%02x".format(it) }
                val occurrence = duplicates.merge(digest, 1, Int::plus) ?: 1
                ImportedReaderSource("legado_$digest" + if (occurrence > 1) "_$occurrence" else "",
                    name, url, type, field("bookSourceGroup"),
                    (rule["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true, rule)
            }
        }
    }
}
