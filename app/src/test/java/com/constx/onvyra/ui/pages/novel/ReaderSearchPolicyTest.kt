package com.constx.onvyra.ui.pages.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReaderSearchPolicyTest {
    @Test fun longChineseQueriesKeepRelatedTitlesAndPutTheExactWorkFirst() {
        val query = "我有一群地球人"
        assertNotNull(ReaderSearchPolicy.matchRank(query, "我有一群地球玩家", "第七个魔方"))
        assertNotNull(ReaderSearchPolicy.matchRank(query, "地球人实在太凶猛了", "卧牛真人"))
        assertNotNull(ReaderSearchPolicy.matchRank(query, "我有一群诡异玩家", "清骨"))
        assertNull(ReaderSearchPolicy.matchRank(query, "猎魔人：我有一只红葫芦", "作者"))
        assertNull(ReaderSearchPolicy.matchRank(query, "我有一个江湖群", "作者"))
        assertNull(ReaderSearchPolicy.matchRank(query, "凡人修仙传", "忘语"))
        assertEquals(listOf("exact", "near", "related"), ReaderSearchPolicy.ranked(query, listOf(
            book("related", "地球人实在太凶猛了"), book("near", "我有一群地球玩家"), book("exact", query)
        )).map { it.id })
    }

    @Test fun shortQueriesStayLiteralAndExactAuthorStillBeatsRelatedTitles() {
        assertNull(ReaderSearchPolicy.matchRank("人", "我有一群地球玩家", "第七个魔方"))
        assertEquals(200, ReaderSearchPolicy.matchRank("地球", "我有一群地球玩家", "作者"))
        assertEquals(300, ReaderSearchPolicy.matchRank("我有一群地球人", "另一部作品", "我有一群地球人"))
    }

    private fun book(id: String, title: String, cover: String = "", chapters: Int = 0) =
        NovelBook(id, title, detailUrl = "https://example.invalid/$id", sourceId = "fixture", coverUrl = cover, chapterCount = chapters)

    @Test fun exactTitlePrecedesDerivativeWithCoverOrMoreChapters() {
        val received = listOf(book("fan", "我在诡秘之主当演员", "https://example.invalid/cover.jpg", 9999),
            book("main", "诡秘之主", chapters = 1435), book("prefix", "诡秘之主之旅人", chapters = 2000))
        assertEquals(listOf("main", "prefix", "fan"), ReaderSearchPolicy.ranked("诡秘之主", received).map { it.id })
    }

    @Test fun rankingKeepsTheFirstDuplicateAndUsesStableTieOrdering() {
        val first = book("tie-z", "书名之甲", chapters = 12)
        val second = book("tie-a", "书名之乙", chapters = 12)
        val moreChapters = book("chapters", "书名之丙", chapters = 20)
        val shorter = book("shorter", "书名丁", chapters = 1)
        val moreSources = book("sources", "书名和更长的标题").copy(
            sources = listOf(book("other", "书名和更长的标题").copy(sourceId = "another").asSource())
        )
        val duplicate = first.copy(title = "书名", chapterCount = 999)

        val ranked = ReaderSearchPolicy.ranked("《书名》",
            listOf(first, second, duplicate, moreChapters, shorter, moreSources))

        assertEquals(listOf(moreSources, shorter, moreChapters, first, second), ranked)
    }

    @Test fun mediaKindsAreSeparateEvenForTheSameTitleAndId() {
        val novel = book("shared", "相同书名")
        assertEquals(listOf(0, 2, 1), listOf(novel, novel.copy(isComic = true), novel.copy(isAudio = true))
            .map(ReaderSearchPolicy::kind))
    }

    @Test fun matchingChapterPreservesUniqueExactTitleBeforeTryingRenumberedVariants() {
        val chapters = listOf(NovelChapter(0, "第十一章：故人来", "https://a.example/11"),
            NovelChapter(1, "第十二章 独行", "https://a.example/12"))
        assertEquals(1, ReaderSearchPolicy.matchChapter(chapters, " 第十二章  独行 "))
        assertEquals(0, ReaderSearchPolicy.matchChapter(chapters, "  第10章 故人来  "))
        assertEquals(0, ReaderSearchPolicy.matchChapter(chapters, "第一百章、故人来"))
    }

    @Test fun ambiguousOrMissingChapterTitlesNeverGuessByPosition() {
        val chapters = listOf(NovelChapter(0, "第1章 归来", "https://a.example/1"),
            NovelChapter(1, "第2章 归来", "https://a.example/2"),
            NovelChapter(2, "第3章 雨", "https://a.example/3"))
        assertNull(ReaderSearchPolicy.matchChapter(chapters, "第99章 归来"))
        assertNull(ReaderSearchPolicy.matchChapter(chapters, "第99章 雨"))
        assertNull(ReaderSearchPolicy.matchChapter(chapters, "第99章"))
        assertNull(ReaderSearchPolicy.matchChapter(chapters, ""))
        assertNull(ReaderSearchPolicy.matchChapter(chapters, "第4章 不存在"))
    }

    @Test fun duplicateExactTitlesAndMeaningfulPunctuationAreChapterCollisions() {
        val duplicate = listOf(NovelChapter(0, "第1章 归来", "https://a.example/1"),
            NovelChapter(1, "第1章 归来", "https://a.example/duplicate"))
        assertNull(ReaderSearchPolicy.matchChapter(duplicate, "第1章 归来"))
        val punctuated = listOf(NovelChapter(0, "第1章 甲·乙", "https://a.example/1"))
        assertNull(ReaderSearchPolicy.matchChapter(punctuated, "第99章 甲乙"))
    }
}
