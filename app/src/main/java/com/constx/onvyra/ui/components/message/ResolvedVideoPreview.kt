package com.constx.onvyra.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.constx.onvyra.data.ai.tools.findResolvedVideoPreview
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.MovieCatalogClient
import com.constx.onvyra.data.media.MovieCatalogItem
import com.constx.onvyra.data.media.buildMovieSourceOptions
import com.constx.onvyra.data.media.movieEpisodeChoices
import com.constx.onvyra.data.media.MoviePlaybackResolver
import com.constx.onvyra.data.media.MoviePlaybackSelection
import com.constx.onvyra.data.media.MovieSourceOption
import com.constx.onvyra.data.media.MovieContentType
import com.constx.onvyra.data.media.hasNonWorkLabel
import com.constx.onvyra.data.media.mergeMovieSourceOptions
import com.constx.onvyra.data.media.MovieCatalogSourceRegistry
import com.constx.onvyra.data.media.MovieCatalogSourceDiscovery
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPreviewPayload
import com.constx.onvyra.data.media.VideoPreviewResolver
import com.constx.onvyra.data.media.parseVideoLink
import com.constx.onvyra.ui.context.LocalVideoPlaybackRecovery
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Display the movie/video the AI resolved, without asking it to print signed media addresses. */
@Composable
internal fun ResolvedVideoPreview(preview: VideoPreviewPayload) {
    val recoverPlayback = LocalVideoPlaybackRecovery.current
    val context = LocalContext.current.applicationContext
    val client = koinInject<OkHttpClient>()
    val resolver = remember(context, client) { VideoPreviewResolver(context, client) }
    val sourceRegistry = remember(context) { MovieCatalogSourceRegistry.get(context) }
    val sourceDiscovery = remember(context, client, sourceRegistry) { MovieCatalogSourceDiscovery(context, client, sourceRegistry) }
    val movieCatalog = remember(context, client, sourceRegistry) { MovieCatalogClient(client, extraSources = { sourceRegistry.all() }, context = context) }
    val movieResolver = remember(resolver, movieCatalog) {
        MoviePlaybackResolver(movieCatalog) { url ->
            resolver.resolve(parseVideoLink(url) ?: VideoLink(url, VideoPlatform.OTHER), enhanced = true)
        }
    }
    val link = remember(preview.originalUrl) {
        parseVideoLink(preview.originalUrl) ?: VideoLink(preview.originalUrl, VideoPlatform.OTHER)
    }
    val cacheUse = remember(preview.playbackId) { booleanArrayOf(false) }
    var selectedMovie by remember(preview.playbackId) { mutableStateOf<MoviePlaybackSelection?>(null) }
    var selectedOption by remember(preview.playbackId) { mutableStateOf<MovieSourceOption?>(null) }
    var listedOptions by remember(preview.playbackId) { mutableStateOf<Map<String, MovieSourceOption>>(emptyMap()) }
    val pendingSelections = remember(preview.playbackId) { mutableMapOf<String, MoviePlaybackSelection>() }
    val hasCatalog = preview.catalogSourceId != null && preview.catalogItemId != null
    var episodeItem by remember(preview.playbackId) { mutableStateOf<MovieCatalogItem?>(null) }
    var episodesLoading by remember(preview.playbackId) { mutableStateOf(false) }
    var episodesError by remember(preview.playbackId) { mutableStateOf(false) }
    val pendingEpisodes = remember(preview.playbackId) { mutableMapOf<Int, Pair<MoviePlaybackSelection, MovieSourceOption>>() }
    val currentEpisode = selectedMovie?.episode ?: preview.catalogEpisode
    val currentLine = selectedMovie?.lineName ?: preview.catalogLine
    val accessRequired = com.constx.onvyra.security.PrivateMediaAccess.isRestrictedSource(
        selectedMovie?.item?.sourceId ?: preview.catalogSourceId.orEmpty(),
    ) || com.constx.onvyra.security.PrivateMediaAccess.isRestrictedUrl(preview.originalUrl) ||
        (selectedMovie?.source?.url?.let(com.constx.onvyra.security.PrivateMediaAccess::isRestrictedUrl) ?: false)
    var privateAccessGranted by remember { mutableStateOf(com.constx.onvyra.security.PrivateMediaAccess.hasAccess()) }
    LaunchedEffect(accessRequired) {
        if (accessRequired) while (true) {
            privateAccessGranted = withContext(Dispatchers.IO) { com.constx.onvyra.security.PrivateMediaAccess.hasAccess() }
            delay(1_000)
        }
    }
    if (accessRequired && !privateAccessGranted) {
        // Removing the player from composition releases it, including a previously cached stream.
        androidx.compose.material3.Text("当前内容不可用。")
        return
    }
    LaunchedEffect(preview.playbackId, selectedMovie?.item?.sourceId, selectedMovie?.item?.id) {
        if (hasCatalog) {
            episodesLoading = true
            episodesError = false
            try {
                episodeItem = selectedMovie?.item ?: withContext(Dispatchers.IO) {
                    movieCatalog.getItem(preview.catalogSourceId!!, preview.catalogItemId!!)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { episodesError = true }
            finally { episodesLoading = false }
        }
    }
    val episodes = episodeItem?.let { movieEpisodeChoices(it, currentLine, selectedOption?.lineIndex) }.orEmpty()
    val showEpisodes = hasCatalog && (episodes.size > 1 || preview.requestedContentType in setOf(MovieContentType.SERIES, MovieContentType.ANIME))
    val sourceUpdates: Flow<List<VideoSourceOption>>? = remember(preview.playbackId, currentEpisode, preview.requestedContentType, movieResolver, movieCatalog, sourceDiscovery, sourceRegistry) {
        if (!hasCatalog) null else channelFlow<List<VideoSourceOption>> {
            val selected = withContext(Dispatchers.Main.immediate) { selectedMovie }
            val knownItem = withContext(Dispatchers.Main.immediate) { selected?.item ?: episodeItem }
            val sourceId = selected?.item?.sourceId ?: preview.catalogSourceId!!
            val itemId = selected?.item?.id ?: preview.catalogItemId!!
            val episode = selected?.episode ?: preview.catalogEpisode
            val line = selected?.lineName ?: preview.catalogLine
            val title = selected?.item?.title ?: preview.catalogTitle ?: preview.title?.substringBefore(" · ")
            val year = selected?.item?.year ?: preview.catalogYear
            val contentType = selected?.contentType ?: preview.requestedContentType
            suspend fun publish(options: List<MovieSourceOption>) {
                val display = withContext(Dispatchers.Main.immediate) {
                    // Parallel discovery rounds add choices; a slower snapshot cannot erase them.
                    val expectedTitle = title ?: options.firstOrNull { it.sourceId == sourceId && it.itemId == itemId }?.title
                        ?: listedOptions.values.firstOrNull { it.sourceId == sourceId && it.itemId == itemId }?.title
                    listedOptions = if (expectedTitle == null) emptyMap() else mergeMovieSourceOptions(
                        listedOptions.values.toList(), options, sourceId, itemId, episode, expectedTitle, year, contentType,
                    ).associateBy { it.id }
                    val chosenId = selectedOption?.id
                    listedOptions.values.map {
                        VideoSourceOption(it.id, it.label, if (chosenId != null) it.id == chosenId else it.selected,
                            it.qualityHint?.let { quality -> "$quality · ${it.label.substringBefore(" · ")} · ${it.lineName}" }
                                ?: "线路 · ${it.lineName} · ${it.label.substringBefore(" · ")}")
                    }
                }
                send(display)
            }
            publish(emptyList())
            coroutineScope {
                val queried = movieCatalog.sourceIdsForSearch().toMutableSet()
                val initialIds = queried.toSet()
                launch {
                    try { movieResolver.optionUpdates(sourceId, itemId, episode, line, title, year,
                        sourceIds = initialIds, currentItem = knownItem, contentType = contentType).collect { publish(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Newly discovered catalogs may still succeed. */ }
                }
                // Discovery never sits in front of the existing catalogs' first result.
                // Reuse the application-wide warmup. It persists independently;
                // reopening a menu must not force another 35-second crawl.
                val discovery = sourceDiscovery.warmUp()
                withTimeoutOrNull(6_000) { do {
                    delay(750)
                    val added = movieCatalog.sourceIdsForSearch() - queried
                    if (added.isNotEmpty()) {
                        queried.addAll(added)
                        launch {
                            withTimeoutOrNull(16_000) {
                                try { movieResolver.optionUpdates(sourceId, itemId, episode, line, title, year,
                                    sourceIds = added, currentItem = knownItem, contentType = contentType).collect { publish(it) } }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* Keep choices already found by another round. */ }
                            }
                        }
                    }
                } while (discovery?.isActive == true) }
            }
        }
    }
    InlineVideoPreview(link, initiallyEnhanced = true,
        onSourcesExhausted = recoverPlayback?.let { recover -> { recover(preview.copy(
            requestedContentType = selectedMovie?.contentType ?: preview.requestedContentType,
            catalogSourceId = selectedMovie?.item?.sourceId ?: preview.catalogSourceId,
            catalogItemId = selectedMovie?.item?.id ?: preview.catalogItemId,
            catalogEpisode = currentEpisode,
            catalogLine = currentLine,
            catalogTitle = selectedMovie?.item?.title ?: preview.catalogTitle,
            catalogYear = selectedMovie?.item?.year ?: preview.catalogYear,
        )) } },
        playbackHttpClient = client,
        allowPlatformPlayer = preview.catalogSourceId == null,
        originalLink = preview.originalUrl.takeIf { preview.catalogSourceId == null },
        sourceOptionsUpdates = sourceUpdates,
        episodeOptions = episodes.map { VideoEpisodeOption(it.number, it.label, it.number == currentEpisode) },
        episodesLoading = episodesLoading,
        episodesError = episodesError,
        resolveEpisode = if (!showEpisodes) null else ({ number ->
            val snapshot = withContext(Dispatchers.Main.immediate) {
                Triple(episodeItem ?: error("剧集目录尚未就绪"), selectedMovie, selectedOption)
            }
            val item = snapshot.first
            val line = snapshot.second?.lineName ?: preview.catalogLine
            val type = snapshot.second?.contentType ?: preview.requestedContentType
            val lineIndex = snapshot.third?.lineIndex ?: item.lines.indexOfFirst { it.name == line }
            val options = buildMovieSourceOptions(listOf(item), item, number, line, contentType = type)
            val option = (if (lineIndex >= 0) options.firstOrNull { it.lineIndex == lineIndex }
                else options.firstOrNull()) ?: error("该线路没有所选集数")
            val selection = movieResolver.resolveOption(option)
            withContext(Dispatchers.Main.immediate) { pendingEpisodes[number] = selection to option }
            selection.source
        }),
        onEpisodeSelected = { number -> pendingEpisodes.remove(number)?.let { (selection, option) ->
            selectedMovie = selection
            selectedOption = option
            episodeItem = selection.item
            listedOptions = emptyMap()
        } },
        loadSourceOptions = if (!hasCatalog) null else ({
            sourceUpdates!!.lastOrNull().orEmpty()
        }),
        resolveSourceOption = if (!hasCatalog) null else ({ id ->
            val option = withContext(Dispatchers.Main.immediate) { listedOptions[id] }
                ?: error("片源列表已变化，请重新打开列表")
            val selection = movieResolver.resolveOption(option)
            withContext(Dispatchers.Main.immediate) { pendingSelections[id] = selection }
            selection.source
        }),
        onSourceSelected = { id -> pendingSelections.remove(id)?.let {
            selectedMovie = it
            selectedOption = listedOptions[id]
        } },
        resolveVideo = { requested, enhanced ->
        com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(
            sourceId = selectedMovie?.item?.sourceId ?: preview.catalogSourceId, url = preview.originalUrl,
        )
        val source = if (enhanced && !cacheUse[0]) {
            cacheUse[0] = true
            findResolvedVideoPreview(preview.playbackId)
        } else null
        val current = withContext(Dispatchers.Main.immediate) { selectedMovie }
        val chosen = withContext(Dispatchers.Main.immediate) { selectedOption }
        (source ?: if (hasCatalog) {
            val refreshed = if (chosen != null) movieResolver.resolveOption(chosen) else movieResolver.refresh(current?.item?.sourceId ?: preview.catalogSourceId!!,
                current?.item?.id ?: preview.catalogItemId!!, current?.episode ?: preview.catalogEpisode,
                current?.lineName ?: preview.catalogLine, current?.contentType ?: preview.requestedContentType)
            withContext(Dispatchers.Main.immediate) { selectedMovie = refreshed }
            refreshed.source
        } else resolver.resolve(requested, enhanced = enhanced)).let {
            com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(
                sourceId = selectedMovie?.item?.sourceId ?: preview.catalogSourceId, url = it.url,
            )
            val contentType = current?.contentType ?: preview.requestedContentType
            require(contentType == MovieContentType.GENERAL_VIDEO || !hasNonWorkLabel(it.title.orEmpty())) {
                "该来源返回了解说、预告或剪辑，不能作为当前正片"
            }
            if (it.title.isNullOrBlank() && !preview.title.isNullOrBlank()) it.copy(title = preview.title) else it
        }
    })
}
