package com.tombytee.releasefetch.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tombytee.releasefetch.data.download.DownloadController
import com.tombytee.releasefetch.data.local.RepoHistoryRepository
import com.tombytee.releasefetch.data.local.SavedRepoEntity
import com.tombytee.releasefetch.data.remote.GitHubReleaseRepository
import com.tombytee.releasefetch.data.remote.ReleaseAnalyzerDataSource
import com.tombytee.releasefetch.data.remote.ReleaseFetchOutcome
import com.tombytee.releasefetch.domain.AnalyzedRepository
import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.domain.ClassifiedAsset
import com.tombytee.releasefetch.domain.ClassifiedRelease
import com.tombytee.releasefetch.domain.DeviceProfile
import com.tombytee.releasefetch.domain.DeviceSpecReader
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.DownloadStatus
import com.tombytee.releasefetch.domain.GitHubUrlParser
import com.tombytee.releasefetch.domain.PlatformCategory
import com.tombytee.releasefetch.domain.UrlParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
sealed interface AnalysisState {
    @Immutable
    data object Idle : AnalysisState

    @Immutable
    data class Loading(val repoFullName: String) : AnalysisState

    @Immutable
    data class NoReleases(
        val canonicalFullName: String,
        val ownerAvatarUrl: String?,
    ) : AnalysisState

    @Immutable
    data class Ready(
        val repository: AnalyzedRepository,
        val selectedReleaseIndex: Int,
        val activeRelease: ClassifiedRelease,
        val selectedPlatform: PlatformCategory = PlatformCategory.ALL,
        val showReleaseNotes: Boolean = false,
        val visibleAssetLimit: Int = INITIAL_ASSET_BATCH,
        val hasMoreAssetsToReveal: Boolean = false,
        val isLoadingMoreReleases: Boolean = false,
    ) : AnalysisState

    @Immutable
    data class Error(
        val message: String,
        val attemptedInput: String,
    ) : AnalysisState

    companion object {
        const val INITIAL_ASSET_BATCH = 8
        const val ASSET_BATCH_STEP = 12
    }
}

