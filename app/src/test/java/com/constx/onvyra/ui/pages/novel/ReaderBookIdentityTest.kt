package com.constx.onvyra.ui.pages.novel

import org.junit.Assert.*
import org.junit.Test

class ReaderBookIdentityTest {
    @Test fun explicitPublisherCanonicalTitlePreservesDisplayMetadataAndAuthorIsolation() {
        val catalog = book("bookstore_catalog", "新海报名称", "本书作者").copy(canonicalTitle = "原始作品")
        val original = book("actual", "原始作品", "作者：本书作者")
        assertEquals("原始作品", ReaderBookIdentity.searchTitle(catalog))
        assertTrue(ReaderBookIdentity.matches(catalog, original))
        assertFalse(ReaderBookIdentity.matches(catalog, original.copy(author = "另一位作者")))
        assertFalse(ReaderBookIdentity.matches(catalog.copy(author = "另一位作者"), original))
        assertFalse(ReaderBookIdentity.matches(catalog, original.copy(title = "原始作品第二部")))
        assertFalse(ReaderBookIdentity.matches(catalog, original.copy(isAudio = true)))
        assertEquals("新海报名称", catalog.title)
        assertEquals(catalog.canonicalTitle, catalog.asSource().asBook().canonicalTitle)
    }

    private fun book(source: String, title: String = "万古神帝", author: String = "飞天鱼") =
        NovelBook("${source}_book", title, author, sourceId = source,
            detailUrl = "https://$source.example/book/1")

    @Test fun catalogAliasUsesTheRealSearchTitleWithoutChangingDisplayMetadata() {
        // Real storefront metadata uses this explicit alias; search sources use the base title.
        val catalog = book("catalog", "万古神帝（又名：盖世神帝）")
        val actual = book("actual", author = "作者：飞天鱼")
        assertEquals("万古神帝", ReaderBookIdentity.searchTitle(catalog.title))
        assertTrue(ReaderBookIdentity.matches(catalog, actual))
        assertEquals("万古神帝（又名：盖世神帝）", catalog.title)
        val merged = ReaderBookIdentity.merge(catalog.title, listOf(catalog, actual)).single()
        assertEquals(catalog.title, merged.title)
        assertEquals(setOf("catalog", "actual"), merged.sources.map { it.sourceId }.toSet())
    }

    @Test fun originalNameSuffixAlsoKeepsThePrimaryTitleAndAuthorIdentity() {
        val labeled = book("catalog", "蛊真人 (原名: 大爱仙尊)", "蛊真人")
        val actual = book("actual", "蛊真人", "蛊真人")
        assertEquals("蛊真人", ReaderBookIdentity.searchTitle(labeled.title))
        assertTrue(ReaderBookIdentity.matches(labeled, actual))
        assertFalse(ReaderBookIdentity.matches(labeled, actual.copy(title = "大爱仙尊")))
    }

    @Test fun descriptiveParenthesesAndSimilarTitlesDoNotBecomeAlternativeRoutes() {
        val original = book("one")
        listOf("万古神帝（上）", "万古神帝（修订版）", "万古神帝（盖世神帝）",
            "万古神帝（又名： ）", "万古神帝飞天鱼", "万古神帝：光明神帝").forEach { title ->
            assertFalse(title, ReaderBookIdentity.matches(original, book("other", title)))
        }
        assertEquals("(又名:盖世神帝)", ReaderBookIdentity.searchTitle("（又名：盖世神帝）"))
    }

    @Test fun aliasesStillRequireTheSameKnownAuthorAndMediaType() {
        val catalog = book("catalog", "万古神帝（又名：盖世神帝）")
        assertFalse(ReaderBookIdentity.matches(catalog, book("other", author = "另一位作者")))
        assertFalse(ReaderBookIdentity.matches(catalog, book("missing", author = "")))
        assertFalse(ReaderBookIdentity.matches(catalog, book("comic").copy(isComic = true)))
        assertFalse(ReaderBookIdentity.matches(catalog, book("audio").copy(isAudio = true)))
        assertTrue(ReaderBookIdentity.sameTitle(catalog, book("missing", author = "")))
        assertFalse(ReaderBookIdentity.sameTitle(catalog, book("comic").copy(isComic = true)))
        assertFalse(ReaderBookIdentity.sameTitle(catalog, book("similar", "万古神帝飞天鱼")))
    }

    @Test fun verifiedMingRetitlingUsesCanonicalTitleOnlyForTheKnownAuthorAndNovelKind() {
        val catalog = book("bookstore_catalog", "大明铁血监国：我爹朱棣，杀穿瓦剌", "朕闻上古")
        val canonical = book("actual", "大明：家父永乐，永镇山河", "朕闻上古")
        assertEquals("大明：家父永乐，永镇山河", ReaderBookIdentity.searchTitle(catalog))
        assertTrue(ReaderBookIdentity.matches(catalog, canonical))
        assertFalse(ReaderBookIdentity.matches(catalog, canonical.copy(author = "另一位作者")))
        assertFalse(ReaderBookIdentity.matches(catalog.copy(author = "另一位作者"), canonical))
        assertFalse(ReaderBookIdentity.matches(catalog.copy(isAudio = true), canonical.copy(isAudio = true)))
        assertFalse(ReaderBookIdentity.matches(catalog, canonical.copy(title = "大明：家父永乐，永镇山河续集")))
        assertEquals("大明铁血监国：我爹朱棣，杀穿瓦剌", catalog.title)
    }

    @Test fun authorLabelsUseTheSameStrictNormalizationAsProductionWorkIdentity() {
        assertEquals("朕闻上古", ReaderBookIdentity.normalizeAuthor("作者:朕闻上古"))
        assertEquals("朕闻上古", ReaderBookIdentity.normalizeAuthor(" 作者：朕闻上古 著 "))
        val catalog = book("catalog", "大明铁血监国：我爹朱棣，杀穿瓦剌", "朕闻上古")
        val canonical = book("actual", "大明：家父永乐，永镇山河", "作者:朕闻上古")
        assertTrue(ReaderBookIdentity.matches(catalog, canonical))
        assertFalse(ReaderBookIdentity.matches(catalog, canonical.copy(author = "作者:朕闻上古续作")))
        assertFalse(ReaderBookIdentity.matches(catalog, canonical.copy(author = "作者:另一位作者")))
        assertFalse(ReaderBookIdentity.matches(catalog, canonical.copy(author = "")))
    }
}
