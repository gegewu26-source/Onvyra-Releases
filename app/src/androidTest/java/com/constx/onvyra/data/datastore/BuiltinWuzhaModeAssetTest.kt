package com.constx.onvyra.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.transformers.transformMessages
import com.constx.onvyra.data.datastore.migration.BUILTIN_WUZHA_MODE_ASSET
import com.constx.onvyra.data.datastore.migration.BUILTIN_WUZHA_MODE_ID
import com.constx.onvyra.data.datastore.migration.BuiltinWuzhaModeMigration
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.PromptInjection
import com.constx.onvyra.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BuiltinWuzhaModeAssetTest {
    @Test fun packagedAssetMigratesExactlyAndRequiresBothSwitchAndBinding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open(BUILTIN_WUZHA_MODE_ASSET).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("56e365c73d64b9e7e6712abb0a0474ffe56757e45174a948dea9a447094cd815", hash)
        val prefs = BuiltinWuzhaModeMigration(context).migrate(emptyPreferences())
        val mode = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(
            prefs[SettingsStore.MODE_INJECTIONS]!!
        ).single { it.id == BUILTIN_WUZHA_MODE_ID }
        assertEquals(bytes.toString(Charsets.UTF_8), mode.content)
        assertTrue(mode.enabled)
        assertEquals(MessageRole.SYSTEM, mode.role)

        val messages = listOf(UIMessage.user("Hello"))
        val linked = Assistant(modeInjectionIds = setOf(mode.id))
        fun transform(assistant: Assistant, enabled: Boolean) = transformMessages(
            messages = messages,
            assistant = assistant,
            modeInjections = listOf(mode.copy(enabled = enabled)),
            lorebooks = emptyList(),
            systemModeCompatibility = true,
        )
        assertEquals(messages, transform(linked, enabled = false))
        assertEquals(messages, transform(Assistant(modeInjectionIds = emptySet()), enabled = true))
        val active = transform(linked, enabled = true)
        assertEquals(2, active.size)
        assertEquals(MessageRole.SYSTEM, active.first().role)
        assertEquals(mode.content, active.first().parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(messages.single(), active.last())
    }
}
