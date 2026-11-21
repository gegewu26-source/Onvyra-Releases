package me.rerere.ai.provider

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.*
import org.junit.Test

class GlmReasoningCapabilitiesTest {
    @Test
    fun `GLM 5 point 3 profiles offer only three real effort levels and no off`() {
        listOf("glm-5.3", "GLM-5.3-FLASH", "z-ai/glm-5.3-flashx").forEach { id ->
            val caps = glmReasoningCapabilities(Model(modelId = id))!!
            assertFalse(caps.supportsOff)
            assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), caps.supportedLevels)
            assertEquals(ReasoningLevel.LOW, caps.effectiveLevel(ReasoningLevel.OFF))
            assertEquals(ReasoningLevel.HIGH, caps.effectiveLevel(ReasoningLevel.MEDIUM))
            assertEquals(ReasoningLevel.MAX, caps.effectiveLevel(ReasoningLevel.XHIGH))
        }
    }

    @Test
    fun `GLM 5 point 3 never encodes unsupported disabled or effort strings`() {
        val model = Model(modelId = "glm-5.3")
        ReasoningLevel.entries.forEach { requested ->
            val body = glmThinkingRequest(model, requested)!!
            if (requested == ReasoningLevel.AUTO) assertTrue(body.isEmpty()) else {
                assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertTrue(body["reasoning_effort"]!!.jsonPrimitive.content in listOf("low", "high", "max"))
            }
        }
    }

    @Test
    fun `GLM 5 point 2 has real off and only high or max active efforts`() {
        val model = Model(modelId = "glm-5.2")
        val caps = glmReasoningCapabilities(model)!!
        assertTrue(caps.supportsOff)
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH, ReasoningLevel.MAX), caps.supportedLevels)
        assertEquals("disabled", glmThinkingRequest(model, ReasoningLevel.OFF)!!["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(glmThinkingRequest(model, ReasoningLevel.OFF)!!["reasoning_effort"])
        assertEquals("high", glmThinkingRequest(model, ReasoningLevel.LOW)!!["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("high", glmThinkingRequest(model, ReasoningLevel.MEDIUM)!!["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("max", glmThinkingRequest(model, ReasoningLevel.XHIGH)!!["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `older GLM models have an on off switch without invented depth levels`() {
        listOf("glm-5.1", "glm-5", "glm-4.7", "glm-4.7-flash", "glm-4.6", "glm-4.5", "glm-4.5-air").forEach { id ->
            val model = Model(modelId = id)
            val caps = glmReasoningCapabilities(model)!!
            assertTrue(caps.isToggleOnly)
            assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH), caps.supportedLevels)
            assertEquals("disabled", glmThinkingRequest(model, ReasoningLevel.OFF)!!["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX).forEach { requested ->
                val body = glmThinkingRequest(model, requested)!!
                assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertNull(body["reasoning_effort"])
            }
        }
    }

    @Test
    fun `auto omits GLM controls and lets configured defaults apply`() {
        listOf("glm-5.3", "glm-5.2", "glm-4.7").forEach { id ->
            assertTrue(glmThinkingRequest(Model(modelId = id), ReasoningLevel.AUTO)!!.isEmpty())
        }
    }

    @Test
    fun `unknown GLM names are not guessed from family appearance`() {
        listOf("glm5.4", "glm-6", "glm-5.4", "glm-4.6v", "my-glm-5.3", "glm-5.2-flash").forEach { id ->
            assertNull(glmReasoningCapabilities(Model(modelId = id)))
            assertNull(glmThinkingRequest(Model(modelId = id), ReasoningLevel.OFF))
        }
    }
    @Test
    fun `user supplied gateway aliases match GLM 5 point 3 without rewriting model id`() {
        listOf("glm5.3", "glm5.3flash", "z-ai/glm5.3", "custom/glm5.3flash").forEach { id ->
            val model = Model(modelId = id)
            val caps = glmReasoningCapabilities(model)!!
            assertFalse(caps.supportsOff)
            assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), caps.supportedLevels)
            val minimum = glmThinkingRequest(model, ReasoningLevel.OFF)!!
            assertEquals("enabled", minimum["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("low", minimum["reasoning_effort"]!!.jsonPrimitive.content)
            val maximum = glmThinkingRequest(model, ReasoningLevel.MAX)!!
            assertEquals("max", maximum["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals(id, model.modelId)
            assertFalse(minimum.containsKey("model"))
        }
    }

}
