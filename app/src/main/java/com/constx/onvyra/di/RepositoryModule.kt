package com.constx.onvyra.di

import android.content.Context
import com.constx.onvyra.data.files.FileFolders
import com.constx.onvyra.data.files.FilesManager
import com.constx.onvyra.data.skills.SkillManager
import com.constx.onvyra.data.repository.ConversationRepository
import com.constx.onvyra.data.repository.FavoriteRepository
import com.constx.onvyra.data.repository.FolderRepository
import com.constx.onvyra.data.repository.FilesRepository
import com.constx.onvyra.data.repository.GenMediaRepository
import com.constx.onvyra.data.repository.MediaCreationRepository
import com.constx.onvyra.data.repository.MemoryRepository
import com.constx.onvyra.data.repository.TranslationHistoryRepository
import com.constx.onvyra.data.repository.WorkspaceRepository
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        TranslationHistoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() },
                    target = "/builtin_skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get(), onWorkspaceDisabled = { root ->
            get<WorkspaceTerminalSessionManager>().closeWorkspace(root)
        })
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
