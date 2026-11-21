package com.constx.onvyra.di

import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import com.constx.onvyra.AppScope
import com.constx.onvyra.data.ai.tools.local.LocalTools
import com.constx.onvyra.data.ai.tools.ChatToolFactory
import com.constx.onvyra.data.event.AppEventBus
import com.constx.onvyra.service.ChatNotificationManager
import com.constx.onvyra.service.ChatService
import com.constx.onvyra.data.schedule.ScheduledTaskRepository
import com.constx.onvyra.service.MediaCreationService
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import com.constx.onvyra.utils.EmojiData
import com.constx.onvyra.utils.AppAnalytics
import com.constx.onvyra.utils.EmojiUtils
import com.constx.onvyra.utils.JsonInstant
import com.constx.onvyra.utils.SoundEffectPlayer
import com.constx.onvyra.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single { ScheduledTaskRepository(get(), get(), get()) }
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        AppAnalytics(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
            conversationRepository = get(),
        )
    }

    single {
        ChatToolFactory(
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            httpClient = get(),
            context = get(),
            androidControlFiles = get(),
            mediaCreationService = get(),
            mediaCreationRepository = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationLoop = get(),
            memoryConsolidationScheduler = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get()
        )
    }

    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            remoteFileStore = get(),
            okHttpClient = get(),
            providerManager = get(),
            filesManager = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
