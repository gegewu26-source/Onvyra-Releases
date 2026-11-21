package com.constx.onvyra.ui.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import com.constx.onvyra.data.media.MovieCatalogSourceDiscovery
import com.constx.onvyra.data.media.MovieCatalogSourceRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional current-network discovery check; decoded movie playback has separate tests. */
@RunWith(AndroidJUnit4::class)
class MovieCatalogDiscoveryLiveTest {
    @Test fun suppliedSeedBundleDiscoversCompatibleHttpCatalogsOnTheCurrentNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMovieDiscovery") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File.createTempFile("movie-discovery-live-", "", context.cacheDir).apply { delete(); mkdirs() }
        try {
            val registry = MovieCatalogSourceRegistry(directory)
            val started = System.nanoTime()
            val sources = MovieCatalogSourceDiscovery(context, OkHttpClient(), registry).discover(force = true)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("MOVIE_DISCOVERY_LIVE_COMPATIBLE_APIS=${sources.size}; ELAPSED_MS=$elapsed; SEARCH_AND_PLAY_VERIFIED=false")
            assertTrue("The provided bundle supplied no usable HTTP catalog definitions on this network", sources.isNotEmpty())
            assertTrue(sources.size <= 128)
        } finally { directory.deleteRecursively() }
    }
}
