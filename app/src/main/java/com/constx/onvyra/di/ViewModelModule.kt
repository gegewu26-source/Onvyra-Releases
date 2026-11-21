package com.constx.onvyra.di

import com.constx.onvyra.ui.pages.assistant.AssistantVM
import com.constx.onvyra.ui.pages.assistant.detail.AssistantDetailVM
import com.constx.onvyra.ui.pages.backup.BackupVM
import com.constx.onvyra.ui.pages.chat.ChatDrawerVM
import com.constx.onvyra.ui.pages.chat.ChatVM
import com.constx.onvyra.ui.pages.debug.DebugVM
import com.constx.onvyra.ui.pages.favorite.FavoriteVM
import com.constx.onvyra.ui.pages.search.SearchVM
import com.constx.onvyra.ui.pages.history.HistoryVM
import com.constx.onvyra.ui.pages.stats.StatsVM
import com.constx.onvyra.ui.pages.imggen.ImgGenVM
import com.constx.onvyra.ui.pages.mediacreation.MediaCreationSessionsVM
import com.constx.onvyra.ui.pages.mediacreation.MediaCreationVM
import com.constx.onvyra.ui.pages.memory.MemoryFileVM
import com.constx.onvyra.ui.pages.extensions.PromptVM
import com.constx.onvyra.ui.pages.extensions.QuickMessagesVM
import com.constx.onvyra.ui.pages.extensions.skills.SkillDetailVM
import com.constx.onvyra.ui.pages.extensions.skills.SkillsVM
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspaceDetailVM
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspaceVM
import com.constx.onvyra.ui.pages.setting.SettingVM
import com.constx.onvyra.ui.pages.share.handler.ShareHandlerVM
import com.constx.onvyra.ui.pages.translator.TranslatorVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.core.qualifier.named
import okio.Path.Companion.toOkioPath

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            memoryConsolidationScheduler = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModel<MemoryFileVM> {
        MemoryFileVM(
            memoryId = it[0],
            path = it[1],
            memoryRepository = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::MediaCreationSessionsVM)
    viewModel<MediaCreationVM> {
        MediaCreationVM(
            id = it.get(),
            context = get(),
            settingsStore = get(),
            repository = get(),
            service = get(),
            remoteFileStore = get(),
            appScope = get(),
        )
    }
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            terminalSessionManager = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)

    single { com.constx.onvyra.ui.pages.novel.NovelAIService(get(), get()) }
    single<okhttp3.OkHttpClient>(named("readerHttpClient")) {
        // Reuse proxy/TLS configuration, without AI request transforms or body logging.
        get<okhttp3.OkHttpClient>().newBuilder().apply {
            interceptors().clear()
            networkInterceptors().clear()
        }.connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(18, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    single<coil3.ImageLoader>(named("readerImageLoader")) {
        val context = get<android.content.Context>()
        val client = get<okhttp3.OkHttpClient>(named("readerHttpClient"))
        coil3.ImageLoader.Builder(context).components {
            add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                callFactory = { client },
                cacheStrategy = { coil3.network.cachecontrol.CacheControlCacheStrategy() },
                connectivityChecker = { coil3.network.ConnectivityChecker.ONLINE },
            ))
        }.diskCache {
            coil3.disk.DiskCache.Builder()
                .directory(java.io.File(context.cacheDir, "reader-images").toOkioPath())
                .maxSizeBytes(64L * 1024 * 1024).build()
        }.build()
    }
    single { com.constx.onvyra.ui.pages.novel.NovelCoverResolver(get(named("readerHttpClient")),
        java.io.File(get<android.content.Context>().cacheDir, "reader-cover-metadata"), referenceProviders = true) }
    single {
        val context = get<android.content.Context>()
        com.constx.onvyra.ui.pages.novel.ReaderSourceLibrary {
            context.assets.open(com.constx.onvyra.ui.pages.novel.ReaderSourceLibrary.ASSET_PATH)
                .bufferedReader().use { it.readText() }
        }
    }
    single {
        com.constx.onvyra.ui.pages.novel.NovelViewModel(
            context = get(), aiService = getOrNull(), coverResolver = get(),
            engine = com.constx.onvyra.ui.pages.novel.NovelSourceEngine(
                get(named("readerHttpClient")), get<android.content.Context>().cacheDir, get()
            )
        )
    }
}
