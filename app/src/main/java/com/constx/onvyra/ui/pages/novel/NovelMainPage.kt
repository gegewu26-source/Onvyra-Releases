package com.constx.onvyra.ui.pages.novel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.Navigator
import org.koin.compose.koinInject

internal val NovelRed = Color(0xFFFF3545)
private val NovelInk = Color(0xFF292929)
private val NovelMuted = Color(0xFF8C8C8C)

@Composable
private fun NovelOpeningPage(book: NovelBook?, error: String?, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding().testTag("reader-opening-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text(book?.title ?: "打开书籍", modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null && book != null) {
                CircularProgressIndicator(color = NovelRed, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                Text("正在打开作品…", modifier = Modifier.padding(top = 16.dp), color = NovelMuted)
            } else {
                Text(error ?: "阅读会话已结束，请返回书架重新打开。", color = NovelMuted)
                if (book != null) TextButton(onClick = onRetry) { Text("重试") }
                TextButton(onClick = onBack) { Text("返回书架") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelMainPage(
    navController: Navigator = LocalNavController.current,
    viewModel: NovelViewModel = koinInject(),
) {
    // The reading area has its own visual identity; the Agent's purple theme should
    // not leak into its tabs, sliders, dialogs or search controls.
    MaterialTheme(colorScheme = lightColorScheme(primary = NovelRed, onPrimary = Color.White,
        primaryContainer = Color(0xFFFFECEF), onPrimaryContainer = NovelRed,
        secondary = NovelRed, secondaryContainer = Color(0xFFFFECEF), onSecondaryContainer = NovelRed,
        surface = Color.White, background = Color.White,
        surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
        surfaceContainerLow = Color.White, surfaceContainerHighest = Color(0xFFF3F3F3),
        onSurface = NovelInk, onBackground = NovelInk)) {
        NovelMainContent(navController, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelMainContent(navController: Navigator, viewModel: NovelViewModel) {
    val shelf by viewModel.bookshelf.collectAsStateWithLifecycle()
    val comicShelf by viewModel.comicBookshelf.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchStatus by viewModel.searchStatus.collectAsStateWithLifecycle()
    val canContinueSearch by viewModel.canContinueSearch.collectAsStateWithLifecycle()
    val discover by viewModel.discoverBooks.collectAsStateWithLifecycle()
    val discovering by viewModel.isLoadingDiscover.collectAsStateWithLifecycle()
    val storeHome by viewModel.storeHome.collectAsStateWithLifecycle()
    val storeLoading by viewModel.storeLoading.collectAsStateWithLifecycle()
    val storeError by viewModel.storeError.collectAsStateWithLifecycle()
    val storeCategory by viewModel.storeCategory.collectAsStateWithLifecycle()
    val book by viewModel.currentBook.collectAsStateWithLifecycle()
    val bookOpenRequestId by viewModel.bookOpenRequestId.collectAsStateWithLifecycle()
    val error by viewModel.errorMessage.collectAsStateWithLifecycle()
    val selectedSource by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val selectedComicSource by viewModel.selectedComicSourceId.collectAsStateWithLifecycle()
    val selectedAudioSource by viewModel.selectedAudioSourceId.collectAsStateWithLifecycle()
    val mediaType by viewModel.activeMediaType.collectAsStateWithLifecycle()
    var reading by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf(false) }
    var pendingBook by remember { mutableStateOf<NovelBook?>(null) }
    var detailTool by remember { mutableStateOf<ReaderBookTool?>(null) }
    var searchPage by rememberSaveable { mutableStateOf(false) }
    val isComic = mediaType == 2
    val isAudio = mediaType == 1
    val visibleResults = remember(results, mediaType) { results.filter { ReaderSearchPolicy.kind(it) == mediaType } }
    val mediaName = when (mediaType) { 2 -> "漫画"; 1 -> "有声"; else -> "小说" }
    var bottomTab by remember { mutableIntStateOf(0) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var category by rememberSaveable(selectedSource, selectedComicSource, selectedAudioSource, mediaType) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    DisposableEffect(viewModel) { onDispose { viewModel.stopSearch() } }
    val activeShelf = if (isComic) comicShelf else remember(shelf, isAudio) { shelf.filter { it.isAudio == isAudio } }
    val sortedShelf = remember(activeShelf, sort) {
        when (sort) {
            1 -> activeShelf.sortedBy { it.title }
            2 -> activeShelf.sortedByDescending { it.lastReadChapterIndex }
            else -> activeShelf.sortedByDescending { it.lastReadTime }
        }
    }
    val activeSource = when { isComic -> selectedComicSource; isAudio -> selectedAudioSource; else -> selectedSource }
    fun search(keyword: String = query) {
        focus.clearFocus()
        viewModel.updateSearchQuery(keyword)
        viewModel.searchBooks(keyword, isComic, isAudio)
    }
    fun open(item: NovelBook) {
        if (ReaderSearchPolicy.kind(item) == mediaType) {
            pendingBook = item; viewModel.openBook(item); detail = true; reading = false
        }
    }
    fun resume(item: NovelBook) {
        if (ReaderSearchPolicy.kind(item) == mediaType) {
            pendingBook = item; viewModel.openBook(item); detail = false; reading = true
        }
    }
    LaunchedEffect(bottomTab, mediaType, activeSource, searchPage, query.isBlank()) {
        if ((bottomTab == 1 && !searchPage) || (searchPage && query.isBlank())) {
            if (mediaType == 0) viewModel.loadStore() else viewModel.loadDiscover(isComic,isAudio)
        }
    }
    if (reading) {
        if (book != null && (pendingBook == null || bookOpenRequestId == pendingBook!!.id)) {
            NovelReaderPage(viewModel, onShowDetails = { reading = false; detail = true }) {
                reading = false; detail = false; viewModel.leaveReader()
            }
        } else {
            BackHandler { reading = false; viewModel.leaveReader() }
            NovelOpeningPage(pendingBook, error, onBack = { reading = false; viewModel.leaveReader() },
                onRetry = { pendingBook?.let(viewModel::openBook) })
        }
        return
    }
    if (detail) {
        if (book != null && (pendingBook == null || bookOpenRequestId == pendingBook!!.id)) {
            NovelBookDetailPage(viewModel, onBack = { detail = false; viewModel.leaveReader() },
                onRead = { detail = false; reading = true }, onTool = { detailTool = it },
                onChapter = { viewModel.loadChapter(it); detail = false; reading = true })
            detailTool?.let { tool -> ReaderBookToolDialog(tool, viewModel, onDismiss = { detailTool = null },
                onJump = { chapter, paragraph, offset, query ->
                    detailTool = null; viewModel.jumpToText(chapter, paragraph, offset, query); detail = false; reading = true
                }) }
        } else {
            BackHandler { detail = false; viewModel.leaveReader() }
            NovelOpeningPage(pendingBook, error, onBack = { detail = false; viewModel.leaveReader() },
                onRetry = { pendingBook?.let(viewModel::openBook) })
        }
        return
    }
    BackHandler(searchPage) { viewModel.stopSearch(); searchPage = false; focus.clearFocus() }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        if (searchPage) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query, onValueChange = {
                        viewModel.updateSearchQuery(it)
                        if (it.isBlank()) viewModel.clearSearchResults()
                    }, placeholder = { Text("请输入书名或作者", fontSize = 14.sp) },
                    singleLine = true, shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFFF3F3F3),
                        unfocusedContainerColor = Color(0xFFF3F3F3), focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent),
                    leadingIcon = { Icon(HugeIcons.Search01, null, Modifier.size(19.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = {
                            viewModel.updateSearchQuery(""); viewModel.clearSearchResults()
                        }) { Icon(HugeIcons.Cancel01, "清空搜索", Modifier.size(17.dp)) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.stopSearch(); focus.clearFocus(); searchPage = false }) { Text("取消", color = NovelInk) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf(0 to "小说", 2 to "漫画", 1 to "有声").forEach { (type, title) ->
                val selected = mediaType == type
                Column(Modifier.clickable {
                    if (!selected) {
                        viewModel.setMediaType(type)
                        viewModel.clearSearchResults()
                        if (searchPage && query.isNotBlank()) viewModel.searchBooks(query, type == 2, type == 1)
                    }
                }.padding(end = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, fontSize = if (selected) 19.sp else 17.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) NovelRed else NovelInk)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (selected) NovelRed else Color.Transparent))
                }
            }
            Spacer(Modifier.weight(1f))
            if (!searchPage) IconButton(onClick = { searchPage = true }) { Icon(HugeIcons.Search01, "搜索", tint = NovelInk) }
            Box {
                IconButton(onClick = { more = true }) { Icon(HugeIcons.MoreVertical, "更多", tint = NovelInk) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (grid) "切换列表模式" else "切换网格模式") },
                        onClick = { grid = !grid; more = false })
                    listOf("最近阅读", "书名", "阅读章节").forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text("按${label}排序${if (sort == index) " ✓" else ""}") },
                            onClick = { sort = index; more = false })
                    }
                    DropdownMenuItem(text = { Text("整理书架") }, enabled = activeShelf.isNotEmpty(),
                        onClick = { management = true; more = false })
                    DropdownMenuItem(text = { Text("返回 Onvyra") }, onClick = { viewModel.stopSearch(); more = false; navController.popBackStack() })
                }
            }
        }
        HorizontalDivider(color = Color(0xFFF2F2F2))
        if (error != null && (bottomTab != 1 || searchPage || mediaType != 0)) NovelErrorBanner(error.orEmpty(), onRetry = {
            if (searchPage) search() else viewModel.loadDiscover(isComic, isAudio)
        }, onDismiss = viewModel::clearError)
        if (searchPage && searchStatus.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (searching) CircularProgressIndicator(color = NovelRed, modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(searchStatus, color = NovelMuted, fontSize = 11.sp, maxLines = 2,
                modifier = Modifier.weight(1f).padding(start = if (searching) 7.dp else 0.dp))
            if (searching) TextButton(onClick = viewModel::stopSearch) { Text("停止", color = NovelRed, fontSize = 12.sp) }
            else if (canContinueSearch) TextButton(onClick = viewModel::continueSearch) { Text("继续查找", color = NovelRed, fontSize = 12.sp) }
        }
        Box(Modifier.weight(1f)) {
            when {
                searchPage -> NovelSearchResultsContent(visibleResults, searching, query, mediaType, ::open) {
                    if (query.isBlank()) SearchHistory(history, recommendations = discover.filter { it.isComic == isComic && it.isAudio == isAudio }
                        .map { it.title }.distinct().take(40), recommendationLoading = discovering,
                        onSelect = { search(it) }, onClear = { clearHistory = true })
                    else if (error != null) NovelFailure("暂时未能完成搜索", "来源连接失败，当前无法判断是否收录这本书。",
                        onRetry = { search() })
                    else NovelEmpty("没有找到相关作品", "请检查关键词，或继续查找更多来源。", "重新搜索") { search() }
                }
                bottomTab == 0 -> Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), color = Color(0xFFF0F0F0))
                        Text("当前${mediaName}书架已收藏${activeShelf.size}本${mediaName}",
                            color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
                        HorizontalDivider(Modifier.weight(1f), color = Color(0xFFF0F0F0))
                    }
                    if (sortedShelf.isEmpty()) NovelEmpty("这里竟然一本书都木有哦~", "", "去书城看看") { bottomTab = 1 }
                    else NovelBookCollection(sortedShelf, grid, "", ::resume, onAdd = { searchPage = true })
                }
                bottomTab == 1 && mediaType == 0 -> NovelStorefront(storeHome,storeLoading,storeError,storeCategory,
                    onChannel = { viewModel.loadStore(it) },onCategory={ viewModel.showStoreCategory(it) },
                    onMore=viewModel::moreStoreCategory,onCloseCategory=viewModel::closeStoreCategory,
                    onRefresh={ if(storeCategory != null) viewModel.showStoreCategory(storeCategory!!.category,force=true) else viewModel.loadStore(force=true) },
                    onOpen=::open,onSearch={searchPage=true})
                bottomTab == 1 -> Column {
                    if (discovering) NovelLoading("正在加载书城…")
                    else if (error != null && discover.isEmpty()) NovelFailure("书城暂时无法加载", "请重试或选择其他来源。",
                        onRetry = { viewModel.loadDiscover(isComic, isAudio) })
                    else if (discover.isEmpty()) NovelEmpty("暂无书城内容", "可以直接搜索书名或作者。", "搜索作品") { searchPage = true }
                    else {
                        val actualBooks = remember(discover, mediaType) { discover.filter { it.isComic == isComic && it.isAudio == isAudio } }
                        val categories = remember(actualBooks) { actualBooks.map { it.category }.filter { it.isNotBlank() }.distinct() }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(category.isBlank(), { category = "" }, label = { Text("全部") })
                            categories.forEach { label -> FilterChip(category == label, { category = label }, label = { Text(label) }) }
                            TextButton(onClick = { viewModel.loadDiscover(isComic, isAudio) }) { Text("换一换") }
                        }
                        val selectedBooks = remember(actualBooks, category) { if (category.isBlank()) actualBooks else actualBooks.filter { it.category == category } }
                        Text(if (category.isBlank()) {
                            if (activeSource == NovelSourceEngine.SOURCE_SKIN) "书库选读"
                            else "精选${if (isComic) "漫画" else "书籍"}推荐"
                        } else category,
                            fontSize = 21.sp, fontWeight = FontWeight.Bold, color = NovelInk,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
                        NovelBookCollection(selectedBooks, true, "", ::open)
                    }
                }

            }
        }
        if (!searchPage) {
            HorizontalDivider(color = Color(0xFFF0F0F0))
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically) {
                listOf("书架", "书城").forEachIndexed { index, title ->
                    Column(Modifier.weight(1f).clickable {
                        bottomTab = index
                    }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ReaderNavigationIcon(index, if (bottomTab == index) NovelRed else Color(0xFFAAAAAA))
                        Text(title, color = if (bottomTab == index) NovelRed else Color(0xFFAAAAAA), fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
    if (management) ShelfManagement(activeShelf, onDismiss = { management = false }, onRemove = viewModel::toggleBookshelf)
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("清空搜索历史？") },
        text = { Text("书架和阅读进度会保留。") },
        confirmButton = { TextButton(onClick = { viewModel.clearSearchHistory(); clearHistory = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("取消") } })

}

@Composable
internal fun NovelSearchResultsContent(
    books: List<NovelBook>, searching: Boolean, query: String, mediaType: Int,
    onOpen: (NovelBook) -> Unit,
    imageLoader: coil3.ImageLoader? = null,
    coverResolver: NovelCoverResolver? = null,
    emptyContent: @Composable () -> Unit = {},
) {
    val visible = remember(books, mediaType) { books.filter { ReaderSearchPolicy.kind(it) == mediaType } }
    Box(Modifier.fillMaxSize().testTag("reader-search-content").semantics {
        stateDescription = if (searching) "继续查找来源" else "搜索完成"
    }) {
        when {
            visible.isNotEmpty() -> NovelBookCollection(visible, false, query, onOpen,
                imageLoader = imageLoader, coverResolver = coverResolver)
            searching -> NovelLoading("正在搜索${when (mediaType) { 2 -> "漫画"; 1 -> "有声"; else -> "小说" }}…")
            else -> emptyContent()
        }
    }
}

@Composable
internal fun NovelBookCollection(books: List<NovelBook>, grid: Boolean, query: String,
    onOpen: (NovelBook) -> Unit, onAdd: (() -> Unit)? = null,
    imageLoader: coil3.ImageLoader? = null, coverResolver: NovelCoverResolver? = null) {
    if (grid) LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.testTag("reader-results"), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(books, key = { "${it.sourceId}:${it.id}" }) { book ->
            Column(Modifier.fillMaxWidth().testTag("reader-book-${book.id}").clickable { onOpen(book) }) {
                NovelNetworkImage(book.coverUrl, book.coverReferer, book.title,
                    Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(5.dp)), requestHeaders = book.artworkHeaders,
                    coverBook = book, imageLoader = imageLoader, coverResolver = coverResolver)
                Text(book.title, Modifier.padding(top = 7.dp), color = NovelInk, fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.readingChapterForDisplay.ifBlank { book.author },
                    color = NovelMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onAdd != null) item {
            Box(Modifier.fillMaxWidth().aspectRatio(0.72f).border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(5.dp))
                .clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                Icon(HugeIcons.Add01, "添加作品", Modifier.size(34.dp), tint = Color(0xFFBBBBBB))
            }
        }
    } else LazyColumn(modifier = Modifier.testTag("reader-results"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(books, key = { "${it.sourceId}:${it.id}" }) { book ->
            Row(Modifier.fillMaxWidth().testTag("reader-book-${book.id}").clickable { onOpen(book) }, verticalAlignment = Alignment.Top) {
                NovelNetworkImage(book.coverUrl, book.coverReferer, book.title,
                    Modifier.width(62.dp).aspectRatio(0.72f).clip(RoundedCornerShape(5.dp)), requestHeaders = book.artworkHeaders,
                    coverBook = book, imageLoader = imageLoader, coverResolver = coverResolver)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(highlightMatch(book.title, query), color = NovelInk,
                        fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(book.author, book.category, book.status).filter { it.isNotBlank() }.joinToString(" · "),
                        color = NovelMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 5.dp))
                    Text("${book.sourceCount} 个来源", color = NovelRed, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                    if (book.description.isNotBlank()) Text(book.description, color = Color(0xFF777777), fontSize = 12.sp,
                        maxLines = 2, lineHeight = 18.sp, overflow = TextOverflow.Ellipsis)
                    val progress = book.readingChapterForDisplay
                    if (progress.isNotBlank()) Text(progress, color = NovelMuted, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (onAdd != null) item { TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加作品", color = NovelRed) } }
    }
}

private fun highlightMatch(title: String, query: String): AnnotatedString = buildAnnotatedString {
    append(title)
    if (query.isNotBlank()) {
        var start = title.indexOf(query, ignoreCase = true)
        while (start >= 0) {
            addStyle(SpanStyle(color = NovelRed), start, start + query.length)
            start = title.indexOf(query, start + query.length, ignoreCase = true)
        }
    }
}

@Composable
private fun ReaderNavigationIcon(index: Int, tint: Color) {
    Canvas(Modifier.size(23.dp)) {
        val u = size.width / 24f
        val line = Stroke(1.7f * u)
        when (index) {
            0 -> {
                val home = Path().apply {
                    moveTo(3 * u, 10 * u); lineTo(12 * u, 2 * u); lineTo(21 * u, 10 * u)
                    lineTo(21 * u, 21 * u); lineTo(3 * u, 21 * u); close()
                }
                drawPath(home, tint, style = line)
                drawLine(tint, Offset(8 * u, 17 * u), Offset(16 * u, 17 * u), 1.7f * u)
            }
            1 -> {
                val book = Path().apply {
                    moveTo(12 * u, 5 * u); cubicTo(9 * u, 2 * u, 4 * u, 3 * u, 2 * u, 4 * u)
                    lineTo(2 * u, 20 * u); cubicTo(6 * u, 18 * u, 9 * u, 19 * u, 12 * u, 21 * u)
                    cubicTo(15 * u, 19 * u, 18 * u, 18 * u, 22 * u, 20 * u)
                    lineTo(22 * u, 4 * u); cubicTo(18 * u, 2 * u, 15 * u, 3 * u, 12 * u, 5 * u)
                    lineTo(12 * u, 21 * u)
                }
                drawPath(book, tint, style = line)
            }
            2 -> for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) {
                drawRoundRect(tint, Offset(x * u, y * u), Size(7 * u, 7 * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = line)
            }
            else -> {
                drawCircle(tint, 4 * u, Offset(12 * u, 7 * u), style = line)
                val person = Path().apply {
                    moveTo(3 * u, 22 * u); cubicTo(3 * u, 14 * u, 8 * u, 16 * u, 8 * u, 12 * u)
                    moveTo(16 * u, 12 * u); cubicTo(16 * u, 16 * u, 21 * u, 14 * u, 21 * u, 22 * u)
                    lineTo(3 * u, 22 * u)
                }
                drawPath(person, tint, style = line)
            }
        }
    }
}

@Composable
private fun NovelFailure(title: String, detail: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = NovelInk, fontSize = 18.sp)
            Text(detail, color = NovelMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = NovelRed)) { Text("重新加载") }
        }
    }
}

@Composable
private fun SearchHistory(history: List<String>, recommendations: List<String>, recommendationLoading: Boolean,
    onSelect: (String) -> Unit, onClear: () -> Unit) {
    var recommendationOffset by remember(recommendations) { mutableIntStateOf(0) }
    val shown = remember(recommendations, recommendationOffset) {
        if (recommendations.isEmpty()) emptyList() else (0 until minOf(8, recommendations.size))
            .map { recommendations[(recommendationOffset + it) % recommendations.size] }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("书城推荐", fontSize = 20.sp, color = NovelInk, modifier = Modifier.weight(1f))
                TextButton(onClick = { recommendationOffset += 8 }, enabled = recommendations.size > 8) {
                    Text("↻ 换一换", color = NovelMuted, fontSize = 14.sp)
                }
            }
        }
        if (recommendationLoading && shown.isEmpty()) item {
            Text("正在加载推荐…", color = NovelMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
        } else if (shown.isEmpty()) item {
            Text("此来源暂时没有推荐，可直接输入书名搜索。", color = NovelMuted, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 18.dp))
        }
        items(shown.chunked(2)) { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { title ->
                    val order = shown.indexOf(title)
                    Row(Modifier.weight(1f).clickable { onSelect(title) }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(when (order) {
                            0 -> NovelRed; 1 -> Color(0xFFFF7A84); 2 -> Color(0xFFF2B8BE); else -> Color(0xFFCCCCCC)
                        }), contentAlignment = Alignment.Center) {
                            Text("${order + 1}", color = Color.White, fontSize = 14.sp)
                        }
                        Text(title, color = Color(0xFF666666), fontSize = 16.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("历史搜索", fontSize = 20.sp, color = NovelInk, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = history.isNotEmpty()) { Text("清除", color = NovelMuted, fontSize = 14.sp) }
            }
        }
        if (history.isEmpty()) item { Text("还没有搜索记录", color = NovelMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp)) }
        items(history, key = { it }) { term ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(term) }.padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(23.dp)) {
                    val w = size.width
                    drawCircle(Color(0xFF999999), w * 0.43f, style = Stroke(w * 0.06f))
                    drawLine(Color(0xFF999999), Offset(w * 0.5f, w * 0.24f), Offset(w * 0.5f, w * 0.5f), w * 0.06f)
                    drawLine(Color(0xFF999999), Offset(w * 0.5f, w * 0.5f), Offset(w * 0.72f, w * 0.63f), w * 0.06f)
                }
                Text(term, color = Color(0xFF666666), fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = Color(0xFFF1F1F1))
        }
    }
}

