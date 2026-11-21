package me.rerere.ai.provider

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import org.junit.Assert.*
import org.junit.Test

class ReasoningCapabilitiesTest {
    private val gateway = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:8080/v1")
    private fun caps(id: String) = reasoningCapabilities(Model(modelId = id), gateway)

    @Test fun `confirmed deepseek alias has three active efforts and a true off switch`() {
        val caps = caps("deepseekv4.1f")
        assertTrue(caps.isKnown)
        assertTrue(caps.supportsOff)
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), caps.supportedLevels)
        assertEquals(ReasoningLevel.HIGH, caps.effectiveLevel(ReasoningLevel.MEDIUM))
        assertEquals(ReasoningLevel.HIGH, caps.effectiveLevel(ReasoningLevel.XHIGH))
    }

    @Test fun `confirmed wb deepseek wire aliases share the native profile without changing route id`() {
        for (alias in listOf("wb-deepseek-v4.1-flash", "wb-cn-deepseek-v4.1-flash")) {
            val model = Model(modelId = alias)
            val resolved = reasoningCapabilities(model, gateway)
            assertTrue(alias, resolved.isKnown)
            assertTrue(alias, resolved.supportsOff)
            assertEquals(alias, listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), resolved.supportedLevels)
            assertEquals(alias, ReasoningLevel.HIGH, resolved.effectiveLevel(ReasoningLevel.MEDIUM))
            assertEquals(alias, ReasoningLevel.HIGH, resolved.effectiveLevel(ReasoningLevel.XHIGH))
            assertEquals(alias, alias, model.modelId)
        }
    }

    @Test fun `unconfirmed gateway prefixes and lookalike suffixes remain unknown`() {
        for (alias in listOf("wb-en-deepseek-v4.1-flash", "wb-deepseek-v4.1-flash-custom", "wb-deepseek-v3.2", "my-wb-deepseek-v4.1-flash")) {
            assertFalse(alias, caps(alias).isKnown)
        }
    }

    @Test fun `unknown alias is not guessed from display name`() {
        val caps = reasoningCapabilities(Model(modelId = "my-best-model", displayName = "deepseek-v4.1-flash"), gateway)
        assertFalse(caps.isKnown)
        assertEquals(ReasoningLevel.entries, caps.supportedLevels)
    }

    @Test fun `substring that resembles a deepseek name stays unknown`() {
        assertFalse(caps("my-deepseek-v4-flash-custom").isKnown)
    }

    @Test fun `gpt6 luna and sol preserve all seven levels`() {
        listOf("gpt-6-luna", "gpt6luna", "gpt-6-sol", "gpt-5.6-sol", "gpt-5.6").forEach { id ->
            assertEquals(id, ReasoningLevel.entries, caps(id).supportedLevels)
        }
    }

    @Test fun `always reasoning openai models replace stale off by low`() {
        listOf("gpt-6-astra", "gpt-6.1-sol").forEach { id ->
            assertFalse(id, caps(id).supportsOff)
            assertEquals(id, ReasoningLevel.LOW, caps(id).effectiveLevel(ReasoningLevel.OFF))
            assertEquals(id, ReasoningLevel.MAX, caps(id).effectiveLevel(ReasoningLevel.MAX))
        }
    }

    @Test fun `gpt 6 point 1 aliases use the confirmed bounded profile`() {
        listOf("gpt-6.1", "gpt-6.1-iso", "gpt61iso").forEach { id ->
            val caps = caps(id)
            assertTrue(id, caps.isKnown)
            assertFalse(id, caps.supportsOff)
            assertEquals(id, ReasoningLevel.LOW, caps.effectiveLevel(ReasoningLevel.OFF))
            assertEquals(id, ReasoningLevel.MAX, caps.effectiveLevel(ReasoningLevel.MAX))
        }
    }

    @Test fun `non chat models expose no independent reasoning levels`() {
        val image = reasoningCapabilities(Model(modelId = "gpt-6.1-iso", type = ModelType.IMAGE), gateway)
        assertEquals(listOf(ReasoningLevel.AUTO), image.supportedLevels)
        assertEquals(ReasoningLevel.AUTO, image.effectiveLevel(ReasoningLevel.MAX))
    }

    @Test fun `older gpt models clamp max to their own highest effort`() {
        listOf("gpt-5.2", "gpt-5.4", "gpt-5.5").forEach { id ->
            assertEquals(id, ReasoningLevel.XHIGH, caps(id).effectiveLevel(ReasoningLevel.MAX))
        }
        assertEquals(ReasoningLevel.HIGH, caps("gpt-5.1").effectiveLevel(ReasoningLevel.MAX))
        assertTrue(caps("gpt-5.1").supportsOff)
    }

    @Test fun `pro and codex suffixes are not inferred from the base model`() {
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.HIGH), caps("gpt-5-pro").supportedLevels)
        assertFalse(caps("gpt-5.1-codex").isKnown)
        assertFalse(caps("o3-pro").isKnown)
    }

    @Test fun `old o model cannot promise to turn off thinking`() {
        assertFalse(caps("o3").supportsOff)
        assertEquals(ReasoningLevel.LOW, caps("o3").effectiveLevel(ReasoningLevel.OFF))
        assertEquals(ReasoningLevel.HIGH, caps("o3").effectiveLevel(ReasoningLevel.MAX))
    }

    @Test fun `binary gateway does not advertise independent effort levels`() {
        val caps = reasoningCapabilities(Model(modelId = "kimi-k2.6"), ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1"))
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH), caps.supportedLevels)
        assertTrue(caps.isToggleOnly)
        assertEquals(ReasoningLevel.HIGH, caps.effectiveLevel(ReasoningLevel.MAX))
    }

    @Test fun `catalogue active efforts retain independently known off support`() {
        val model = Model(modelId = "deepseekv4.1f", reasoningEffortLevels = listOf("low", "high", "max"))
        val caps = reasoningCapabilities(model, gateway)
        assertTrue(caps.supportsOff)
        assertTrue(ReasoningLevel.OFF in caps.supportedLevels)
        assertEquals(ReasoningLevel.HIGH, caps.effectiveLevel(ReasoningLevel.XHIGH))
    }

    @Test fun `old model json without capabilities remains readable`() {
        val model = Json.decodeFromString<Model>("""{"modelId":"deepseekv4.1f"}""")
        assertNull(model.reasoningEffortLevels)
        assertTrue(reasoningCapabilities(model, gateway).isKnown)
    }
    @Test fun `native providers do not borrow a foreign models wire profile`() {
        assertFalse(reasoningCapabilities(Model(modelId = "deepseekv4.1f"), ProviderSetting.Google()).isKnown)
        assertFalse(reasoningCapabilities(Model(modelId = "gpt-6-sol"), ProviderSetting.Claude()).isKnown)
        assertFalse(reasoningCapabilities(Model(modelId = "ordinary-model"), ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1")).isKnown)
    }
    @Test fun `actual user supplied gpt aliases share their canonical support without changing model id`() {
        listOf("gpt6a", "6.1sol", "gpt6.1sol").forEach { alias ->
            val model = Model(modelId = alias)
            val resolved = reasoningCapabilities(model, gateway)
            assertTrue(alias, resolved.isKnown)
            assertFalse(alias, resolved.supportsOff)
            assertEquals(alias, ReasoningLevel.LOW, resolved.effectiveLevel(ReasoningLevel.OFF))
            assertEquals(alias, model.modelId)
        }
        listOf("6luna", "gpt6luna").forEach { alias ->
            assertEquals(alias, ReasoningLevel.entries, caps(alias).supportedLevels)
        }
        listOf("gpt-5.4-nano", "gpt-5.6-terra", "gpt-5.6-luna").forEach { id ->
            assertFalse(id, caps(id).isKnown)
        }
    }

    @Test fun `effort metadata cannot fabricate extra native or binary controls`() {
        val advertised = listOf("low", "medium", "high", "xhigh", "max")
        val glm = reasoningCapabilities(Model(modelId = "glm5.3", reasoningEffortLevels = advertised), gateway)
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), glm.supportedLevels)
        val binary = reasoningCapabilities(Model(modelId = "glm-4.7", reasoningEffortLevels = advertised), gateway)
        assertTrue(binary.isToggleOnly)
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH), binary.supportedLevels)
        val native = reasoningCapabilities(Model(modelId = "gemini-3.8-flash", reasoningEffortLevels = advertised, providerOverwrite = ProviderSetting.Google()), gateway)
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), native.supportedLevels)
        val gpt = reasoningCapabilities(Model(modelId = "gpt-5.4", reasoningEffortLevels = advertised), gateway)
        assertFalse(ReasoningLevel.MAX in gpt.supportedLevels)
    }
}
