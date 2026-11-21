package com.constx.onvyra.data.media

import org.junit.Assert.*
import org.junit.Test

class MovieContentTypeTest {
    @Test fun commentaryMixesTrailersAndLiveActionAreNotAnimeEpisodes() {
        listOf("鬼灭之刃解说", "鬼灭之刃 混剪", "鬼灭之刃预告", "鬼灭之刃讲解").forEach { title ->
            assertFalse(isEligibleCatalogWork(MovieCatalogItem("1", title, category = "日韩动漫"), MovieContentType.ANIME))
        }
        assertFalse(isEligibleCatalogWork(MovieCatalogItem("1", "鬼灭之刃", category = "真人电视剧"), MovieContentType.ANIME))
        assertTrue(isEligibleCatalogWork(MovieCatalogItem("1", "鬼灭之刃 第1集", category = "日韩动漫"), MovieContentType.ANIME))
    }

    @Test fun unknownMetadataDoesNotBecomePositiveAnimeVerification() {
        assertFalse(verifiesMovieContentType(MovieContentType.ANIME, "鬼灭之刃 第1集"))
        assertTrue(verifiesMovieContentType(MovieContentType.ANIME, "日韩动漫"))
        assertTrue(isEligibleCatalogWork(MovieCatalogItem("1", "鬼灭之刃", category = null), MovieContentType.ANIME))
    }
}
