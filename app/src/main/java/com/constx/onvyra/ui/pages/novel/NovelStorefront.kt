package com.constx.onvyra.ui.pages.novel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val StoreAccent = Color(0xFF3687EE)
private val StoreInk = Color(0xFF202329)
private val StoreMuted = Color(0xFF9399A3)
private val StoreSoft = Color(0xFFF3F5F8)

/** A curated home and a genuinely paged category are separate views of the same catalog. */
@Composable
internal fun NovelStorefront(
    home: ReaderStoreHome?, loading: Boolean, error: String?, categoryPage: ReaderStoreCategoryPage?,
    onChannel: (String) -> Unit, onCategory: (ReaderStoreCategory) -> Unit,
    onMore: () -> Unit, onCloseCategory: () -> Unit, onRefresh: () -> Unit,
    onOpen: (NovelBook) -> Unit, onSearch: (() -> Unit)? = null,
) {
    if (categoryPage != null) {
        StoreCategoryPage(categoryPage, onMore, onCloseCategory, onRefresh, onOpen)
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(Color.White).testTag("store-home"),
        contentPadding = PaddingValues(bottom = 22.dp)) {
        item(key = "store-controls") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(StoreSoft).padding(3.dp)) {
                    listOf("male" to "男生", "female" to "女生").forEach { (channel, title) ->
                        val selected = (home?.channel ?: "male") == channel
                        Box(Modifier.widthIn(min = 58.dp).height(32.dp).clip(RoundedCornerShape(50))
                            .background(if (selected) StoreAccent else Color.Transparent)
                            .clickable(enabled = !selected || error != null) { onChannel(channel) }
                            .testTag("store-channel-$channel")
                            .semantics { contentDescription = "${title}书城" }, contentAlignment = Alignment.Center) {
                            Text(title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Color.White else StoreMuted)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.testTag("store-refresh")) { Text("刷新", fontSize = 12.sp, color = StoreMuted) }
            }
            if (onSearch != null) Row(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(38.dp)
                .clip(RoundedCornerShape(50)).background(StoreSoft).clickable(onClick = onSearch)
                .testTag("store-search").padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⌕", fontSize = 24.sp, color = StoreMuted)
                Text("搜索书名、作者", fontSize = 12.sp, color = StoreMuted, modifier = Modifier.padding(start = 9.dp))
            }
            if (loading && home != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = StoreAccent)
            if (error != null) StoreInlineError(error, onRefresh)
            if (home?.notice != null) Text(home.notice, fontSize = 11.sp, color = StoreMuted,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }
        if (home == null && loading) item(key = "store-loading") { StoreLoading("正在加载书城…") }
        if (home != null) {
            if (home.featured.isNotEmpty()) item(key = "store-featured") { StoreFeatured(home.featured, onOpen) }
            val categories = home.categories.ifEmpty { home.sections.map { it.category } }
            if (categories.isNotEmpty()) item(key = "store-categories") {
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).testTag("store-categories")) {
                    items(categories, key = { it.id }) { category ->
                        Surface(shape = RoundedCornerShape(50), color = StoreSoft, modifier = Modifier
                            .clickable { onCategory(category) }.testTag("store-category-${category.id}")) {
                            Text(category.title, fontSize = 12.sp, color = StoreInk,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            home.sections.forEach { section ->
                item(key = "store-section-${section.category.id}", contentType = if (section.horizontal) "covers" else "synopses") {
                    StoreSection(section, onCategory, onOpen)
                }
            }
            if (home.featured.isEmpty() && home.sections.isEmpty() && !loading && error == null)
                item(key = "store-empty") { StoreEmpty("暂时没有书城内容", onRefresh) }
        } else if (!loading && error == null) item(key = "store-empty") { StoreEmpty("暂时没有书城内容", onRefresh) }
    }
}

@Composable
private fun StoreFeatured(books: List<NovelBook>, onOpen: (NovelBook) -> Unit) {
    val pager = rememberPagerState(pageCount = { books.size })
    LaunchedEffect(books.map { it.id }, pager) {
        if (books.size > 1) while (true) {
            delay(5_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % books.size)
        }
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        HorizontalPager(pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth().testTag("store-featured")) { index ->
            val book = books[index]
            Row(Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFDCEBFF), Color(0xFFEDF4FE))))
                .clickable { onOpen(book) }.testTag("store-featured-${book.id}").padding(15.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (book.category.isNotBlank()) Surface(color = Color.White.copy(alpha = .8f), shape = RoundedCornerShape(5.dp)) {
                        Text(book.category, fontSize = 10.sp, color = StoreAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1)
                    }
                    Text(book.title, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = StoreInk)
                    if (book.description.isNotBlank()) Text(book.description, fontSize = 11.sp, lineHeight = 17.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFF79899B))
                    Text(book.author, fontSize = 10.sp, color = Color(0xFF9BA9B8), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                StoreCover(book, Modifier.width(82.dp).height(118.dp))
            }
        }
        if (books.size > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(books.size) { index ->
                Box(Modifier.padding(horizontal = 3.dp).width(if (pager.currentPage == index) 14.dp else 4.dp)
                    .height(4.dp).clip(RoundedCornerShape(50))
                    .background(if (pager.currentPage == index) StoreAccent else Color(0xFFDBE1EA)))
            }
        }
    }
}

