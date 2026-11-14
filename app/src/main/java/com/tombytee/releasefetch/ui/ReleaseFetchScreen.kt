package com.tombytee.releasefetch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tombytee.releasefetch.data.update.AppUpdateManager
import com.tombytee.releasefetch.domain.ClassifiedAsset
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.PlatformCategory
import com.tombytee.releasefetch.ui.components.AboutDeveloperBottomSheet
import com.tombytee.releasefetch.ui.components.DeviceSpecsBanner
import com.tombytee.releasefetch.ui.components.DownloadsBottomSheet
import com.tombytee.releasefetch.ui.components.ErrorAnalysisCard
import com.tombytee.releasefetch.ui.components.LoadingAnalysisCard
import com.tombytee.releasefetch.ui.components.NoReleasesStateCard
import com.tombytee.releasefetch.ui.components.RepoAnalyzerInputCard
import com.tombytee.releasefetch.ui.components.TopBrandBar
import com.tombytee.releasefetch.ui.components.releaseDashboardItems
import com.tombytee.releasefetch.ui.components.savedReposItems
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ReleaseFetchScreen(
    viewModel: ReleaseAnalyzerViewModel,
    modifier: Modifier = Modifier,
    appUpdateManager: AppUpdateManager? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showDownloadsSheet by rememberSaveable { mutableStateOf(false) }
    var showAboutDeveloperSheet by rememberSaveable { mutableStateOf(false) }
    var promptedNotificationPermission by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Download notifications are disabled. You can track progress in the Downloads panel.")
            }
        }
    }

    // Single SnapshotStateMap collector so fast flings allocate zero per-card Flow observers
    val downloadsSnapshotMap = remember { mutableStateMapOf<Long, DownloadItemState>() }
    LaunchedEffect(viewModel) {
        viewModel.downloadsByAssetId.collect { latest ->
            val removedKeys = downloadsSnapshotMap.keys.filter { it !in latest }
            for (key in removedKeys) {
                downloadsSnapshotMap.remove(key)
            }
            for ((assetId, itemState) in latest) {
                if (downloadsSnapshotMap[assetId] != itemState) {
                    downloadsSnapshotMap[assetId] = itemState
                }
            }
        }
    }
    val downloadLookup = remember(downloadsSnapshotMap) {
        { assetId: Long -> downloadsSnapshotMap[assetId] }
    }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(appUpdateManager) {
        appUpdateManager?.snackbarEvents?.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val onPasteClicked = remember(clipboardManager, coroutineScope, snackbarHostState, viewModel) {
        {
            val pastedText = clipboardManager.getText()?.text?.trim()
            if (!pastedText.isNullOrBlank()) {
                viewModel.onUrlInputChanged(pastedText)
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Clipboard is empty. Copy a GitHub repository link first.")
                }
            }
            Unit
        }
    }

    val onAnalyzeClicked = remember(keyboardController, viewModel) {
        {
            keyboardController?.hide()
            viewModel.analyzeCurrentInput()
        }
    }

    val onRepoSelected = remember(keyboardController, viewModel) {
        { repoFullName: String ->
            keyboardController?.hide()
            viewModel.analyzeRepository(repoFullName)
        }
    }

    val onDownloadAsset = remember(context, viewModel, notificationPermissionLauncher) {
        { asset: ClassifiedAsset, repoFullName: String ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !promptedNotificationPermission &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                promptedNotificationPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.downloadAsset(asset, repoFullName)
        }
    }

    // Exclude IME insets so keyboard opening/closing animation does not relayout the entire LazyColumn
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isExpandedLayout = maxWidth >= 840.dp
            ReleaseFetchLayout(
                isExpandedLayout = isExpandedLayout,
                viewModel = viewModel,
                appUpdateManager = appUpdateManager,
                downloadLookup = downloadLookup,
                onOpenDownloads = { showDownloadsSheet = true },
                onOpenAboutDeveloper = { showAboutDeveloperSheet = true },
                onPasteClicked = onPasteClicked,
                onAnalyzeClicked = onAnalyzeClicked,
                onRepoSelected = onRepoSelected,
                onDownloadAsset = onDownloadAsset,
            )
        }
    }

    if (showDownloadsSheet) {
        val downloadsList by viewModel.downloadsList.collectAsStateWithLifecycle()
        DownloadsBottomSheet(
            downloads = downloadsList,
            onDismissSheet = { showDownloadsSheet = false },
            onOpenSystemDownloads = {
                showDownloadsSheet = false
                viewModel.openSystemDownloads()
            },
            onCancelDownload = viewModel::cancelDownload,
            onDismissRecord = viewModel::dismissDownloadRecord,
        )
    }

    if (showAboutDeveloperSheet) {
        AboutDeveloperBottomSheet(
            onDismissSheet = { showAboutDeveloperSheet = false },
            onOpenUrlError = { message ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            },
        )
    }
}

