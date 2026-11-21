package com.constx.onvyra.ui.pages.novel

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import com.constx.onvyra.utils.UiCrashBreadcrumbs
import kotlin.coroutines.coroutineContext

class NovelViewModel(
    private val context: Context,
    private val engine: NovelSourceEngine = NovelSourceEngine(),
    private val aiService: NovelAIService? = null,
    internal val coverResolver: NovelCoverResolver? = null,
    private val suppliedStoreRepository: ReaderStoreRepository? = null,
) : ViewModel() {
    init {
        coverResolver?.useSourceLookup(engine::resolveSourceCover)
        engine.useReadingRouteDirectory(File(context.filesDir, "reader-reading-routes-v1"))
        engine.useSourceDiscoveryDirectory(File(context.filesDir, "reader-source-discovery-v1"))
    }
    val library = ReaderBookLibrary(File(context.filesDir, "reader-library-v8"), engine, viewModelScope)
    private val storeRepository by lazy { suppliedStoreRepository ?: engine.storeRepository(File(context.cacheDir,"reader-store-v9")) }
    private val _storeHome = MutableStateFlow<ReaderStoreHome?>(null)
    val storeHome = _storeHome.asStateFlow()
    private val _storeLoading = MutableStateFlow(false)
    val storeLoading = _storeLoading.asStateFlow()
    private val _storeError = MutableStateFlow<String?>(null)
    val storeError = _storeError.asStateFlow()
    private val _storeCategory = MutableStateFlow<ReaderStoreCategoryPage?>(null)
    val storeCategory = _storeCategory.asStateFlow()
    private var storeJob: Job? = null
    private var categoryJob: Job? = null
    private var storeGeneration = 0
    private var categoryGeneration = 0
    private var storeChannel = "male"
    private val _readerNotice = MutableStateFlow<String?>(null)
    val readerNotice = _readerNotice.asStateFlow()
    private var visibleParagraph = 0
    private var visibleOffset = 0
    private var visibleQuote = ""
    private var visibleChapter = 0
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val diskLock = Mutex()
    private val shelfLock = Mutex()
    private val bookshelfFile by lazy { File(context.filesDir, "novel_bookshelf.json") }
    private val comicBookshelfFile by lazy { File(context.filesDir, "comic_bookshelf.json") }
    private val settingsFile by lazy { File(context.filesDir, "novel_settings.json") }
    private val historyFile by lazy { File(context.filesDir, "novel_search_history.json") }

    private val _bookshelf = MutableStateFlow<List<NovelBook>>(emptyList())
    val bookshelf = _bookshelf.asStateFlow()
    private val _comicBookshelf = MutableStateFlow<List<NovelBook>>(emptyList())
    val comicBookshelf = _comicBookshelf.asStateFlow()
    private val _selectedComicSourceId = MutableStateFlow(NovelSourceEngine.SOURCE_MERGE)
    val selectedComicSourceId = _selectedComicSourceId.asStateFlow()
    private val _comicPages = MutableStateFlow<List<String>>(emptyList())
    val comicPages = _comicPages.asStateFlow()
    private val _readerSettings = MutableStateFlow(NovelReaderSettings())
    val readerSettings = _readerSettings.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _selectedSourceId = MutableStateFlow(NovelSourceEngine.SOURCE_MERGE)
    val selectedSourceId = _selectedSourceId.asStateFlow()
    private val _importedSources = MutableStateFlow<List<ImportedReaderSource>>(emptyList())
    val importedSources = _importedSources.asStateFlow()
    private val _activeMediaType = MutableStateFlow(0)
    val activeMediaType = _activeMediaType.asStateFlow()
    private val _selectedAudioSourceId = MutableStateFlow(NovelSourceEngine.SOURCE_MERGE)
    val selectedAudioSourceId = _selectedAudioSourceId.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
    private val _searchStatus = MutableStateFlow("")
    val searchStatus = _searchStatus.asStateFlow()
    private val _canContinueSearch = MutableStateFlow(false)
    val canContinueSearch = _canContinueSearch.asStateFlow()
    private var aggregateProgress: ReaderSearchProgress? = null
    private val _searchResults = MutableStateFlow<List<NovelBook>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory = _searchHistory.asStateFlow()
    private val _discoverBooks = MutableStateFlow<List<NovelBook>>(emptyList())
    val discoverBooks = _discoverBooks.asStateFlow()
    private val _isLoadingDiscover = MutableStateFlow(false)
    val isLoadingDiscover = _isLoadingDiscover.asStateFlow()
    private val _currentBook = MutableStateFlow<NovelBook?>(null)
    val currentBook = _currentBook.asStateFlow()
    // Presentation ownership follows the user's open request, not a mutable
    // cross-source title. A verified catalogue retitling can change that title.
    private val _bookOpenRequestId = MutableStateFlow<String?>(null)
    val bookOpenRequestId = _bookOpenRequestId.asStateFlow()
    private val _isDiscoveringBookSources = MutableStateFlow(false)
    val isDiscoveringBookSources = _isDiscoveringBookSources.asStateFlow()
    private val _bookSourceStatus = MutableStateFlow("")
    val bookSourceStatus = _bookSourceStatus.asStateFlow()
    private val _isSwitchingSource = MutableStateFlow(false)
    val isSwitchingSource = _isSwitchingSource.asStateFlow()
    private val _catalog = MutableStateFlow<List<NovelChapter>>(emptyList())
    val catalog = _catalog.asStateFlow()
    private val _isLoadingCatalog = MutableStateFlow(false)
    val isLoadingCatalog = _isLoadingCatalog.asStateFlow()
    private val _isCatalogComplete = MutableStateFlow(false)
    val isCatalogComplete = _isCatalogComplete.asStateFlow()
    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex = _currentChapterIndex.asStateFlow()
    private val _chapterContent = MutableStateFlow("")
    val chapterContent = _chapterContent.asStateFlow()
    private val _enterChapterAtEnd = MutableStateFlow(false)
    val enterChapterAtEnd = _enterChapterAtEnd.asStateFlow()
    private val _continuousSections = MutableStateFlow<List<ContinuousReaderSection>>(emptyList())
    val continuousSections = _continuousSections.asStateFlow()
    private val _continuousAnchor = MutableStateFlow(ContinuousScrollAnchor())
    val continuousAnchor = _continuousAnchor.asStateFlow()
    private val _isLoadingChapter = MutableStateFlow(false)
    val isLoadingChapter = _isLoadingChapter.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private var searchJob: Job? = null
    private var readJob: Job? = null
    private var catalogJob: Job? = null
    private var settingsSaveJob: Job? = null
    private var readerGeneration = 0
    private var discoverJob: Job? = null
    private var coverJob: Job? = null
    private var discoverCoverJob: Job? = null
    private var prefetchJob: Job? = null
    private data class EarlyChapter(val source: String, val url: String, val result: Deferred<String?>)
    private var earlyChapter: EarlyChapter? = null
    private var searchGeneration = 0
    private var discoverGeneration = 0
    private val continuousJobs = mutableMapOf<Int, Job>()
    private val continuousPermits = Semaphore(2)
    private var continuousProgressJob: Job? = null
    private var earlyPreviewJob: Job? = null
    private var discoverWarmJob: Job? = null
    @Volatile private var discoverWarmTarget: Pair<String, String>? = null
    @Volatile private var stopWarmAfterCurrent = false
    private val failedReaderSources = mutableSetOf<String>()
    private var bookSourceJob: Job? = null
    private var bookSourceGeneration = 0
    private var manualSourceSelection: String? = null
    private var openRequestGeneration = 0
    private var openRequestJob: Job? = null
    private var sourceDiscoveryAfterContent = false
    private var sourceDiscoveryStartJob: Job? = null
    private val bookSourceProgress = object : LinkedHashMap<String, ReaderSearchProgress>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReaderSearchProgress>) = size > 8
    }
    private val _bookSourceAvailability = MutableStateFlow<Map<String, ReaderSourceAvailability>>(emptyMap())
    internal val bookSourceAvailability = _bookSourceAvailability.asStateFlow()

    private suspend fun openWithSavedSources(book: NovelBook, forceCatalog: Boolean) {
        val state = engine.restoreBookSources(book)
        openResolvedBook(state.book, forceCatalog)
        applySourceState(state)
        sourceDiscoveryAfterContent = state.hasSnapshot && state.progress.completedSources < state.progress.eligibleSources
    }
    private fun applySourceState(state: ReaderSourceDiscoveryState) {
        _bookSourceAvailability.value = state.availability
        bookSourceProgress[ReaderBookIdentity.key(state.book)] = state.progress
        val usable = state.availability.values.count { it.status == ReaderSourceAvailability.USABLE }
        _bookSourceStatus.value = "已查 ${state.progress.completedSources}/${state.progress.eligibleSources} 个来源 · 匹配 ${state.book.sourceCount} 个 · 已验证章节 $usable 个" +
            if (state.progress.finished && state.progress.completedSources < state.progress.eligibleSources)
                " · 本次已暂停，剩余 ${state.progress.eligibleSources - state.progress.completedSources} 个待续查" else ""
    }

    // Load existing data before read-modify-write; never seed synthetic books.
    private val initialization = viewModelScope.launch(Dispatchers.IO) {
        loadStored(bookshelfFile, _bookshelf)
        loadStored(comicBookshelfFile, _comicBookshelf)
        loadStored(settingsFile, _readerSettings)
        loadStored(historyFile, _searchHistory)
        try {
            _importedSources.value = engine.sourceLibrary?.all().orEmpty()
        } catch (e: Exception) { _errorMessage.value = errorText("书源库加载失败", e) }
    }
    private inline fun <reified T> loadStored(file: File, target: MutableStateFlow<T>) {
        if (!file.exists()) return
        try {
            target.value = json.decodeFromString(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            _errorMessage.value = "本地阅读数据未能加载，原文件已保留。"
        }
    }
    private suspend fun persist(file: File, text: String) = withContext(Dispatchers.IO) {
        diskLock.withLock { writeAtomic(file, text) }
    }
    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun updateSearchQuery(query: String) {
        if (_searchQuery.value != query) { clearSearchResults(); _searchQuery.value = query }
    }
    fun setMediaType(type: Int) {
        require(type in 0..2)
        if (_activeMediaType.value == type) return
        _activeMediaType.value = type
        clearSearchResults()
        discoverGeneration++; discoverJob?.cancel(); discoverCoverJob?.cancel()
        discoverWarmJob?.cancel()
        _discoverBooks.value = emptyList(); _isLoadingDiscover.value = false
    }
    fun selectSource(sourceId: String) {
        if (NovelSourceEngine.AVAILABLE_SOURCES.any { it.id == sourceId && it.enabled } ||
            _importedSources.value.any { it.id == sourceId && it.type == 0 && it.enabled }) _selectedSourceId.value = sourceId
    }
    fun selectComicSource(sourceId: String) {
        if (sourceId == NovelSourceEngine.SOURCE_MERGE || NovelSourceEngine.AVAILABLE_COMIC_SOURCES.any { it.id == sourceId && it.enable } ||
            _importedSources.value.any { it.id == sourceId && it.type == 2 && it.enabled }) _selectedComicSourceId.value = sourceId
    }
    fun selectAudioSource(sourceId: String) {
        if (sourceId == NovelSourceEngine.SOURCE_MERGE || _importedSources.value.any { it.id == sourceId && it.type == 1 && it.enabled }) _selectedAudioSourceId.value = sourceId
    }
    fun searchBooks(keyword: String? = null, isComic: Boolean = false, isAudio: Boolean = false, resume: Boolean = false) {
        val query = (keyword ?: _searchQuery.value).trim()
        if (query.isEmpty()) return
        val type = when { isComic -> 2; isAudio -> 1; else -> 0 }
        val previous = if (resume && query == _searchQuery.value && type == _activeMediaType.value) aggregateProgress else null
        setMediaType(type)
        val generation = ++searchGeneration
        searchJob?.cancel()
        coverJob?.cancel()
        coverJob = null
        discoverJob?.cancel()
        discoverGeneration++; discoverCoverJob?.cancel()
        discoverWarmJob?.cancel()
        _isLoadingDiscover.value = false
        _searchQuery.value = query
        if (previous == null) { _searchResults.value = emptyList(); aggregateProgress = null }
        _isSearching.value = true
        _canContinueSearch.value = false
        _searchStatus.value = "正在连接书源…"
        _errorMessage.value = null
        searchJob = viewModelScope.launch {
            try {
                initialization.join()
                val source = when { isComic -> _selectedComicSourceId.value; isAudio -> _selectedAudioSourceId.value; else -> _selectedSourceId.value }
                require(source.isNotBlank()) { "请先选择${if (isAudio) "有声" else "阅读"}书源。" }
                _searchHistory.value = (listOf(query) + _searchHistory.value).distinct().take(30)
                persist(historyFile, json.encodeToString(_searchHistory.value))
                checkSourceType(source, type)
                val received = if (source == NovelSourceEngine.SOURCE_MERGE) engine.searchAggregate(query, type,
                    continuous = true, previous = previous, retryDeferred = resume) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == searchGeneration && _activeMediaType.value == type) {
                            aggregateProgress = progress
                            val priorArt = _searchResults.value.associateBy(ReaderBookIdentity::key)
                            _searchResults.value = progress.books.map { candidate ->
                                val artwork = priorArt[ReaderBookIdentity.key(candidate)]
                                if (NovelCoverResolver.needsCover(candidate) && artwork != null && !NovelCoverResolver.needsCover(artwork))
                                    candidate.copy(coverUrl = artwork.coverUrl, coverReferer = artwork.coverReferer,
                                        coverRequestHeaders = artwork.artworkHeaders) else candidate
                            }
                            _canContinueSearch.value = progress.finished && progress.completedSources < progress.eligibleSources
                            _searchStatus.value = "${if (progress.finished) "已查" else "正在聚合"} ${progress.completedSources}/${progress.eligibleSources} 个来源" +
                                if (_canContinueSearch.value) " · 部分来源暂缓" else ""
                            if (coverJob == null && _searchResults.value.isNotEmpty()) {
                                coverJob = enrichCovers(_searchResults.value) { original, updated ->
                                    if (generation == searchGeneration && _activeMediaType.value == type)
                                        _searchResults.value = _searchResults.value.map {
                                            if (ReaderBookIdentity.matches(it, original)) it.copy(coverUrl = updated.coverUrl,
                                                coverReferer = updated.coverReferer, coverRequestHeaders = updated.artworkHeaders) else it
                                        }
                                }
                            }
                        }
                    }
                } else if (isComic) engine.searchComics(query, source) else engine.search(query, source)
                require(received.all { ReaderSearchPolicy.kind(it) == type }) { "所选来源返回的作品类型不符，请更换当前类型的书源。" }
                val result = withContext(Dispatchers.Default) { ReaderSearchPolicy.ranked(query, received) }
                ensureActive()
                if (generation != searchGeneration || _activeMediaType.value != type) return@launch
                val priorArt = _searchResults.value.associateBy(ReaderBookIdentity::key)
                _searchResults.value = result.map { candidate ->
                    val artwork = priorArt[ReaderBookIdentity.key(candidate)]
                    if (NovelCoverResolver.needsCover(candidate) && artwork != null && !NovelCoverResolver.needsCover(artwork))
                        candidate.copy(coverUrl = artwork.coverUrl, coverReferer = artwork.coverReferer,
                            coverRequestHeaders = artwork.artworkHeaders) else candidate
                }
                coverJob?.cancel()
                coverJob = enrichCovers(result) { original, updated ->
                    if (generation == searchGeneration && _activeMediaType.value == type)
                        _searchResults.value = _searchResults.value.map { if (it.id == original.id && it.sourceId == original.sourceId) updated else it }
                }
                if (source != NovelSourceEngine.SOURCE_MERGE) _searchStatus.value = "已搜索 1 个来源"
                else if (aggregateProgress == null) _searchStatus.value = "已打开作品链接"
                if (result.isEmpty()) _errorMessage.value = if (_canContinueSearch.value)
                    "已查来源没有找到结果，部分来源暂缓，可继续查找。" else "已查来源没有找到结果，请检查书名或作者关键词。"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == searchGeneration) _errorMessage.value = errorText("搜索失败", e)
            } finally { if (generation == searchGeneration) _isSearching.value = false }
        }
    }
    fun stopSearch() {
        searchGeneration++; searchJob?.cancel(); coverJob?.cancel()
        _isSearching.value = false
        val progress = aggregateProgress
        _canContinueSearch.value = progress != null && progress.searchedSourceIds.size < progress.eligibleSources
        if (progress != null) _searchStatus.value = "已暂停 · 已查 ${progress.completedSources}/${progress.eligibleSources} 个来源"
    }
    fun continueSearch() = searchBooks(isComic = _activeMediaType.value == 2,
        isAudio = _activeMediaType.value == 1, resume = true)
    fun searchWithAI(userDescription: String) {
        val query = userDescription.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            try {
                val keywords = aiService?.analyzeBookKeywords(query) ?: listOf(query)
                ensureActive()
                // Schedule independently: searchBooks cancels the completed AI job.
                viewModelScope.launch { searchBooks(keywords.firstOrNull() ?: query) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _errorMessage.value = errorText("AI 找书失败", e); _isSearching.value = false }
        }
    }
    fun clearSearchResults() {
        searchGeneration++; searchJob?.cancel(); coverJob?.cancel(); _searchResults.value = emptyList(); _isSearching.value = false; _searchStatus.value = "";
        _canContinueSearch.value = false; aggregateProgress = null; clearError()
    }
    fun clearSearchHistory() { viewModelScope.launch {
        initialization.join(); _searchHistory.value = emptyList()
        persist(historyFile, json.encodeToString(emptyList<String>()))
    } }
    fun loadDiscover(isComic: Boolean = false, isAudio: Boolean = false) {
        val type = when { isComic -> 2; isAudio -> 1; else -> 0 }
        val generation = ++discoverGeneration
        discoverJob?.cancel(); discoverCoverJob?.cancel()
        _discoverBooks.value = emptyList(); _isLoadingDiscover.value = true; clearError()
        discoverJob = viewModelScope.launch {
            try {
                initialization.join()
                val selected = when { isComic -> _selectedComicSourceId.value; isAudio -> _selectedAudioSourceId.value; else -> _selectedSourceId.value }
                val source = if (selected != NovelSourceEngine.SOURCE_MERGE || type == 0) selected
                    else if (isComic) NovelSourceEngine.AVAILABLE_COMIC_SOURCES.firstOrNull { it.enable }?.id.orEmpty()
                    else _importedSources.value.firstOrNull { it.type == 1 && it.enabled }?.id.orEmpty()
                require(source.isNotBlank()) { "请先选择书源。" }
                checkSourceType(source, type)
                val books = if (isComic) engine.getDiscoverComics(source) else engine.getDiscoverBooks(source)
                ensureActive()
                if (generation != discoverGeneration || type != _activeMediaType.value) return@launch
                require(books.all { ReaderSearchPolicy.kind(it) == type }) { "来源返回的作品类型与当前分类不符。" }
                _discoverBooks.value = books
                discoverWarmJob?.cancel()
                stopWarmAfterCurrent = false
                discoverWarmTarget = null
                discoverCoverJob = enrichCovers(books) { original, updated ->
                    if (generation == discoverGeneration && type == _activeMediaType.value)
                        _discoverBooks.value = _discoverBooks.value.map { if (it.id == original.id) updated else it }
                }
                if (books.isEmpty()) _errorMessage.value = "此来源暂时没有可显示的书籍，请使用搜索或换源。"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == discoverGeneration) _errorMessage.value = errorText("书城加载失败", e)
            } finally { if (generation == discoverGeneration) _isLoadingDiscover.value = false }
        }
    }

    fun openBook(requested: NovelBook) = openBookRequest(requested, false)
    fun loadStore(channel: String = storeChannel, force: Boolean = false) {
        val selected = if (channel == "female") "female" else "male"
        if (!force && selected == storeChannel && (_storeLoading.value || _storeHome.value?.channel == selected)) return
        storeChannel = selected
        val generation = ++storeGeneration
        storeJob?.cancel();closeStoreCategory();_storeError.value = null;_storeLoading.value = true
        if (_storeHome.value?.channel != selected) _storeHome.value = null
        storeJob = viewModelScope.launch {
            try {
                val home = storeRepository.home(selected,force) { partial ->
                    if (generation == storeGeneration) {
                        _storeHome.value = partial
                        if (_activeMediaType.value == 0) _discoverBooks.value =
                            (partial.featured + partial.sections.flatMap { it.books }).distinctBy { it.id }
                    }
                }
                ensureActive()
                if (generation == storeGeneration) {
                    _storeHome.value = home
                    if (_activeMediaType.value == 0) _discoverBooks.value = (home.featured + home.sections.flatMap { it.books }).distinctBy { it.id }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (generation == storeGeneration) _storeError.value = errorText("书城加载失败",error) }
            finally { if (generation == storeGeneration) _storeLoading.value = false }
        }
    }
    fun showStoreCategory(category: ReaderStoreCategory, force: Boolean = false) {
        val generation = ++categoryGeneration
        categoryJob?.cancel();_storeCategory.value = ReaderStoreCategoryPage(category,loading = true)
        categoryJob = viewModelScope.launch {
            try { val page = storeRepository.category(category,1,force);ensureActive()
                if (generation == categoryGeneration) _storeCategory.value = page
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (generation == categoryGeneration) _storeCategory.value = _storeCategory.value?.copy(loading=false,error=errorText("分类加载失败",error)) }
        }
    }
    fun moreStoreCategory() {
        val current = _storeCategory.value ?: return
        if (current.loading || !current.hasMore) return
        if (current.books.isEmpty()) { showStoreCategory(current.category);return }
        val generation = categoryGeneration
        _storeCategory.value = current.copy(loading=true,error=null)
        categoryJob = viewModelScope.launch {
            try {
                val next = storeRepository.category(current.category,current.page+1);ensureActive()
                if (generation == categoryGeneration) {
                    val books = (current.books + next.books).distinctBy { it.id }
                    _storeCategory.value = next.copy(books=books,hasMore=next.hasMore && books.size > current.books.size)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (generation == categoryGeneration) _storeCategory.value = current.copy(error=errorText("加载更多失败",error)) }
        }
    }
    fun closeStoreCategory() { categoryGeneration++;categoryJob?.cancel();_storeCategory.value=null }
    private fun openBookRequest(requested: NovelBook, forceCatalog: Boolean, presentationRequestId: String = requested.id) {
        val request = ++openRequestGeneration
        openRequestJob?.cancel()
        // The user has chosen a work; the previous all-source search no longer
        // owns network/CPU while its exact reading route is being resolved.
        stopSearch()
        openRequestJob = viewModelScope.launch {
            initialization.join()
            ensureActive()
            if (request != openRequestGeneration) return@launch
            val shelf = if (requested.isComic) _comicBookshelf.value else _bookshelf.value
            if (requested.sourceId == NovelSourceEngine.SOURCE_CATALOG) {
                readerGeneration++; readJob?.cancel();catalogJob?.cancel();prefetchJob?.cancel();cancelContinuousLoads()
                earlyPreviewJob?.cancel();earlyChapter?.result?.cancel();earlyChapter=null
                bookSourceGeneration++;bookSourceJob?.cancel();_isDiscoveringBookSources.value=false
                _currentBook.value=requested;_catalog.value=emptyList();_isLoadingCatalog.value=true
                _bookOpenRequestId.value=presentationRequestId
                _isCatalogComplete.value=false;_isLoadingChapter.value=false;clearError()
                try {
                    val stored = shelf.firstOrNull { it.sourceId != NovelSourceEngine.SOURCE_SKIN && it.sourceId != NovelSourceEngine.SOURCE_CATALOG && ReaderBookIdentity.matches(requested,it) }
                    val resolved = stored ?: engine.resolveCatalogBook(requested)
                    ensureActive();if (request != openRequestGeneration) return@launch
                    openWithSavedSources(resolved,forceCatalog)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { if (request == openRequestGeneration) {
                    _isLoadingCatalog.value=false;_isLoadingChapter.value=false;_errorMessage.value=errorText("查找本书来源失败",error)
                } }
            } else {
                try {
                    openWithSavedSources(ReaderBookIdentity.preferredRoute(requested, shelf), forceCatalog)
                    ensureActive()
                    if (request == openRequestGeneration) _bookOpenRequestId.value = presentationRequestId
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { if (request == openRequestGeneration) {
                    _isLoadingCatalog.value=false;_isLoadingChapter.value=false
                    _errorMessage.value=errorText("打开书籍失败",error)
                } }
            }
        }
    }

    private fun openResolvedBook(book: NovelBook, forceCatalog: Boolean = false) {
        sourceDiscoveryStartJob?.cancel()
        sourceDiscoveryAfterContent = false
        _readerNotice.value = null
        visibleChapter = book.lastReadChapterIndex; visibleParagraph = 0; visibleOffset = 0; visibleQuote = ""
        library.cancelSearch()
        val generation = ++readerGeneration
        bookSourceGeneration++; bookSourceJob?.cancel(); _isDiscoveringBookSources.value = false
        _bookSourceStatus.value = ""; _isSwitchingSource.value = false
        manualSourceSelection = book.preferredSourceId?.takeIf(String::isNotBlank)
        failedReaderSources.clear()
        resetContinuous(book.lastReadChapterIndex, false)
        if (discoverWarmJob?.isActive == true && discoverWarmTarget == (book.sourceId to book.detailUrl)) stopWarmAfterCurrent = true
        else discoverWarmJob?.cancel()
        readJob?.cancel()
        prefetchJob?.cancel(); _enterChapterAtEnd.value = false
        earlyChapter?.result?.cancel(); earlyChapter = null
        catalogJob?.cancel()
        discoverJob?.cancel()
        discoverGeneration++; discoverCoverJob?.cancel(); coverJob?.cancel()
        stopSearch()
        _isLoadingDiscover.value = false
        _currentBook.value = book; _catalog.value = emptyList()
        if (book.sourceId == NovelSourceEngine.SOURCE_SKIN) refreshBookSources()
        // A complete offline catalog/body must open without firing a new search
        // across every source. Fresh online details below start discovery; the
        // explicit switch dialog can refresh candidates whenever requested.
        enrichCovers(listOf(book)) { original, updated ->
            if (generation == readerGeneration && _currentBook.value?.let { sameBook(it, original) } == true) {
                _currentBook.value = _currentBook.value?.copy(coverUrl = updated.coverUrl, coverReferer = updated.coverReferer,
                    coverRequestHeaders = updated.artworkHeaders)
            }
        }
        _currentChapterIndex.value = book.lastReadChapterIndex
        _isCatalogComplete.value = false; _isLoadingCatalog.value = true
        _chapterContent.value = ""; _comicPages.value = emptyList(); _isLoadingChapter.value = true; clearError()
        catalogJob = viewModelScope.launch {
            var requestedContent = false
            try {
                val localCatalog = if (!forceCatalog && !book.isComic && !book.isAudio) library.cachedCatalog(book) else null
                if (!localCatalog.isNullOrEmpty()) {
                    _catalog.value = localCatalog; _isCatalogComplete.value = true
                    _currentBook.value = book.copy(chapterCount = localCatalog.size)
                    val target = localCatalog.indexOfFirst { it.url == book.lastReadChapterUrl }.takeIf { it >= 0 }
                        ?: book.lastReadChapterIndex.coerceIn(localCatalog.indices)
                    requestedContent = true
                    readJob = viewModelScope.launch {
                        try { loadChapterInternal(target) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { _errorMessage.value = errorText("章节加载失败", e) }
                        finally { if (generation == readerGeneration && coroutineContext[Job]?.isActive == true) _isLoadingChapter.value = false }
                    }
                    return@launch
                }
                val detailed = if (book.isComic) book else if (book.sources.size > 1)
                    withTimeoutOrNull(8_000) { engine.getBookDetail(book) }
                        ?: throw java.io.IOException("当前来源详情连接超时。")
                    else engine.getBookDetail(book)
                if (book.title.isNotBlank() && book.author.isNotBlank()) require(ReaderBookIdentity.matches(book, detailed)) {
                    "来源详情的书名或作者与搜索结果不一致。"
                }
                ensureActive()
                engine.initialChapterUrl(detailed)?.let { url ->
                    val candidate = EarlyChapter(detailed.sourceId, url, viewModelScope.async(Dispatchers.IO) {
                        try { readChapterText(url, detailed.sourceId) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null } // Catalog may resolve a directory alias before retry.
                    })
                    earlyChapter = candidate
                    earlyPreviewJob = viewModelScope.launch {
                        val preview = candidate.result.await() ?: return@launch
                        if (generation != readerGeneration || !_readerSettings.value.isVerticalScroll ||
                            _currentChapterIndex.value != book.lastReadChapterIndex) return@launch
                        val known = _catalog.value.getOrNull(book.lastReadChapterIndex)
                        val chapter = known ?: NovelChapter(book.lastReadChapterIndex,
                            book.lastReadChapterTitle.ifBlank { "正文" }, url)
                        publishContinuous(chapter, preview)
                        _chapterContent.value = preview
                        _isLoadingChapter.value = false
                    }
                }
                val art = _currentBook.value?.takeIf { it.id == book.id }
                val retainedSources = if (art != null && ReaderBookIdentity.matches(detailed, art))
                    ReaderBookIdentity.merge(detailed.title, listOf(detailed, art)).first().sources else detailed.sources
                _currentBook.value = if (art != null && !NovelCoverResolver.needsCover(art))
                    detailed.copy(coverUrl = art.coverUrl, coverReferer = art.coverReferer,
                        coverRequestHeaders = art.artworkHeaders, sources = retainedSources) else detailed.copy(sources = retainedSources)
                // Fetch the selected route's catalog/body first. Scanning hundreds
                // of alternatives before this point competed with the first page.
                sourceDiscoveryAfterContent = true
                fun applyCatalog(chapters: List<NovelChapter>, complete: Boolean) {
                    if (generation != readerGeneration || chapters.isEmpty()) return
                    if (_isCatalogComplete.value && !complete) return
                    _catalog.value = chapters
                    _continuousSections.value = _continuousSections.value.map { section ->
                        chapters.getOrNull(section.chapter.index)?.takeIf { it.url == section.chapter.url }
                            ?.let { section.copy(chapter = it) } ?: section
                    }
                    if (complete) {
                        _isCatalogComplete.value = true
                        // A later catalogue page can add neighbours without changing
                        // the viewport. Revisit the window even when no scroll occurs.
                        if (_continuousSections.value.isNotEmpty()) {
                            ensureContinuousWindow(_currentChapterIndex.value, _currentChapterIndex.value)
                        }
                    }
                    if (requestedContent) return
                    val saved = chapters.indexOfFirst { it.url == book.lastReadChapterUrl }
                    val target = when {
                        saved >= 0 -> saved
                        book.lastReadChapterIndex in chapters.indices -> book.lastReadChapterIndex
                        complete -> book.lastReadChapterIndex.coerceIn(chapters.indices)
                        else -> return
                    }
                    requestedContent = true
                    readJob = viewModelScope.launch {
                        try { loadChapterInternal(target) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { _errorMessage.value = errorText("章节加载失败", e) }
                        finally { if (generation == readerGeneration && coroutineContext[Job]?.isActive == true) _isLoadingChapter.value = false }
                    }
                }
                val chapters = if (book.isComic) engine.getComicCatalog(book.detailUrl, book.sourceId)
                               else engine.getCatalog(detailed.detailUrl, detailed.sourceId) { partial ->
                                   viewModelScope.launch {
                                       if (generation == readerGeneration) applyCatalog(partial, false)
                                   }
                               }
                ensureActive()
                require(chapters.isNotEmpty()) { "目录为空，请换源或重试。" }
                applyCatalog(chapters, true)
                _currentBook.value = (_currentBook.value ?: detailed).copy(chapterCount = chapters.size)
                if (!book.isComic && !book.isAudio) library.rememberCatalog(_currentBook.value ?: detailed, chapters)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == readerGeneration) {
                    engine.recordReadingRouteFailure(book)
                    val recovered = recoverBookSource(_currentBook.value ?: book,
                        _catalog.value.getOrNull(_currentChapterIndex.value)?.title ?: book.lastReadChapterTitle,
                        _currentChapterIndex.value, generation)
                    if (recovered) requestedContent = true
                    else _errorMessage.value = errorText(
                        if (_catalog.value.isEmpty()) "打开书籍失败" else "完整目录尚未取得，可重试刷新目录", e)
                }
            } finally {
                if (generation == readerGeneration && coroutineContext[Job]?.isActive == true) {
                    _isLoadingCatalog.value = false
                    if (!requestedContent) _isLoadingChapter.value = false
                }
            }
        }
    }
    fun refreshCatalog() { _currentBook.value?.let { book ->
        engine.invalidateCatalog(book.detailUrl, book.sourceId)
        openBookRequest(book, true, _bookOpenRequestId.value ?: book.id)
    } }
    fun retryChapter() { if (_catalog.value.isEmpty()) refreshCatalog() else loadChapter(_currentChapterIndex.value) }
    private suspend fun readChapterText(url: String, sourceId: String): String {
        library.cachedContent(sourceId, url)?.let { return ReaderSourceContentValidator.requireReadable(it) }
        val captured = _currentBook.value?.takeIf { it.sourceId == sourceId && !it.isAudio && !it.isComic }
        val chapter = _catalog.value.firstOrNull { it.url == url } ?: NovelChapter(0, "正文", url)
        val text = engine.getChapterContent(url, sourceId).let {
            if (captured != null) ReaderSourceContentValidator.requireReadable(it, chapter.title) else it
        }
        if (captured != null) rememberContentInBackground(captured, chapter, text)
        return text
    }
    private fun rememberContentInBackground(book: NovelBook, chapter: NovelChapter, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { library.rememberContent(book, chapter, text) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Online text remains visible if optional caching fails. */ }
        }
    }

    private fun discoverSourcesAfterContent() {
        if (!sourceDiscoveryAfterContent) return
        sourceDiscoveryAfterContent = false
        val generation = readerGeneration
        sourceDiscoveryStartJob?.cancel()
        sourceDiscoveryStartJob = viewModelScope.launch {
            // Let the text frame render and adjacent chapters get their turn first.
            delay(250)
            if (generation == readerGeneration) refreshBookSources()
        }
    }
    fun onReaderPosition(chapter: Int, paragraph: Int, offset: Int, quote: String) {
        visibleChapter = chapter; visibleParagraph = paragraph; visibleOffset = offset; visibleQuote = quote
    }
    fun addCurrentBookmark() {
        val book = _currentBook.value ?: return
        val chapter = _catalog.value.getOrNull(visibleChapter) ?: _catalog.value.getOrNull(_currentChapterIndex.value) ?: return
        if (book.isComic || book.isAudio) { _readerNotice.value = "书签支持文字小说"; return }
        val paragraph = visibleParagraph; val offset = visibleOffset; val quote = visibleQuote
        viewModelScope.launch {
            try { library.addBookmark(book, chapter, paragraph, offset, quote); _readerNotice.value = "已保存当前位置书签" }
            catch (e: Exception) { _readerNotice.value = "书签未能保存：${e.message.orEmpty()}" }
        }
    }
    fun downloadCurrentBook() {
        val book = _currentBook.value ?: return
        if (!_isCatalogComplete.value) { _readerNotice.value = "请等待完整目录加载完成"; return }
        library.download(book, _catalog.value.toList())
    }
    fun searchBookText(query: String) { _currentBook.value?.let { library.search(it, _catalog.value.toList(), query) } }
    fun jumpToText(chapter: NovelChapter, paragraph: Int, offset: Int, query: String = "") {
        val index = _catalog.value.indexOfFirst { it.url == chapter.url }.takeIf { it >= 0 }
            ?: ReaderSearchPolicy.matchChapter(_catalog.value, chapter.title)
        if (index == null) { _errorMessage.value = "当前来源没有对应章节，请先切回原来源或刷新目录"; return }
        loadChapter(index)
        _continuousAnchor.value = _continuousAnchor.value.copy(paragraphIndex = paragraph.coerceAtLeast(0),
            charOffset = offset.coerceAtLeast(0), highlight = query)
    }
    fun leaveReader() {
        sourceDiscoveryStartJob?.cancel(); sourceDiscoveryAfterContent = false
        library.cancelSearch()
        openRequestGeneration++; openRequestJob?.cancel()
        readerGeneration++; readJob?.cancel(); catalogJob?.cancel(); prefetchJob?.cancel()
        bookSourceGeneration++; bookSourceJob?.cancel(); _isDiscoveringBookSources.value = false
        _isSwitchingSource.value = false
        cancelContinuousLoads(); earlyPreviewJob?.cancel(); discoverWarmJob?.cancel()
        earlyChapter?.result?.cancel(); earlyChapter = null
        _currentBook.value?.let { book -> viewModelScope.launch { updateBookshelfBook(book) } }
        _isLoadingChapter.value = false; _isLoadingCatalog.value = false; clearError()
    }
    fun loadChapter(index: Int, atEnd: Boolean = false) {
        if (index !in _catalog.value.indices) return
        resetContinuous(index, atEnd)
        readJob?.cancel()
        prefetchJob?.cancel(); _enterChapterAtEnd.value = atEnd
        earlyChapter?.result?.cancel(); earlyChapter = null
        readJob = viewModelScope.launch {
            try { loadChapterInternal(index)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _errorMessage.value = errorText("章节加载失败", e)
            } finally { if (coroutineContext[Job]?.isActive == true) _isLoadingChapter.value = false }
        }
    }
    fun nextChapter() {
        if (_currentChapterIndex.value + 1 in _catalog.value.indices) loadChapter(_currentChapterIndex.value + 1)
        else _errorMessage.value = "已到当前目录末尾，可刷新目录检查更新。"
    }
    fun prevChapter() {
        if (_currentChapterIndex.value > 0) loadChapter(_currentChapterIndex.value - 1, atEnd = true)
        else _errorMessage.value = "已经是第一章。"
    }
    private suspend fun loadChapterInternal(index: Int) {
        val book = _currentBook.value ?: return
        val title = _catalog.value.getOrNull(index)?.title.orEmpty()
        try { loadChapterFromCurrentSource(index) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            engine.recordReadingRouteFailure(book)
            if (manualSourceSelection == book.sourceId) throw e
            if (!recoverBookSource(book, title, index, readerGeneration)) throw e
        }
    }

    /** A failed source can only fall over to a hit already proven to be this work. */
    private suspend fun recoverBookSource(book: NovelBook, chapterTitle: String, oldIndex: Int, generation: Int): Boolean {
        if (book.sourceId == NovelSourceEngine.SOURCE_SKIN) return false
        // Both opening/catalog and later-body failures go through this guard.
        // A saved explicit source remains selected after process recreation too.
        if (manualSourceSelection != null || !book.preferredSourceId.isNullOrBlank()) return false
        if (book.author.isBlank()) return false
        failedReaderSources += book.sourceId
        val recoveringJob = coroutineContext[Job]
        return withTimeoutOrNull(18_000) {
            val confirmed = try { if (book.sourceCount < 2) engine.discoverAlternatives(book) else book }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@withTimeoutOrNull false }
            if (confirmed.sourceCount < 2) return@withTimeoutOrNull false
            repeat(4) {
                ensureActive()
                if (generation != readerGeneration) return@withTimeoutOrNull false
                val alternative = try { engine.alternativeCatalog(confirmed, failedReaderSources.toSet()) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { return@withTimeoutOrNull false }
                failedReaderSources += alternative.book.sourceId
                val aligned = ReaderSearchPolicy.matchChapter(alternative.chapters, chapterTitle)
                    ?: if (oldIndex == 0 && chapterTitle.isBlank()) 0 else return@withTimeoutOrNull false
                earlyChapter?.result?.cancel(); earlyChapter = null; earlyPreviewJob?.cancel()
                prefetchJob?.cancel()
                if (readJob != recoveringJob) readJob?.cancel()
                resetContinuous(aligned, false)
                _catalog.value = alternative.chapters
                _isCatalogComplete.value = true
                _currentBook.value = alternative.book.copy(lastReadChapterIndex = aligned,
                    chapterCount = alternative.chapters.size)
                try {
                    loadChapterFromCurrentSource(aligned)
                    clearError()
                    try { replaceShelfSource(book, _currentBook.value ?: alternative.book) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { _errorMessage.value = "已打开作品，但书架来源未能保存，请检查本地存储。" }
                    return@withTimeoutOrNull true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Try the next confirmed source within one budget. */ }
            }
            false
        } ?: false
    }

    private suspend fun loadChapterFromCurrentSource(index: Int, preparedText: String? = null,
        preparedPages: List<String>? = null) {
        val book = _currentBook.value ?: return
        val chapter = _catalog.value.getOrNull(index) ?: return
        _isLoadingChapter.value = true; clearError(); _chapterContent.value = ""; _comicPages.value = emptyList()
        _currentChapterIndex.value = index
        if (book.isComic) {
            val pages = preparedPages ?: engine.getComicChapterPages(chapter.url, book.sourceId)
            coroutineContext.ensureActive(); require(pages.isNotEmpty()) { "当前章节没有可用图片。" }
            _comicPages.value = pages
        } else {
            val early = earlyChapter?.takeIf { it.source == book.sourceId && it.url == chapter.url }
            val text = preparedText ?: early?.result?.await() ?: readChapterText(chapter.url, book.sourceId)
            earlyChapter = null
            coroutineContext.ensureActive(); require(text.isNotBlank()) { "当前章节没有可用正文。" }
            if (!book.isAudio) ReaderSourceContentValidator.requireReadable(text, chapter.title)
            if (!book.isAudio) {
                if (preparedText != null) rememberContentInBackground(book, chapter, text)
                publishContinuous(chapter, text)
                ensureContinuousWindow(index, index)
            }
            _chapterContent.value = text
        }
        discoverSourcesAfterContent()
        val latest = _currentBook.value?.takeIf { it.sourceId == book.sourceId && ReaderBookIdentity.matches(it, book) }
        val retained = if (latest == null) book else ReaderBookIdentity.merge(book.title, listOf(book, latest)).first()
        val updated = retained.copy(lastReadChapterTitle = chapter.title, lastReadChapterUrl = chapter.url,
            lastReadChapterIndex = index, lastReadTime = System.currentTimeMillis(), chapterCount = _catalog.value.size)
        _currentBook.value = updated
        engine.recordReadingRouteSuccess(updated)
        val restoredSources = engine.restoreBookSources(updated)
        if (_currentBook.value?.let { sameBook(it, updated) } == true) _bookSourceAvailability.value = restoredSources.availability
        // One adjacent chapter only; display the current chapter before this request.
        prefetchJob?.cancel()
        if (!book.isComic && !book.isAudio && !_readerSettings.value.isVerticalScroll) prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            _catalog.value.getOrNull(index + 1)?.let { next ->
                try { readChapterText(next.url, book.sourceId) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Prefetch never replaces visible content or errors. */ }
            }
        }
        try { updateBookshelfBook(updated) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { _errorMessage.value = "正文已加载，但阅读进度未能保存，请检查本地存储。" }
    }
    /** Find only identity-matched candidates; never change the active route here. */
    fun refreshBookSources(force: Boolean = false) {
        val book = _currentBook.value ?: return
        if (bookSourceJob?.isActive == true && !force) return
        if (book.title.isBlank() || book.author.isBlank()) {
            _bookSourceStatus.value = "正在补全书名与作者后自动查源"
            return
        }
        val identity = ReaderBookIdentity.key(book)
        val previous = if (force) null else bookSourceProgress[identity]
        bookSourceJob?.cancel()
        val generation = ++bookSourceGeneration
        _isDiscoveringBookSources.value = true
        _bookSourceStatus.value = "正在自动查找本书来源…"
        bookSourceJob = viewModelScope.launch {
            try {
                initialization.join()
                engine.discoverBookSources(book, previous, retryDeferred = force,
                    chapterTitle = _catalog.value.getOrNull(_currentChapterIndex.value)?.title.orEmpty(),
                    chapterIndex = _currentChapterIndex.value, onState = { state ->
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == bookSourceGeneration && _currentBook.value?.let { sameBook(it, book) } == true)
                                applySourceState(state)
                        }
                    }) { matched, progress ->
                    withContext(Dispatchers.Main.immediate) {
                        if (generation != bookSourceGeneration) return@withContext
                        val current = _currentBook.value ?: return@withContext
                        if (!ReaderBookIdentity.matches(current, book)) return@withContext
                        // The current source stays first, including during a manual switch.
                        val updated = ReaderBookIdentity.refreshSourceCandidates(current, listOf(matched))
                        bookSourceProgress[identity] = progress.copy(books = listOf(updated))
                        _currentBook.value = updated
                    }
                }
                _currentBook.value?.takeIf { ReaderBookIdentity.matches(it, book) }?.let { updateBookshelfBook(it) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == bookSourceGeneration) _bookSourceStatus.value = errorText("查源未完成，可重新查找", e)
            } finally {
                if (generation == bookSourceGeneration) _isDiscoveringBookSources.value = false
            }
        }
    }

    fun switchSource(newSourceId: String) {
        val previous = _currentBook.value ?: return
        if (previous.sourceId == newSourceId || _isSwitchingSource.value) return
        UiCrashBreadcrumbs.mark(UiCrashBreadcrumbs.Phase.READER_SOURCE_SWITCH_BEGIN)
        val oldIndex = _currentChapterIndex.value
        val oldTitle = _catalog.value.getOrNull(oldIndex)?.title.orEmpty()
        val generation = ++readerGeneration
        catalogJob?.cancel(); _isLoadingCatalog.value = false
        cancelContinuousLoads()
        _continuousSections.value = _continuousSections.value.map { if (it.loading) it.copy(loading = false) else it }
        prefetchJob?.cancel(); earlyChapter?.result?.cancel(); earlyChapter = null; earlyPreviewJob?.cancel()
        readJob?.cancel(); _isSwitchingSource.value = true; _isLoadingChapter.value = true; clearError()
        readJob = viewModelScope.launch {
            var committed = false
            try {
                initialization.join()
                checkSourceType(newSourceId, ReaderSearchPolicy.kind(previous))
                val prepared = withTimeoutOrNull(20_000) {
                    engine.prepareSourceSwitch(previous, newSourceId, oldTitle, oldIndex)
                } ?: throw java.io.IOException("新来源连接超时，已保留原来源与阅读位置。")
                ensureActive()
                if (generation != readerGeneration) return@launch
                val latest = _currentBook.value?.takeIf { ReaderBookIdentity.matches(it, previous) } ?: previous
                val target = prepared.book.copy(lastReadChapterIndex = prepared.chapterIndex,
                    preferredSourceId = prepared.book.sourceId,
                    chapterCount = prepared.chapters.size,
                    sources = ReaderBookIdentity.merge(previous.title, listOf(latest, prepared.book)).first().sources,
                    coverUrl = latest.coverUrl.ifBlank { prepared.book.coverUrl },
                    coverReferer = if (latest.coverUrl.isNotBlank()) latest.coverReferer else prepared.book.coverReferer,
                    coverRequestHeaders = if (latest.coverUrl.isNotBlank()) latest.artworkHeaders else prepared.book.artworkHeaders)
                resetContinuous(prepared.chapterIndex, false)
                _enterChapterAtEnd.value = false
                _catalog.value = prepared.chapters
                _isCatalogComplete.value = true
                _currentBook.value = target
                UiCrashBreadcrumbs.mark(UiCrashBreadcrumbs.Phase.READER_SOURCE_SWITCH_COMMIT)
                manualSourceSelection = target.sourceId
                if (!target.isComic && !target.isAudio) library.rememberCatalog(target, prepared.chapters)
                committed = true
                // Explicit switches never call the automatic fallback path, so a
                // failed new source cannot silently send the reader back to the old one.
                loadChapterFromCurrentSource(prepared.chapterIndex, prepared.content, prepared.comicPages)
                replaceShelfSource(previous, _currentBook.value ?: target)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                UiCrashBreadcrumbs.mark(UiCrashBreadcrumbs.Phase.READER_SOURCE_SWITCH_FAILED)
                val candidate = previous.sources.firstOrNull { it.sourceId == newSourceId }
                if (!committed && candidate != null) {
                    val value = ReaderSourceAvailability(newSourceId, candidate.detailUrl, ReaderSourceAvailability.FAILED,
                        System.currentTimeMillis(), oldTitle, chapterIndex = oldIndex,
                        reason = e.message.orEmpty().take(160), retryAfter = System.currentTimeMillis() + 600_000)
                    engine.recordSourceAvailability(previous, value)
                    _bookSourceAvailability.value = _bookSourceAvailability.value + (value.routeKey to value)
                }
                _errorMessage.value = errorText(if (committed) "已换源，保存进度失败" else "换源失败，已保留原来源与阅读位置", e)
            } finally {
                if (generation == readerGeneration) {
                    _isSwitchingSource.value = false; _isLoadingChapter.value = false
                    if (!committed && _readerSettings.value.isVerticalScroll) ensureContinuousWindow(oldIndex, oldIndex)
                }
            }
        }
    }
    private fun normalized(text: String) = text.replace(Regex("[\\s　·•]"), "")
    private fun cancelContinuousLoads() {
        val jobs = continuousJobs.values.toList()
        continuousJobs.clear()
        jobs.forEach { it.cancel() }
        continuousProgressJob?.cancel()
    }
    private fun resetContinuous(index: Int, atEnd: Boolean) {
        cancelContinuousLoads()
        earlyPreviewJob?.cancel()
        _continuousSections.value = emptyList()
        _continuousAnchor.value = ContinuousScrollAnchor(_continuousAnchor.value.revision + 1, index, atEnd)
    }
    private suspend fun publishContinuous(chapter: NovelChapter, text: String) {
        val generation = readerGeneration
        val existing = _continuousSections.value.firstOrNull { it.chapter.index == chapter.index }
        val paragraphs = if (existing?.text == text) existing.paragraphs else withContext(Dispatchers.Default) {
            readerParagraphs(text)
        }
        coroutineContext.ensureActive()
        if (generation != readerGeneration) return
        if (_continuousSections.value.isEmpty()) {
            _continuousAnchor.value = _continuousAnchor.value.copy(chapterIndex = chapter.index)
        }
        val section = ContinuousReaderSection(chapter, text, paragraphs)
        _continuousSections.value = (_continuousSections.value.filterNot { it.chapter.index == chapter.index } + section)
            .sortedBy { it.chapter.index }
    }
    /** The viewport moves reading progress; it never triggers a replacement-page load. */
    fun onContinuousViewport(firstVisible: Int, lastVisible: Int) {
        val book = _currentBook.value ?: return
        if (book.isComic || book.isAudio || !_readerSettings.value.isVerticalScroll) return
        val first = _continuousSections.value.firstOrNull { it.chapter.index == firstVisible && it.ready }
        if (first != null && firstVisible != _currentChapterIndex.value) {
            _currentChapterIndex.value = firstVisible
            _chapterContent.value = first.text
            val updated = book.copy(lastReadChapterTitle = first.chapter.title, lastReadChapterUrl = first.chapter.url,
                lastReadChapterIndex = firstVisible, lastReadTime = System.currentTimeMillis())
            _currentBook.value = updated
            continuousProgressJob?.cancel()
            continuousProgressJob = viewModelScope.launch { delay(400); updateBookshelfBook(updated) }
        }
        ensureContinuousWindow(firstVisible, lastVisible)
    }
    private fun ensureContinuousWindow(firstVisible: Int, lastVisible: Int) {
        val book = _currentBook.value ?: return
        if (book.isComic || book.isAudio || !_readerSettings.value.isVerticalScroll || _catalog.value.isEmpty()) return
        val first = firstVisible.coerceIn(_catalog.value.indices)
        val last = lastVisible.coerceIn(first, _catalog.value.lastIndex)
        // Stable keys keep the visible paragraph stationary when earlier sections
        // are prepended or old distant chapters leave this bounded document window.
        val keepStart = (first - 4).coerceAtLeast(0)
        val keepEnd = (last + 5).coerceAtMost(first + 12).coerceAtMost(_catalog.value.lastIndex)
        val removed = continuousJobs.keys.filter { it !in keepStart..keepEnd }
        removed.forEach { continuousJobs.remove(it)?.cancel() }
        _continuousSections.value = _continuousSections.value.filter { it.chapter.index in keepStart..keepEnd }
        val wantedEnd = (last + 2).coerceAtMost(first + 10).coerceAtMost(_catalog.value.lastIndex)
        // Forward requests go first, because they will soon become visible.
        val wanted = (first..wantedEnd).toList() + listOf(first - 1)
        wanted.filter { it in _catalog.value.indices }.forEach { requestContinuousChapter(it, false) }
    }
    fun retryContinuousChapter(index: Int) { requestContinuousChapter(index, true) }
    private fun requestContinuousChapter(index: Int, retry: Boolean) {
        val book = _currentBook.value ?: return
        val chapter = _catalog.value.getOrNull(index) ?: return
        val existing = _continuousSections.value.firstOrNull { it.chapter.index == index }
        if (continuousJobs[index]?.isActive == true || existing?.ready == true || (!retry && existing?.error != null)) return
        val generation = readerGeneration
        _continuousSections.value = (_continuousSections.value.filterNot { it.chapter.index == index } +
            ContinuousReaderSection(chapter, loading = true)).sortedBy { it.chapter.index }
        continuousJobs[index] = viewModelScope.launch {
            try {
                val text = continuousPermits.withPermit { readChapterText(chapter.url, book.sourceId) }
                ensureActive()
                if (generation == readerGeneration && _continuousSections.value.any { it.chapter.url == chapter.url }) {
                    require(text.isNotBlank()) { "当前章节没有可用正文。" }
                    publishContinuous(chapter, text)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == readerGeneration) _continuousSections.value = _continuousSections.value.map {
                    if (it.chapter.url == chapter.url) it.copy(loading = false, error = errorText("章节加载失败", e)) else it
                }
            } finally {
                // A canceled request can finish after a new request for the same
                // chapter starts; never remove the replacement job from the map.
                if (generation == readerGeneration && continuousJobs[index] == coroutineContext[Job]) {
                    continuousJobs.remove(index)
                }
            }
        }
    }
    private fun checkSourceType(sourceId: String, expected: Int) {
        if (sourceId == NovelSourceEngine.SOURCE_MERGE) return
        val actual = _importedSources.value.firstOrNull { it.id == sourceId }?.type
            ?: if (NovelSourceEngine.AVAILABLE_COMIC_SOURCES.any { it.id == sourceId }) 2 else 0
        require(actual == expected) { "书源类型与当前分类不符，请重新选择${when(expected) { 2 -> "漫画"; 1 -> "有声"; else -> "小说" }}源。" }
    }
    private fun enrichCovers(books: List<NovelBook>, apply: (NovelBook, NovelBook) -> Unit): Job? {
        val resolver = coverResolver ?: return null
        return viewModelScope.launch {
            // Warm only the first viewport; each visible image also resolves lazily,
            // including shelves and rows beyond this initial batch.
            books.asSequence().filter(NovelCoverResolver::needsCover).take(8).forEach { book ->
                launch {
                    val cover = resolver.resolve(book) ?: return@launch
                    ensureActive()
                    apply(book, book.copy(coverUrl = cover.url, coverReferer = cover.referer,
                        coverRequestHeaders = cover.requestHeaders))
                }
            }
        }
    }
    private fun alignChapter(chapters: List<NovelChapter>, title: String, oldIndex: Int): Int {
        if (title.isBlank()) return oldIndex.coerceIn(chapters.indices)
        chapters.indexOfFirst { normalized(it.title) == normalized(title) }.takeIf { it >= 0 }?.let { return it }
        val core = title.replace(Regex("^(?:第)?[零〇一二三四五六七八九十百千万两\\d]+[章话回节卷]?\\s*"), "").trim()
        if (core.isNotBlank()) chapters.indexOfFirst { it.title.endsWith(core) }.takeIf { it >= 0 }?.let { return it }
        return oldIndex.coerceIn(chapters.indices)
    }
    private fun sameBook(a: NovelBook, b: NovelBook): Boolean {
        if (ReaderSearchPolicy.kind(a) != ReaderSearchPolicy.kind(b)) return false
        if (a.sourceId == b.sourceId && a.detailUrl == b.detailUrl) return true
        return ReaderBookIdentity.matches(a, b)
    }
    fun isBookInBookshelf(book: NovelBook?): Boolean = book != null &&
        (if (book.isComic) _comicBookshelf.value else _bookshelf.value).any { sameBook(it, book) }
    fun toggleBookshelf(book: NovelBook) { viewModelScope.launch {
        initialization.join()
        shelfLock.withLock {
            val state = if (book.isComic) _comicBookshelf else _bookshelf
            state.value = if (state.value.any { sameBook(it, book) }) state.value.filterNot { sameBook(it, book) }
                          else listOf(book) + state.value
            persist(if (book.isComic) comicBookshelfFile else bookshelfFile, json.encodeToString(state.value))
        }
    } }
    private suspend fun updateBookshelfBook(book: NovelBook) {
        initialization.join()
        shelfLock.withLock {
            val state = if (book.isComic) _comicBookshelf else _bookshelf
            if (state.value.none { sameBook(it, book) }) return@withLock
            state.value = state.value.map { if (sameBook(it, book)) book else it }
            persist(if (book.isComic) comicBookshelfFile else bookshelfFile, json.encodeToString(state.value))
        }
    }
    private suspend fun replaceShelfSource(previous: NovelBook, updated: NovelBook) {
        initialization.join()
        shelfLock.withLock {
            val state = if (previous.isComic) _comicBookshelf else _bookshelf
            if (state.value.none { sameBook(it, previous) }) return@withLock
            state.value = state.value.map { if (sameBook(it, previous)) updated else it }
            persist(if (previous.isComic) comicBookshelfFile else bookshelfFile, json.encodeToString(state.value))
        }
    }
    private fun updateSettings(transform: (NovelReaderSettings) -> NovelReaderSettings) { viewModelScope.launch {
        initialization.join()
        _readerSettings.value = transform(_readerSettings.value)
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            // Slider feedback is immediate; coalesce rapid changes into one atomic write.
            delay(150)
            persist(settingsFile, json.encodeToString(_readerSettings.value))
        }
    } }
    fun updateFontSize(size: Float) = updateSettings { it.copy(fontSizeSp = size.coerceIn(12f, 36f)) }
    fun toggleScrollMode(vertical: Boolean) {
        if (!vertical) cancelContinuousLoads()
        else if (!_readerSettings.value.isVerticalScroll) _continuousAnchor.value =
            ContinuousScrollAnchor(_continuousAnchor.value.revision + 1, _currentChapterIndex.value, false)
        updateSettings { it.copy(isVerticalScroll = vertical) }
    }
    fun selectTheme(theme: NovelReaderTheme) = updateSettings { it.copy(theme = theme, customBackgroundColor = null) }
    fun selectCustomBackground(color: Long) = updateSettings { it.copy(customBackgroundColor = ReaderColorMath.opaque(color)) }
    fun updateBrightness(value: Float) = updateSettings { it.copy(brightness = if (value < 0f) -1f else value.coerceIn(.02f, 1f)) }
    fun updateLineSpacing(value: Float) = updateSettings { it.copy(lineSpacingMultiplier = value.coerceIn(1.1f, 2.2f)) }
    fun toggleComicPaging(horizontal: Boolean) = updateSettings { it.copy(comicHorizontalPaging = horizontal) }
    fun updateComicGap(value: Float) = updateSettings { it.copy(comicPageGapDp = value.coerceIn(0f, 24f)) }
    fun setAutoScrollSpeed(value: Float) = updateSettings { it.copy(autoScrollSpeed = value.coerceIn(0f, 100f)) }
    fun clearError() { _errorMessage.value = null }
    private fun errorText(prefix: String, e: Exception) = prefix + "：" + (e.message?.take(220) ?: "网络异常，请重试或换源。")
}
