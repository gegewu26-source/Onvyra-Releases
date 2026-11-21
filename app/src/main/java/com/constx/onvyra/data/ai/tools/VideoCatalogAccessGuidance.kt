package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.media.EXTERNAL_VIDEO_CATALOG_SOURCES

/** Read on every model request. An earlier conversation's access status is not current authority. */
internal fun videoCatalogAccessGuidance(authorized: Boolean): String {
    if (!authorized || EXTERNAL_VIDEO_CATALOG_SOURCES.isEmpty()) return ""
    val names = EXTERNAL_VIDEO_CATALOG_SOURCES.joinToString { it.name }
    return "The application currently authorizes these additional built-in video catalogs in this session: $names. " +
        "For an explicit query intended for one of these catalogs, including a catalog identifier/code, person name or tag, " +
        "call video_search first with the user's actual query instead of repeatedly starting ordinary web searches. " +
        "Use general_video for a catalog query that is not explicitly a film, series or anime request. " +
        "Omit year unless the user supplied or verified it; code/name/tag catalog results may have no release-year metadata. " +
        "This current runtime authorization supersedes older assistant statements or tool errors saying the catalogs were unavailable. " +
        "A ready result is rendered in this chat. Availability is not proof of a matching or playable result; use the actual tool result. " +
        "Do not repeat an identical completed query or revisit failed candidates without new information. " +
        "If the built-in, discovery and bounded webpage attempts make no progress, report the actual failure and stop."
}
