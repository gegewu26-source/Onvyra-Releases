package com.constx.onvyra.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VideoQualityOptionTest {
    @Test fun oneLowResolutionTrackDoesNotInventHdRenditions() {
        val options = videoQualityOptions(listOf(VideoTrackDescription("low", 640, 360, 700_000)))
        assertEquals(listOf("360p"), options.map { it.label })
        assertEquals("low", options.single().id)
        assertFalse(options.any { (it.height ?: 0) >= 720 })
    }

    @Test fun duplicateResolutionChoosesItsHighestAvailableBitrateAndSortsByActualSize() {
        val options = videoQualityOptions(listOf(
            VideoTrackDescription("hd-low", 1280, 720, 1_000_000),
            VideoTrackDescription("sd", 854, 480, 600_000),
            VideoTrackDescription("hd-high", 1280, 720, 2_000_000),
            VideoTrackDescription("full-hd", 1920, 1080, 4_000_000),
        ))
        assertEquals(listOf("1080p", "720p", "480p"), options.map { it.label })
        assertEquals("hd-high", options[1].id)
    }

    @Test fun portraitVideoUsesItsShortDimensionInsteadOfPretendingToBeHigherQuality() {
        assertEquals(720, videoResolutionHeight(720, 1280))
        assertEquals(720, videoResolutionHeight(1280, 720))
        assertEquals("720p", videoQualityOptions(listOf(VideoTrackDescription("portrait", 720, 1280, 1_000_000))).single().label)
    }

    @Test fun unknownDimensionsOnlyShowKnownBitrateOrOriginalQuality() {
        assertNull(videoResolutionHeight(-1, -1))
        val options = videoQualityOptions(listOf(VideoTrackDescription("bitrate", -1, -1, 2_500_000),
            VideoTrackDescription("unknown", -1, -1, -1)))
        assertEquals(listOf("2.5 Mbps", "原始画质"), options.map { it.label })
        assertEquals(listOf(null, null), options.map { it.height })
    }
}
