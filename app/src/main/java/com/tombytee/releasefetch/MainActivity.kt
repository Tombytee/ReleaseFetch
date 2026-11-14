package com.tombytee.releasefetch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import com.tombytee.releasefetch.data.download.ReleaseDownloadManager
import com.tombytee.releasefetch.data.local.AppDatabase
import com.tombytee.releasefetch.data.local.RepoHistoryRepository
import com.tombytee.releasefetch.data.remote.GitHubReleaseRepository
import com.tombytee.releasefetch.data.remote.NetworkModule
import com.tombytee.releasefetch.data.update.AndroidUpdateSystemGateway
import com.tombytee.releasefetch.data.update.AppUpdateManager
import com.tombytee.releasefetch.data.update.AppUpdateUiState
import com.tombytee.releasefetch.data.update.UpdateStatePreferences
import com.tombytee.releasefetch.ui.ReleaseAnalyzerViewModel
import com.tombytee.releasefetch.ui.ReleaseFetchScreen
import com.tombytee.releasefetch.ui.components.MandatoryUpdateScreen
import com.tombytee.releasefetch.ui.theme.ReleaseFetchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContext = applicationContext
        Coil.setImageLoader {
            ImageLoader.Builder(appContext)
                .memoryCache {
                    MemoryCache.Builder(appContext)
                        .maxSizePercent(0.10)
                        .build()
                }
                .allowRgb565(true)
                .crossfade(false)
                .build()
        }

        val historyRepository = RepoHistoryRepository {
            AppDatabase.getInstance(appContext).savedRepoDao()
        }
        val releaseRepository = GitHubReleaseRepository(
            apiServiceProvider = {
                NetworkModule.configureCacheDirectory(appContext.cacheDir)
                NetworkModule.gitHubApiService
            },
            repoCacheDaoProvider = {
                AppDatabase.getInstance(appContext).repoCacheDao()
            },
        )
        val downloadManager = ReleaseDownloadManager(appContext)

        val updateGateway = AndroidUpdateSystemGateway(appContext)
        val updateManager = AppUpdateManager(
            gateway = updateGateway,
            stateStore = UpdateStatePreferences(appContext),
            scope = lifecycleScope,
        )

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    updateManager.onAppForegrounded()
                }

                override fun onResume(owner: LifecycleOwner) {
                    updateManager.onAppResumed()
                }
            },
        )

        val factory = ReleaseAnalyzerViewModel.Factory(
            releaseRepository = releaseRepository,
            historyRepository = historyRepository,
            downloadController = downloadManager,
        )

        setContent {
            ReleaseFetchTheme {
                val updateState by updateManager.uiState.collectAsStateWithLifecycle()
                when (val currentUpdate = updateState) {
                    is AppUpdateUiState.UpdateRequired -> {
                        MandatoryUpdateScreen(
                            state = currentUpdate,
                            launchEffects = updateManager.launchEffects,
                            systemGateway = updateGateway,
                            onAllowUnknownSourcesClicked = updateManager::onAllowUnknownSourcesClicked,
                            onInstallClicked = updateManager::onInstallClicked,
                            onInstallerResultReturned = updateManager::onInstallerResultReturned,
                            onUnknownSourcesResultReturned = updateManager::onAppResumed,
                            onRetryDownloadClicked = updateManager::onRetryDownloadClicked,
                        )
                    }

                    AppUpdateUiState.UpToDate -> {
                        val analyzerViewModel: ReleaseAnalyzerViewModel = viewModel(factory = factory)
                        ReleaseFetchScreen(
                            viewModel = analyzerViewModel,
                            appUpdateManager = updateManager,
                        )
                    }
                }
            }
        }
    }
}
