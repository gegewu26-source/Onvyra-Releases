package com.constx.onvyra.ui.pages.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderPage(viewModel: NovelViewModel, onShowDetails: (() -> Unit)? = null, onBackToShelf: () -> Unit) {
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val book by viewModel.currentBook.collectAsStateWithLifecycle()
    val comic = book?.isComic == true
    val ink = if (comic) Color(0xFFEAEAEA) else Color(settings.readerTextColor())
    val background = if (comic) Color(0xFF111111) else Color(settings.readerBackgroundColor())
    val colors = if (comic || settings.isReaderDark()) darkColorScheme(
        primary = if (comic) NovelRed else ink, onPrimary = background,
        surface = background, onSurface = ink, background = background, onBackground = ink,
        secondary = ink, secondaryContainer = ink.copy(alpha = 0.12f), onSecondaryContainer = ink,
        surfaceContainerHigh = background, surfaceContainerLow = background,
    ) else lightColorScheme(primary = ink, onPrimary = background,
        surface = background, onSurface = ink, background = background, onBackground = ink,
        secondary = ink, primaryContainer = ink.copy(alpha = 0.09f), onPrimaryContainer = ink,
        secondaryContainer = ink.copy(alpha = 0.09f), onSecondaryContainer = ink,
        surfaceContainerHigh = background, surfaceContainerLow = background)
    MaterialTheme(colorScheme = colors) { NovelReaderContent(viewModel, onBackToShelf, onShowDetails) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelReaderContent(viewModel: NovelViewModel, onBackToShelf: () -> Unit, onShowDetails: (() -> Unit)? = null) {
    val book by viewModel.currentBook.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val chapterIndex by viewModel.currentChapterIndex.collectAsStateWithLifecycle()
    val enterChapterAtEnd by viewModel.enterChapterAtEnd.collectAsStateWithLifecycle()
    val continuousSections by viewModel.continuousSections.collectAsStateWithLifecycle()
    val continuousAnchor by viewModel.continuousAnchor.collectAsStateWithLifecycle()
    val content by viewModel.chapterContent.collectAsStateWithLifecycle()
    val images by viewModel.comicPages.collectAsStateWithLifecycle()
    val loading by viewModel.isLoadingChapter.collectAsStateWithLifecycle()
    val loadingCatalog by viewModel.isLoadingCatalog.collectAsStateWithLifecycle()
    val completeCatalog by viewModel.isCatalogComplete.collectAsStateWithLifecycle()
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val error by viewModel.errorMessage.collectAsStateWithLifecycle()
    val importedSources by viewModel.importedSources.collectAsStateWithLifecycle()
    val discoveringSources by viewModel.isDiscoveringBookSources.collectAsStateWithLifecycle()
    val bookSourceStatus by viewModel.bookSourceStatus.collectAsStateWithLifecycle()
    val sourceAvailability by viewModel.bookSourceAvailability.collectAsStateWithLifecycle()
    val switchingSource by viewModel.isSwitchingSource.collectAsStateWithLifecycle()
    // Read the shelf state so a collection change updates this action immediately.
    val shelf by viewModel.bookshelf.collectAsStateWithLifecycle()
    val comicShelf by viewModel.comicBookshelf.collectAsStateWithLifecycle()
    val collected = if (book?.isComic == true) comicShelf.any { it.id == book?.id }
        else shelf.any { it.id == book?.id }
    var controls by remember { mutableStateOf(false) }
    var settingsSheet by remember { mutableStateOf(false) }
    var sourcePicker by remember { mutableStateOf(false) }
    var libraryTool by remember { mutableStateOf<ReaderBookTool?>(null) }
    var readerMore by remember { mutableStateOf(false) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var chapterSlider by remember(chapterIndex) { mutableFloatStateOf(chapterIndex.toFloat()) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val title = catalog.getOrNull(chapterIndex)?.title.orEmpty()
    ReaderSystemBars(controls || drawer.isOpen || settingsSheet || sourcePicker || libraryTool != null || readerMore)
    val isComic = book?.isComic == true
    val bg = if (isComic) Color(0xFF141416) else Color(settings.readerBackgroundColor())
    val ink = if (isComic) Color(0xFFEAEAEA) else Color(settings.readerTextColor())
    val continuous = !isComic && book?.isAudio != true && settings.isVerticalScroll
    ReaderBrightness(settings.brightness)
    BackHandler { onBackToShelf() }
    val toggleControls: () -> Unit = { controls = !controls }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.80f).widthIn(max = 360.dp).testTag("reader-catalog-drawer"), drawerContainerColor = bg) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
                Row(Modifier.padding(start = 14.dp, top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    NovelNetworkImage(book?.coverUrl.orEmpty(), book?.coverReferer.orEmpty(), book?.title.orEmpty(),
                        Modifier.width(44.dp).height(60.dp).clip(RoundedCornerShape(4.dp)), requestHeaders = book?.artworkHeaders.orEmpty(), coverBook = book)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(book?.title.orEmpty(), color = ink, fontWeight = FontWeight.Bold,
                            fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book?.author.orEmpty(), fontSize = 12.sp, color = ink.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { scope.launch { drawer.close() } }) { Icon(HugeIcons.Cancel01, "关闭目录") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = viewModel::refreshCatalog, enabled = !loadingCatalog) {
                        Text(if (loadingCatalog) "已载 ${catalog.size} 章，继续加载…"
                            else if (!completeCatalog) "已载 ${catalog.size} 章，目录加载未完成"
                            else "共 ${catalog.size} ${if (isComic) "话" else "章"}",
                            color = ink.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                    TextButton(onClick = { descending = !descending }) { Text(if (descending) "倒序 ↓" else "正序 ↑", color = ink) }
                }
                HorizontalDivider(color = ink.copy(alpha = 0.1f))
                val listState = rememberLazyListState()
                LaunchedEffect(drawer.isOpen, descending, chapterIndex, catalog.size) {
                    if (drawer.isOpen && catalog.isNotEmpty()) {
                        val position = if (descending) catalog.lastIndex - chapterIndex else chapterIndex
                        listState.scrollToItem((position - 3).coerceAtLeast(0))
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(catalog.size, key = { displayIndex ->
                        val actual = if (descending) catalog.lastIndex - displayIndex else displayIndex
                        "${actual}:${catalog[actual].url}"
                    }) { displayIndex ->
                        val actual = if (descending) catalog.lastIndex - displayIndex else displayIndex
                        val current = actual == chapterIndex
                        Text(catalog[actual].title, color = if (current) NovelRed else ink,
                            fontSize = 14.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().background(if (current) ink.copy(alpha = 0.06f) else Color.Transparent)
                                .clickable { viewModel.loadChapter(actual); scope.launch { drawer.close() }; controls = false }
                                .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp))
                        HorizontalDivider(color = ink.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }) {
        Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
            // Menus overlay a fixed reading viewport. Toggling them must never
            // resize/paginate the document or move its current paragraph.
            Box(Modifier.fillMaxSize().padding(top = if (!isComic && book?.isAudio != true) 24.dp else 0.dp)) {
            when {
                continuous && continuousSections.isNotEmpty() -> ContinuousNovelReader(
                    continuousSections, continuousAnchor, settings, ink, controls, toggleControls,
                    onViewport = viewModel::onContinuousViewport, onRetry = viewModel::retryContinuousChapter,
                    onPosition = viewModel::onReaderPosition,
                    atDocumentEnd = completeCatalog && catalog.lastOrNull()?.index?.let { last ->
                        continuousSections.any { it.chapter.index == last }
                    } == true)
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = NovelRed, modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                        Text("正在加载${if (isComic) "漫画" else "章节"}…", color = ink, modifier = Modifier.padding(top = 16.dp))
                    }
                }
                error != null && (if (isComic) images.isEmpty() else content.isBlank()) -> {
                    Column(Modifier.align(Alignment.Center).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error.orEmpty(), color = ink, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 20.dp)) {
                            OutlinedButton(onClick = viewModel::retryChapter) { Text("重新加载", color = ink) }
                            OutlinedButton(onClick = { sourcePicker = true }) { Text("换源", color = ink) }
                        }
                    }
                }
                isComic -> ComicChapterReader(images, catalog.getOrNull(chapterIndex)?.url.orEmpty(),
                    title, chapterIndex, settings, ink, controls, toggleControls,
                    canPrevious = chapterIndex > 0, canNext = chapterIndex < catalog.lastIndex,
                    onPrevious = viewModel::prevChapter, onNext = viewModel::nextChapter,
                    requestHeaders = book?.requestHeaders.orEmpty())
                book?.isAudio == true && content.isNotBlank() -> book?.let { audioBook ->
                    AudioChapterPlayer(content, title, audioBook.coverUrl,
                        audioBook.detailUrl.substringBefore(",{"), audioBook.requestHeaders, ink, coverBook = audioBook)
                }
                content.isNotBlank() -> NovelChapterReader(content, "", chapterIndex, settings, ink, controls, toggleControls,
                    canPrevious = chapterIndex > 0, canNext = chapterIndex < catalog.lastIndex,
                    onPrevious = viewModel::prevChapter, onNext = viewModel::nextChapter,
                    enterAtEnd = enterChapterAtEnd, textAnchor = continuousAnchor,
                    onPosition = viewModel::onReaderPosition)
                else -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂时没有可阅读内容", color = ink)
                    TextButton(onClick = viewModel::retryChapter) { Text("重新加载", color = NovelRed) }
                }
            }
            }
            if (!isComic && book?.isAudio != true) Text(title, color = ink.copy(alpha=.5f),fontSize=11.sp,
                maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().height(24.dp)
                    .clickable(onClick=toggleControls).padding(horizontal=22.dp,vertical=3.dp).testTag("reader-current-chapter"))
            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Surface(color = bg, shadowElevation = 2.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBackToShelf) { Icon(HugeIcons.ArrowLeft01, "返回书架", tint = ink) }
                            Text(book?.title.orEmpty(), color = ink, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { sourcePicker = true }) { Text("换源", color = NovelRed) }
                            if (onShowDetails != null) TextButton(onClick = onShowDetails) { Text("详情", color = NovelRed) }
                            Box {
                                TextButton(onClick = { readerMore = true }) { Text("⋯", fontSize = 24.sp, color = ink) }
                                DropdownMenu(expanded = readerMore, onDismissRequest = { readerMore = false }) {
                                    listOf("缓存全书" to ReaderBookTool.DOWNLOAD, "搜索正文" to ReaderBookTool.SEARCH, "我的书签" to ReaderBookTool.BOOKMARKS).forEach { (label, tool) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { readerMore = false; libraryTool = tool })
                                    }
                                    DropdownMenuItem(text = { Text("添加当前位置书签") }, onClick = {
                                        readerMore = false; viewModel.addCurrentBookmark(); libraryTool = ReaderBookTool.BOOKMARKS
                                    })
                                    DropdownMenuItem(text = { Text(if (collected) "移出书架" else "加入书架") }, onClick = { readerMore = false; book?.let(viewModel::toggleBookshelf) })
                                }
                            }
                        }
                        if (error != null) NovelErrorBanner(error.orEmpty(), viewModel::retryChapter, viewModel::clearError)
                    }
                }
            }
            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Surface(color = bg, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(title.ifBlank { "目录尚未加载" }, color = ink.copy(alpha = 0.5f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = viewModel::prevChapter, enabled = chapterIndex > 0 && !loading) { Text(if (isComic) "上一话" else "上一章") }
                            if (catalog.size > 1) Slider(value = chapterSlider.coerceIn(0f, catalog.lastIndex.toFloat()),
                                onValueChange = { chapterSlider = it }, onValueChangeFinished = { viewModel.loadChapter(chapterSlider.toInt()) },
                                valueRange = 0f..catalog.lastIndex.toFloat(), modifier = Modifier.weight(1f), enabled = !loading)
                            else Spacer(Modifier.weight(1f))
                            TextButton(onClick = viewModel::nextChapter, enabled = chapterIndex < catalog.lastIndex && !loading) { Text(if (isComic) "下一话" else "下一章") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            ReaderAction("目录", HugeIcons.Menu03, ink) { scope.launch { drawer.open() } }
                            if (isComic) {
                                ReaderAction(if (settings.autoScrollSpeed > 0f) "停止" else "自动", HugeIcons.Book01, ink) {
                                    viewModel.setAutoScrollSpeed(if (settings.autoScrollSpeed > 0f) 0f else 30f)
                                    if (settings.autoScrollSpeed <= 0f) controls = false
                                }
                                ReaderAction("亮度", HugeIcons.View, ink) { settingsSheet = true }
                            } else ReaderAction(if (settings.isReaderDark()) "日间" else "夜间", HugeIcons.View, ink) {
                                    viewModel.selectTheme(if (settings.isReaderDark()) NovelReaderTheme.PARCHMENT else NovelReaderTheme.NIGHT)
                                }
                            ReaderAction("设置", HugeIcons.Settings03, ink) { settingsSheet = true }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(sourcePicker) { if (sourcePicker) viewModel.refreshBookSources() }
    if (sourcePicker) book?.let { selectedBook -> NovelBookSourcePicker(selectedBook,
        importedSources, discoveringSources, bookSourceStatus, switchingSource,
        onDismiss = { sourcePicker = false }, onSelect = { sourcePicker = false; viewModel.switchSource(it) },
        onRefresh = { viewModel.refreshBookSources(force = true) }, availability = sourceAvailability,
        onContinue = { viewModel.refreshBookSources() }) }
    if (settingsSheet) ReaderSettingsSheet(settings, isComic, viewModel, onStartAuto = { controls = false }) {
        settingsSheet = false
    }
    libraryTool?.let { tool -> ReaderBookToolDialog(tool, viewModel, onDismiss = { libraryTool = null },
        onJump = { chapter, paragraph, offset, query ->
            libraryTool = null; viewModel.jumpToText(chapter, paragraph, offset, query); controls = false
        }) }
}

@Composable
internal fun NovelChapterReader(content: String, title: String, chapterIndex: Int, settings: NovelReaderSettings,
    ink: Color, controls: Boolean, onTap: () -> Unit, canPrevious: Boolean, canNext: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, enterAtEnd: Boolean = false,
    textAnchor: ContinuousScrollAnchor? = null, onPosition: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> }) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp, color = ink)
    if (settings.isVerticalScroll) {
        val chapter = remember(title, chapterIndex) { NovelChapter(chapterIndex, title, "single:$chapterIndex") }
        val section = remember(chapter, content) {
            ContinuousReaderSection(chapter, content, content.split('\n'))
        }
        ContinuousNovelReader(listOf(section), textAnchor ?: ContinuousScrollAnchor(chapterIndex, chapterIndex, enterAtEnd),
            settings, ink, controls, onTap, onViewport = { _, _ -> }, onRetry = {}, onPosition = onPosition)
    } else {
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val density = LocalDensity.current
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
            if (title.isNotBlank()) Text(title, color = ink.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 14.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                val height = with(density) { (maxHeight - 56.dp).roundToPx() }.coerceAtLeast(1)
                val layoutStyle = textStyle.copy(color = Color.Unspecified)
                val pages by produceState<List<String>>(emptyList(), content, layoutStyle, width, height, density) {
                    value = withContext(Dispatchers.Default) {
                        measuredReaderPages(content, layoutStyle, width, height, measurer)
                    }
                }
                val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
                LaunchedEffect(chapterIndex, content, pages, textAnchor?.revision) {
                    val target = textAnchor?.takeIf { it.chapterIndex == chapterIndex }?.let { anchor ->
                        val paragraphs = readerParagraphs(content)
                        val before = paragraphs.take(anchor.paragraphIndex ?: 0).sumOf { it.length + 1 } + anchor.charOffset
                        var characters = 0
                        pages.indexOfFirst { page -> characters += page.length; characters > before }.coerceAtLeast(0)
                    } ?: 0
                    if (pages.isNotEmpty()) pager.scrollToPage(target.coerceAtMost(pages.lastIndex))
                }
                LaunchedEffect(pager, pages) {
                    snapshotFlow { pager.currentPage }.collect { page ->
                        val offset = pages.take(page).sumOf { it.length }
                        val paragraphs = readerParagraphs(content)
                        var consumed = 0; var paragraph = 0
                        while (paragraph < paragraphs.lastIndex && consumed + paragraphs[paragraph].length < offset) {
                            consumed += paragraphs[paragraph].length + 1; paragraph++
                        }
                        onPosition(chapterIndex, paragraph, (offset - consumed).coerceAtLeast(0), pages.getOrNull(page).orEmpty().take(100))
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    HorizontalPager(pager, modifier = Modifier.weight(1f)) { index ->
                        if (pages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = ink, modifier = Modifier.size(24.dp))
                        } else Text(pages.getOrElse(index) { "" }, style = textStyle,
                            modifier = Modifier.fillMaxSize().readerTap(onTap))
                    }
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onPrevious, enabled = canPrevious) { Text("上一章", color = ink.copy(alpha = if (canPrevious) 0.65f else 0.25f), fontSize = 12.sp) }
                        Text(if (pages.isEmpty()) "正在排版…" else "${pager.currentPage + 1} / ${pages.size}", color = ink.copy(alpha = 0.55f), fontSize = 12.sp,
                            modifier = Modifier.clickable(onClick = onTap))
                        TextButton(onClick = onNext, enabled = canNext) { Text("下一章", color = ink.copy(alpha = if (canNext) 0.65f else 0.25f), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

/** Page breaks come from the same text layout as the visible body, never character counts. */
private fun measuredReaderPages(text: String, style: TextStyle, width: Int, height: Int,
    measurer: TextMeasurer): List<String> {
    if (text.isEmpty()) return listOf("")
    val layout = measurer.measure(AnnotatedString(text), style = style, constraints = Constraints(maxWidth = width))
    val pages = mutableListOf<String>()
    var firstLine = 0
    while (firstLine < layout.lineCount) {
        var lastLine = firstLine
        val top = layout.getLineTop(firstLine)
        while (lastLine + 1 < layout.lineCount && layout.getLineBottom(lastLine + 1) - top <= height) lastLine++
        val start = layout.getLineStart(firstLine)
        val end = layout.getLineEnd(lastLine, visibleEnd = false)
        if (end > start) pages += text.substring(start, end)
        firstLine = lastLine + 1
    }
    return pages.ifEmpty { listOf(text) }
}

@Composable
private fun ComicChapterReader(images: List<String>, referer: String, title: String, chapterIndex: Int,
    settings: NovelReaderSettings, ink: Color, controls: Boolean, onTap: () -> Unit, canPrevious: Boolean,
    canNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, requestHeaders: Map<String, String> = emptyMap()) {
    if (settings.comicHorizontalPaging) {
        val pager = rememberPagerState { images.size.coerceAtLeast(1) }
        LaunchedEffect(chapterIndex, images) { pager.scrollToPage(0) }
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, modifier = Modifier.weight(1f)) { page ->
                images.getOrNull(page)?.let { url ->
                    NovelNetworkImage(url, referer, "第 ${page + 1} 页", Modifier.fillMaxSize().readerTap(onTap), ContentScale.Fit,
                        requestHeaders = requestHeaders)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${images.size}", color = ink.copy(alpha = 0.6f), fontSize = 12.sp,
                    modifier = Modifier.weight(1f).clickable(onClick = onTap))
                TextButton(onClick = onPrevious, enabled = canPrevious) { Text("上一话", color = ink) }
                TextButton(onClick = onNext, enabled = canNext) { Text("下一话", color = ink) }
            }
        }
    } else {
        val list = rememberLazyListState()
        LaunchedEffect(chapterIndex, images) { list.scrollToItem(0) }
        LaunchedEffect(settings.autoScrollSpeed, chapterIndex, controls) {
            if (settings.autoScrollSpeed > 0f && !controls) while (isActive) { delay(40); list.scrollBy(settings.autoScrollSpeed * 0.04f) }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(settings.comicPageGapDp.dp),
            contentPadding = PaddingValues(top = 26.dp, bottom = 100.dp)) {
            item { Text(title, color = ink, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).readerTap(onTap)) }
            itemsIndexed(images, key = { index, url -> "$index:$url" }) { index, url ->
                var ratio by remember(url) { mutableFloatStateOf(0.68f) }
                NovelNetworkImage(url, referer, "第 ${index + 1} 页",
                    Modifier.fillMaxWidth().aspectRatio(ratio).readerTap(onTap), ContentScale.FillWidth,
                    onImageSize = { width, height -> if (width > 0 && height > 0) ratio = width.toFloat() / height },
                    requestHeaders = requestHeaders)
            }
            item { ChapterNavigation(canPrevious, canNext, onPrevious, onNext, ink, Modifier.padding(18.dp)) }
        }
    }
}

@Composable
private fun Modifier.readerTap(onClick: () -> Unit): Modifier = this.clickable(
    indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)

@Composable
private fun ChapterNavigation(previous: Boolean, next: Boolean, onPrevious: () -> Unit, onNext: () -> Unit,
    color: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = onPrevious, enabled = previous) { Text("上一章", color = color.copy(alpha = if (previous) 1f else 0.35f)) }
        OutlinedButton(onClick = onNext, enabled = next) { Text("下一章", color = color.copy(alpha = if (next) 1f else 0.35f)) }
    }
}

