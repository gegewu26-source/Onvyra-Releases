package com.constx.onvyra.data.media

import com.constx.onvyra.security.PrivateMediaAccess
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the actual catalog-client -> external-site-client path without contacting a real site.
 * The interceptor only returns local HTML fixtures for the four private catalog hosts.
 */
class UnlockedExternalCatalogFlowTest {
    private var allowed = false

    @Before fun lock() {
        PrivateMediaAccess.installVerifier { allowed }
    }

    @After fun restore() {
        PrivateMediaAccess.installVerifier { false }
    }

    @Test fun sameCatalogClientStartsPrivateSearchOnlyAfterUnlockAndStopsAfterRelock() = runBlocking {
        val requests = AtomicInteger()
        val hosts = ConcurrentHashMap.newKeySet<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val host = chain.request().url.host
            requests.incrementAndGet()
            hosts += host
            val body = when (host) {
                "missav-primary.private-media.invalid" -> """
                    <div class="grid">
                      <div class="thumbnail group">
                        <a href="https://missav-primary.private-media.invalid/dm57/cn/jul-331"><img alt="Fixture title"></a>
                        <div class="my-2 text-sm truncate"><a class="text-secondary" href="https://missav-primary.private-media.invalid/dm57/cn/jul-331">JUL-331 Fixture title</a></div>
                      </div>
                    </div>
                """.trimIndent()
                "jable-primary.private-media.invalid", "jable-mirror.private-media.invalid" -> "<div id=\"list_videos_common_videos_list\"></div>"
                "supjav-primary.private-media.invalid" -> "<div class=\"posts\"><div class=\"post\"></div></div>"
                "hanime1-primary.private-media.invalid", "hanime1-mirror-1.private-media.invalid", "hanime1-mirror-2.private-media.invalid", "hanime1-alternate.private-media.invalid" ->
                    "<div class=\"horizontal-card\"></div>"
                else -> error("Unexpected host in offline fixture: $host")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }.build()
        val catalog = MovieCatalogClient(http, listOf(MovieCatalogSource(
            SITE_ID_MISSAV, "Fixture catalog",
            "https://${ExternalVideoEndpointConfig.host("missav_primary")}/",
        )))

        assertTrue(catalog.sourceIdsForSearch().isEmpty())
        try {
            catalog.search("JUL-331")
            error("A locked catalog must not return private results")
        } catch (_: MovieCatalogSearchException) {
            // Expected: no private source is eligible while locked.
        }
        assertEquals(0, requests.get())

        allowed = true
        val items = catalog.search("JUL-331")
        assertTrue("Unlock must enter the real external-site search handlers", requests.get() > 0)
        assertTrue(hosts.contains("missav-primary.private-media.invalid"))
        assertTrue(items.any { it.sourceId == SITE_ID_MISSAV && it.id == "jul-331" })

        val afterUnlock = requests.get()
        allowed = false
        assertTrue(catalog.sourceIdsForSearch().isEmpty())
        try {
            catalog.search("JUL-331")
            error("A relocked catalog must not return private results")
        } catch (_: MovieCatalogSearchException) {
            // Expected: the same client re-evaluates the grant before starting work.
        }
        assertEquals(afterUnlock, requests.get())
        assertFalse(PrivateMediaAccess.hasAccess())
    }
}
