package com.constx.onvyra.ui.pages.novel

/** Types are kept separate from source names and from the currently visible tab. */
internal object ReaderSearchPolicy {
    private val searchSeparators = Regex("[\\s　·•]")
    private val chapterPrefix = Regex("^(?:第)?[零〇一二三四五六七八九十百千万两\\d]+[章话回节卷]\\s*[:：、.．]?\\s*")

    /** Across sites chapter numbers can differ; never guess a saved chapter by index. */
    fun matchChapter(chapters: List<NovelChapter>, title: String): Int? {
        if (title.isBlank()) return null
        // Chapter identity keeps meaningful punctuation. Search ranking's looser
        // normalization is unsuitable here: "甲·乙" and "甲乙" can be two chapters.
        val exact = ReaderBookIdentity.normalize(title)
        val exactMatches = chapters.indices.filter { ReaderBookIdentity.normalize(chapters[it].title) == exact }
        if (exactMatches.isNotEmpty()) return exactMatches.singleOrNull()
        fun core(value: String) = ReaderBookIdentity.normalize(value).replace(chapterPrefix, "")
        val wanted = core(title)
        if (wanted.length < 2) return null
        val matches = chapters.indices.filter { core(chapters[it].title) == wanted }
        return matches.singleOrNull()
    }
    fun kind(book: NovelBook): Int = when { book.isComic -> 2; book.isAudio -> 1; else -> 0 }
    fun normalize(value: String): String = value.trim().lowercase().replace(searchSeparators, "")

    /** Search relevance is intentionally separate from exact work/chapter identity. */
    fun matchRank(query: String, title: String, author: String): Int? {
        val key = normalizeQuery(query)
        if (key.isEmpty()) return null
        return matchRankForTitle(key, normalize(title), author)
    }

    private fun normalizeQuery(query: String) = normalize(query).trim('《', '》')

    private fun matchRankForTitle(key: String, name: String, author: String): Int? {
        if (key.isEmpty()) return null
        when {
            name == key -> return 0
            name.startsWith(key) -> return 100
            name.contains(key) -> return 200
        }
        val writer = normalize(author)
        when {
            writer == key -> return 300
            writer.startsWith(key) -> return 400
            writer.contains(key) -> return 500
        }
        // A long title fragment can still identify related works when its last
        // word differs. Never broaden one-character searches into random books.
        if (key.length !in 4..160) return null
        fun han(text: String) = text.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val pairs = key.windowed(2).filter(::han).distinct()
        if (pairs.size < 3) return null
        val shared = pairs.count(name::contains)
        if (shared < 2) return null
        // For a long phrase, a generic opening such as "我有一" is too weak
        // by itself. A two-pair match must preserve its meaningful ending.
        if (key.length >= 6 && shared == 2) {
            val ending = key.takeLast(3)
            if (!han(ending) || !name.contains(ending)) return null
        }
        val consecutive = key.windowed(3).any { han(it) && name.contains(it) }
        if (!consecutive && (shared < 3 || shared * 5 < pairs.size * 3)) return null
        return 600 + (pairs.size - shared) * 100 / pairs.size
    }

    private class RankedBook(val book: NovelBook, val rank: Int, val titleLength: Int) {
        val sourceCount = book.sourceCount
    }

    fun ranked(query: String, books: List<NovelBook>): List<NovelBook> {
        val key = normalizeQuery(query)
        return books.distinctBy { it.sourceId + ":" + it.id }
            .map { book ->
                val title = normalize(book.title)
                RankedBook(book, matchRankForTitle(key, title, book.author) ?: Int.MAX_VALUE, title.length)
            }
            .sortedWith(
                compareBy<RankedBook> { it.rank }
                    .thenByDescending { it.sourceCount }
                    .thenBy { it.titleLength }
                    .thenByDescending { it.book.chapterCount }
            )
            .map { it.book }
    }
}
