package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.security.PrivateMediaAccess
import com.constx.onvyra.security.PrivateMediaAccessDeniedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrivateMediaPreviewAccessTest {
    @Before fun closeInitially() { PrivateMediaAccess.installVerifier { false } }
    @After fun closeFinally() { PrivateMediaAccess.installVerifier { false } }

    @Test fun cachedGenericCdnCannotBypassItsPrivateOriginalPageAfterExpiry() {
        var authorized = true
        PrivateMediaAccess.installVerifier { authorized }
        val cache = ResolvedVideoPreviewCache()
        val source = VideoPlaybackSource("https://cdn.example.org/video.m3u8", VideoPlaybackKind.DIRECT)
        val key = cache.put(source, "https://missav-primary.private-media.invalid/cn/fixture-123")
        assertEquals(source, cache.get(key))
        authorized = false
        assertNull(cache.get(key))
        PrivateMediaAccess.installVerifier { true }
        assertNull("A revoked cache entry is removed rather than reactivated", cache.get(key))
    }

    @Test fun blockedResolveDoesNotRunTheResolverOrReturnThePrivateUrl() = runBlocking {
        var called = false
        val tool = createVideoResolveTool { called = true; error("Must not execute") }
        val result = tool.execute(buildJsonObject { put("url", "https://jable-mirror.private-media.invalid/videos/fixture-123/") })
        assertFalse(called)
        val text = (result.single() as UIMessagePart.Text).text
        assertTrue(text.contains("unavailable"))
        assertFalse(text.contains("jable"))
    }

    @Test fun privateStreamCannotBeStoredWithAnOrdinaryOriginalUrl() {
        val source = VideoPlaybackSource("https://missav-stream.private-media.invalid/fixture/index.m3u8", VideoPlaybackKind.DIRECT)
        assertTrue(runCatching { ResolvedVideoPreviewCache().put(source, "https://example.org/video") }
            .exceptionOrNull() is PrivateMediaAccessDeniedException)
    }
}
