package com.constx.onvyra.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import com.constx.onvyra.data.model.Avatar

// Read old backup/database avatar discriminators while all new data uses stable,
// brand-independent serial names. This does not rewrite user prompts or file URLs.
private val archiveCompatibility = SerializersModule {
    polymorphic(Avatar::class) {
        defaultDeserializer { name ->
            val suffix = listOf("me.rerere.rikkahub.data.model.Avatar.", "com.constx.onvyra.data.model.Avatar.")
                .firstOrNull { name?.startsWith(it) == true }?.let { name!!.removePrefix(it) }
            when (suffix) {
                "Dummy" -> Avatar.Dummy.serializer()
                "Emoji" -> Avatar.Emoji.serializer()
                "Image" -> Avatar.Image.serializer()
                else -> null
            }
        }
    }
}

val JsonInstant by lazy {
    Json {
        serializersModule = archiveCompatibility
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

val JsonInstantPretty by lazy {
    Json {
        serializersModule = archiveCompatibility
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
