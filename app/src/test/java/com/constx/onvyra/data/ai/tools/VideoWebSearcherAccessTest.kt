package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.media.MovieWebCandidate
import com.constx.onvyra.security.PrivateMediaAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoWebSearcherAccessTest {
    // Fixtures are returned directly by the fake callback; no address is requested.
    private val ordinary = MovieWebCandidate("Ordinary fixture", "https://example.invalid/watch/fixture")
    private val restricted = MovieWebCandidate("Private fixture", "https://missav-primary.private-media.invalid/cn/fixture")

    @Test fun cachedCandidatesFollowUnlockAndRelockWithoutAnotherProviderRequest() = runBlocking {
        var allowed = false
        var requests = 0
        val search = createVideoWebSearcher(
            searchCandidates = {
                requests++
                listOf(restricted, ordinary)
            },
            isAllowed = { allowed || !PrivateMediaAccess.isRestrictedUrl(it) },
        )

        assertEquals(listOf(ordinary), search(" Fixture "))
        assertEquals(listOf(ordinary), search("Fixture"))
        allowed = true
        assertEquals(listOf(restricted, ordinary), search("Fixture"))
        allowed = false
        assertEquals(listOf(ordinary), search("Fixture"))
        assertEquals(1, requests)
    }

    @Test fun revocationDuringProviderRequestFiltersTheReturnedCandidates() = runBlocking {
        var allowed = true
        val search = createVideoWebSearcher(
            searchCandidates = {
                allowed = false
                listOf(restricted, ordinary)
            },
            isAllowed = { allowed || !PrivateMediaAccess.isRestrictedUrl(it) },
        )

        assertEquals(listOf(ordinary), search("Fixture"))
    }

    @Test fun unlockDuringProviderRequestImmediatelyAllowsTheReturnedCandidates() = runBlocking {
        var allowed = false
        val search = createVideoWebSearcher(
            searchCandidates = {
                allowed = true
                listOf(restricted, ordinary)
            },
            isAllowed = { allowed || !PrivateMediaAccess.isRestrictedUrl(it) },
        )

        assertEquals(listOf(restricted, ordinary), search("Fixture"))
    }

    @Test fun hiddenCandidatesDoNotUseUpTheVisibleResultLimit() = runBlocking {
        val privateCandidates = (1..10).map { restricted.copy(url = "${restricted.url}/$it") }
        val search = createVideoWebSearcher(
            searchCandidates = { privateCandidates + ordinary },
            isAllowed = { !PrivateMediaAccess.isRestrictedUrl(it) },
        )

        assertEquals(listOf(ordinary), search("Fixture"))
    }
}
