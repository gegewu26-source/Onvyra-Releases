package com.constx.onvyra.ui.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.constx.onvyra.data.media.EnhancedVideoExtractor
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPlaybackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoExtractorRuntimeTest {
    @Test
    fun cancellingAnInFlightNativeExtractionStopsPromptly() = runBlocking {
        VideoFixtureServer().use { server ->
            val extractor = EnhancedVideoExtractor(InstrumentationRegistry.getInstrumentation().targetContext)
            // Initialize before measuring cancellation, so unpacking the runtime is not part of it.
            extractor.resolve(VideoLink("${server.url}/page", VideoPlatform.OTHER))
            val extraction = launch(Dispatchers.Default) {
                extractor.resolve(VideoLink("${server.url}/slow", VideoPlatform.OTHER))
            }
            withTimeout(8_000) { while (server.slowPageRequests.get() == 0) delay(20) }
            withTimeout(3_000) { extraction.cancelAndJoin() }
            assertTrue(extraction.isCancelled)
        }
    }

    @Test
    fun bundledAndroidPythonActuallyExtractsPlayableUrlWithoutDownloadingMedia() = runBlocking {
        VideoFixtureServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val source = EnhancedVideoExtractor(context).resolve(VideoLink("${server.url}/page", VideoPlatform.OTHER))
            assertEquals(VideoPlaybackKind.DIRECT, source.kind)
            assertEquals("${server.url}/clip.mp4", source.url)
            // version(context) is the wrapper's optional updater cache, not the executed script.
            val version = YoutubeDL.execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim()
            assertEquals("2026.10.26", version)
        }
    }
}
