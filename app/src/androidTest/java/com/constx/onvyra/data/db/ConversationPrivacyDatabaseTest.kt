package com.constx.onvyra.data.db

import android.content.ContentValues
import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.constx.onvyra.data.db.entity.ConversationEntity
import com.constx.onvyra.data.db.fts.MessageFtsManager
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.MessageNode
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationPrivacyDatabaseTest {
    @get:Rule val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Before fun laboratoryOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allow_chat_redesign_lab") == "true")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("StatsUsageTest", androidx.test.uiautomator.UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation()).executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
    }

    @Test fun version30MigrationRetainsMessagesAndDefaultsToUnlockedUnarchived() {
        val name = "privacy-migration-${Uuid.random()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            migration.createDatabase(name, 30).use { db ->
                db.insert("ConversationEntity", 0, ContentValues().apply {
                    put("id", "old-chat"); put("assistant_id", "old-assistant")
                    put("title", "保留已有聊天"); put("nodes", "[]")
                    put("create_at", 1L); put("update_at", 2L)
                })
                db.insert("message_node", 0, ContentValues().apply {
                    put("id", "old-node"); put("conversation_id", "old-chat")
                    put("node_index", 0); put("messages", "[{\"retained\":true}]")
                    put("select_index", 0)
                })
            }
            migration.runMigrationsAndValidate(name, 31, true).use { db ->
                db.query("SELECT title,is_archived,is_locked FROM ConversationEntity WHERE id='old-chat'").use {
                    assertTrue(it.moveToFirst()); assertEquals("保留已有聊天", it.getString(0))
                    assertEquals(0, it.getInt(1)); assertEquals(0, it.getInt(2))
                }
                db.query("SELECT messages FROM message_node WHERE id='old-node'").use {
                    assertTrue(it.moveToFirst()); assertEquals("[{\"retained\":true}]", it.getString(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun archiveAndLockFilterSearchAndRestoreWithoutDeletingRowsOrIndex() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "privacy-query-${Uuid.random()}"
        val db = AppDatabaseFactory.create(context, name)
        try {
            val dao = db.conversationDao()
            val assistant = Uuid.random()
            fun entity(id: Uuid) = ConversationEntity(id.toString(), assistant.toString(),
                "marker", "[]", 1, 2, "[]", false)
            val normal = Uuid.random(); val archived = Uuid.random(); val locked = Uuid.random()
            listOf(normal, archived, locked).forEach { dao.insert(entity(it)) }
            val fts = MessageFtsManager(db)
            listOf(normal, archived, locked).forEach { id ->
                fts.indexConversation(Conversation(id, assistant, "marker", listOf(MessageNode(messages = listOf(
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("privacyprobe marker"))),
                )))))
            }
            assertEquals(3, fts.search("privacyprobe").size)
            dao.updateArchiveStatus(archived.toString(), true)
            dao.updateLockStatus(locked.toString(), true)
            assertEquals(setOf(normal.toString(), locked.toString()),
                dao.getConversationsOfAssistant(assistant.toString()).first().map { it.id }.toSet())
            assertEquals(listOf(archived.toString()), dao.getArchivedConversations(assistant.toString()).first().map { it.id })
            assertEquals(listOf(normal.toString()), dao.searchConversations("marker").first().map { it.id })
            assertEquals(listOf(normal.toString()), fts.search("privacyprobe").map { it.conversationId })
            assertEquals(3, dao.getConversationsOfAssistantForMaintenance(assistant.toString()).size)
            dao.updateArchiveStatus(archived.toString(), false)
            dao.updateLockStatus(locked.toString(), false)
            assertEquals(3, fts.search("privacyprobe").size)
            assertEquals(3, dao.getAllIds().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