@Composable
private fun ReleaseFetchLayout(
    isExpandedLayout: Boolean,
    viewModel: ReleaseAnalyzerViewModel,
    appUpdateManager: AppUpdateManager?,
    downloadLookup: (Long) -> DownloadItemState?,
    onOpenDownloads: () -> Unit,
    onOpenAboutDeveloper: () -> Unit,
    onPasteClicked: () -> Unit,
    onAnalyzeClicked: () -> Unit,
    onRepoSelected: (String) -> Unit,
    onDownloadAsset: (ClassifiedAsset, String) -> Unit,
) {
    val analysisState by viewModel.analysisState.collectAsStateWithLifecycle()
    val pinnedRepoNames by viewModel.pinnedRepoNames.collectAsStateWithLifecycle()

    if (!isExpandedLayout && analysisState !is AnalysisState.Idle) {
        BackHandler {
            viewModel.resetToHome()
        }
    }

    val hasMoreAssetsToReveal = (analysisState as? AnalysisState.Ready)?.hasMoreAssetsToReveal == true

    if (isExpandedLayout) {
        val leftListState = rememberLazyListState()
        val rightListState = rememberLazyListState()
        val pinnedRepos by viewModel.pinnedRepos.collectAsStateWithLifecycle()
        val recentRepos by viewModel.recentRepos.collectAsStateWithLifecycle()

        RevealMoreAssetsOnApproach(
            listState = rightListState,
            enabled = hasMoreAssetsToReveal,
            onRevealMore = viewModel::revealMoreAssets,
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TopBrandBar(
                    activeDownloadCountFlow = viewModel.activeDownloadCount,
                    downloadsListFlow = viewModel.downloadsList,
                    onOpenDownloads = onOpenDownloads,
                    onBrandClick = viewModel::resetToHome,
                    isCheckingUpdatesFlow = appUpdateManager?.isCheckingManual,
                    onCheckUpdates = appUpdateManager?.let { manager -> manager::checkForUpdatesManually },
                    onOpenAboutDeveloper = onOpenAboutDeveloper,
                )
                LazyColumn(
                    state = leftListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "device_banner", contentType = "device_banner") {
                        DeviceSpecsBanner(deviceProfile = viewModel.deviceProfile)
                    }
                    item(key = "input_card", contentType = "input_card") {
                        RepoAnalyzerInputCard(
                            urlInputFlow = viewModel.urlInput,
                            isLoadingFlow = viewModel.isAnalyzing,
                            onUrlInputChanged = viewModel::onUrlInputChanged,
                            onPasteClicked = onPasteClicked,
                            onClearClicked = viewModel::clearInput,
                            onAnalyzeClicked = onAnalyzeClicked,
                        )
                    }
                    savedReposItems(
                        pinnedRepos = pinnedRepos,
                        recentRepos = recentRepos,
                        onRepoSelected = onRepoSelected,
                        onTogglePin = viewModel::togglePin,
                        onRemoveRepo = viewModel::removeSavedRepo,
                        onClearRecent = viewModel::clearRecentHistory,
                    )
                }
            }

            LazyColumn(
                state = rightListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("main_release_list"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                analysisResultItems(
                    analysisState = analysisState,
                    pinnedRepoNames = pinnedRepoNames,
                    downloadLookup = downloadLookup,
                    onSelectReleaseIndex = viewModel::selectReleaseVersion,
                    onSelectPlatform = viewModel::selectPlatform,
                    onToggleReleaseNotes = viewModel::toggleReleaseNotes,
                    onTogglePin = viewModel::togglePin,
                    onDownloadAsset = onDownloadAsset,
                    onOpenSystemDownloads = viewModel::openSystemDownloads,
                    onRetry = viewModel::analyzeRepository,
                    onBackToHome = viewModel::resetToHome,
                    onLoadMoreReleases = viewModel::loadMoreReleases,
                    onRevealMoreAssets = viewModel::revealMoreAssets,
                    showExpandedPlaceholder = true,
                )
            }
        }
    } else {
        val listState = rememberLazyListState()
        val pinnedRepos by viewModel.pinnedRepos.collectAsStateWithLifecycle()
        val recentRepos by viewModel.recentRepos.collectAsStateWithLifecycle()

        RevealMoreAssetsOnApproach(
            listState = listState,
            enabled = hasMoreAssetsToReveal,
            onRevealMore = viewModel::revealMoreAssets,
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 640.dp),
            ) {
                TopBrandBar(
                    activeDownloadCountFlow = viewModel.activeDownloadCount,
                    downloadsListFlow = viewModel.downloadsList,
                    onOpenDownloads = onOpenDownloads,
                    onBrandClick = viewModel::resetToHome,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    isCheckingUpdatesFlow = appUpdateManager?.isCheckingManual,
                    onCheckUpdates = appUpdateManager?.let { manager -> manager::checkForUpdatesManually },
                    onOpenAboutDeveloper = onOpenAboutDeveloper,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("main_release_list"),
                    contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "device_banner", contentType = "device_banner") {
                        DeviceSpecsBanner(deviceProfile = viewModel.deviceProfile)
                    }
                    item(key = "input_card", contentType = "input_card") {
                        RepoAnalyzerInputCard(
                            urlInputFlow = viewModel.urlInput,
                            isLoadingFlow = viewModel.isAnalyzing,
                            onUrlInputChanged = viewModel::onUrlInputChanged,
                            onPasteClicked = onPasteClicked,
                            onClearClicked = viewModel::clearInput,
                            onAnalyzeClicked = onAnalyzeClicked,
                        )
                    }

                    if (analysisState is AnalysisState.Idle) {
                        savedReposItems(
                            pinnedRepos = pinnedRepos,
                            recentRepos = recentRepos,
                            onRepoSelected = onRepoSelected,
                            onTogglePin = viewModel::togglePin,
                            onRemoveRepo = viewModel::removeSavedRepo,
                            onClearRecent = viewModel::clearRecentHistory,
                        )
                    } else {
                        analysisResultItems(
                            analysisState = analysisState,
                            pinnedRepoNames = pinnedRepoNames,
                            downloadLookup = downloadLookup,
                            onSelectReleaseIndex = viewModel::selectReleaseVersion,
                            onSelectPlatform = viewModel::selectPlatform,
                            onToggleReleaseNotes = viewModel::toggleReleaseNotes,
                            onTogglePin = viewModel::togglePin,
                            onDownloadAsset = onDownloadAsset,
                            onOpenSystemDownloads = viewModel::openSystemDownloads,
                            onRetry = viewModel::analyzeRepository,
                            onBackToHome = viewModel::resetToHome,
                            onLoadMoreReleases = viewModel::loadMoreReleases,
                            onRevealMoreAssets = viewModel::revealMoreAssets,
                            showExpandedPlaceholder = false,
                        )
                    }

                    item(key = "bottom_spacer", contentType = "spacer") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RevealMoreAssetsOnApproach(
    listState: LazyListState,
    enabled: Boolean,
    onRevealMore: () -> Unit,
) {
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= (info.totalItemsCount - 4).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd) {
                    onRevealMore()
                }
            }
    }
}

