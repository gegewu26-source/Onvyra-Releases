package com.constx.onvyra.data.media

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MovieCatalogSourceRegistryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun registryPersistsStableIdsAndMostRecentlyDiscoveredSourcesFirst() {
        val directory = temporary.newFolder()
        var now = 10L
        val registry = MovieCatalogSourceRegistry(directory) { now }
        val first = source("first")
        val second = source("second")
        registry.register(listOf(first, second))
        now = 20
        registry.register(listOf(second.copy(name = "发现·更新")))
        assertEquals(listOf(second.id, first.id), registry.all().map { it.id })
        val reloaded = MovieCatalogSourceRegistry(directory) { now }
        assertEquals(registry.all(), reloaded.all())
        assertEquals("发现·更新", reloaded.all().first().name)
    }

    @Test fun invalidIdentityUnsafeUrlsAndExecutableFilesCannotEnterRegistry() {
        val registry = MovieCatalogSourceRegistry(temporary.newFolder())
        val good = source("good")
        registry.register(listOf(good, good.copy(id = "wsyzy"), good.copy(apiUrl = "https://user:password@example.test/api"),
            good.copy(apiUrl = "http://127.0.0.1/api"), good.copy(apiUrl = "file:///private"),
            good.copy(apiUrl = "https://example.test/provider.jar"), good.copy(apiUrl = "https://example.test/provider.js")))
        assertEquals(listOf(good), registry.all())
    }

    @Test fun canonicalRequestParametersDoNotCreateDuplicateProvidersAndRegistryIsBounded() {
        val canonical = canonicalDiscoveredMovieApi("https://example.test/api.php/provide/vod/?token=public&ac=list&wd=film&ids=3&pg=1#fragment")!!
        assertEquals("https://example.test/api.php/provide/vod/?token=public", canonical)
        val registry = MovieCatalogSourceRegistry(temporary.newFolder())
        val sources = (0..140).map { source("provider$it") }
        registry.register(sources + sources.first())
        assertEquals(128, registry.all().size)
        assertEquals(128, registry.all().map { it.id }.distinct().size)
        assertTrue(registry.all().all { it.id.matches(Regex("discovered_[0-9a-f]{16}")) })
    }

    @Test fun successfulAndFailedDiscoveryCachesHaveDifferentPersistedLifetimes() {
        val directory = temporary.newFolder()
        var now = 1_000L
        val registry = MovieCatalogSourceRegistry(directory) { now }
        assertFalse(registry.isDiscoveryCacheFresh())
        registry.recordDiscoveryAttempt(false, emptyList())
        now += TimeUnit.MINUTES.toMillis(4)
        assertTrue(MovieCatalogSourceRegistry(directory) { now }.isDiscoveryCacheFresh())
        now += TimeUnit.MINUTES.toMillis(1)
        assertFalse(registry.isDiscoveryCacheFresh())
        registry.recordDiscoveryAttempt(true, listOf("https://example.test/config.json", "file:///private"))
        now += TimeUnit.HOURS.toMillis(5)
        val restored = MovieCatalogSourceRegistry(directory) { now }
        assertTrue(restored.isDiscoveryCacheFresh())
        assertEquals(listOf("https://example.test/config.json"), restored.recentConfigUrls())
        now += TimeUnit.HOURS.toMillis(1)
        assertFalse(restored.isDiscoveryCacheFresh())
    }

    @Test fun malformedOversizedOrMismatchedSavedDataDoesNotCrashOrTrustBadEntries() {
        val directory = temporary.newFolder()
        val file = File(directory, "sources.json")
        file.writeText("not JSON")
        assertTrue(MovieCatalogSourceRegistry(directory).all().isEmpty())
        file.writeText("x".repeat(512 * 1024 + 1))
        assertTrue(MovieCatalogSourceRegistry(directory).all().isEmpty())
        file.writeText("""{"sources":[{"id":"discovered_0000000000000000","name":"Fake","api_url":"https://example.test/api"}]}""")
        assertTrue(MovieCatalogSourceRegistry(directory).all().isEmpty())
    }

    private fun source(name: String): MovieCatalogSource {
        val api = "https://$name.example.test/api.php/provide/vod/"
        return MovieCatalogSource(discoveredMovieSourceId(api), "发现·$name", api)
    }
}