@Composable
private fun StoreSection(section: ReaderStoreSection, onCategory: (ReaderStoreCategory) -> Unit, onOpen: (NovelBook) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("store-section-${section.category.id}")) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(section.category.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = StoreInk,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { onCategory(section.category) }, modifier = Modifier.testTag("store-more-${section.category.id}")) {
                Text("更多 ›", fontSize = 11.sp, color = StoreMuted)
            }
        }
        if (section.horizontal) LazyRow(contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            items(section.books, key = { it.id }) { book ->
                Column(Modifier.width(76.dp).clickable { onOpen(book) }.testTag("store-book-${book.id}")) {
                    StoreCover(book, Modifier.width(76.dp).height(106.dp))
                    Text(book.title, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                        color = StoreInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                    Text(book.author, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = StoreMuted,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        } else section.books.take(4).forEach { book -> StoreBookRow(book, onOpen) }
    }
}

@Composable
private fun StoreBookRow(book: NovelBook, onOpen: (NovelBook) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(book) }.testTag("store-book-${book.id}")
        .padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        StoreCover(book, Modifier.width(65.dp).height(92.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(book.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = StoreInk)
            if (book.description.isNotBlank()) Text(book.description, fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = StoreMuted)
            Text(listOf(book.author, book.category).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = StoreMuted)
        }
    }
}

@Composable
private fun StoreCover(book: NovelBook, modifier: Modifier) {
    NovelNetworkImage(book.coverUrl, book.coverReferer, "${book.title}封面", modifier.clip(RoundedCornerShape(9.dp)),
        requestHeaders = book.artworkHeaders, coverBook = book)
}

@Composable
private fun StoreCategoryPage(page: ReaderStoreCategoryPage, onMore: () -> Unit, onBack: () -> Unit,
    onRefresh: () -> Unit, onOpen: (NovelBook) -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color.White).testTag("store-category-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("store-category-back")) { Text("‹ 返回", color = StoreInk) }
            Text(page.category.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = StoreInk, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("刷新", fontSize = 12.sp, color = StoreAccent) }
        }
        HorizontalDivider(color = StoreSoft)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("store-category-list"), contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp)) {
            items(page.books, key = { it.id }) { book -> StoreBookRow(book, onOpen) }
            if (page.error != null) item(key = "store-category-error") { StoreInlineError(page.error, onMore) }
            item(key = "store-category-footer") {
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        page.loading -> { CircularProgressIndicator(Modifier.size(23.dp), color = StoreAccent, strokeWidth = 2.dp)
                            Text("正在加载…", fontSize = 12.sp, color = StoreMuted, modifier = Modifier.padding(top = 9.dp)) }
                        page.hasMore -> OutlinedButton(onClick = onMore, modifier = Modifier.testTag("store-load-more")) {
                            Text(if (page.error != null) "重试加载" else "加载更多", fontSize = 13.sp, color = StoreAccent)
                        }
                        page.books.isEmpty() -> Text("该分类暂时没有作品", fontSize = 13.sp, color = StoreMuted)
                        else -> Text("已加载全部作品", fontSize = 12.sp, color = StoreMuted)
                    }
                }
            }
        }
    }
}

@Composable private fun StoreInlineError(message: String, onRetry: () -> Unit) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Color(0xFFFFF0F0)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color(0xFFA25757), modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp, color = StoreAccent) }
    }
}

@Composable private fun StoreLoading(label: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(25.dp), color = StoreAccent, strokeWidth = 2.dp)
        Text(label, fontSize = 13.sp, color = StoreMuted, modifier = Modifier.padding(top = 13.dp))
    }
}

@Composable private fun StoreEmpty(label: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 14.sp, color = StoreMuted)
        TextButton(onClick = onRetry) { Text("重新加载", color = StoreAccent) }
    }
}
