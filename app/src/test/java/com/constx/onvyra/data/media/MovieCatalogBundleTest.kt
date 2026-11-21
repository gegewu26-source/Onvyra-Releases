package com.constx.onvyra.data.media

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MovieCatalogBundleTest {
    @Test fun scanBudgetsDoNotSilentlyTruncateTheInventoryOfABiggerManifest() {
        val manifest = buildJsonObject {
            put("base_lines", buildJsonArray { repeat(100) { index -> add(buildJsonObject {
                put("name", "root$index"); put("url", "https://config.example.org/$index.json")
            }) } })
            put("search_pages", buildJsonArray { repeat(40) { add(JsonPrimitive("https://page.example.org/$it")) } })
        }
        val (roots, pages) = decodeBundledMovieDiscoverySources(manifest.toString())
        assertEquals(100, roots.size)
        assertEquals(40, pages.size)
    }

    @Test fun runtimeDecoderLoadsTheActualBundledConfigRootsAndDiscoveryPages() {
        val file = listOf(File("src/main/assets/movie_catalog/sources.json"), File("app/src/main/assets/movie_catalog/sources.json"))
            .first { it.isFile }
        val content = file.readText()
        val raw = Json.parseToJsonElement(content).jsonObject
        val (roots, pages) = decodeBundledMovieDiscoverySources(content)
        assertEquals(raw.getValue("base_lines").jsonArray.size, roots.size)
        assertEquals(raw.getValue("search_pages").jsonArray.size, pages.size)
        assertTrue("Configuration roots must not be replaced with the first three playback catalogs", roots.size > 3)
        assertEquals(roots.size, roots.map { it.url }.distinct().size)
        assertEquals(pages.size, pages.distinct().size)
        assertTrue(roots.all { safeDiscoveryConfigUrl(it.url) != null })
        assertTrue(pages.all { safeDiscoveryConfigUrl(it) != null })
    }
}
