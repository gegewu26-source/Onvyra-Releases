package com.constx.onvyra.ui.pages.novel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal enum class ReaderBookTool { SOURCE, CATALOG, DOWNLOAD, SEARCH, BOOKMARKS }

internal fun readerSourceName(book: NovelBook, imported: List<ImportedReaderSource>) =
    if (book.sourceId == NovelSourceEngine.SOURCE_CATALOG) "正在匹配本书来源" else imported.firstOrNull { it.id == book.sourceId }?.name
        ?: NovelSourceEngine.AVAILABLE_SOURCES.firstOrNull { it.id == book.sourceId }?.name
        ?: NovelSourceEngine.AVAILABLE_COMIC_SOURCES.firstOrNull { it.id == book.sourceId }?.name ?: "当前来源"

@Composable
internal fun NovelBookDetailPage(vm: NovelViewModel, onBack: () -> Unit, onRead: () -> Unit,
    onTool: (ReaderBookTool) -> Unit, onChapter: (Int) -> Unit) {
    val book by vm.currentBook.collectAsStateWithLifecycle()
    val chapters by vm.catalog.collectAsStateWithLifecycle()
    val loading by vm.isLoadingCatalog.collectAsStateWithLifecycle()
    val error by vm.errorMessage.collectAsStateWithLifecycle()
    val shelf by vm.bookshelf.collectAsStateWithLifecycle()
    val comics by vm.comicBookshelf.collectAsStateWithLifecycle()
    val imported by vm.importedSources.collectAsStateWithLifecycle()
    val downloads by vm.library.downloads.collectAsStateWithLifecycle()
    val b = book ?: return
    val collected = (if (b.isComic) comics else shelf).any { ReaderBookIdentity.matches(it, b) || it.id == b.id }
    val cached = downloads[vm.library.downloadKey(b)]
    var expanded by remember(b.id) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFFF7F7FA)).statusBarsPadding().navigationBarsPadding().testTag("reader-book-detail")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 16.sp) }
            Text("作品详情", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onTool(ReaderBookTool.SOURCE) }) { Text("换源") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFFFFE8EC), Color(0xFFF7F7FA))))
                .padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                NovelNetworkImage(b.coverUrl, b.coverReferer, "${b.title}封面",
                    Modifier.width(100.dp).height(140.dp).clip(RoundedCornerShape(12.dp)),
                    requestHeaders = b.artworkHeaders, coverBook = b, coverResolver = vm.coverResolver)
                Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(b.title, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 30.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(b.author.ifBlank { "作者待补全" }, fontSize = 14.sp, color = Color(0xFF777681))
                    Text(listOf(b.category, b.status).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = Color(0xFF777681))
                    Text(if (loading) "目录加载中 · 已载 ${chapters.size} 章" else "共 ${chapters.size.takeIf { it > 0 } ?: b.chapterCount} 章 · 读到第 ${b.lastReadChapterIndex + 1} 章",
                        fontSize = 12.sp, color = Color(0xFF777681))
                    Surface(color = Color.White.copy(alpha = .75f), shape = RoundedCornerShape(50)) {
                        Text(readerSourceName(b, imported), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onTool(ReaderBookTool.SOURCE) }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.White) {
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(Triple("↓", if (cached?.running == true) "缓存中" else "缓存", ReaderBookTool.DOWNLOAD),
                        Triple("⇄", "换源", ReaderBookTool.SOURCE), Triple("⌕", "正文搜索", ReaderBookTool.SEARCH),
                        Triple("☆", "书签", ReaderBookTool.BOOKMARKS)).forEach { (glyph, label, tool) ->
                        Column(Modifier.weight(1f).clickable { onTool(tool) }.testTag("detail-tool-${tool.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFFFF0F2)) {
                                Text(glyph, fontSize = 26.sp, color = NovelRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            }
                            Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                }
            }
            if (error != null) Column(Modifier.padding(20.dp)) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                TextButton(onClick = vm::refreshCatalog) { Text("重新加载详情与目录") }
            }
            DetailCard("目录", "${chapters.size} 章 ›", onClick = { onTool(ReaderBookTool.CATALOG) }) {
                if (chapters.isEmpty()) Text(if (loading) "正在获取目录…" else "目录尚未就绪", color = Color.Gray, fontSize = 13.sp)
                chapters.takeLast(3).asReversed().forEachIndexed { index, chapter ->
                    Row(Modifier.fillMaxWidth().clickable { onChapter(chapter.index) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (index == 0) "最新" else "·", color = if (index == 0) NovelRed else Color.Gray, fontSize = 11.sp,
                            modifier = Modifier.width(34.dp))
                        Text(chapter.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            DetailCard("简介", if (expanded) "收起" else "展开", onClick = { expanded = !expanded }) {
                Text(b.description.ifBlank { "当前来源暂无简介，可查看目录或切换这本书的其他来源。" },
                    fontSize = 15.sp, lineHeight = 26.sp, maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis, color = Color(0xFF55515A))
            }
            if (cached != null) Text(cached.message, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Spacer(Modifier.height(18.dp))
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { vm.toggleBookshelf(b) }, modifier = Modifier.height(48.dp)) { Text(if (collected) "已在书架" else "加入书架") }
            Button(onClick = onRead, enabled = chapters.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp).testTag("detail-start-reading")) {
                Text(if (b.lastReadChapterUrl.isBlank()) "开始阅读" else "继续阅读")
            }
        }
    }
}

@Composable private fun DetailCard(title: String, action: String, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(action, fontSize = 12.sp, color = NovelRed, modifier = Modifier.clickable(onClick = onClick).padding(4.dp))
            }
            content()
        }
    }
}

