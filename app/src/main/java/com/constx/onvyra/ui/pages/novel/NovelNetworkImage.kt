package com.constx.onvyra.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.HttpException
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.getKoin

/** All source images use their source page as Referer, including comic slices. */
@Composable
internal fun NovelNetworkImage(
    url: String,
    referer: String,
    label: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onImageSize: ((Int, Int) -> Unit)? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    coverBook: NovelBook? = null,
    imageLoader: ImageLoader? = null,
    coverResolver: NovelCoverResolver? = null,
) {
    val context = LocalContext.current
    val loader = imageLoader ?: remember(context) {
        runCatching { getKoin().getOrNull<ImageLoader>(named("readerImageLoader")) }.getOrNull()
            ?: SingletonImageLoader.get(context)
    }
    val resolver = coverResolver ?: remember(context) {
        runCatching { getKoin().getOrNull<NovelCoverResolver>() }.getOrNull()
    }
    var resolvedCover by remember(url, coverBook?.id) { mutableStateOf<ResolvedCover?>(null) }
    var attempted by remember(url, coverBook?.id) { mutableStateOf(emptySet<String>()) }
    val imageReferer = resolvedCover?.referer ?: referer.ifBlank { coverBook?.detailUrl.orEmpty() }
    val originalImageUrl = normalizedReaderImageUrl(url, imageReferer)
    // A valid HTTP response containing the source's known "no cover" bitmap is
    // still missing artwork; never let its successful decode block enrichment.
    val imageUrl = resolvedCover?.url ?: originalImageUrl.takeUnless { normalized ->
        coverBook != null && NovelCoverResolver.needsCover(coverBook.copy(coverUrl = normalized))
    }.orEmpty()
    // Source cookies and authentication headers belong only to the original
    // source image, never a separately matched publisher CDN image.
    val imageHeaders = resolvedCover?.requestHeaders ?: requestHeaders
    var retry by remember(url, coverBook?.id) { mutableIntStateOf(0) }
    var failed by remember(imageUrl, retry) { mutableStateOf(false) }
    var loaded by remember(imageUrl, retry) { mutableStateOf(false) }
    val candidateUrls = remember(coverBook?.sources, coverBook?.title, coverBook?.author) {
        coverBook?.let { book -> book.sources.map { it.asBook() }
            .filter { ReaderBookIdentity.matches(book, it) && !NovelCoverResolver.needsCover(it) }
            .map { it.coverUrl }.distinct() }.orEmpty()
    }
    LaunchedEffect(imageUrl, failed, coverBook?.title, coverBook?.author, retry, candidateUrls) {
        val book = coverBook ?: return@LaunchedEffect
        val newCandidate = candidateUrls.any { it != imageUrl && it !in attempted }
        if (resolver == null || book.isComic || loaded || (attempted.size >= 3 && !newCandidate)) return@LaunchedEffect
        if (!failed && !NovelCoverResolver.needsCover(book)) return@LaunchedEffect
        // A slower source can add valid artwork after the first cover has already failed.
        if (imageUrl in attempted && !newCandidate) return@LaunchedEffect
        attempted = attempted + imageUrl
        val replacement = resolver.resolve(book, failedUrl = imageUrl.takeIf { failed }) ?: return@LaunchedEffect
        if (replacement.url != imageUrl) resolvedCover = replacement
    }
    val imageSizeCallback by rememberUpdatedState(onImageSize)
    val retryImage: () -> Unit = {
        coverBook?.let { resolver?.retry(it, attempted + imageUrl) }
        attempted = emptySet()
        resolvedCover = null
        retry++
    }
    val request = remember(imageUrl, imageReferer, retry, imageHeaders) {
        val headers = NetworkHeaders.Builder()
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
            .apply { if (imageReferer.isNotBlank()) set("Referer", imageReferer) }
            .apply { imageHeaders.forEach { (name, value) -> set(name, value) } }
            .build()
        ImageRequest.Builder(context).data(imageUrl).httpHeaders(headers).apply {
            if (retry > 0) {
                // A 200 response can contain an expired proxy/error page. Decode
                // failure must not make every explicit retry read the same bytes.
                diskCachePolicy(CachePolicy.WRITE_ONLY)
                memoryCachePolicy(CachePolicy.WRITE_ONLY)
            }
        }.build()
    }
    if (imageUrl.isBlank()) {
        val canRetry = resolver != null && coverBook != null && !coverBook.isComic &&
            coverBook.title.isNotBlank() && coverBook.author.isNotBlank()
        Box(modifier.background(Color(0xFFEDE8E3))
            .then(if (canRetry) Modifier.clickable(onClick = retryImage) else Modifier).semantics {
            contentDescription = label; stateDescription = "暂无封面"
        }, contentAlignment = Alignment.Center) {
            Text(if (canRetry) "暂无封面\n点击重试" else "暂无封面", color = Color(0xFF81776E), fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
        return
    }
    // AsyncImage avoids subcomposition and a continuously animated spinner for every
    // visible cover. All visible images share one reader-specific loader and cache.
    Box(modifier.background(Color(0xFFF1EEEA)).semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = if (loaded) "图片已加载" else if (failed) "图片加载失败" else "图片加载中"
    }, contentAlignment = Alignment.Center) {
        if (!loaded) Text(if (failed) "图片暂未加载\n点击重试" else "书",
            color = Color(0xFF9A9187), fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp))
        key(imageUrl, retry) {
            AsyncImage(model = request, imageLoader = loader,
                contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = {
                    loaded = true; failed = false
                    imageSizeCallback?.invoke(it.result.image.width, it.result.image.height)
                },
                onError = { error ->
                    // Refresh once before rejecting a real cover URL: the disk
                    // entry may be an old HTTP-200 error page. HTTP status errors
                    // use the existing fallback immediately; comic pages keep
                    // their existing download behavior.
                    if (coverBook != null && !coverBook.isComic && retry == 0 &&
                        error.result.throwable !is HttpException) retry++
                    else failed = true
                },
            )
        }
        if (failed) {
            Box(Modifier.fillMaxSize().clickable(onClick = retryImage))
        }
    }
}

/** Resolve source-relative and protocol-relative URLs before passing them to Coil. */
internal fun normalizedReaderImageUrl(raw: String, referer: String): String {
    val value = raw.trim()
    if (value.isBlank()) return ""
    if (value.startsWith("data:image/")) return value
    if (value.startsWith("//")) return "https:$value"
    return value.toHttpUrlOrNull()?.toString()
        ?: referer.toHttpUrlOrNull()?.resolve(value)?.toString().orEmpty()
}