@Composable
internal fun NovelErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val shortMessage = remember(message) {
        message.removePrefix("搜索失败：").removePrefix("当前书源均未完成搜索：")
            .replace(Regex("(新笔趣阁|笔趣阁 Piquge|笔趣阁 Skin)：\\1：")) { "${it.groupValues[1]}：" }
    }
    Surface(color = Color(0xFFFFEEEE), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shortMessage, color = Color(0xFF9D3030), fontSize = 12.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("重试", color = NovelRed) }
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭提示", Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun NovelLoading(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = NovelRed, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
            Text(message, Modifier.padding(top = 14.dp), color = NovelMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NovelEmpty(title: String, detail: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(HugeIcons.Book02, null, Modifier.size(48.dp), tint = Color(0xFFCCCCCC))
            Text(title, Modifier.padding(top = 16.dp), fontSize = 17.sp, color = NovelInk)
            Text(detail, Modifier.padding(top = 8.dp, bottom = 18.dp), color = NovelMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = NovelRed)) { Text(action) }
        }
    }
}

@Composable
internal fun NovelBookSourcePicker(book: NovelBook, importedSources: List<ImportedReaderSource>,
    searching: Boolean, status: String, switching: Boolean,
    onDismiss: () -> Unit, onSelect: (String) -> Unit, onRefresh: () -> Unit,
    availability: Map<String, ReaderSourceAvailability> = emptyMap(), onContinue: (() -> Unit)? = null) {
    val matches = remember(book) { (listOf(book.asSource()) + book.sources)
        .filter { it.sourceId != NovelSourceEngine.SOURCE_SKIN && it.sourceId != NovelSourceEngine.SOURCE_CATALOG && ReaderBookIdentity.matches(book, it.asBook()) }.distinctBy { it.sourceId } }
    fun name(id: String): String = importedSources.firstOrNull { it.id == id }?.name
        ?: NovelSourceEngine.AVAILABLE_SOURCES.firstOrNull { it.id == id }?.name
        ?: NovelSourceEngine.AVAILABLE_COMIC_SOURCES.firstOrNull { it.id == id }?.name ?: id
    AlertDialog(onDismissRequest = onDismiss, title = { Text("《${book.title}》的来源", fontSize = 17.sp) }, text = {
        Column(Modifier.testTag("reader-book-source-picker")) {
            Text("已匹配 ${matches.size} 个来源 · 章节验证 ${availability.values.count { it.status == ReaderSourceAvailability.USABLE }} 个", color = NovelMuted, fontSize = 13.sp)
            Text(status.ifBlank { if (searching) "正在自动查找同一本作品…" else "仅显示匹配书名、作者和类型的来源" },
                color = NovelMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            if (searching || switching) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(matches, key = { it.sourceId }) { candidate ->
                    val current = candidate.sourceId == book.sourceId
                    val validation = availability[ReaderSourceAvailability.key(candidate.sourceId, candidate.detailUrl)]
                    Row(Modifier.fillMaxWidth().testTag("reader-candidate-${candidate.sourceId}")
                        .clickable(enabled = !switching && !current) { onSelect(candidate.sourceId) }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current, onClick = { if (!current) onSelect(candidate.sourceId) }, enabled = !switching)
                        Column(Modifier.weight(1f)) {
                            Text(name(candidate.sourceId), fontSize = 14.sp)
                            Text(if (current) "正在使用" else when (validation?.status) {
                                ReaderSourceAvailability.USABLE -> "已验证：${validation.chapterTitle} · 切换时核对当前章"
                                ReaderSourceAvailability.FAILED -> "验证失败，可点击重试：${validation.reason}"
                                else -> "仅书名作者匹配，尚待验证目录与正文"
                            },
                                color = NovelMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { Row {
            onContinue?.let { TextButton(onClick = it, enabled = !searching && !switching) { Text("继续查找") } }
            TextButton(onClick = onRefresh, enabled = !searching && !switching) { Text("重新查找") }
        } })
}

@Composable
internal fun NovelSourcePicker(isComic: Boolean, selected: String, onDismiss: () -> Unit,
    onSelect: (String) -> Unit, importedSources: List<ImportedReaderSource> = emptyList(), isAudio: Boolean = false) {
    var filter by remember { mutableStateOf("") }
    val type = when { isComic -> 2; isAudio -> 1; else -> 0 }
    data class Entry(val id: String, val title: String, val detail: String, val enabled: Boolean)
    val entries = remember(importedSources, type) {
        val builtins = when (type) {
            2 -> NovelSourceEngine.AVAILABLE_COMIC_SOURCES.map { Entry(it.id, it.name, it.description, it.enable) }
            1 -> emptyList()
            else -> NovelSourceEngine.AVAILABLE_SOURCES.map { Entry(it.id, it.name, it.description, it.enabled) }
        }
        val aggregate = if (type == 0) emptyList() else listOf(Entry(NovelSourceEngine.SOURCE_MERGE,
            "多源聚合", "自动搜索本分类已启用来源，同名同作者合并", true))
        aggregate + builtins + importedSources.filter { it.type == type }.map {
            Entry(it.id, it.name, listOf(it.group, it.url).filter(String::isNotBlank).joinToString(" · "), it.enabled)
        }
    }
    val filtered = remember(entries, filter) {
        entries.filter { filter.isBlank() || it.title.contains(filter, true) || it.detail.contains(filter, true) }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("选择${when (type) { 2 -> "漫画源"; 1 -> "有声源"; else -> "小说源" }}") },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("搜索名称、分组或网址") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${entries.count { it.id != NovelSourceEngine.SOURCE_MERGE }} 个来源 · 默认自动聚合，可指定单源", color = NovelMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = entry.enabled) { onSelect(entry.id) }
                            .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == entry.id, enabled = entry.enabled, onClick = { onSelect(entry.id) })
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, fontWeight = FontWeight.Medium,
                                    color = if (entry.enabled) NovelInk else NovelMuted)
                                if (entry.detail.isNotBlank()) Text(entry.detail, color = NovelMuted, fontSize = 11.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!entry.enabled) Text("暂不可用", color = NovelMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun ShelfManagement(books: List<NovelBook>, onDismiss: () -> Unit, onRemove: (NovelBook) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("整理书架") }, text = {
        Column {
            Text("选择要移出书架的作品", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(books) { item ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.id in selected, onCheckedChange = {
                            selected = if (it) selected + item.id else selected - item.id
                        })
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            TextButton(onClick = { selected = if (selected.size == books.size) emptySet() else books.map { it.id }.toSet() }) {
                Text(if (selected.size == books.size) "取消全选" else "全选")
            }
        }
    }, confirmButton = { TextButton(onClick = { confirm = true }, enabled = selected.isNotEmpty()) { Text("移除 ${selected.size} 本", color = NovelRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("移除所选作品？") },
        text = { Text("将从当前书架移除 ${selected.size} 本作品。") },
        confirmButton = { TextButton(onClick = { books.filter { it.id in selected }.forEach(onRemove); onDismiss() }) { Text("移除", color = NovelRed) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
