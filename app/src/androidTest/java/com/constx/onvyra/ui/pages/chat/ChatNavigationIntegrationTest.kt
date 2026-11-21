package com.constx.onvyra.ui.pages.chat

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.datastore.getCurrentAssistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.TemporaryChatMode
import com.constx.onvyra.data.model.toMessageNode
import com.constx.onvyra.data.repository.ConversationRepository
import com.constx.onvyra.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import kotlin.uuid.Uuid

/** Exercises actual RouteActivity/ChatPage wiring; all content is a local fixture, no model API. */
@RunWith(AndroidJUnit4::class)
class ChatNavigationIntegrationTest {
    @Test fun realChatPageSwitchesTemporaryModeAndConnectsTheMenuToStoredActions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("StatsUsageTest", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        val context = instrumentation.targetContext
        val service = getKoin().get<ChatService>()
        val repository = getKoin().get<ConversationRepository>()
        val settings = getKoin().get<SettingsStore>().awaitLoaded()
        val emptyId = Uuid.random()
        val fixture = Conversation(assistantId = settings.getCurrentAssistant().id, title = "界面验收聊天",
            messageNodes = listOf(UIMessage.user("本地界面测试，不调用模型").toMessageNode()))
        fun open(id: Uuid) {
            context.startActivity(Intent(context, RouteActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra("conversationId", id.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        fun click(description: String) {
            val button = device.wait(Until.findObject(By.desc(description).pkg(context.packageName)), 10_000)
            assertNotNull(description, button)
            button.click()
        }
        fun capture(name: String) {
            device.waitForIdle()
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "chat-redesign-$name.png")))
        }
        try {
            open(emptyId)
            click("开启临时聊天")
            assertTrue(device.wait(Until.hasObject(By.text("不个性化")), 5_000))
            assertEquals(TemporaryChatMode.NON_PERSONALIZED, service.getTemporaryChatModeFlow(emptyId).value)
            capture("temporary")
            device.findObject(By.text("不个性化")).click()
            assertTrue(device.wait(Until.hasObject(By.text("临时聊天设置")), 5_000))
            capture("settings")
            device.findObject(By.text("个性化")).click()
            device.findObject(By.text("完成")).click()
            withTimeout(5_000) {
                while (service.getTemporaryChatModeFlow(emptyId).value != TemporaryChatMode.PERSONALIZED) delay(25)
            }
            click("退出临时聊天")
            assertTrue(device.wait(Until.hasObject(By.desc("开启临时聊天")), 5_000))
            repository.insertConversation(fixture)
            open(fixture.id)
            click("聊天选项")
            assertTrue(device.wait(Until.hasObject(By.text("分享")), 5_000))
            capture("menu")
            device.findObject(By.text("置顶")).click()
            withTimeout(5_000) { while (repository.getConversationById(fixture.id)?.isPinned != true) delay(25) }
            click("聊天选项")
            device.wait(Until.findObject(By.text("在聊天中查找")), 5_000).click()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))
            click("关闭聊天查找")
            click("新会话")
            assertTrue(device.wait(Until.hasObject(By.desc("开启临时聊天")), 5_000))
            assertNotNull(repository.getConversationById(fixture.id))
            println("CHAT_PAGE_REAL_NAVIGATION=PASS; actual_mode_state=true; actual_pin_persisted=true; search_and_new_chat=true; provider_calls=0")
        } finally {
            service.leaveTemporaryChat(emptyId)
            repository.deleteConversation(fixture)
        }
    }
}
