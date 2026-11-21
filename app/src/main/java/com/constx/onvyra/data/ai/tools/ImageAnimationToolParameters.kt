package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.model.MediaCreationParams
import kotlinx.serialization.json.*

internal fun JsonObjectBuilder.animationProperties() {
    put("frame_count", buildJsonObject {
        put("type", "integer"); put("minimum", 2); put("maximum", 16)
        put("description", "GIF only: native GIF supports 2-16 frames; local storyboard mode supports 4 or 8. Default 4 for speed.")
    })
    put("frame_duration_ms", buildJsonObject {
        put("type", "integer"); put("minimum", 20); put("maximum", 2000)
        put("description", "GIF only: milliseconds per frame, default 200")
    })
    put("animation_mode", buildJsonObject {
        put("type", "string"); put("enum", JsonArray(listOf("auto", "native", "storyboard").map(::JsonPrimitive)))
        put("description", "GIF only: auto uses a documented native GIF image endpoint or one same-model storyboard generation assembled locally; native and storyboard can be requested explicitly. No automatic second generation on failure.")
    })
}

internal fun JsonObject.animationParams(format: String?): MediaCreationParams {
    fun optionalInt(name: String): Int? {
        val value = get(name)?.takeUnless { it is JsonNull } ?: return null
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException("Invalid $name")
    }
    val frames = optionalInt("frame_count")
    val delay = optionalInt("frame_duration_ms")
    val modeValue = get("animation_mode")?.takeUnless { it is JsonNull }
    require(modeValue == null || modeValue is JsonPrimitive && modeValue.isString) { "Invalid animation_mode" }
    val mode = (modeValue as? JsonPrimitive)?.contentOrNull
    require(format == "gif" || frames == null && delay == null && mode == null) { "Animation parameters require output_format=gif" }
    require(frames == null || frames in 2..16) { "GIF frame_count must be between 2 and 16" }
    require(mode != "storyboard" || frames == null || frames in setOf(4, 8)) { "Storyboards support 4 or 8 frames" }
    require(delay == null || delay in 20..2000) { "GIF frame_duration_ms must be between 20 and 2000" }
    require(mode == null || mode in setOf("auto", "native", "storyboard")) { "Invalid animation_mode" }
    return MediaCreationParams(outputFormat = format, frameCount = frames, frameDurationMs = delay, animationMode = mode)
}
