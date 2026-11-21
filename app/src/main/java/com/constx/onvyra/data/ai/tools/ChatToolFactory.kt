package com.constx.onvyra.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.data.ai.mcp.McpManager
import com.constx.onvyra.data.ai.mcp.resolveArguments
import com.constx.onvyra.data.ai.mcp.schemaWithSavedArguments
import com.constx.onvyra.data.ai.tools.local.LocalTools
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPreviewResolver
import com.constx.onvyra.data.media.MovieCatalogClient
import com.constx.onvyra.data.media.MoviePlaybackResolver
import com.constx.onvyra.data.media.MovieCatalogSourceRegistry
import com.constx.onvyra.data.media.MovieCatalogSourceDiscovery
import com.constx.onvyra.data.media.MovieWebSearchFallback
import com.constx.onvyra.data.media.MovieContentType
import com.constx.onvyra.data.media.MovieCatalogLookupException
import com.constx.onvyra.data.media.parseVideoLink
import com.constx.onvyra.data.skills.SkillManager
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.repository.ConversationRepository
import com.constx.onvyra.data.repository.MemoryRepository
import com.constx.onvyra.data.repository.WorkspaceRepository
import com.constx.onvyra.data.repository.memoryId
import com.constx.onvyra.data.netdisk.NetDiskChatCoordinator
import com.constx.onvyra.data.netdisk.NetDiskRepository
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadManager
import com.constx.onvyra.data.netdisk.download.NetDiskDestinationStore
import me.rerere.workspace.WorkspaceShellStatus
import okhttp3.OkHttpClient
import java.io.File
import com.constx.onvyra.data.files.FileFolders
import com.constx.onvyra.data.files.GalleryMediaExporter
import com.constx.onvyra.data.files.GalleryStoragePermissionRequiredException
import me.rerere.androidcontrol.AndroidControl
import com.constx.onvyra.data.model.AndroidControlPermissionMode

private const val TAG = "ChatToolFactory"

