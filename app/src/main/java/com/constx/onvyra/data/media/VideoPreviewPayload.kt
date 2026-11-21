package com.constx.onvyra.data.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class VideoPreviewPayload(
    val originalUrl: String,
    val title: String?,
    val playbackId: String,
    val catalogSourceId: String? = null,
    val catalogItemId: String? = null,
    val catalogEpisode: Int = 1,
    val catalogLine: String? = null,
    val catalogTitle: String? = null,
    val catalogYear: Int? = null,
    val requestedContentType: MovieContentType = MovieContentType.GENERAL_VIDEO,
)

/** Read only the documented result of our video tool, never arbitrary Markdown/JSON. */
internal fun parseVideoPreviewPayload(text: String): VideoPreviewPayload? = runCatching {
    if (text.length > 16_000) return null
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
    fun field(name: String) = (root[name] as? JsonPrimitive)?.contentOrNull
    if (field("type") != "video_preview" || field("status") != "ready" || field("playable") != "true") return null
    val url = field("original_url")?.toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty()
    } ?: return null
    val id = field("playback_id")?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,80}")) } ?: return null
    val sourceId = field("catalog_source_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
    val itemId = field("catalog_item_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
    if (sourceId == null && itemId != null || sourceId != null && itemId == null) return null
    val episode = (root["catalog_episode"] as? JsonPrimitive)?.intOrNull ?: 1
    if (episode < 1) return null
    val movieTitle = field("catalog_title")?.take(200)
    val movieYear = (root["year"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..9999 }
    VideoPreviewPayload(url.toString(), field("title")?.take(512), id, sourceId, itemId, episode,
        field("catalog_line")?.take(128), movieTitle, movieYear,
        field("requested_content_type")?.let { MovieContentType.fromWire(it) } ?: MovieContentType.GENERAL_VIDEO)
}.getOrNull()