@Composable
internal fun ReaderBookToolDialog(tool: ReaderBookTool, vm: NovelViewModel, onDismiss: () -> Unit,
    onJump: (NovelChapter, Int, Int, String) -> Unit) {
    val book by vm.currentBook.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val complete by vm.isCatalogComplete.collectAsStateWithLifecycle()
    val imported by vm.importedSources.collectAsStateWithLifecycle()
    val discovering by vm.isDiscoveringBookSources.collectAsStateWithLifecycle()
    val sourceStatus by vm.bookSourceStatus.collectAsStateWithLifecycle()
    val sourceAvailability by vm.bookSourceAvailability.collectAsStateWithLifecycle()
    val switching by vm.isSwitchingSource.collectAsStateWithLifecycle()
    val downloads by vm.library.downloads.collectAsStateWithLifecycle()
    val marks by vm.library.bookmarks.collectAsStateWithLifecycle()
    val search by vm.library.searchState.collectAsStateWithLifecycle()
    val notice by vm.readerNotice.collectAsStateWithLifecycle()
    val b = book ?: return
    var query by remember(tool, b.id) { mutableStateOf("") }
    LaunchedEffect(tool) { if (tool == ReaderBookTool.SOURCE) vm.refreshBookSources() }
    if (tool == ReaderBookTool.SOURCE) {
        NovelBookSourcePicker(b, imported, discovering, sourceStatus, switching, onDismiss,
            onSelect = { vm.switchSource(it); onDismiss() }, onRefresh = { vm.refreshBookSources(force = true) },
            availability = sourceAvailability, onContinue = { vm.refreshBookSources() })
        return
    }
    val title = when (tool) { ReaderBookTool.CATALOG -> "完整目录"; ReaderBookTool.DOWNLOAD -> "离线缓存"; ReaderBookTool.SEARCH -> "搜索本书正文"; else -> "我的书签" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title, fontSize = 18.sp) }, text = {
        Column(Modifier.fillMaxWidth().testTag("reader-tool-${tool.name}")) {
            if (tool == ReaderBookTool.SEARCH || tool == ReaderBookTool.CATALOG) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text(if (tool == ReaderBookTool.SEARCH) "输入正文关键词，例如：少年" else "搜索章节标题") },
                    modifier = Modifier.fillMaxWidth().testTag("reader-tool-query"))
            }
            if (tool == ReaderBookTool.SEARCH) {
                if (b.isComic || b.isAudio) Text("正文搜索支持文字小说。", modifier = Modifier.padding(vertical = 16.dp))
                else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.searchBookText(query) }, enabled = query.isNotBlank() && catalog.isNotEmpty(),
                            modifier = Modifier.testTag("reader-search-submit")) { Text("搜索") }
                        if (search.running) TextButton(onClick = vm.library::cancelSearch) { Text("停止") }
                    }
                    if (search.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("已检索 ${search.completed}/${search.total} 章 · ${search.hits.size} 处匹配" + if (search.truncated) "（显示前 2000 处）" else "",
                        fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                    if (search.message.isNotBlank()) Text(search.message, fontSize = 11.sp)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(search.hits, key = { "${it.chapter.url}:${it.paragraphIndex}:${it.matchOffset}" }) { hit ->
                            Column(Modifier.fillMaxWidth().clickable { onJump(hit.chapter, hit.paragraphIndex, hit.matchOffset, search.query) }
                                .testTag("reader-search-hit-${hit.chapter.index}-${hit.paragraphIndex}-${hit.matchOffset}").padding(vertical = 10.dp)) {
                                Text(hit.chapter.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(hit.excerpt, fontSize = 12.sp, maxLines = 3, color = Color.Gray)
                            }
                            HorizontalDivider(color = Color(0xFFEDEDF2))
                        }
                    }
                }
            } else if (tool == ReaderBookTool.DOWNLOAD) {
                val state = downloads[vm.library.downloadKey(b)]
                if (b.isComic || b.isAudio) Text("离线正文缓存支持文字小说。")
                else {
                    Text("缓存当前来源的完整目录和正文，完成后可断网阅读。", fontSize = 13.sp, lineHeight = 21.sp)
                    if (!complete) Text("完整目录尚未加载，请稍后重试。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Text(state?.message ?: "共 ${catalog.size} 章，尚未缓存全书", modifier = Modifier.padding(vertical = 14.dp), fontSize = 13.sp)
                    if (state?.running == true) {
                        LinearProgressIndicator(progress = { state.completed.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { vm.library.cancelDownload(b) }) { Text("暂停缓存") }
                    } else Button(onClick = vm::downloadCurrentBook, enabled = complete && catalog.isNotEmpty(), modifier = Modifier.testTag("reader-download-start")) {
                        Text(if ((state?.completed ?: 0) > 0) "继续 / 重试缺章" else "缓存全书")
                    }
                }
            } else if (tool == ReaderBookTool.CATALOG) {
                Text("共 ${catalog.size} 章" + if (!complete) " · 仍在加载" else "", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(catalog.filter { query.isBlank() || it.title.contains(query, true) }, key = { it.url }) { chapter ->
                        Text(chapter.title, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable { onJump(chapter, 0, 0, "") }.padding(vertical = 12.dp))
                    }
                }
            } else {
                TextButton(onClick = vm::addCurrentBookmark, enabled = catalog.isNotEmpty()) { Text("＋ 添加当前位置书签") }
                if (notice != null) Text(notice.orEmpty(), color = NovelRed, fontSize = 12.sp)
                val visible = marks.filter { it.bookKey == ReaderBookIdentity.key(b) }
                if (visible.isEmpty()) Text("尚无书签。阅读到想保留的位置时可添加。", color = Color.Gray, fontSize = 13.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(visible, key = { it.id }) { mark ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onJump(mark.chapter, mark.paragraphIndex, mark.charOffset, "") }
                                .testTag("reader-bookmark-${mark.id}").padding(vertical = 12.dp)) {
                                Text(mark.chapter.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(mark.quote.ifBlank { "第 ${mark.chapter.index + 1} 章" }, maxLines = 2, fontSize = 12.sp, color = Color.Gray)
                            }
                            TextButton(onClick = { vm.library.removeBookmark(mark.id) }) { Text("删除", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
