package com.constx.onvyra.ui.pages.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

private data class ContinuousRow(val key: String, val chapter: Int, val kind: Int,
    val text: String = "", val error: String? = null, val loading: Boolean = false)
private data class ContinuousViewport(val first: Int, val last: Int, val firstKey: String, val firstReady: Boolean,
    val offset: Int = 0)

/** One stable lazy list holds adjacent chapter bodies. No edge gesture replaces its contents. */
@Composable
internal fun ContinuousNovelReader(
    sections: List<ContinuousReaderSection>, anchor: ContinuousScrollAnchor,
    settings: NovelReaderSettings, ink: Color, controls: Boolean,
    onTap: () -> Unit, onViewport: (Int, Int) -> Unit, onRetry: (Int) -> Unit,
    atDocumentEnd: Boolean = true,
    onPosition: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
) {
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp, color = ink)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val horizontalPadding = with(density) { 44.dp.roundToPx() }
    val rows = remember(sections) {
        buildList {
            sections.forEach { section ->
                val chapter = section.chapter
                add(ContinuousRow("${chapter.url}:heading", chapter.index, 0, chapter.title))
                if (section.ready) {
                    section.paragraphs.forEachIndexed { index, paragraph ->
                        add(ContinuousRow("${chapter.url}:paragraph:$index", chapter.index, 1, paragraph))
                    }
                    add(ContinuousRow("${chapter.url}:space", chapter.index, 3))
                } else add(ContinuousRow("${chapter.url}:status", chapter.index, 2,
                    error = section.error, loading = section.loading))
            }
        }
    }
    val currentRows by rememberUpdatedState(rows)
    val rowByKey by rememberUpdatedState(remember(rows) { rows.associateBy { it.key } })
    val viewportAction by rememberUpdatedState(onViewport)
    val positionAction by rememberUpdatedState(onPosition)
    val readyChapters by rememberUpdatedState(sections.filter { it.ready }.map { it.chapter.index }.toSet())
    val firstRow = rows.indexOfFirst { it.chapter == anchor.chapterIndex }.coerceAtLeast(0)
    val scroll = remember(anchor.revision) { LazyListState(firstVisibleItemIndex = firstRow) }
    var anchorApplied by remember(anchor.revision) { mutableStateOf(false) }
    // At index zero LazyColumn can expose a newly prepended placeholder before
    // its keyed position reconciliation. Preserve the measured row and pixel
    // offset before that measure, rather than feeding the temporary row back to
    // ViewModel and recursively requesting chapter N-1, N-2, N-3 while idle.
    val measuredFirst = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == scroll.firstVisibleItemIndex }
    val retainedIndex = measuredFirst?.let { item -> rows.indexOfFirst { it.key == item.key } } ?: -1
    SideEffect {
        if (anchorApplied && measuredFirst != null && retainedIndex >= 0 &&
            retainedIndex != measuredFirst.index && !scroll.isScrollInProgress) {
            scroll.requestScrollToItem(retainedIndex, scroll.firstVisibleItemScrollOffset)
        }
    }
    val targetReady = sections.any { it.chapter.index == anchor.chapterIndex && it.ready }
    LaunchedEffect(anchor.revision, targetReady) {
        // This happens once for an explicit open/directory/button action. Loading
        // and prepending neighbouring sections never enters this branch again.
        if (!anchorApplied && targetReady) {
            val index = if (anchor.atEnd) currentRows.indexOfLast { it.chapter == anchor.chapterIndex && it.kind == 1 }
                else if (anchor.paragraphIndex != null) currentRows.indexOfFirst {
                    it.chapter == anchor.chapterIndex && it.kind == 1 && it.key.substringAfterLast(':').toIntOrNull() == anchor.paragraphIndex }
                    .takeIf { it >= 0 } ?: currentRows.indexOfFirst { it.chapter == anchor.chapterIndex }
                else currentRows.indexOfFirst { it.chapter == anchor.chapterIndex }
            if (index >= 0) {
                val row = currentRows[index]
                val offset = if (row.kind == 1 && anchor.charOffset > 0) {
                    val layout = measurer.measure(AnnotatedString(row.text), style = style,
                        constraints = Constraints(maxWidth = (scroll.layoutInfo.viewportSize.width - horizontalPadding).coerceAtLeast(1)))
                    layout.getLineTop(layout.getLineForOffset(anchor.charOffset.coerceIn(0, row.text.length))).toInt()
                } else 0
                scroll.scrollToItem(index, offset); anchorApplied = true
            }
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow {
            val visible = scroll.layoutInfo.visibleItemsInfo.filter {
                it.offset + it.size > scroll.layoutInfo.viewportStartOffset.coerceAtLeast(0) + 4
            }.mapNotNull { info ->
                // Chapter separators are padding, never reading progress. A few
                // pixels of the previous separator can remain above the new heading.
                rowByKey[info.key]?.takeIf { it.kind != 3 }?.let { info.key.toString() to it.chapter }
            }
            visible.firstOrNull()?.let { first ->
                ContinuousViewport(first.second, visible.last().second, first.first, first.second in readyChapters,
                    scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == first.first }?.offset ?: 0)
            }
        }.distinctUntilChanged().collect { viewport ->
            if (viewport != null && anchorApplied && viewport.firstReady) {
                viewportAction(viewport.first, viewport.last)
                rowByKey[viewport.firstKey]?.let { row ->
                    if (row.kind == 1) {
                        val layout = measurer.measure(AnnotatedString(row.text), style = style,
                            constraints = Constraints(maxWidth = (scroll.layoutInfo.viewportSize.width - horizontalPadding).coerceAtLeast(1)))
                        val offset = layout.getLineStart(layout.getLineForVerticalPosition((-viewport.offset).coerceAtLeast(0).toFloat()))
                        positionAction(row.chapter, row.key.substringAfterLast(':').toIntOrNull() ?: 0, offset,
                            row.text.drop(offset).take(100))
                    } else if (row.kind == 0) positionAction(row.chapter, 0, 0, row.text)
                }
            }
        }
    }
    LaunchedEffect(scroll, settings.autoScrollSpeed, controls) {
        if (settings.autoScrollSpeed > 0f && !controls) while (isActive) {
            delay(40); scroll.scrollBy(settings.autoScrollSpeed * .04f)
        }
    }
    LazyColumn(state = scroll, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize().testTag("reader-body").clickable(indication = null,
            interactionSource = remember { MutableInteractionSource() }, onClick = onTap)) {
        items(rows, key = { it.key }, contentType = { it.kind }) { row ->
            when (row.kind) {
                0 -> Text(row.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = ink, lineHeight = 31.sp,
                    modifier = Modifier.fillMaxWidth().testTag("continuous-heading-${row.chapter}")
                        .padding(top = 24.dp, bottom = 20.dp))
                1 -> Text(buildAnnotatedString {
                        append(row.text.ifEmpty { " " })
                        if (row.chapter == anchor.chapterIndex && anchor.highlight.isNotBlank()) {
                            var start = row.text.indexOf(anchor.highlight, ignoreCase = true)
                            while (start >= 0) {
                                addStyle(SpanStyle(background = Color(0xFFFFDA79), color = Color(0xFF322700)),
                                    start, start + anchor.highlight.length)
                                start = row.text.indexOf(anchor.highlight, start + anchor.highlight.length, ignoreCase = true)
                            }
                        }
                    }, style = style,
                    modifier = Modifier.fillMaxWidth().testTag("continuous-paragraph-${row.chapter}-${row.key.substringAfterLast(':')}"))
                2 -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (row.error != null) {
                        Text(row.error, color = ink.copy(alpha = .65f), fontSize = 13.sp)
                        TextButton(onClick = { onRetry(row.chapter) }) { Text("重新加载这一章", color = ink) }
                    } else {
                        CircularProgressIndicator(Modifier.size(20.dp), color = ink.copy(alpha = .5f), strokeWidth = 2.dp)
                        Text("正在加载后续正文…", color = ink.copy(alpha = .5f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
                else -> Spacer(Modifier.height(22.dp))
            }
        }
        // Let an explicitly opened final/short chapter align at the top. Without
        // trailing space the list clamps to its end as preceding sections arrive,
        // which incorrectly shifts reading progress into an earlier chapter.
        // This tail follows the document; it never separates adjacent chapters.
        if (atDocumentEnd) item(key = "reader-document-tail", contentType = 4) {
            Spacer(Modifier.fillParentMaxHeight())
        }
    }
}
