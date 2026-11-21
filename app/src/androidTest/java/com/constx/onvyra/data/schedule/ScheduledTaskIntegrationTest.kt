package com.constx.onvyra.data.schedule

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.repository.ConversationRepository
import com.constx.onvyra.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Real WorkManager -> ChatService -> Room; model HTTP is confined to device loopback. */
@RunWith(AndroidJUnit4::class)
class ScheduledTaskIntegrationTest {
    @Test fun scheduledPromptRunsOnceAndPersistsAnOpenableChatWithoutChangingCurrentAssistant() = runBlocking {
        withFixture { fixture ->
            val task = fixture.createTask()
            repeat(3) { fixture.tasks.restoreSchedules() } // Reconciliation must not duplicate a run.
            val finished = withTimeout(90_000) {
                fixture.tasks.tasks.first { entries ->
                    entries.any { it.id == task.id && it.lastStatus in setOf(ScheduledTaskStatus.SUCCEEDED, ScheduledTaskStatus.FAILED) }
                }.single { it.id == task.id }
            }
            assertEquals(finished.lastMessage + "\n" + fixture.generationError(finished.lastConversationId),
                ScheduledTaskStatus.SUCCEEDED, finished.lastStatus)
            assertFalse(finished.enabled)
            assertNull(finished.nextRunAtMillis)
            val conversationId = Uuid.parse(finished.lastConversationId!!)
            val chat = fixture.conversations.getConversationById(conversationId)!!
            assertEquals(fixture.assistant.id, chat.assistantId)
            assertEquals(fixture.model.id, chat.config!!.chatModelId)
            assertTrue(chat.currentMessages.any { it.role == MessageRole.USER && it.toText() == fixture.prompt })
            assertTrue(chat.currentMessages.any { it.role == MessageRole.ASSISTANT && it.toText() == "scheduled-local-ok" })
            assertEquals(fixture.originalAssistantId, fixture.settings.settingsFlow.value.assistantId)
            assertEquals(1, fixture.server.requests.size)
            assertTrue(fixture.server.requests.single().contains(fixture.prompt))
            assertTrue(fixture.server.requests.single().contains(fixture.model.modelId))
        }
    }