class ReleaseAnalyzerViewModel(
    private val releaseRepository: ReleaseAnalyzerDataSource,
    private val historyRepository: RepoHistoryRepository,
    private val downloadController: DownloadController,
    val deviceProfile: DeviceProfile = DeviceSpecReader.readCurrentDevice(),
) : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    val isAnalyzing: StateFlow<Boolean> = _analysisState
        .map { it is AnalysisState.Loading }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private var analyzeJob: Job? = null
    private var loadMoreJob: Job? = null
    private val requestedPages = HashSet<Pair<String, Int>>()

    val pinnedRepos: StateFlow<List<SavedRepoEntity>> = historyRepository.pinnedRepos
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val pinnedRepoNames: StateFlow<Set<String>> = pinnedRepos
        .map { repos -> repos.mapTo(HashSet(repos.size)) { it.fullName } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    val recentRepos: StateFlow<List<SavedRepoEntity>> = historyRepository.recentRepos
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> =
        downloadController.downloadsByAssetId

    val downloadsList: StateFlow<List<DownloadItemState>> = downloadsByAssetId
        .map { map -> map.values.sortedByDescending { it.downloadId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val activeDownloadCount: StateFlow<Int> = downloadsByAssetId
        .map { map ->
            map.values.count {
                it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.RUNNING ||
                    it.status == DownloadStatus.PAUSED
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    fun onUrlInputChanged(newValue: String) {
        _urlInput.value = newValue
    }

    fun clearInput() {
        _urlInput.value = ""
    }

    fun resetToHome() {
        analyzeJob?.cancel()
        loadMoreJob?.cancel()
        requestedPages.clear()
        _analysisState.value = AnalysisState.Idle
    }

    fun analyzeCurrentInput() {
        analyzeRepository(_urlInput.value)
    }

    fun analyzeRepository(rawInput: String) {
        val trimmed = rawInput.trim()
        _urlInput.value = trimmed

        analyzeJob?.cancel()
        loadMoreJob?.cancel()
        requestedPages.clear()

        when (val parsed = GitHubUrlParser.parse(trimmed)) {
            is UrlParseResult.Error -> {
                _analysisState.value = AnalysisState.Error(
                    message = parsed.message,
                    attemptedInput = trimmed,
                )
            }

            is UrlParseResult.Success -> {
                val identifier = parsed.identifier
                _urlInput.value = identifier.htmlUrl
                _analysisState.value = AnalysisState.Loading(identifier.fullName)

                analyzeJob = viewModelScope.launch {
                    when (val outcome = releaseRepository.analyzeRepository(identifier, deviceProfile)) {
                        is ReleaseFetchOutcome.Success -> {
                            val analyzed = outcome.repository
                            val initialReleaseIndex = analyzed.latestReleaseIndex
                                .coerceIn(0, (analyzed.rawReleases.size - 1).coerceAtLeast(0))
                            val initialRaw = analyzed.rawReleases.getOrNull(initialReleaseIndex) ?: return@launch
                            val activeClassified = analyzed.classifiedCache[initialRaw.id]
                                ?: withContext(Dispatchers.Default) {
                                    AssetClassifier.classifySingleRelease(
                                        raw = initialRaw,
                                        isLatest = initialReleaseIndex == analyzed.latestReleaseIndex,
                                        deviceProfile = deviceProfile,
                                    )
                                }

                            emitReadyPrioritized(
                                repository = analyzed,
                                selectedIndex = initialReleaseIndex,
                                classified = activeClassified,
                            )

                            launch(Dispatchers.IO) {
                                historyRepository.recordAnalysis(
                                    fullName = analyzed.canonicalFullName,
                                    owner = analyzed.identifier.owner,
                                    repoName = analyzed.identifier.repo,
                                    avatarUrl = analyzed.ownerAvatarUrl,
                                    latestTag = activeClassified.tagName,
                                    assetCount = activeClassified.assets.size,
                                    hasReleases = true,
                                )
                            }
                        }

                        is ReleaseFetchOutcome.NoReleases -> {
                            _analysisState.value = AnalysisState.NoReleases(
                                canonicalFullName = outcome.canonicalFullName,
                                ownerAvatarUrl = outcome.ownerAvatarUrl,
                            )

                            launch(Dispatchers.IO) {
                                historyRepository.recordAnalysis(
                                    fullName = outcome.canonicalFullName,
                                    owner = outcome.identifier.owner,
                                    repoName = outcome.identifier.repo,
                                    avatarUrl = outcome.ownerAvatarUrl,
                                    latestTag = null,
                                    assetCount = 0,
                                    hasReleases = false,
                                )
                            }
                        }

                        is ReleaseFetchOutcome.Failure -> {
                            _analysisState.value = AnalysisState.Error(
                                message = outcome.message,
                                attemptedInput = trimmed,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun emitReadyPrioritized(
        repository: AnalyzedRepository,
        selectedIndex: Int,
        classified: ClassifiedRelease,
        selectedPlatform: PlatformCategory = PlatformCategory.ALL,
    ) {
        val totalForPlatform = countAssetsForPlatform(classified, selectedPlatform)
        val initialLimit = AnalysisState.INITIAL_ASSET_BATCH.coerceAtMost(totalForPlatform)
        _analysisState.value = AnalysisState.Ready(
            repository = repository,
            selectedReleaseIndex = selectedIndex,
            activeRelease = classified,
            selectedPlatform = selectedPlatform,
            showReleaseNotes = false,
            visibleAssetLimit = initialLimit,
            hasMoreAssetsToReveal = initialLimit < totalForPlatform,
        )
    }

    fun revealMoreAssets() {
        val current = _analysisState.value as? AnalysisState.Ready ?: return
        if (!current.hasMoreAssetsToReveal) return

        val totalForPlatform = countAssetsForPlatform(current.activeRelease, current.selectedPlatform)
        val nextLimit = (current.visibleAssetLimit + AnalysisState.ASSET_BATCH_STEP)
            .coerceAtMost(totalForPlatform)
        if (nextLimit <= current.visibleAssetLimit) return

        _analysisState.value = current.copy(
            visibleAssetLimit = nextLimit,
            hasMoreAssetsToReveal = nextLimit < totalForPlatform,
        )
    }

    fun selectReleaseVersion(index: Int) {
        val current = _analysisState.value as? AnalysisState.Ready ?: return
        val repo = current.repository
        if (index !in repo.rawReleases.indices || index == current.selectedReleaseIndex) return

        val raw = repo.rawReleases[index]
        val cachedClassified = repo.classifiedCache[raw.id]
        if (cachedClassified != null) {
            val keepPlatform = resolvePlatformFilter(current.selectedPlatform, cachedClassified)
            emitReadyPrioritized(
                repository = repo,
                selectedIndex = index,
                classified = cachedClassified,
                selectedPlatform = keepPlatform,
            )
            return
        }

        // Classify the selected release lazily on Dispatchers.Default
        viewModelScope.launch {
            val classified = withContext(Dispatchers.Default) {
                AssetClassifier.classifySingleRelease(
                    raw = raw,
                    isLatest = index == repo.latestReleaseIndex,
                    deviceProfile = deviceProfile,
                )
            }
            val latestState = _analysisState.value as? AnalysisState.Ready ?: return@launch
            if (latestState.repository.canonicalFullName != repo.canonicalFullName) return@launch

            val updatedRepo = latestState.repository.withClassifiedRelease(raw.id, classified)
            (releaseRepository as? GitHubReleaseRepository)?.updateCachedRepository(updatedRepo)

            val keepPlatform = resolvePlatformFilter(latestState.selectedPlatform, classified)
            emitReadyPrioritized(
                repository = updatedRepo,
                selectedIndex = index,
                classified = classified,
                selectedPlatform = keepPlatform,
            )
        }
    }

    fun loadMoreReleases() {
        val current = _analysisState.value as? AnalysisState.Ready ?: return
        val repo = current.repository
        if (!repo.hasMoreReleases || current.isLoadingMoreReleases) return

        val nextPage = repo.currentPage + 1
        val pageKey = repo.canonicalFullName.lowercase() to nextPage
        if (pageKey in requestedPages) return
        requestedPages += pageKey

        loadMoreJob?.cancel()
        _analysisState.value = current.copy(isLoadingMoreReleases = true)

        loadMoreJob = viewModelScope.launch {
            val result = releaseRepository.fetchReleasesPage(repo.identifier, nextPage)
            val latestState = _analysisState.value as? AnalysisState.Ready ?: return@launch
            if (latestState.repository.canonicalFullName != repo.canonicalFullName) return@launch

            result.onSuccess { pageResult ->
                val existingIds = latestState.repository.rawReleases.mapTo(HashSet()) { it.id }
                val newEntries = pageResult.releases.filter { it.id !in existingIds }
                val updatedRepo = latestState.repository.copy(
                    rawReleases = latestState.repository.rawReleases + newEntries,
                    currentPage = nextPage,
                    hasMoreReleases = pageResult.hasMoreReleases && newEntries.isNotEmpty(),
                )
                (releaseRepository as? GitHubReleaseRepository)?.updateCachedRepository(updatedRepo)
                _analysisState.value = latestState.copy(
                    repository = updatedRepo,
                    isLoadingMoreReleases = false,
                )
            }.onFailure { error ->
                requestedPages -= pageKey
                _analysisState.value = latestState.copy(isLoadingMoreReleases = false)
                _snackbarEvents.tryEmit(error.localizedMessage ?: "Failed to load older releases.")
            }
        }
    }

    private fun countAssetsForPlatform(
        release: ClassifiedRelease,
        platform: PlatformCategory,
    ): Int {
        return if (platform == PlatformCategory.ALL) {
            release.assets.size
        } else {
            release.groupedAssets[platform]?.size ?: 0
        }
    }

    private fun resolvePlatformFilter(
        requested: PlatformCategory,
        release: ClassifiedRelease,
    ): PlatformCategory {
        return if (requested == PlatformCategory.ALL || (release.platformCounts[requested] ?: 0) > 0) {
            requested
        } else {
            PlatformCategory.ALL
        }
    }

    fun selectPlatform(platform: PlatformCategory) {
        val current = _analysisState.value as? AnalysisState.Ready ?: return
        val totalForPlatform = countAssetsForPlatform(current.activeRelease, platform)
        val initialLimit = AnalysisState.INITIAL_ASSET_BATCH.coerceAtMost(totalForPlatform)
        _analysisState.value = current.copy(
            selectedPlatform = platform,
            visibleAssetLimit = initialLimit,
            hasMoreAssetsToReveal = initialLimit < totalForPlatform,
        )
    }

    fun toggleReleaseNotes() {
        val current = _analysisState.value as? AnalysisState.Ready ?: return
        _analysisState.value = current.copy(showReleaseNotes = !current.showReleaseNotes)
    }

    fun togglePin(fullName: String, currentlyPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.togglePin(fullName, currentlyPinned)
        }
    }

    fun removeSavedRepo(fullName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.removeRepo(fullName)
        }
    }

    fun clearRecentHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.clearRecentHistory()
        }
    }

    fun downloadAsset(asset: ClassifiedAsset, repoFullName: String) {
        val result = downloadController.startDownload(asset, repoFullName)
        result.onSuccess {
            _snackbarEvents.tryEmit("Downloading ${asset.name}…")
        }.onFailure { error ->
            _snackbarEvents.tryEmit(error.localizedMessage ?: "Failed to start download.")
        }
    }

    fun cancelDownload(assetId: Long) {
        downloadController.cancelDownload(assetId)
    }

    fun openSystemDownloads() {
        if (!downloadController.openSystemDownloads()) {
            _snackbarEvents.tryEmit("Could not open the system Downloads app.")
        }
    }

    fun dismissDownloadRecord(assetId: Long) {
        downloadController.dismissDownloadRecord(assetId)
    }

    class Factory(
        private val releaseRepository: ReleaseAnalyzerDataSource,
        private val historyRepository: RepoHistoryRepository,
        private val downloadController: DownloadController,
        private val deviceProfile: DeviceProfile = DeviceSpecReader.readCurrentDevice(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReleaseAnalyzerViewModel(
                releaseRepository = releaseRepository,
                historyRepository = historyRepository,
                downloadController = downloadController,
                deviceProfile = deviceProfile,
            ) as T
        }
    }
}
