package com.constx.onvyra.data.ai.prompts

import com.constx.onvyra.data.datastore.Settings
import me.rerere.mediagen.model.MediaKind

internal val DEFAULT_IMAGE_GENERATION_PROMPT = """
    Create the image described in the task below. Follow its requested subject, composition,
    style, colors, lighting, aspect ratio, and reference-image constraints. Render requested
    text accurately in its original language. Do not add unrequested text, logos, or watermarks.
    For edits, preserve the original subject and details outside the requested changes.
    If the requested output is an animated GIF, keep the subject and background consistent,
    use readable motion, and return naturally to the starting pose when a loop is requested.
    The task's explicit requirements take precedence over these general defaults.
""".trimIndent()

internal val DEFAULT_VIDEO_GENERATION_PROMPT = """
    Create the video described in the task below. Follow its requested subject, action,
    setting, visual style, camera movement, duration, aspect ratio, and reference materials.
    Keep characters, objects, lighting, and spatial relationships consistent across frames.
    Use coherent motion and stable transitions; avoid unintended flicker, sudden identity
    changes, or extra limbs. Do not add unrequested text, logos, subtitles, or watermarks.
    Preserve supplied reference details and make a natural loop when explicitly requested.
    The task's explicit requirements take precedence over these general defaults.
""".trimIndent()

/** Extra generation guidance never replaces or rewrites the user's actual task. */
internal fun Settings.mediaGenerationPromptFor(kind: MediaKind, userPrompt: String): String {
    val guidance = when (kind) {
        MediaKind.IMAGE -> imageGenerationPrompt
        MediaKind.VIDEO -> videoGenerationPrompt
    }.trim()
    return if (guidance.isEmpty()) userPrompt else "$guidance\n\n$userPrompt"
}
