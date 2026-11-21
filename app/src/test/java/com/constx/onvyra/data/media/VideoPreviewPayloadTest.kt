package com.constx.onvyra.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPreviewPayloadTest {
    private val valid = """{"type":"video_preview","status":"ready","playable":true,"original_url":"https://example.org/film","title":"Film","playback_id":"c2cbeb10-662e-4b3b-b305-687b69c8ea58"}"""

    @Test fun requestedWorkTypeSurvivesToolPayloadParsingAndLegacyPayloadsStayCompatible() {
        assertEquals(MovieContentType.ANIME, parseVideoPreviewPayload(valid.dropLast(1) + ",\"requested_content_type\":\"anime\"}")!!.requestedContentType)
        assertEquals(MovieContentType.GENERAL_VIDEO, parseVideoPreviewPayload(valid)!!.requestedContentType)
        assertEquals(MovieContentType.GENERAL_VIDEO, parseVideoPreviewPayload(valid.dropLast(1) + ",\"requested_content_type\":\"unknown\"}")!!.requestedContentType)
    }

    @Test fun successfulToolMetadataIsRecognized() {
        val payload = parseVideoPreviewPayload(valid)!!
        assertEquals("https://example.org/film", payload.originalUrl)
        assertEquals("Film", payload.title)
    }

    @Test fun incompleteFailedOrUnrelatedResultsHaveNoPlayer() {
        listOf("{}", "not JSON", valid.replace("ready", "error"), valid.replace("true", "false"),
            valid.replace("video_preview", "web_fetch"), valid.replace("\"playback_id\"", "\"ignored\""))
            .forEach { assertNull(it, parseVideoPreviewPayload(it)) }
    }

    @Test fun unsafeSchemesAndEmbeddedCredentialsHaveNoPlayer() {
        listOf("file:///sdcard/private.mp4", "javascript:alert(1)", "https://user:secret@example.org/film")
            .forEach { assertNull(parseVideoPreviewPayload(valid.replace("https://example.org/film", it))) }
    }

    @Test fun oversizedAndInvalidIdsHaveNoPlayer() {
        assertNull(parseVideoPreviewPayload(valid.replace("Film", "x".repeat(16_000))))
        assertNull(parseVideoPreviewPayload(valid.replace("c2cbeb10-662e-4b3b-b305-687b69c8ea58", "../../../data")))
    }
}
