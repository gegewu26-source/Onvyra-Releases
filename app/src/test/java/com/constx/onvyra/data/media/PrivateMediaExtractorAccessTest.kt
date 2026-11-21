package com.constx.onvyra.data.media

import com.constx.onvyra.security.PrivateMediaAccess
import com.constx.onvyra.security.PrivateMediaAccessDeniedException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrivateMediaExtractorAccessTest {
    @Before fun closeInitially() { PrivateMediaAccess.installVerifier { false } }
    @After fun closeFinally() { PrivateMediaAccess.installVerifier { false } }

    @Test fun metadataCannotHideItsPrivateOriginBehindAnOrdinaryCdn() {
        val json = """{"webpage_url":"https://missav-primary.private-media.invalid/cn/fixture-123","url":"https://cdn.example.org/video.mp4","ext":"mp4"}"""
        assertTrue(runCatching { parseYtDlpVideoInfo(json) }.exceptionOrNull() is PrivateMediaAccessDeniedException)
        PrivateMediaAccess.installVerifier { true }
        assertEquals("https://cdn.example.org/video.mp4", parseYtDlpVideoInfo(json).url)
    }

    @Test fun privateAudioAndRefererAreDeniedEvenWhenTheVideoAddressIsOrdinary() {
        val audio = """{"requested_formats":[{"url":"https://cdn.example.org/video.mp4","vcodec":"h264","acodec":"none"},{"url":"https://missav-stream.private-media.invalid/audio.m4a","vcodec":"none","acodec":"aac"}]}"""
        val referer = """{"url":"https://cdn.example.org/video.mp4","http_headers":{"Referer":"https://jable-mirror.private-media.invalid/videos/fixture-123/"}}"""
        listOf(audio, referer).forEach {
            assertTrue(runCatching { parseYtDlpVideoInfo(it) }.exceptionOrNull() is PrivateMediaAccessDeniedException)
        }
    }

    @Test fun redirectCannotRequestAProtectedDestinationWhileLocked() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture")
                .header("Location", "https://missav-primary.private-media.invalid/cn/fixture-123").body("".toResponseBody()).build()
        }.build()
        val link = parseVideoLink("https://v.douyin.com/Fixture/")!!
        val failure = runCatching { VideoLinkResolver(client).resolveLink(link) }.exceptionOrNull()
        assertTrue(failure is PrivateMediaAccessDeniedException)
        assertEquals("Only the ordinary initial URL is requested", 1, calls)
    }

    @Test fun aDirectPrivateStreamIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; error("Must not request") }.build()
        val link = parseVideoLink("https://missav-stream.private-media.invalid/fixture/video.m3u8")!!
        assertTrue(runCatching { VideoLinkResolver(client).verifyDirect(link) }.exceptionOrNull() is PrivateMediaAccessDeniedException)
        assertEquals(0, calls)
    }
}
