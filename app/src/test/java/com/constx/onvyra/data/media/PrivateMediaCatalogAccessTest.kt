package com.constx.onvyra.data.media

import com.constx.onvyra.security.PrivateMediaAccess
import com.constx.onvyra.security.PrivateMediaAccessDeniedException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateMediaCatalogAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun lock() { PrivateMediaAccess.installVerifier { false } }
    @After fun restore() { PrivateMediaAccess.installVerifier { false } }

    @Test fun defaultPoolHidesPrivateIdsAndForgedIdsDoNotMakePrivateHostsAvailable() {
        val catalog = MovieCatalogClient(OkHttpClient())
        assertEquals(setOf("wsyzy", "haohua"), catalog.builtInSourceIds())
        val forged = MovieCatalogClient(OkHttpClient(), listOf(
            MovieCatalogSource("ordinary", "Ordinary", "https://example.test/api"),
            MovieCatalogSource("forged", "Private", "https://missav-primary.private-media.invalid/api"),
            MovieCatalogSource("forged_suggest", "Private", "https://example.test/api", "https://hanime1-primary.private-media.invalid/search"),
        ))
        assertEquals(setOf("ordinary"), forged.sourceIdsForSearch())
        val item = MovieCatalogItem("1", "Fixture", sourceId = "ordinary", detailUrl = "https://example.test/detail",
            lines = listOf(MoviePlaybackLine("main", listOf(MovieEpisode("1", "https://supjav-primary.private-media.invalid/zh/1.html")))))
        assertFalse(isAllowedMovieCatalogItem(item))
    }

    @Test fun previouslyRegisteredPrivateSourceIsHiddenImmediatelyWhenGrantExpires() {
        var allowed = true
        PrivateMediaAccess.installVerifier { allowed }
        val registry = MovieCatalogSourceRegistry(temporary.newFolder())
        val api = "https://missav-primary.private-media.invalid/api.php/provide/vod/"
        val source = MovieCatalogSource(discoveredMovieSourceId(api), "Private", api)
        registry.register(listOf(source))
        val catalog = MovieCatalogClient(OkHttpClient(), emptyList(), extraSources = { registry.all() })
        assertEquals(setOf(source.id), catalog.sourceIdsForSearch())
        allowed = false
        assertTrue(registry.all().isEmpty())
        assertTrue(catalog.sourceIdsForSearch().isEmpty())
        allowed = true
        assertEquals(listOf(source), registry.all())
    }

    @Test fun closedPrivateOperationsNeverStartNetworkRequests() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { requests++; error("Network must remain unused") }.build()
        val client = ExternalVideoSiteClient(http)
        val operations: List<suspend () -> Unit> = listOf(
            { client.search("missav", "fixture"); Unit },
            { client.getItem("jable", "fixture"); Unit },
            { client.resolveVideo("https://supjav-primary.private-media.invalid/zh/1.html"); Unit },
        )
        operations.forEach { operation ->
            try { operation(); fail("Closed private operation was accepted") }
            catch (_: PrivateMediaAccessDeniedException) { }
        }
        assertEquals(0, requests)
    }

    @Test fun expiryDuringResponseDoesNotRetryOtherMirrorsOrCrossSiteFallback() = runBlocking {
        var allowed = true
        var requests = 0
        PrivateMediaAccess.installVerifier { allowed }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            allowed = false
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html>fixture</html>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        try {
            ExternalVideoSiteClient(http).resolveVideo("https://jable-mirror.private-media.invalid/videos/fixture-123/")
            fail("Expired grant returned a result")
        } catch (_: PrivateMediaAccessDeniedException) { }
        assertEquals(1, requests)
    }
}
