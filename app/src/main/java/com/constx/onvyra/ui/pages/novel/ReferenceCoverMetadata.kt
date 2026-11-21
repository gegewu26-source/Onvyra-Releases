package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

/** The two optional metadata providers configured in the user's reference APK coverRule.json. */
internal suspend fun referenceCoverMetadata(book: NovelBook, rejected: (String) -> Boolean,
    fetch: suspend (Request) -> Pair<String, String>): ResolvedCover? = coroutineScope {
    val workers = listOf("https://pre-api.tuishujun.com/api/searchBook", "https://m.ypshuo.com/api/novel/search").mapIndexed { index, endpoint ->
        async {
            try {
                val query = book.title.trim().let {
                    if (book.sourceId == NovelSourceEngine.SOURCE_SKIN && it.length >= 10 && it.all { ch -> ch in '\u4e00'..'\u9fff' }) it.take(6) else it
                }
                val url = endpoint.toHttpUrl().newBuilder()
                    .addQueryParameter(if (index == 0) "search_value" else "keyword", query)
                    .addQueryParameter("page", "1")
                    .apply { if (index == 0) addQueryParameter("pageSize", "20") else addQueryParameter("searchType", "1") }.build()
                val (body, page) = fetch(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile").build())
                val rows = ((Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject)?.get("data") as? JsonArray
                rows.orEmpty().asSequence().mapNotNull { it as? JsonObject }.mapNotNull { row ->
                    fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val title = text(if (index == 0) "title" else "novel_name")
                    val author = text(if (index == 0) "author_nickname" else "author_name")
                    fun authorKey(value: String) = ReaderBookIdentity.normalize(value.trim()
                        .removePrefix("作者：").removePrefix("作者:")
                        .replace(Regex("[，,]\\s*(?:分类|状态|字数)[:：].*$"), "").removeSuffix(" 著"))
                    val sameAuthor = authorKey(author) == authorKey(book.author)
                    val sameTitle = referenceArtworkTitleMatches(book, title)
                    val image = text(if (index == 0) "cover" else "novel_img").toHttpUrlOrNull()
                    if (!sameAuthor || !sameTitle || image == null || rejected(image.toString())) null
                    else ResolvedCover(image.toString(), page, page)
                }.firstOrNull()
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: IOException) { null }
              catch (_: SerializationException) { null }
        }
    }
    try {
        // The reference APK fetches concurrently but prefers provider 1's exact
        // match. A fast backup response must not displace valid primary artwork.
        workers.forEach { worker -> worker.await()?.let { return@coroutineScope it } }
        null
    } finally { workers.forEach { it.cancel() } }
}

/** A cover-only compatibility rule for Skin index titles polluted with trailing character names.
 * Unlike the reference APK's unrestricted contains(), reject volumes, sequels and adaptations.
 * Reading routes, catalog matching and source rotation keep their strict full identities.
 */
internal fun referenceArtworkTitleMatches(book: NovelBook, actual: String): Boolean {
    val expected = ReaderBookIdentity.normalize(book.title)
    val title = ReaderBookIdentity.normalize(actual)
    if (title == expected) return true
    if (book.sourceId != NovelSourceEngine.SOURCE_SKIN || title.length < 6 || !expected.startsWith(title)) return false
    val suffix = expected.removePrefix(title)
    return suffix.length in 2..8 && suffix.all { it in '\u4e00'..'\u9fff' } &&
        !Regex("第|部|卷|续|传|篇|同人|漫画|下载|全集|全文|番外|完结").containsMatchIn(suffix)
}