    @Test fun disablingDuringGenerationCancelsOwnedJobsAndLateResponseCannotRestartTheTask() = runBlocking {
        withFixture(holdResponse = true) { fixture ->
            val task = fixture.createTask()
            val received = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                while (fixture.server.requests.isEmpty()) {
                    val current = fixture.tasks.tasks.value.single { it.id == task.id }
                    check(current.lastStatus != ScheduledTaskStatus.FAILED) {
                        current.lastMessage + "\n" + fixture.generationError(current.lastConversationId)
                    }
                    delay(30)
                }
                true
            } == true
            assertTrue("The dedicated loopback model received no request", received)
            val running = fixture.tasks.tasks.value.single { it.id == task.id }
            assertEquals(ScheduledTaskStatus.RUNNING, running.lastStatus)
            val conversationId = Uuid.parse(running.lastConversationId!!)
            fixture.tasks.setEnabled(task.id, false)
            fixture.server.release.countDown()
            withTimeout(20_000) {
                fixture.chatService.getGenerationJobStateFlow(conversationId).first { it == null }
            }
            assertTrue(fixture.chatService.getMessageQueueFlow(conversationId).value.messages.isEmpty())
            repeat(3) { fixture.tasks.restoreSchedules() }
            delay(1_200)
            val stopped = fixture.tasks.tasks.value.single { it.id == task.id }
            assertFalse(stopped.enabled)
            assertEquals(ScheduledTaskStatus.IDLE, stopped.lastStatus)
            assertTrue(stopped.revision > running.revision)
            assertEquals(1, fixture.server.requests.size)
            assertEquals(fixture.originalAssistantId, fixture.settings.settingsFlow.value.assistantId)
        }
    }

    @Test fun deletingBeforeDueTimeCancelsRealScheduledWorkWithoutAnyModelRequest() = runBlocking {
        withFixture { fixture ->
            val task = fixture.createTask(delayMillis = 3_000)
            fixture.tasks.delete(task.id)
            fixture.tasks.restoreSchedules()
            delay(4_500)
            assertTrue(fixture.tasks.tasks.value.none { it.id == task.id })
            assertTrue(fixture.server.requests.isEmpty())
            assertEquals(fixture.originalAssistantId, fixture.settings.settingsFlow.value.assistantId)
        }
    }

    private suspend fun withFixture(holdResponse: Boolean = false, block: suspend (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("Physical phones are forbidden for task fixtures", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val avd = UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()
        assertTrue("Use only an explicitly dedicated QA AVD", avd in setOf("CodexReaderQA", "CodexReaderBarsQA"))
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = settings.awaitLoaded()
        val tasks = koin.get<ScheduledTaskRepository>()
        tasks.awaitLoaded()
        val conversations = koin.get<ConversationRepository>()
        val chatService = koin.get<ChatService>()
        val providerManager = koin.get<ProviderManager>()
        val originalProvider = providerManager.getProvider("openai")
        val client = koin.get<OkHttpClient>()
        val originalSelector = client.proxySelector
        val loopbackSelector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> =
                if (uri.host == "127.0.0.1") listOf(Proxy.NO_PROXY) else originalSelector.select(uri)
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
                if (uri.host != "127.0.0.1") originalSelector.connectFailed(uri, address, failure)
            }
        }
        val loopbackClient = client.newBuilder().proxy(null).proxySelector(loopbackSelector)
            .connectionPool(ConnectionPool()).build()
        val server = LocalModelServer(holdResponse)
        val model = Model(modelId = "scheduled-qa-loopback")
        val provider = ProviderSetting.OpenAI(
            name = "Scheduled QA loopback",
            baseUrl = "http://127.0.0.1:${server.port}/v1",
            apiKey = "test-only-loopback",
            models = listOf(model),
        )
        val assistant = Assistant(
            name = "Scheduled QA",
            chatModelId = model.id,
            streamOutput = false,
            localTools = emptyList(),
        )
        val created = mutableListOf<String>()
        var scenario: ActivityScenario<RouteActivity>? = null
        try {
            // Keep the real app pipeline, interceptors and serializer. Only the loopback HTTP
            // fixture bypasses inherited app/system proxies; all other routes retain their selector.
            println("SCHEDULED_QA_PROXY selector=${originalSelector.javaClass.name} originalTypes=${originalSelector.select(URI(provider.baseUrl)).map { it.type() }}")
            assertEquals(listOf(Proxy.NO_PROXY), loopbackClient.proxySelector.select(URI(provider.baseUrl)))
            providerManager.registerProvider("openai", OpenAIProvider(loopbackClient, instrumentation.targetContext))
            settings.update {
                it.copy(
                    providers = it.providers + provider,
                    assistants = it.assistants + assistant,
                )
            }
            scenario = ActivityScenario.launch(RouteActivity::class.java)
            block(Fixture(tasks, settings, conversations, chatService, server, model, assistant, original.assistantId, created))
        } finally {
            server.release.countDown()
            try {
                created.forEach { id ->
                    val conversationId = tasks.tasks.value.firstOrNull { it.id == id }?.lastConversationId
                    tasks.delete(id)
                    conversationId?.let {
                        val uuid = Uuid.parse(it)
                        runCatching { chatService.stopGeneration(uuid) }
                        conversations.getConversationById(uuid)?.let { chat -> conversations.deleteConversation(chat) }
                    }
                }
            } finally {
                try {
                    settings.update {
                        it.copy(
                            providers = it.providers.filterNot { entry -> entry.id == provider.id },
                            assistants = it.assistants.filterNot { entry -> entry.id == assistant.id },
                            assistantId = original.assistantId,
                        )
                    }
                } finally {
                    try {
                        providerManager.registerProvider("openai", originalProvider)
                        loopbackClient.connectionPool.evictAll()
                    } finally {
                        try { scenario?.close() } finally { server.close() }
                    }
                }
            }
        }
    }

    private class Fixture(
        val tasks: ScheduledTaskRepository,
        val settings: SettingsStore,
        val conversations: ConversationRepository,
        val chatService: ChatService,
        val server: LocalModelServer,
        val model: Model,
        val assistant: Assistant,
        val originalAssistantId: Uuid,
        private val created: MutableList<String>,
    ) {
        val prompt = "scheduled-prompt-${Uuid.random()}"
        fun generationError(id: String?): String = chatService.errors.value
            .filter { it.conversationId?.toString() == id }
            .joinToString("\n") { it.error.stackTraceToString() }
        suspend fun createTask(delayMillis: Long = 1_500): ScheduledTask {
            val task = ScheduledTask(
                title = "Scheduled QA ${Uuid.random()}",
                assistantId = assistant.id.toString(),
                prompt = prompt,
                firstRunAtMillis = System.currentTimeMillis() + delayMillis,
            )
            created += task.id
            tasks.save(task)
            return tasks.tasks.value.single { it.id == task.id }
        }
    }

    private class LocalModelServer(holdResponse: Boolean) : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requests = CopyOnWriteArrayList<String>()
        val release = CountDownLatch(if (holdResponse) 1 else 0)
        private val worker = thread(isDaemon = true, name = "scheduled-loopback-model") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 15_000
                        val input = client.getInputStream().buffered()
                        val requestLine = line(input)
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val header = line(input)
                            if (header.isEmpty()) break
                            val colon = header.indexOf(':')
                            if (colon > 0) headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                        }
                        var bytes = if (headers["transfer-encoding"]?.contains("chunked") == true) {
                            val body = ByteArrayOutputStream()
                            while (true) {
                                val size = line(input).substringBefore(';').trim().toInt(16)
                                if (size == 0) { line(input); break }
                                body.write(readBytes(input, size))
                                line(input)
                            }
                            body.toByteArray()
                        } else readBytes(input, headers["content-length"]?.toInt() ?: 0)
                        if (headers["content-encoding"]?.contains("gzip") == true) {
                            bytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                        }
                        check(requestLine.startsWith("POST /v1/chat/completions "))
                        requests += bytes.toString(Charsets.UTF_8)
                        release.await(30, TimeUnit.SECONDS)
                        val body = """{"id":"scheduled-local","object":"chat.completion","created":1,"model":"scheduled-qa-loopback","choices":[{"index":0,"message":{"role":"assistant","content":"scheduled-local-ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":4,"total_tokens":9}}""".toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }

        override fun close() {
            release.countDown()
            socket.close()
            worker.join(1_000)
        }

        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0 || value == 10) break
                if (value != 13) bytes.write(value)
            }
            return bytes.toString("UTF-8")
        }

        private fun readBytes(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var position = 0
            while (position < count) {
                val read = input.read(bytes, position, count - position)
                check(read > 0) { "Incomplete loopback request" }
                position += read
            }
            return bytes
        }
    }
}