private fun LazyListScope.analysisResultItems(
    analysisState: AnalysisState,
    pinnedRepoNames: Set<String>,
    downloadLookup: (Long) -> DownloadItemState?,
    onSelectReleaseIndex: (Int) -> Unit,
    onSelectPlatform: (PlatformCategory) -> Unit,
    onToggleReleaseNotes: () -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onDownloadAsset: (ClassifiedAsset, String) -> Unit,
    onOpenSystemDownloads: () -> Unit,
    onRetry: (String) -> Unit,
    onBackToHome: () -> Unit,
    onLoadMoreReleases: () -> Unit,
    onRevealMoreAssets: () -> Unit,
    showExpandedPlaceholder: Boolean,
) {
    when (analysisState) {
        is AnalysisState.Idle -> {
            if (showExpandedPlaceholder) {
                item(key = "expanded_placeholder", contentType = "placeholder") {
                    val shape = MaterialTheme.shapes.large
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, shape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                            .padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Paste a GitHub repository link to inspect its releases",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = "ReleaseFetch automatically strips out source trees, highlights the best APK for your device's CPU architecture, and groups every Windows, macOS, Linux, iOS, and Android download.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        is AnalysisState.Loading -> {
            item(key = "loading_card", contentType = "loading") {
                LoadingAnalysisCard(repoFullName = analysisState.repoFullName)
            }
        }

        is AnalysisState.NoReleases -> {
            item(key = "no_releases_card", contentType = "no_releases") {
                NoReleasesStateCard(
                    canonicalFullName = analysisState.canonicalFullName,
                    ownerAvatarUrl = analysisState.ownerAvatarUrl,
                    onAnalyzeAnother = onBackToHome,
                )
            }
        }

        is AnalysisState.Error -> {
            item(key = "error_card", contentType = "error") {
                ErrorAnalysisCard(
                    message = analysisState.message,
                    onRetry = { onRetry(analysisState.attemptedInput) },
                    onBack = onBackToHome,
                )
            }
        }

        is AnalysisState.Ready -> {
            val fullName = analysisState.repository.canonicalFullName
            val isPinned = fullName in pinnedRepoNames
            releaseDashboardItems(
                state = analysisState,
                isPinned = isPinned,
                downloadLookup = downloadLookup,
                onSelectReleaseIndex = onSelectReleaseIndex,
                onSelectPlatform = onSelectPlatform,
                onToggleReleaseNotes = onToggleReleaseNotes,
                onTogglePin = { onTogglePin(fullName, isPinned) },
                onDownloadAsset = { asset -> onDownloadAsset(asset, fullName) },
                onOpenSystemDownloads = onOpenSystemDownloads,
                onLoadMoreReleases = onLoadMoreReleases,
                onRevealMoreAssets = onRevealMoreAssets,
            )
        }
    }
}
