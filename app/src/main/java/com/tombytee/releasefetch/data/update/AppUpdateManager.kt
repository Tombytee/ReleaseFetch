package com.tombytee.releasefetch.data.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface UpdateManifestFetcher {
    suspend fun fetchManifest(url: String): Result<String>
}

class OkHttpUpdateManifestFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UpdateManifestFetcher {

    override suspend fun fetchManifest(url: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "ReleaseFetch-Android/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} fetching update manifest")
                }
                response.body?.string() ?: throw IOException("Empty update manifest response")
            }
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }
    }
}

class AppUpdateManager(
    private val gateway: UpdateSystemGateway,
    private val stateStore: UpdateStateStore,
    private val manifestFetcher: UpdateManifestFetcher = OkHttpUpdateManifestFetcher(),
    private val manifestUrl: String = UPDATE_MANIFEST_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = 400L,
    scope: CoroutineScope? = null,
) {
    private val managerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.UpToDate)
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    private val _isCheckingManual = MutableStateFlow(false)
    val isCheckingManual: StateFlow<Boolean> = _isCheckingManual.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val _launchEffects = Channel<UpdateLaunchEffect>(Channel.BUFFERED)
    val launchEffects: Flow<UpdateLaunchEffect> = _launchEffects.receiveAsFlow()

    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()
    private var pollJob: Job? = null
    private var hasPromptedUnknownSourcesForCurrentAttempt = false

    fun onAppForegrounded() {
        managerScope.launch {
            val unlockedAfterUpgrade = cleanupIfUpgraded()
            if (!unlockedAfterUpgrade) {
                restorePendingUpdateIfNeeded()
            }
            checkForUpdatesInternal(isManual = false)
        }
    }

    fun onAppResumed() {
        managerScope.launch {
            if (cleanupIfUpgraded()) {
                return@launch
            }
            val current = _uiState.value as? AppUpdateUiState.UpdateRequired ?: return@launch
            val installed = withContext(ioDispatcher) { gateway.getInstalledAppVersion() }
            if (installed.versionCode >= current.manifest.versionCode) {
                cleanupIfUpgraded()
                return@launch
            }

            when (current.step) {
                is UpdateStep.PermissionRequired -> {
                    if (gateway.canRequestPackageInstalls()) {
                        val persisted = stateStore.readPersistedDownload()
                        val apkPath = persisted?.localFilePath
                        if (apkPath != null && withContext(ioDispatcher) { gateway.doesApkExist(apkPath) }) {
                            transitionToInstalling(current.manifest, installed, apkPath)
                        } else {
                            ensureDownloadActive(current.manifest, installed, forceFresh = true, autoPromptExternal = true)
                        }
                    }
                }

                is UpdateStep.Installing -> {
                    val persisted = stateStore.readPersistedDownload()
                    val apkPath = persisted?.localFilePath
                    if (apkPath != null && withContext(ioDispatcher) { gateway.doesApkExist(apkPath) }) {
                        _uiState.value = current.copy(
                            installedVersionName = installed.versionName,
                            installedVersionCode = installed.versionCode,
                            step = UpdateStep.ReadyToInstall(wasCancelledOrFailed = true),
                        )
                    } else {
                        ensureDownloadActive(current.manifest, installed, forceFresh = true, autoPromptExternal = true)
                    }
                }

                is UpdateStep.ReadyToInstall -> {
                    val persisted = stateStore.readPersistedDownload()
                    val apkPath = persisted?.localFilePath
                    if (apkPath == null || !withContext(ioDispatcher) { gateway.doesApkExist(apkPath) }) {
                        ensureDownloadActive(current.manifest, installed, forceFresh = true, autoPromptExternal = true)
                    }
                }

                is UpdateStep.Downloading -> {
                    refreshActiveDownloadOnce()
                }

                is UpdateStep.DownloadError -> Unit
            }
        }
    }

    fun checkForUpdatesManually() {
        managerScope.launch {
            checkForUpdatesInternal(isManual = true)
        }
    }

    fun onAllowUnknownSourcesClicked() {
        _launchEffects.trySend(UpdateLaunchEffect.OpenUnknownAppsSettings)
    }

    fun onInstallClicked() {
        managerScope.launch {
            if (cleanupIfUpgraded()) return@launch
            val current = _uiState.value as? AppUpdateUiState.UpdateRequired ?: return@launch
            val installed = withContext(ioDispatcher) { gateway.getInstalledAppVersion() }
            val persisted = stateStore.readPersistedDownload()
            val apkPath = persisted?.localFilePath

            if (apkPath == null || !withContext(ioDispatcher) { gateway.doesApkExist(apkPath) }) {
                ensureDownloadActive(current.manifest, installed, forceFresh = true, autoPromptExternal = true)
                return@launch
            }

            if (!gateway.canRequestPackageInstalls()) {
                _uiState.value = current.copy(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    step = UpdateStep.PermissionRequired,
                )
                _launchEffects.trySend(UpdateLaunchEffect.OpenUnknownAppsSettings)
                return@launch
            }

            transitionToInstalling(current.manifest, installed, apkPath)
        }
    }

    fun onInstallerResultReturned() {
        onAppResumed()
    }

    fun onRetryDownloadClicked() {
        managerScope.launch {
            val current = _uiState.value as? AppUpdateUiState.UpdateRequired ?: return@launch
            val installed = withContext(ioDispatcher) { gateway.getInstalledAppVersion() }
            hasPromptedUnknownSourcesForCurrentAttempt = false
            ensureDownloadActive(current.manifest, installed, forceFresh = true, autoPromptExternal = true)
        }
    }

    suspend fun cleanupIfUpgraded(): Boolean {
        return withContext(ioDispatcher) {
            val installed = gateway.getInstalledAppVersion()
            val persisted = stateStore.readPersistedDownload()
            if (persisted != null && installed.versionCode >= persisted.targetVersionCode) {
                pollJob?.cancel()
                pollJob = null
                gateway.removeDownloadAndFile(persisted.downloadId, persisted.localFilePath)
                stateStore.clearPersistedDownload()
                hasPromptedUnknownSourcesForCurrentAttempt = false
                _uiState.value = AppUpdateUiState.UpToDate
                return@withContext true
            }

            val current = _uiState.value as? AppUpdateUiState.UpdateRequired
            if (current != null && installed.versionCode >= current.manifest.versionCode) {
                pollJob?.cancel()
                pollJob = null
                if (persisted != null) {
                    gateway.removeDownloadAndFile(persisted.downloadId, persisted.localFilePath)
                    stateStore.clearPersistedDownload()
                }
                hasPromptedUnknownSourcesForCurrentAttempt = false
                _uiState.value = AppUpdateUiState.UpToDate
                return@withContext true
            }

            false
        }
    }

    private suspend fun restorePendingUpdateIfNeeded() {
        val persisted = withContext(ioDispatcher) { stateStore.readPersistedDownload() } ?: return
        val installed = withContext(ioDispatcher) { gateway.getInstalledAppVersion() }
        if (installed.versionCode >= persisted.targetVersionCode) {
            cleanupIfUpgraded()
            return
        }
        if (UpdateManifestValidator.validateApkUrl(persisted.apkUrl) != null) {
            withContext(ioDispatcher) {
                gateway.removeDownloadAndFile(persisted.downloadId, persisted.localFilePath)
                stateStore.clearPersistedDownload()
            }
            return
        }

        val manifest = UpdateManifest(
            versionName = persisted.targetVersionName,
            versionCode = persisted.targetVersionCode,
            apkUrl = persisted.apkUrl,
            releaseNotes = persisted.releaseNotes,
        )
        ensureDownloadActive(manifest, installed, forceFresh = false, autoPromptExternal = false)
    }

    private suspend fun checkForUpdatesInternal(isManual: Boolean) {
        if (!checkMutex.tryLock()) {
            return
        }
        if (isManual) {
            _isCheckingManual.value = true
        }
        try {
            val installed = withContext(ioDispatcher) { gateway.getInstalledAppVersion() }
            val fetchResult = manifestFetcher.fetchManifest(manifestUrl)
            val rawJson = fetchResult.getOrElse {
                if (isManual) {
                    _snackbarEvents.tryEmit("Could not check for updates right now. Please try again later.")
                }
                return
            }

            when (val validation = UpdateManifestValidator.parseAndValidate(rawJson)) {
                is ManifestValidationResult.Invalid -> {
                    // Never lock users out because of invalid/placeholder update metadata
                    clearPersistedIfMatchesOrUnlocked(installed.versionCode)
                    if (isManual) {
                        val message = when (validation.reason) {
                            ManifestInvalidReason.PLACEHOLDER_URL ->
                                "Update download link is not published yet. Continuing with current version."
                            ManifestInvalidReason.MALFORMED_JSON,
                            ManifestInvalidReason.INVALID_VERSION,
                            ManifestInvalidReason.INVALID_URL ->
                                "Update metadata is currently invalid or unavailable."
                        }
                        _snackbarEvents.tryEmit(message)
                    }
                }

                is ManifestValidationResult.Valid -> {
                    val manifest = validation.manifest
                    if (manifest.versionCode <= installed.versionCode) {
                        clearPersistedIfMatchesOrUnlocked(installed.versionCode)
                        _uiState.value = AppUpdateUiState.UpToDate
                        if (isManual) {
                            _snackbarEvents.tryEmit("You are running the latest version (${installed.versionName}).")
                        }
                    } else {
                        val currentRequired = _uiState.value as? AppUpdateUiState.UpdateRequired
                        val sameVersionInProgress = currentRequired != null &&
                            currentRequired.manifest.versionCode == manifest.versionCode &&
                            currentRequired.manifest.apkUrl == manifest.apkUrl

                        if (sameVersionInProgress) {
                            // Keep active state/progress intact; just refresh notes if updated
                            if (currentRequired.manifest.releaseNotes != manifest.releaseNotes) {
                                _uiState.value = currentRequired.copy(manifest = manifest)
                            }
                            if (currentRequired.step is UpdateStep.Downloading) {
                                refreshActiveDownloadOnce()
                            }
                        } else {
                            hasPromptedUnknownSourcesForCurrentAttempt = false
                            ensureDownloadActive(
                                manifest = manifest,
                                installed = installed,
                                forceFresh = false,
                                autoPromptExternal = true,
                            )
                        }
                    }
                }
            }
        } finally {
            if (isManual) {
                _isCheckingManual.value = false
            }
            checkMutex.unlock()
        }
    }

    private suspend fun clearPersistedIfMatchesOrUnlocked(installedVersionCode: Long) {
        withContext(ioDispatcher) {
            val persisted = stateStore.readPersistedDownload()
            if (persisted != null && (installedVersionCode >= persisted.targetVersionCode || _uiState.value is AppUpdateUiState.UpToDate)) {
                pollJob?.cancel()
                pollJob = null
                gateway.removeDownloadAndFile(persisted.downloadId, persisted.localFilePath)
                stateStore.clearPersistedDownload()
            }
        }
    }

    private suspend fun ensureDownloadActive(
        manifest: UpdateManifest,
        installed: InstalledAppVersion,
        forceFresh: Boolean,
        autoPromptExternal: Boolean,
    ) {
        downloadMutex.withLock {
            val persisted = withContext(ioDispatcher) { stateStore.readPersistedDownload() }

            if (persisted != null && !forceFresh &&
                persisted.targetVersionCode == manifest.versionCode &&
                persisted.apkUrl == manifest.apkUrl
            ) {
                val snapshot = withContext(ioDispatcher) {
                    gateway.queryDownloadStatus(persisted.downloadId, persisted.localFilePath)
                }

                when (snapshot?.status) {
                    DownloadQueryStatus.PENDING,
                    DownloadQueryStatus.RUNNING,
                    DownloadQueryStatus.PAUSED -> {
                        val percent = computeProgressPercent(snapshot.bytesDownloaded, snapshot.totalBytes)
                        _uiState.value = AppUpdateUiState.UpdateRequired(
                            installedVersionName = installed.versionName,
                            installedVersionCode = installed.versionCode,
                            manifest = manifest,
                            step = UpdateStep.Downloading(
                                progressPercent = percent,
                                downloadedBytes = snapshot.bytesDownloaded,
                                totalBytes = snapshot.totalBytes,
                                isPausedOrWaiting = snapshot.status != DownloadQueryStatus.RUNNING,
                            ),
                        )
                        startDownloadPolling(persisted, manifest, installed)
                        return
                    }

                    DownloadQueryStatus.SUCCESSFUL -> {
                        val exists = withContext(ioDispatcher) { gateway.doesApkExist(snapshot.localFilePath) }
                        if (exists) {
                            handleCompletedDownload(manifest, installed, snapshot.localFilePath, autoPromptExternal)
                            return
                        } else {
                            withContext(ioDispatcher) {
                                gateway.removeDownloadAndFile(persisted.downloadId, persisted.localFilePath)
                                stateStore.clearPersistedDownload()
                            }
                        }
                    }

                    DownloadQueryStatus.FAILED -> {
                        // Interrupted/failed download in previous run: remove failed entry and restart
                        withContext(ioDispatcher) {
                            gateway.removeDownloadAndFile(persisted.downloadId, persisted.localFilePath)
                            stateStore.clearPersistedDownload()
                        }
                    }

                    null -> {
                        val exists = withContext(ioDispatcher) { gateway.doesApkExist(persisted.localFilePath) }
                        if (exists) {
                            handleCompletedDownload(manifest, installed, persisted.localFilePath, autoPromptExternal)
                            return
                        } else {
                            withContext(ioDispatcher) { stateStore.clearPersistedDownload() }
                        }
                    }
                }
            } else if (persisted != null) {
                withContext(ioDispatcher) {
                    gateway.removeDownloadAndFile(persisted.downloadId, persisted.localFilePath)
                    stateStore.clearPersistedDownload()
                }
            }

            // Start a brand-new DownloadManager enqueue
            pollJob?.cancel()
            pollJob = null
            val enqueuedResult = withContext(ioDispatcher) {
                runCatching { gateway.enqueueUpdateDownload(manifest) }
            }

            enqueuedResult.onSuccess { enqueued ->
                val record = PersistedUpdateDownload(
                    downloadId = enqueued.downloadId,
                    targetVersionCode = manifest.versionCode,
                    targetVersionName = manifest.versionName,
                    apkUrl = manifest.apkUrl,
                    localFilePath = enqueued.localFilePath,
                    releaseNotes = manifest.releaseNotes,
                )
                withContext(ioDispatcher) { stateStore.savePersistedDownload(record) }
                _uiState.value = AppUpdateUiState.UpdateRequired(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    manifest = manifest,
                    step = UpdateStep.Downloading(
                        progressPercent = -1,
                        downloadedBytes = 0L,
                        totalBytes = -1L,
                        isPausedOrWaiting = true,
                    ),
                )
                startDownloadPolling(record, manifest, installed)
            }.onFailure { error ->
                _uiState.value = AppUpdateUiState.UpdateRequired(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    manifest = manifest,
                    step = UpdateStep.DownloadError(
                        error.message ?: "Unable to start update download. Please retry.",
                    ),
                )
            }
        }
    }

    private fun startDownloadPolling(
        record: PersistedUpdateDownload,
        manifest: UpdateManifest,
        installed: InstalledAppVersion,
    ) {
        pollJob?.cancel()
        pollJob = managerScope.launch {
            while (isActive) {
                val finished = pollDownloadStepOnce(record, manifest, installed)
                if (finished) break
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun refreshActiveDownloadOnce() {
        val current = _uiState.value as? AppUpdateUiState.UpdateRequired ?: return
        val persisted = withContext(ioDispatcher) { stateStore.readPersistedDownload() } ?: return
        val installed = withContext(ioDispatcher) { gateway.getInstalledAppVersion() }
        pollDownloadStepOnce(persisted, current.manifest, installed)
    }

    private suspend fun pollDownloadStepOnce(
        record: PersistedUpdateDownload,
        manifest: UpdateManifest,
        installed: InstalledAppVersion,
    ): Boolean {
        val snapshot = withContext(ioDispatcher) {
            gateway.queryDownloadStatus(record.downloadId, record.localFilePath)
        }
        if (snapshot == null) {
            val exists = withContext(ioDispatcher) { gateway.doesApkExist(record.localFilePath) }
            return if (exists) {
                handleCompletedDownload(manifest, installed, record.localFilePath, autoPromptExternal = true)
                true
            } else {
                _uiState.value = AppUpdateUiState.UpdateRequired(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    manifest = manifest,
                    step = UpdateStep.DownloadError("Update download was cancelled or removed."),
                )
                true
            }
        }

        return when (snapshot.status) {
            DownloadQueryStatus.PENDING,
            DownloadQueryStatus.RUNNING,
            DownloadQueryStatus.PAUSED -> {
                _uiState.value = AppUpdateUiState.UpdateRequired(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    manifest = manifest,
                    step = UpdateStep.Downloading(
                        progressPercent = computeProgressPercent(snapshot.bytesDownloaded, snapshot.totalBytes),
                        downloadedBytes = snapshot.bytesDownloaded,
                        totalBytes = snapshot.totalBytes,
                        isPausedOrWaiting = snapshot.status != DownloadQueryStatus.RUNNING,
                    ),
                )
                false
            }

            DownloadQueryStatus.SUCCESSFUL -> {
                val exists = withContext(ioDispatcher) { gateway.doesApkExist(snapshot.localFilePath) }
                if (exists) {
                    handleCompletedDownload(manifest, installed, snapshot.localFilePath, autoPromptExternal = true)
                } else {
                    _uiState.value = AppUpdateUiState.UpdateRequired(
                        installedVersionName = installed.versionName,
                        installedVersionCode = installed.versionCode,
                        manifest = manifest,
                        step = UpdateStep.DownloadError("Downloaded APK file could not be found on disk."),
                    )
                }
                true
            }

            DownloadQueryStatus.FAILED -> {
                _uiState.value = AppUpdateUiState.UpdateRequired(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    manifest = manifest,
                    step = UpdateStep.DownloadError(
                        snapshot.failureReason ?: "Update download failed. Please retry.",
                    ),
                )
                true
            }
        }
    }

    private fun handleCompletedDownload(
        manifest: UpdateManifest,
        installed: InstalledAppVersion,
        localFilePath: String,
        autoPromptExternal: Boolean,
    ) {
        pollJob?.cancel()
        pollJob = null

        if (!gateway.canRequestPackageInstalls()) {
            _uiState.value = AppUpdateUiState.UpdateRequired(
                installedVersionName = installed.versionName,
                installedVersionCode = installed.versionCode,
                manifest = manifest,
                step = UpdateStep.PermissionRequired,
            )
            if (autoPromptExternal && !hasPromptedUnknownSourcesForCurrentAttempt) {
                hasPromptedUnknownSourcesForCurrentAttempt = true
                _launchEffects.trySend(UpdateLaunchEffect.OpenUnknownAppsSettings)
            }
        } else {
            if (autoPromptExternal) {
                transitionToInstalling(manifest, installed, localFilePath)
            } else {
                _uiState.value = AppUpdateUiState.UpdateRequired(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    manifest = manifest,
                    step = UpdateStep.ReadyToInstall(wasCancelledOrFailed = false),
                )
            }
        }
    }

    private fun transitionToInstalling(
        manifest: UpdateManifest,
        installed: InstalledAppVersion,
        localFilePath: String,
    ) {
        _uiState.value = AppUpdateUiState.UpdateRequired(
            installedVersionName = installed.versionName,
            installedVersionCode = installed.versionCode,
            manifest = manifest,
            step = UpdateStep.Installing,
        )
        _launchEffects.trySend(UpdateLaunchEffect.LaunchPackageInstaller(localFilePath))
    }

    private fun computeProgressPercent(downloaded: Long, total: Long): Int {
        if (total <= 0L) return -1
        return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
    }
}
