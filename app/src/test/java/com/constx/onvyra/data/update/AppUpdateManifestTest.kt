package com.constx.onvyra.data.update

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppUpdateManifestTest {
    @Test fun ownerSignedReleasePreservesGroupedNotesAndSelectsDeviceAbi() {
        val release = verifier.verify(envelope(payload()))
        assertEquals(229L, release.versionCode)
        assertEquals("fix", release.changes.single().type)
        assertEquals("A real repair", release.changes.single().items.single())
        assertEquals("arm64-v8a", release.downloadFor(listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertEquals("universal", release.downloadFor(listOf("riscv64"))?.abi)
    }

    @Test fun changingEvenOneSignedByteIsRejected() {
        val signed = Json.parseToJsonElement(envelope(payload()).toString(Charsets.UTF_8)).jsonObject
        val changed = payload().toMutableMap().apply { put("versionCode", JsonPrimitive(9999)) }
        val forged = JsonObject(signed + ("payload_base64" to JsonPrimitive(Base64.getEncoder()
            .encodeToString(JsonObject(changed).toString().toByteArray())))).toString().toByteArray()
        rejected { verifier.verify(forged) }
    }

    @Test fun anotherSigningKeyCannotAuthorizeAnUpdate() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val verifierWithAnotherKey = AppUpdateManifestVerifier(Base64.getEncoder().encodeToString(other.public.encoded), origin)
        rejected { verifierWithAnotherKey.verify(envelope(payload())) }
    }

    @Test fun aSignedFeedForAnotherApplicationOrSchemaIsRejected() {
        for (pair in listOf("applicationId" to JsonPrimitive("another.app"), "schemaVersion" to JsonPrimitive(2))) {
            rejected { verifier.verify(envelope(JsonObject(payload() + pair))) }
        }
    }

    @Test fun signedDownloadCannotEscapeTheTrustedHttpsReleaseDirectory() {
        for (url in listOf(
            "http://updates.example/onvyra-updates/releases/229/app.apk",
            "https://evil.example/onvyra-updates/releases/229/app.apk",
            "https://updates.example:444/onvyra-updates/releases/229/app.apk",
            "https://user@updates.example/onvyra-updates/releases/229/app.apk",
            "https://updates.example/onvyra-updates/releases/../secret.apk",
            "https://updates.example/onvyra-updates/releases/%2e%2e/secret.apk",
            "https://updates.example/onvyra-updates/releases/229/app.apk?next=https://evil.example",
            "https://updates.example/other/app.apk",
            "https://updates.example/onvyra-updates/releases/229/app.html",
        )) {
            rejected { verifier.verify(envelope(payload(downloads = JsonArray(listOf(download("arm64-v8a", url)))))) }
        }
    }

    @Test fun malformedChecksumAndImpossiblePackageSizesAreRejected() {
        for (download in listOf(
            JsonObject(download("universal") + ("sha256" to JsonPrimitive("not-a-checksum"))),
            JsonObject(download("universal") + ("sizeBytes" to JsonPrimitive(0))),
            JsonObject(download("universal") + ("sizeBytes" to JsonPrimitive(9_000_000_000L))),
        )) rejected { verifier.verify(envelope(payload(JsonArray(listOf(download))))) }
    }

    @Test fun duplicateAbisAndInvalidVersionCodesAreRejected() {
        rejected { verifier.verify(envelope(payload(JsonArray(listOf(download("universal"), download("universal")))))) }
        for (code in listOf(0L, -1L, 9_000_000_000L)) {
            rejected { verifier.verify(envelope(JsonObject(payload() + ("versionCode" to JsonPrimitive(code))))) }
        }
    }

    @Test fun oversizedEnvelopeAndReleaseNotesAreRejected() {
        rejected { verifier.verify(ByteArray(AppUpdateManifestVerifier.MAX_ENVELOPE_BYTES + 1)) }
        val tooLong = JsonArray(listOf(buildJsonObject {
            put("type", "notice"); put("items", JsonArray(listOf(JsonPrimitive("x".repeat(601)))))
        }))
        rejected { verifier.verify(envelope(JsonObject(payload() + ("changes" to tooLong)))) }
    }

    @Test fun missingCompatibleAbiDoesNotFallBackToAnUnrelatedArchitecture() {
        val release = verifier.verify(envelope(payload(JsonArray(listOf(download("x86_64"))))))
        assertNull(release.downloadFor(listOf("arm64-v8a")))
    }

    @Test fun installedAndOlderReleasesNeverTriggerAnAutomaticPrompt() {
        assertFalse(shouldShowAutomaticUpdate(228, 228, 0, 0, 0, 1_000))
        assertFalse(shouldShowAutomaticUpdate(227, 228, 0, 0, 0, 1_000))
        assertTrue(shouldShowAutomaticUpdate(229, 228, 0, 0, 0, 1_000))
    }

    @Test fun skippingOneVersionCannotSuppressANewerRelease() {
        assertFalse(shouldShowAutomaticUpdate(229, 228, 229, 0, 0, 1_000))
        assertTrue(shouldShowAutomaticUpdate(230, 228, 229, 0, 0, 1_000))
    }

    @Test fun tomorrowReminderSuppressesOnlyItsReleaseUntilTheDeadline() {
        assertFalse(shouldShowAutomaticUpdate(229, 228, 0, 229, 10_000, 9_999))
        assertTrue(shouldShowAutomaticUpdate(229, 228, 0, 229, 10_000, 10_000))
        assertTrue(shouldShowAutomaticUpdate(230, 228, 0, 229, 10_000, 9_999))
        assertEquals(86_400_000L, UPDATE_REMINDER_DELAY_MS)
    }

    @Test fun aFailedStartupCanRetrySoonWithoutCheckingOnEveryChatNavigation() {
        assertTrue(shouldCheckAutomaticUpdate(0, false, 1_000))
        assertFalse(shouldCheckAutomaticUpdate(1_000, true, 300_999))
        assertTrue(shouldCheckAutomaticUpdate(1_000, true, 301_000))
        assertFalse(shouldCheckAutomaticUpdate(1_000, false, 301_000))
        assertTrue(shouldCheckAutomaticUpdate(1_000, false, 901_000))
        assertTrue(shouldCheckAutomaticUpdate(1_000, true, 500)) // A changed system clock must not suppress checks forever.
    }

    private fun payload(downloads: JsonArray = JsonArray(listOf(download("arm64-v8a"), download("universal")))) = buildJsonObject {
        put("schemaVersion", 1); put("applicationId", "com.constx.onvyra")
        put("versionCode", 229); put("versionName", "2.5.6-reader.38")
        put("publishedAt", "2026-11-09T12:00:00Z"); put("title", "Onvyra update"); put("summary", "Small changes")
        put("changes", JsonArray(listOf(buildJsonObject { put("type", "fix"); put("items", JsonArray(listOf(JsonPrimitive("A real repair")))) })))
        put("downloads", downloads)
    }

    private fun download(abi: String, url: String = "$origin/onvyra-updates/releases/229/$abi.apk") = buildJsonObject {
        put("abi", abi); put("url", url); put("sha256", "a".repeat(64)); put("sizeBytes", 85_000_000)
    }

    private fun envelope(payload: JsonObject): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(bytes); sign() }
        return buildJsonObject {
            put("payload_base64", Base64.getEncoder().encodeToString(bytes))
            put("signature_base64", Base64.getEncoder().encodeToString(signature))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("An untrusted or invalid update must be rejected") } catch (_: Exception) { }
    }

    companion object {
        private const val origin = "https://updates.example"
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val verifier = AppUpdateManifestVerifier(Base64.getEncoder().encodeToString(keys.public.encoded), origin)
    }
}