@Composable
private fun ReaderAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    ink: Color, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, title, Modifier.size(21.dp), tint = ink)
        Text(title, fontSize = 11.sp, color = ink, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(settings: NovelReaderSettings, isComic: Boolean,
    viewModel: NovelViewModel, onStartAuto: () -> Unit, onDismiss: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    var fontDialog by remember { mutableStateOf(false) }
    var moreSettings by remember { mutableStateOf(false) }
    var customColorDialog by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isComic) "漫画设置" else "阅读设置", fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, color = ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(32.dp)) { Text("完成", color = ink, fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("亮度", color = ink, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                Box(Modifier.weight(1f)) {
                    ReaderSettingSlider(if (settings.brightness < 0) .5f else settings.brightness,
                        viewModel::updateBrightness, .02f..1f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { viewModel.updateBrightness(-1f) }, modifier = Modifier.height(32.dp)) {
                    Text(if (settings.brightness < 0) "◯ 跟随系统 ✓" else "◯ 跟随系统", fontSize = 12.sp, color = ink)
                }
                TextButton(onClick = {
                    viewModel.selectTheme(if (settings.theme == NovelReaderTheme.GREEN) NovelReaderTheme.PARCHMENT else NovelReaderTheme.GREEN)
                }, modifier = Modifier.height(32.dp)) {
                    Text(if (settings.theme == NovelReaderTheme.GREEN) "护眼模式 ✓" else "护眼模式", fontSize = 12.sp, color = ink)
                }
            }
            if (!isComic) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("字号", color = ink, fontSize = 13.sp, modifier = Modifier.width(35.dp))
                    ReaderOptionPill("A−", false, Modifier.weight(1f)) { viewModel.updateFontSize(settings.fontSizeSp - 1f) }
                    Text(settings.fontSizeSp.toInt().toString(), color = ink, fontSize = 15.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.width(24.dp).testTag("reader-font-size"))
                    ReaderOptionPill("A＋", false, Modifier.weight(1f)) { viewModel.updateFontSize(settings.fontSizeSp + 1f) }
                    ReaderOptionPill("字体设置 ›", false, Modifier.weight(1.25f)) { fontDialog = true }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("背景", fontSize = 13.sp, color = ink, modifier = Modifier.width(40.dp))
                    Box(Modifier.weight(1f)) { ReaderThemeChoices(settings.theme, viewModel::selectTheme,
                        customColor = settings.customBackgroundColor, onCustomColor = { customColorDialog = true }) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("翻页", fontSize = 13.sp, color = ink, modifier = Modifier.width(33.dp))
                    ReaderOptionPill("上下滚动", settings.isVerticalScroll && settings.autoScrollSpeed == 0f, Modifier.weight(1f)) {
                        viewModel.toggleScrollMode(true); viewModel.setAutoScrollSpeed(0f)
                    }
                    ReaderOptionPill("左右翻页", !settings.isVerticalScroll, Modifier.weight(1f)) {
                        viewModel.toggleScrollMode(false); viewModel.setAutoScrollSpeed(0f)
                    }
                    ReaderOptionPill("自动", settings.autoScrollSpeed > 0f, Modifier.weight(.75f)) {
                        viewModel.toggleScrollMode(true)
                        if (settings.autoScrollSpeed <= 0f) onStartAuto()
                        viewModel.setAutoScrollSpeed(if (settings.autoScrollSpeed > 0f) 0f else 30f)
                        onDismiss()
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderOptionPill("连续下滑", !settings.comicHorizontalPaging, Modifier.weight(1f)) { viewModel.toggleComicPaging(false) }
                    ReaderOptionPill("横向翻页", settings.comicHorizontalPaging, Modifier.weight(1f)) { viewModel.toggleComicPaging(true) }
                }
                Text("图片间距 ${settings.comicPageGapDp.toInt()} dp", fontSize = 12.sp, color = ink)
                ReaderSettingSlider(settings.comicPageGapDp, viewModel::updateComicGap, 0f..24f)
            }
            HorizontalDivider(color = ink.copy(alpha = .12f), modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = { moreSettings = true }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                Text("更多设置", color = ink, fontSize = 13.sp)
            }
        }
    }
    if (fontDialog) ReaderFontDialog(settings, viewModel) { fontDialog = false }
    if (customColorDialog) ReaderColorPickerDialog(settings.readerBackgroundColor(), viewModel::selectCustomBackground) {
        customColorDialog = false
    }
    if (moreSettings) AlertDialog(onDismissRequest = { moreSettings = false }, title = { Text("更多设置") }, text = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("行距 ${"%.1f".format(settings.lineSpacingMultiplier)}", Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { viewModel.updateLineSpacing(settings.lineSpacingMultiplier - .1f) }) { Text("−") }
                TextButton(onClick = { viewModel.updateLineSpacing(settings.lineSpacingMultiplier + .1f) }) { Text("＋") }
            }
            Text("自动滚动速度 ${settings.autoScrollSpeed.toInt()}", fontSize = 13.sp)
            ReaderSettingSlider(settings.autoScrollSpeed, viewModel::setAutoScrollSpeed, 0f..100f)
        }
    }, confirmButton = { TextButton(onClick = { moreSettings = false }) { Text("完成") } })
}

