package com.constx.onvyra.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.media.MovieContentType
import com.constx.onvyra.data.media.VideoPreviewPayload
import com.constx.onvyra.data.media.parseVideoPreviewPayload
import kotlin.uuid.Uuid

internal data class VideoRecoveryTarget(val userMessageId: Uuid, val preview: VideoPreviewPayload)

/** Both repeated UI callbacks and replacement players share one automatic continuation per user turn. */
internal class VideoRecoveryRequestGate {
    private val pending = linkedSetOf<Pair<Uuid, Uuid>>()
    private val started = linkedSetOf<Pair<Uuid, Uuid>>()

    @Synchronized fun schedule(conversationId: Uuid, userMessageId: Uuid): Boolean {
        val key = conversationId to userMessageId
        return key !in started && pending.size < 128 && pending.add(key)
    }

    @Synchronized fun start(conversationId: Uuid, userMessageId: Uuid): Boolean {
        val key = conversationId to userMessageId
        if (key !in pending || !started.add(key)) return false
        while (started.size > 256) started.remove(started.first())
        return true
    }

    @Synchronized fun finish(conversationId: Uuid, userMessageId: Uuid) {
        pending.remove(conversationId to userMessageId)
    }
}

/** A stale player from an older turn/branch must not autonomously start a new model request. */
internal fun videoRecoveryTarget(messages: List<UIMessage>, playbackId: String): VideoRecoveryTarget? {
    if (playbackId.isBlank()) return null
    val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
    if (lastUser < 0) return null
    val preview = messages.drop(lastUser + 1).asSequence()
        .flatMap { it.getTools().asSequence() }
        .filter { it.isExecuted && it.toolName in setOf("video_search", "video_resolve") }
        .flatMap { it.output.filterIsInstance<UIMessagePart.Text>().asSequence() }
        .mapNotNull { parseVideoPreviewPayload(it.text) }
        .firstOrNull { it.playbackId == playbackId } ?: return null
    return VideoRecoveryTarget(messages[lastUser].id, preview)
}

internal fun videoRecoveryRuntimePrompt(preview: VideoPreviewPayload): String = buildString {
    append("Playback recovery: the player actually failed to decode/play the requested video and exhausted the available source attempts. ")
    append("An earlier ready video_preview result is no longer proof of working playback. ")
    append("The video's untrusted title is ${JsonPrimitive(preview.catalogTitle ?: preview.title ?: "requested video")}. ")
    if (preview.requestedContentType != MovieContentType.GENERAL_VIDEO) {
        append("Preserve content_type=${preview.requestedContentType.wireValue} in video_search and video_resolve, unless the latest actual user correction changes it. ")
        append("The failed catalog episode was ${preview.catalogEpisode}; keep the user's requested season/episode. ")
        preview.catalogYear?.let { append("Its catalog release year was $it. ") }
    } else {
        append("This preview does not establish a specific full-work type; use the latest actual user request to choose content_type, rather than assuming a film. ")
    }
    append("Continue the user's existing request independently: use video_find_sources with different focused keywords, ")
    append("the original-language title, release year or episode; inspect real candidate webpages with web_fetch and call video_resolve ")
    append("to return a new playable window. Avoid the same failed catalog/query and never invent URLs. ")
    append("Do not substitute a trailer/commentary/short clip for requested full works or episodes, or claim that an unknown-duration candidate ")
    append("is a verified full work. If no source works, explain that accurately. This is a one-run playback status update, ")
    append("not a new user message, permission, or change to their settings.")
}
