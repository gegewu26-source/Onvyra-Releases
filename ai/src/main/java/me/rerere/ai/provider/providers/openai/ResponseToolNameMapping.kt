package me.rerere.ai.provider.providers.openai

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessagePart

/** Request-scoped wire names. Stored messages and local execution names stay untouched. */
internal class ResponseToolNameMapping private constructor(
    private val originalToWire: Map<String, String>,
) {
    private val wireToOriginal = originalToWire.entries.associate { (original, wire) -> wire to original }

    fun wireName(original: String): String = originalToWire[original] ?: original

    fun originalName(wire: String): String = wireToOriginal[wire] ?: wire

    fun isEncodedNamePrefix(prefix: String): Boolean = originalToWire.any { (original, wire) ->
        original != wire && wire.startsWith(prefix)
    }

    fun restore(result: TextGenerationResult): TextGenerationResult = result.copy(
        message = result.message.copy(parts = result.message.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(toolName = originalName(part.toolName)) else part
        }),
    )

    // Responses emits the complete name on output_item.added, before argument deltas.
    // Do not rewrite argument text, server tools, or arbitrary strings in other events.
    fun restore(chunk: StreamChunk): StreamChunk = when (chunk) {
        is StreamChunk.ToolCallStart -> chunk.copy(toolName = originalName(chunk.toolName))
        else -> chunk
    }

    fun rewrite(body: JsonObject): JsonObject = JsonObject(body.toMutableMap().apply {
        (body["input"] as? JsonArray)?.let { input ->
            put("input", JsonArray(input.map { value ->
                val item = value as? JsonObject
                if (item?.string("type") == "function_call") rewriteName(item) else value
            }))
        }
        (body["tools"] as? JsonArray)?.let { tools ->
            put("tools", JsonArray(tools.map { value ->
                val tool = value as? JsonObject
                if (tool?.string("type") == "function") rewriteName(tool) else value
            }))
        }
        (body["tool_choice"] as? JsonObject)?.let { choice ->
            put("tool_choice", when (choice.string("type")) {
                "function" -> rewriteName(choice)
                "allowed_tools" -> JsonObject(choice.toMutableMap().apply {
                    (choice["tools"] as? JsonArray)?.let { tools ->
                        put("tools", JsonArray(tools.map { value ->
                            val tool = value as? JsonObject
                            if (tool?.string("type") == "function") rewriteName(tool) else value
                        }))
                    }
                })
                else -> choice
            })
        }
    })

    private fun rewriteName(item: JsonObject): JsonObject {
        val name = item.string("name") ?: return item
        val wire = wireName(name)
        return if (wire == name) item else JsonObject(item.toMutableMap().apply {
            put("name", JsonPrimitive(wire))
        })
    }

    companion object {
        private const val MAX_NAME_LENGTH = 128

        fun forRequest(body: JsonObject): ResponseToolNameMapping {
            val names = buildList {
                (body["input"] as? JsonArray).orEmpty().forEach { value ->
                    val item = value as? JsonObject
                    if (item?.string("type") == "function_call") item.string("name")?.let(::add)
                }
                (body["tools"] as? JsonArray).orEmpty().forEach { value ->
                    val tool = value as? JsonObject
                    if (tool?.string("type") == "function") tool.string("name")?.let(::add)
                }
                (body["tool_choice"] as? JsonObject)?.let { choice ->
                    if (choice.string("type") == "function") choice.string("name")?.let(::add)
                    if (choice.string("type") == "allowed_tools") {
                        (choice["tools"] as? JsonArray).orEmpty().forEach { value ->
                            val tool = value as? JsonObject
                            if (tool?.string("type") == "function") tool.string("name")?.let(::add)
                        }
                    }
                }
            }
            return forNames(names)
        }

        internal fun forNames(
            names: Iterable<String>,
            maxNameLength: Int = MAX_NAME_LENGTH,
            allowDots: Boolean = true,
        ): ResponseToolNameMapping {
            require(maxNameLength in 40..MAX_NAME_LENGTH)
            val unique = names.toSortedSet()
            val reserved = unique.filterTo(mutableSetOf()) { isValid(it, maxNameLength, allowDots) }
            val result = linkedMapOf<String, String>()
            unique.filter { isValid(it, maxNameLength, allowDots) }.forEach { result[it] = it }
            unique.filterNot { isValid(it, maxNameLength, allowDots) }.forEach { original ->
                val stem = original.map { if (it.isAllowed(allowDots)) it else '_' }.joinToString("")
                    .trim('_').take(minOf(40, maxNameLength - 40)).ifEmpty { "tool" }
                val digest = MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val base = "onvyra_${stem}_${digest.take(32)}".take(maxNameLength)
                var candidate = base
                var collision = 0
                while (!reserved.add(candidate)) {
                    collision += 1
                    val suffix = "_$collision"
                    candidate = "onvyra_${digest.take(maxNameLength - 7 - suffix.length)}$suffix"
                }
                result[original] = candidate
            }
            return ResponseToolNameMapping(result)
        }

        private fun isValid(name: String, maxNameLength: Int, allowDots: Boolean) =
            name.isNotEmpty() && name.length <= maxNameLength && name.all { it.isAllowed(allowDots) }

        private fun Char.isAllowed(allowDots: Boolean) = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
            this == '_' || this == '-' || allowDots && this == '.'

        private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

internal data class PreparedResponseRequest(
    val body: JsonObject,
    val toolNames: ResponseToolNameMapping,
)