private data class MovieSearchAttemptKey(
    val title: String,
    val year: Int?,
    val episode: Int,
    val contentType: MovieContentType,
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    httpClient: OkHttpClient,
    context: Context,
    private val androidControlFiles: com.constx.onvyra.data.files.FilesManager,
    private val mediaCreationService: com.constx.onvyra.service.MediaCreationService,
    private val mediaCreationRepository: com.constx.onvyra.data.repository.MediaCreationRepository,
) {
    private val androidControlContext = context.applicationContext
    private val netDiskRepository by lazy { NetDiskRepository(androidControlContext) }
    private val netDiskProviderMutex = Mutex()
    private val netDiskChatCoordinator by lazy {
        NetDiskChatCoordinator(
            open = { text, password -> netDiskProviderMutex.withLock { netDiskRepository.open(text, password) } },
            list = { session, directoryId, cursor -> netDiskProviderMutex.withLock { netDiskRepository.list(session, directoryId, cursor) } },
            resolve = { session, file -> netDiskProviderMutex.withLock { netDiskRepository.resolve(session, file) } },
            enqueue = { link -> NetDiskDownloadManager.get(androidControlContext).enqueue(link) },
            tasks = { NetDiskDownloadManager.get(androidControlContext).tasks.value },
            destinationLabel = { NetDiskDestinationStore.get(androidControlContext).current.value.label },
            destinationKey = { NetDiskDestinationStore.get(androidControlContext).current.value.treeUri ?: "default_download_directory" },
            enqueueWithDestination = { link, key ->
                NetDiskDownloadManager.get(androidControlContext).enqueue(link, expectedDestinationKey = key)
            },
        )
    }
    private val webFetchTool = createWebFetchTool(WebPageFetcher(httpClient, AndroidDynamicWebPageRenderer(context)))
    private val videoPreviewResolver = VideoPreviewResolver(context, httpClient)
    private val videoResolveTool = createVideoResolveTool { url ->
        videoPreviewResolver.resolve(parseVideoLink(url) ?: VideoLink(url, VideoPlatform.OTHER), enhanced = true)
    }
    private val movieSourceRegistry = MovieCatalogSourceRegistry.get(context)
    private val movieSourceDiscovery = MovieCatalogSourceDiscovery(context, httpClient, movieSourceRegistry)
    private val movieCatalogClient = MovieCatalogClient(httpClient, extraSources = { movieSourceRegistry.all() }, context = context)
    private val moviePlaybackResolver = MoviePlaybackResolver(movieCatalogClient) { url ->
        videoPreviewResolver.resolve(parseVideoLink(url) ?: VideoLink(url, VideoPlatform.OTHER), enhanced = true)
    }

    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        readContextHistory: (suspend () -> List<UIMessage>)? = null,
        androidControlOwner: String = assistant.id.toString(),
        onAndroidControlUserStop: (() -> Unit)? = null,
        shouldReplan: () -> Boolean = { false },
        latestUserInstruction: () -> me.rerere.ai.ui.UIMessage? = { null },
        requestRevision: () -> Long = { 0L },
    ): List<Tool> = buildList {
        com.constx.onvyra.security.AppIntegrity.requireTrusted()
        val media = ChatMediaRegistry(androidControlOwner,
            listOf(File(androidControlContext.filesDir, FileFolders.UPLOAD), File(androidControlContext.filesDir, "images")),
            File(androidControlContext.filesDir, FileFolders.MEDIA_CREATION))
        suspend fun refreshMedia() = withContext(Dispatchers.IO) {
            media.includeHistory(readContextHistory?.invoke().orEmpty(), mediaCreationRepository::getRecord)
        }
        val phoneControl = AndroidControl.get(androidControlContext)
        if (phoneControl.enabled) refreshMedia()
        else media.rememberHistoryRecords(readContextHistory?.invoke().orEmpty())
        val generateWithMediaReferences: suspend (me.rerere.mediagen.provider.MediaGenerationProviderSetting,
            me.rerere.mediagen.model.MediaGenerationModel, String, com.constx.onvyra.data.model.MediaCreationParams) -> List<me.rerere.ai.ui.UIMessagePart> =
            { provider, imageModel, prompt, params ->
                val parts = mediaCreationService.generateChatImage(provider, imageModel, prompt, params)
                withContext(Dispatchers.IO) { media.captureGenerated(parts) }
            }
        addAll(createAndroidControlTools(androidControlContext, androidControlOwner, model, androidControlFiles,
            assistant.androidControlPermissionMode, onAndroidControlUserStop, shouldReplan, latestUserInstruction))
        if (phoneControl.enabled) {
            val gallery = GalleryMediaExporter(androidControlContext)
            val picker = ChatMediaPickerLocator(androidControlContext, phoneControl, androidControlOwner,
                shouldReplan, onAndroidControlUserStop)
            addAll(createChatMediaExportTools(media, ::refreshMedia, { phoneControl.enabled },
                { AndroidControlPermissionMode.valueOf(phoneControl.currentApprovalMode(androidControlOwner).name) },
                export = { asset, name ->
                    val saved = runInterruptible(Dispatchers.IO) { gallery.export(asset.file, asset.mimeType, name) }
                    SavedChatMedia(saved.uri.toString(), saved.displayName, saved.mimeType, saved.sizeBytes, saved.relativePath,
                        saved.savedAtMillis, saved.width, saved.height)
                },
                storagePermission = { (it as? GalleryStoragePermissionRequiredException)?.permission },
                savedStillReadable = { saved ->
                    runInterruptible(Dispatchers.IO) {
                        savedChatMediaStillReadable(androidControlContext, saved)
                    }
                },
                locateInPicker = picker::locate,
                verifySaved = { asset, saved ->
                    runInterruptible(Dispatchers.IO) { savedChatMediaStillReadable(androidControlContext, saved, asset.file) }
                },
            ))
        }
        add(webFetchTool)
        addAll(createNetDiskTools(netDiskChatCoordinator, androidControlOwner))
        createNativeChatImageTool(settings, model, requestRevision, generateWithMediaReferences)?.let(::add)
        if (me.rerere.ai.provider.ModelAbility.TOOL in model.abilities) {
            addAll(createMediaGenerationTools(settings,
                submit = { provider, mediaModel, prompt, params ->
                    require(mediaModel.kind == me.rerere.mediagen.model.MediaKind.VIDEO) {
                        "Chat images must use the direct chat result path"
                    }
                    val session = mediaCreationRepository.getOrCreateEmptySession(
                        com.constx.onvyra.data.model.MediaCreationDraft(modelId = mediaModel.id)
                    )
                    mediaCreationService.submit(com.constx.onvyra.service.MediaCreationSubmission(
                        sessionId = session.id, provider = provider, model = mediaModel,
                        prompt = prompt, params = params, assets = emptyList(),
                    )).also { media.rememberSubmittedRecord(it); withContext(Dispatchers.IO) { media.registerRecord(it) } }
                },
                getRecord = { id ->
                    if (!media.knowsRecord(id)) null else mediaCreationRepository.getRecord(id)?.also {
                        withContext(Dispatchers.IO) { media.registerRecord(it) }
                    }
                },
                currentModel = model,
                generateChatImage = generateWithMediaReferences,
                requestRevision = requestRevision,
            ))
        }
        add(videoResolveTool)
        val searchVideoPages = createVideoWebSearcher(settings)
        val webFallback = MovieWebSearchFallback(
            searchWeb = searchVideoPages, readPage = videoPreviewResolver::inspectPage,
            resolveVideo = { url -> videoPreviewResolver.resolve(parseVideoLink(url) ?: VideoLink(url, VideoPlatform.OTHER), enhanced = true) },
        )
        val attemptedSourceIds = java.util.concurrent.ConcurrentHashMap<MovieSearchAttemptKey, Set<String>>()
        // Refresh configuration APIs independently; cached discovered catalogs
        // already participate in the first lookup even if a built-in succeeds.
        movieSourceDiscovery.warmUp()
        add(createTypedVideoSearchTool(
            discoverAndPlay = { title, year, episode, contentType ->
                val discovered = movieSourceDiscovery.discover()
                val freshIds = (movieCatalogClient.sourceIdsForSearch() + discovered.map { it.id }) -
                    attemptedSourceIds[MovieSearchAttemptKey(title, year, episode, contentType)].orEmpty()
                check(freshIds.isNotEmpty()) { "No additional catalog API was discovered" }
                moviePlaybackResolver.searchAndResolve(title, year, episode, freshIds, contentType)
            },
            webFallback = webFallback::search,
            sourceInventory = { movieSourceDiscovery.inventory(movieCatalogClient) },
        ) { title, year, episode, contentType ->
            val requestKey = MovieSearchAttemptKey(title, year, episode, contentType)
            attemptedSourceIds[requestKey] = emptySet()
            try {
                moviePlaybackResolver.searchAndResolve(title, year, episode, sourceIds = null, contentType = contentType).also {
                    attemptedSourceIds[requestKey] = it.searchReport?.attemptedSourceIds.orEmpty()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (ambiguous: com.constx.onvyra.data.media.MovieTitleAmbiguousException) {
                throw ambiguous
            } catch (error: Exception) {
                val firstReport = (error as? MovieCatalogLookupException)?.report
                attemptedSourceIds[requestKey] = firstReport?.attemptedSourceIds.orEmpty()
                throw error
            }
        })
        add(createVideoSourceSearchTool(searchVideoPages))
        if (readContextHistory != null) add(createContextHistoryTool(readContextHistory))
        if (assistant.enableMemory) {
            addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
        }
        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createSearchTools(settings))
        }
        val optionalLocalTools = localTools.getTools(assistant.localTools)
        addAll(optionalLocalTools)
        // Clarification is part of task interaction, available to existing conversations as well.
        if (optionalLocalTools.none { it.name == "ask_user" }) add(localTools.askUserTool)
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = withContext(Dispatchers.IO) { skillManager.listSkills() },
                )
            )
        }

        if (assistant.mcpServers.isNotEmpty()) {
            // Metadata only. Refresh cached catalogs once per run, independently for each server.
            mcpManager.syncSelected(assistant, force = false)
            add(createMcpStatusTool(mcpManager, assistant))
        }
        val mcpTools = mcpManager.getAllAvailableTools(assistant)
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = (tool.description ?: "") + if (tool.defaultArguments.isNotEmpty())
                        " Omitted arguments use the user's saved defaults shown in the schema. Explicit arguments override defaults." else "",
                    parameters = { tool.schemaWithSavedArguments() },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, tool.resolveArguments(it.jsonObject)) },
                )
            )
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (!workspace.enabled || workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