@Composable
private fun ReaderOptionPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
        .background(ink.copy(alpha = if (selected) .08f else .045f))
        .border(if (selected) 1.5.dp else .7.dp, ink.copy(alpha = if (selected) .8f else .16f), RoundedCornerShape(18.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = ink, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ReaderFontDialog(settings: NovelReaderSettings, viewModel: NovelViewModel, onDismiss: () -> Unit) {
    var size by remember(settings.fontSizeSp) { mutableStateOf(settings.fontSizeSp.toInt().toString()) }
    val value = size.toIntOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("字体设置") }, text = {
        Column {
            Text("使用系统字体", fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            OutlinedTextField(size, { size = it.filter(Char::isDigit).take(2) }, label = { Text("字号（12–36）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = value == null || value !in 12..36)
        }
    }, confirmButton = { TextButton(onClick = { value?.let { viewModel.updateFontSize(it.toFloat()) }; onDismiss() },
        enabled = value != null && value in 12..36) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ReaderThemeChoices(selected: NovelReaderTheme, onSelect: (NovelReaderTheme) -> Unit,
    customColor: Long? = null, onCustomColor: () -> Unit = {}) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        NovelReaderTheme.entries.filter { it != NovelReaderTheme.NIGHT }.forEach { theme ->
            Column(Modifier.clickable { onSelect(theme) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(theme.backgroundColorHex))
                    .border(if (theme == selected && customColor == null) 2.dp else 1.dp,
                        if (theme == selected && customColor == null) ink else ink.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (theme == selected && customColor == null) Icon(HugeIcons.Tick01, "已选中", tint = Color(theme.textColorHex), modifier = Modifier.size(20.dp))
                }
                Text(theme.title, fontSize = 9.sp, color = ink.copy(alpha = 0.7f), modifier = Modifier.padding(top = 3.dp))
            }
        }
        CustomColorWheelButton(selected = customColor != null, onClick = onCustomColor)
    }
}

private fun Context.readerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.readerActivity()
    else -> null
}

@Composable
private fun ReaderBrightness(brightness: Float) {
    val window = LocalContext.current.readerActivity()?.window
    DisposableEffect(window) {
        val original = window?.attributes?.screenBrightness ?: -1f
        onDispose { window?.let { it.attributes = it.attributes.apply { screenBrightness = original } } }
    }
    LaunchedEffect(window, brightness) {
        window?.let {
            val target = if (brightness < 0) -1f else brightness.coerceIn(0.02f, 1f)
            if (it.attributes.screenBrightness != target) it.attributes = it.attributes.apply { screenBrightness = target }
        }
    }
}

@Composable
internal fun ReaderSettingSlider(value: Float, onCommit: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    var pending by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Slider(value = pending, onValueChange = { pending = it }, valueRange = range,
        onValueChangeFinished = { onCommit(pending) })
}
