package com.constx.onvyra.security

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivateMediaAccessTest {
    @Before fun startLocked() {
        PrivateMediaAccess.installVerifier { false }
    }

    @After fun restoreLockedState() {
        PrivateMediaAccess.installVerifier { false }
    }

    @Test fun noGrantKeepsEveryPrivateSourceLocked() {
        assertFalse(PrivateMediaAccess.hasAccess())
        listOf("jable", "missav", "supjav", "hanime1").forEach {
            assertFalse(PrivateMediaAccess.isAllowed(sourceId = it))
        }
        assertTrue(PrivateMediaAccess.isAllowed(sourceId = "ordinary-catalog", url = "https://example.com/video"))
    }

    @Test fun verifierFailureStaysLocked() {
        PrivateMediaAccess.installVerifier { error("Synthetic verifier failure") }
        assertFalse(PrivateMediaAccess.hasAccess())
        assertFalse(PrivateMediaAccess.isAllowed(sourceId = "missav"))
        assertFalse(PrivateMediaAccess.isAllowed(url = "https://missav-primary.private-media.invalid/video"))
    }

    @Test fun sourceNamesAreCaseInsensitiveAndTrimmed() {
        listOf(" MISSAV ", "JaBlE", "\thanime1\n", "SupJav").forEach {
            assertTrue(PrivateMediaAccess.isRestrictedSource(it))
            assertFalse(PrivateMediaAccess.isAllowed(sourceId = it))
        }
        assertFalse(PrivateMediaAccess.isRestrictedSource("missav-public"))
    }

    @Test fun allKnownMirrorsAndPrivateCdnsRemainRestricted() {
        listOf(
            "jable-mirror.private-media.invalid", "jable-primary.private-media.invalid", "missav-primary.private-media.invalid", "missav-mirror-1.private-media.invalid", "missav-mirror-2.private-media.invalid",
            "missav-mirror-3.private-media.invalid", "missav-mirror-4.private-media.invalid", "missav-mirror-5.private-media.invalid", "missav-legacy-1.private-media.invalid", "missav-legacy-2.private-media.invalid", "missav-legacy-3.private-media.invalid",
            "missav-legacy-4.private-media.invalid", "supjav-primary.private-media.invalid", "hanime1-primary.private-media.invalid", "hanime1-mirror-1.private-media.invalid", "hanime1-mirror-2.private-media.invalid", "hanime1-alternate.private-media.invalid",
            "missav-stream.private-media.invalid", "hanime1-restricted-1.private-media.invalid", "hanime1-restricted-2.private-media.invalid", "hanime1-stream-backup.private-media.invalid",
        ).forEach {
            assertTrue(it, PrivateMediaAccess.isRestrictedUrl("https://$it/video"))
            assertFalse(it, PrivateMediaAccess.isAllowed(url = "https://$it/video"))
        }
    }

    @Test fun hostNormalizationIncludesCaseTrailingDotSubdomainsAndPort() {
        listOf(
            "https://missav-primary.private-media.invalid/video",
            "https://missav-primary.private-media.invalid./video",
            "https://media.missav-primary.private-media.invalid/video",
            "https://missav-primary.private-media.invalid:8443/video",
            "https://www.hanime1-primary.private-media.invalid/watch?v=123",
            "https://evil.example@missav-primary.private-media.invalid/video",
        ).forEach { assertTrue(it, PrivateMediaAccess.isRestrictedUrl(it)) }
    }

    @Test fun encodedHostCannotBypassTheSameNormalizationUsedByHttpRequests() {
        listOf(
            "https://%6dissav-primary.private-media.invalid/video",
            "https://missav-primary%2eprivate-media.invalid/video",
            "https://%6dissav-primary.private-media.invalid/video",
        ).forEach {
            assertTrue(it, PrivateMediaAccess.isRestrictedUrl(it))
            assertFalse(it, PrivateMediaAccess.isAllowed(url = it))
        }
    }

    @Test fun hostBoundariesDoNotBlockUnrelatedUrlsContainingASiteName() {
        listOf(
            "https://notmissav-primary.private-media.invalid/video",
            "https://missav-primary.private-media.invalid.evil.example/video",
            "https://example.com/missav-primary.private-media.invalid/video",
            "https://example.com/?next=https://missav-primary.private-media.invalid/video",
            "https://missav-primary.private-media.invalid@ordinary.example/video",
        ).forEach { assertFalse(it, PrivateMediaAccess.isRestrictedUrl(it)) }
    }

    @Test fun forgedPublicSourceNameCannotAllowAPrivateUrl() {
        assertFalse(PrivateMediaAccess.isAllowed(sourceId = "public", url = "https://missav-primary.private-media.invalid/video"))
        assertFalse(PrivateMediaAccess.isAllowed(sourceId = "missav", url = "https://example.com/video"))
    }

    @Test fun accessIsRecheckedAndRevokedImmediately() {
        var authorized = true
        PrivateMediaAccess.installVerifier { authorized }
        assertTrue(PrivateMediaAccess.hasAccess())
        assertTrue(PrivateMediaAccess.isAllowed(sourceId = "missav"))
        PrivateMediaAccess.requireAllowed(url = "https://missav-primary.private-media.invalid/video")
        authorized = false
        assertFalse(PrivateMediaAccess.hasAccess())
        assertFalse(PrivateMediaAccess.isAllowed(sourceId = "missav"))
        assertTrue(runCatching { PrivateMediaAccess.requireAllowed(url = "https://missav-primary.private-media.invalid/video") }
            .exceptionOrNull() is PrivateMediaAccessDeniedException)
    }

    @Test fun requireAccessChecksTheCurrentVerifierEveryTime() {
        assertTrue(runCatching { PrivateMediaAccess.requireAccess() }.exceptionOrNull() is PrivateMediaAccessDeniedException)
        PrivateMediaAccess.installVerifier { true }
        PrivateMediaAccess.requireAccess()
        assertTrue(PrivateMediaAccess.isAllowed(sourceId = "jable", url = "https://jable-mirror.private-media.invalid/videos/id"))
        PrivateMediaAccess.installVerifier { false }
        assertTrue(runCatching { PrivateMediaAccess.requireAccess() }.exceptionOrNull() is PrivateMediaAccessDeniedException)
    }
}
