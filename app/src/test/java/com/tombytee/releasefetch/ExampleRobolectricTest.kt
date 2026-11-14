package com.tombytee.releasefetch

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tombytee.releasefetch.data.download.DownloadController
import com.tombytee.releasefetch.data.local.AppDatabase
import com.tombytee.releasefetch.data.local.RepoHistoryRepository
import com.tombytee.releasefetch.data.remote.ReleaseAnalyzerDataSource
import com.tombytee.releasefetch.data.remote.ReleaseFetchOutcome
import com.tombytee.releasefetch.data.remote.ReleasesPageResult
import com.tombytee.releasefetch.domain.AnalyzedRepository
import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.domain.ClassifiedAsset
import com.tombytee.releasefetch.domain.DeviceProfile
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.RawRelease
import com.tombytee.releasefetch.domain.RawReleaseAsset
import com.tombytee.releasefetch.domain.RepoIdentifier
import com.tombytee.releasefetch.ui.ReleaseAnalyzerViewModel
import com.tombytee.releasefetch.ui.ReleaseFetchScreen
import com.tombytee.releasefetch.ui.theme.ReleaseFetchTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var historyRepository: RepoHistoryRepository

    private val testDevice = DeviceProfile(
        primaryAbi = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        androidVersion = "15",
        sdkInt = 35,
        deviceModel = "Test Pixel",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        historyRepository = RepoHistoryRepository(database.savedRepoDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `app name matches ReleaseFetch`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ReleaseFetch", context.getString(R.string.app_name))
    }

    @Test
    fun `analyzing repository highlights recommended apk and categorizes downloads`() {
        val fakeDataSource = object : ReleaseAnalyzerDataSource {
            override suspend fun analyzeRepository(
                identifier: RepoIdentifier,
                deviceProfile: DeviceProfile,
            ): ReleaseFetchOutcome {
                val raw = RawRelease(
                    id = 1L,
                    tagName = "v3.1.0",
                    name = "v3.1.0",
                    publishedAt = "2026-11-01T10:00:00Z",
                    isPrerelease = false,
                    body = "Improved performance",
                    assets = listOf(
                        RawReleaseAsset(10L, "demo-arm64-v8a.apk", "https://example.com/arm64.apk", 15_000_000, 120, null),
                        RawReleaseAsset(11L, "demo-x86_64.apk", "https://example.com/x64.apk", 15_500_000, 30, null),
                        RawReleaseAsset(12L, "demo-win64.exe", "https://example.com/win.exe", 40_000_000, 90, null),
                    ),
                )
                val classified = AssetClassifier.classifySingleRelease(
                    raw = raw,
                    isLatest = true,
                    deviceProfile = deviceProfile,
                )
                return ReleaseFetchOutcome.Success(
                    AnalyzedRepository(
                        identifier = identifier,
                        canonicalFullName = "${identifier.owner}/${identifier.repo}",
                        ownerAvatarUrl = null,
                        rawReleases = listOf(raw),
                        classifiedCache = mapOf(raw.id to classified),
                        latestReleaseIndex = 0,
                        currentPage = 1,
                        hasMoreReleases = false,
                    )
                )
            }
        }

        val fakeDownloader = object : DownloadController {
            override val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> = MutableStateFlow(emptyMap())
            override fun startDownload(asset: ClassifiedAsset, repoFullName: String) = Result.success(99L)
            override fun openSystemDownloads() = true
            override fun dismissDownloadRecord(assetId: Long) {}
        }

        val viewModel = ReleaseAnalyzerViewModel(
            releaseRepository = fakeDataSource,
            historyRepository = historyRepository,
            downloadController = fakeDownloader,
            deviceProfile = testDevice,
        )

        composeTestRule.setContent {
            ReleaseFetchTheme(dynamicColor = false) {
                ReleaseFetchScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithTag("repo_url_input")
            .performTextInput("https://github.com/acme/demo-app")
        composeTestRule.onNodeWithTag("analyze_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(hasTestTag("release_header_card"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("main_release_list")
            .performScrollToNode(hasTestTag("recommended_asset_card"))
        composeTestRule.onNodeWithTag("recommended_asset_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recommended for Your Device").assertIsDisplayed()
        composeTestRule.onNodeWithTag("main_release_list")
            .performScrollToNode(hasTestTag("platform_chip_WINDOWS"))
        composeTestRule.onNodeWithTag("platform_chip_WINDOWS").assertIsDisplayed()
    }

    @Test
    fun `repository without releases displays clear No Releases Available message`() {
        val emptyReleasesDataSource = object : ReleaseAnalyzerDataSource {
            override suspend fun analyzeRepository(
                identifier: RepoIdentifier,
                deviceProfile: DeviceProfile,
            ): ReleaseFetchOutcome {
                return ReleaseFetchOutcome.NoReleases(
                    identifier = identifier,
                    canonicalFullName = "${identifier.owner}/${identifier.repo}",
                    ownerAvatarUrl = null,
                )
            }
        }

        val fakeDownloader = object : DownloadController {
            override val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> = MutableStateFlow(emptyMap())
            override fun startDownload(asset: ClassifiedAsset, repoFullName: String) = Result.success(1L)
            override fun openSystemDownloads() = true
            override fun dismissDownloadRecord(assetId: Long) {}
        }

        val viewModel = ReleaseAnalyzerViewModel(
            releaseRepository = emptyReleasesDataSource,
            historyRepository = historyRepository,
            downloadController = fakeDownloader,
            deviceProfile = testDevice,
        )

        composeTestRule.setContent {
            ReleaseFetchTheme(dynamicColor = false) {
                ReleaseFetchScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithTag("repo_url_input").performTextInput("acme/empty-repo")
        composeTestRule.onNodeWithTag("analyze_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(androidx.compose.ui.test.hasTestTag("no_releases_card"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("no_releases_card")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("No Releases Available").assertIsDisplayed()
    }

    @Test
    fun `large release with many assets renders lazily and supports scrolling to bottom asset`() {
        val manyAssets = (1..40).map { idx ->
            when {
                idx == 1 -> RawReleaseAsset(idx.toLong(), "app-arm64-v8a.apk", "https://example.com/$idx.apk", 12_000_000, 50, null)
                idx <= 15 -> RawReleaseAsset(idx.toLong(), "app-variant-$idx-x86_64.apk", "https://example.com/$idx.apk", 12_500_000, 10, null)
                idx <= 28 -> RawReleaseAsset(idx.toLong(), "desktop-win-$idx.exe", "https://example.com/$idx.exe", 30_000_000, 20, null)
                else -> RawReleaseAsset(idx.toLong(), "desktop-linux-$idx.AppImage", "https://example.com/$idx.AppImage", 32_000_000, 15, null)
            }
        }

        val largeRepoSource = object : ReleaseAnalyzerDataSource {
            override suspend fun analyzeRepository(
                identifier: RepoIdentifier,
                deviceProfile: DeviceProfile,
            ): ReleaseFetchOutcome {
                val raw = RawRelease(
                    id = 777L,
                    tagName = "v9.0.0",
                    name = "v9.0.0",
                    publishedAt = "2026-11-10T08:00:00Z",
                    isPrerelease = false,
                    body = null,
                    assets = manyAssets,
                )
                val classified = AssetClassifier.classifySingleRelease(
                    raw = raw,
                    isLatest = true,
                    deviceProfile = deviceProfile,
                )
                return ReleaseFetchOutcome.Success(
                    AnalyzedRepository(
                        identifier = identifier,
                        canonicalFullName = "${identifier.owner}/${identifier.repo}",
                        ownerAvatarUrl = null,
                        rawReleases = listOf(raw),
                        classifiedCache = mapOf(raw.id to classified),
                        latestReleaseIndex = 0,
                        currentPage = 1,
                        hasMoreReleases = false,
                    )
                )
            }
        }

        val fakeDownloader = object : DownloadController {
            override val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> = MutableStateFlow(emptyMap())
            override fun startDownload(asset: ClassifiedAsset, repoFullName: String) = Result.success(1L)
            override fun openSystemDownloads() = true
            override fun dismissDownloadRecord(assetId: Long) {}
        }

        val viewModel = ReleaseAnalyzerViewModel(
            releaseRepository = largeRepoSource,
            historyRepository = historyRepository,
            downloadController = fakeDownloader,
            deviceProfile = testDevice,
        )

        composeTestRule.setContent {
            ReleaseFetchTheme(dynamicColor = false) {
                ReleaseFetchScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithTag("repo_url_input").performTextInput("acme/huge-repo")
        composeTestRule.onNodeWithTag("analyze_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(hasTestTag("release_header_card"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Verify prioritized initial batch: header + recommended + first 8 assets only
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val state = viewModel.analysisState.value as? com.tombytee.releasefetch.ui.AnalysisState.Ready
            state != null && state.visibleAssetLimit == 8 && state.hasMoreAssetsToReveal
        }

        // Swiping down triggers RevealMoreAssetsOnApproach until all 40 assets are revealed
        for (step in 1..15) {
            val state = viewModel.analysisState.value as? com.tombytee.releasefetch.ui.AnalysisState.Ready
            if (state != null && !state.hasMoreAssetsToReveal) break
            composeTestRule.onNodeWithTag("main_release_list").performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val state = viewModel.analysisState.value as? com.tombytee.releasefetch.ui.AnalysisState.Ready
            state != null && !state.hasMoreAssetsToReveal && state.visibleAssetLimit >= 40
        }

        composeTestRule.onNodeWithTag("main_release_list")
            .performScrollToNode(hasTestTag("asset_card_desktop-linux-40.AppImage"))
        composeTestRule.onNodeWithTag("asset_card_desktop-linux-40.AppImage")
            .assertIsDisplayed()
    }

    @Test
    fun `switching versions classifies lazily and supports loading older release pages`() {
        val v2Raw = RawRelease(
            id = 200L,
            tagName = "v2.0.0",
            name = "v2.0.0",
            publishedAt = "2026-11-12T08:00:00Z",
            isPrerelease = false,
            body = "Latest release",
            assets = listOf(
                RawReleaseAsset(201L, "app-v2-arm64-v8a.apk", "https://example.com/v2.apk", 14_000_000, 100, null),
            ),
        )
        val v1Raw = RawRelease(
            id = 100L,
            tagName = "v1.0.0",
            name = "v1.0.0",
            publishedAt = "2026-10-01T08:00:00Z",
            isPrerelease = false,
            body = "Older release",
            assets = listOf(
                RawReleaseAsset(101L, "app-v1-arm64-v8a.apk", "https://example.com/v1.apk", 11_000_000, 80, null),
            ),
        )
        val v0Raw = RawRelease(
            id = 50L,
            tagName = "v0.9.0",
            name = "v0.9.0",
            publishedAt = "2026-09-01T08:00:00Z",
            isPrerelease = false,
            body = "Paged older release",
            assets = listOf(
                RawReleaseAsset(51L, "app-v0.9-arm64-v8a.apk", "https://example.com/v0.apk", 9_000_000, 40, null),
            ),
        )

        val pagedDataSource = object : ReleaseAnalyzerDataSource {
            override suspend fun analyzeRepository(
                identifier: RepoIdentifier,
                deviceProfile: DeviceProfile,
            ): ReleaseFetchOutcome {
                val latestClassified = AssetClassifier.classifySingleRelease(
                    raw = v2Raw,
                    isLatest = true,
                    deviceProfile = deviceProfile,
                )
                return ReleaseFetchOutcome.Success(
                    AnalyzedRepository(
                        identifier = identifier,
                        canonicalFullName = "${identifier.owner}/${identifier.repo}",
                        ownerAvatarUrl = null,
                        rawReleases = listOf(v2Raw, v1Raw),
                        classifiedCache = mapOf(v2Raw.id to latestClassified),
                        latestReleaseIndex = 0,
                        currentPage = 1,
                        hasMoreReleases = true,
                    )
                )
            }

            override suspend fun fetchReleasesPage(
                identifier: RepoIdentifier,
                page: Int,
            ): Result<ReleasesPageResult> {
                return if (page == 2) {
                    Result.success(ReleasesPageResult(releases = listOf(v0Raw), hasMoreReleases = false))
                } else {
                    Result.success(ReleasesPageResult(emptyList(), hasMoreReleases = false))
                }
            }
        }

        val fakeDownloader = object : DownloadController {
            override val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> = MutableStateFlow(emptyMap())
            override fun startDownload(asset: ClassifiedAsset, repoFullName: String) = Result.success(1L)
            override fun openSystemDownloads() = true
            override fun dismissDownloadRecord(assetId: Long) {}
        }

        val viewModel = ReleaseAnalyzerViewModel(
            releaseRepository = pagedDataSource,
            historyRepository = historyRepository,
            downloadController = fakeDownloader,
            deviceProfile = testDevice,
        )

        composeTestRule.setContent {
            ReleaseFetchTheme(dynamicColor = false) {
                ReleaseFetchScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithTag("repo_url_input").performTextInput("acme/paged-repo")
        composeTestRule.onNodeWithTag("analyze_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasTestTag("release_header_card")).fetchSemanticsNodes().isNotEmpty()
        }

        // Scroll header & recommended card so version_switcher_button is fully inside the viewport
        composeTestRule.onNodeWithTag("main_release_list")
            .performScrollToNode(hasTestTag("recommended_asset_card"))

        // Open version dropdown and select v1.0.0 (which was NOT pre-classified)
        composeTestRule.onNodeWithTag("version_switcher_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasTestTag("version_option_v1.0.0")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.onNodeWithTag("version_option_v1.0.0")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.shadowMainLooper().idleFor(25L, TimeUnit.MILLISECONDS)
            val state = viewModel.analysisState.value as? com.tombytee.releasefetch.ui.AnalysisState.Ready
            state?.activeRelease?.tagName == "v1.0.0"
        }
        composeTestRule.onNodeWithTag("main_release_list")
            .performScrollToNode(hasTestTag("asset_card_app-v1-arm64-v8a.apk"))
        composeTestRule.onNodeWithTag("asset_card_app-v1-arm64-v8a.apk").assertIsDisplayed()

        // Scroll back to header and open viewport-based VersionPickerSheet (auto-loads page 2 -> v0.9.0)
        composeTestRule.onNodeWithTag("main_release_list")
            .performScrollToNode(hasTestTag("release_header_card"))
        composeTestRule.onNodeWithTag("version_switcher_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.shadowMainLooper().idleFor(25L, TimeUnit.MILLISECONDS)
            val state = viewModel.analysisState.value as? com.tombytee.releasefetch.ui.AnalysisState.Ready
            state?.repository?.rawReleases?.size == 3
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasTestTag("version_option_v0.9.0")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.onNodeWithTag("version_option_v0.9.0")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.shadowMainLooper().idleFor(25L, TimeUnit.MILLISECONDS)
            val state = viewModel.analysisState.value as? com.tombytee.releasefetch.ui.AnalysisState.Ready
            state?.activeRelease?.tagName == "v0.9.0"
        }
        composeTestRule.onNodeWithTag("main_release_list")
            .performScrollToNode(hasTestTag("asset_card_app-v0.9-arm64-v8a.apk"))
        composeTestRule.onNodeWithTag("asset_card_app-v0.9-arm64-v8a.apk").assertIsDisplayed()
    }
}
