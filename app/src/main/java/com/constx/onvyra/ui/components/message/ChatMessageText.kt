package com.constx.onvyra.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.constx.onvyra.data.media.extractVideoLinks
import com.constx.onvyra.ui.components.richtext.MarkdownBlock

/** Video previews belong only to visible chat text, not tool output or settings Markdown. */
@Composable
internal fun ChatMessageText(
    content: String,
    modifier: Modifier = Modifier,
    autoPreviewHtml: Boolean = false,
    previewVideos: Boolean = true,
    excludedVideoUrls: Set<String> = emptySet(),
    onClickCitation: (String) -> Unit = {},
) {
    val links = remember(content, previewVideos, excludedVideoUrls) {
        if (previewVideos) extractVideoLinks(content).filter { it.url !in excludedVideoUrls } else emptyList()
    }
    if (links.isEmpty()) {
        MarkdownBlock(content, modifier, autoPreviewHtml = autoPreviewHtml, onClickCitation = onClickCitation)
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownBlock(content, autoPreviewHtml = autoPreviewHtml, onClickCitation = onClickCitation)
            links.forEach { link ->
                key(link.embedUrl ?: link.url) { InlineVideoPreview(link) }
            }
        }
    }
}
